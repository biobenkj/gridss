package au.edu.wehi.idsv.debruijn.positional;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.api.client.util.Lists;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import au.edu.wehi.idsv.AssemblyEvidenceSource;
import au.edu.wehi.idsv.AssemblyFactory;
import au.edu.wehi.idsv.BreakendDirection;
import au.edu.wehi.idsv.BreakendSummary;
import au.edu.wehi.idsv.Defaults;
import au.edu.wehi.idsv.SAMRecordAssemblyEvidence;
import au.edu.wehi.idsv.debruijn.DeBruijnGraphBase;
import au.edu.wehi.idsv.debruijn.KmerEncodingHelper;
import au.edu.wehi.idsv.graph.ScalingHelper;
import au.edu.wehi.idsv.model.Models;
import au.edu.wehi.idsv.util.IntervalUtil;
import au.edu.wehi.idsv.visualisation.PositionalDeBruijnGraphTracker;
import au.edu.wehi.idsv.visualisation.PositionalDeBruijnGraphTracker.ContigStats;
import au.edu.wehi.idsv.visualisation.PositionalExporter;
import htsjdk.samtools.util.Log;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;


/**
 * Calls optimal contigs from a positional de Bruijn graph
 * 
 * @author Daniel Cameron
 *
 */
public class NonReferenceContigAssembler implements Iterator<SAMRecordAssemblyEvidence> {
	private static final Log log = Log.getInstance(NonReferenceContigAssembler.class);
	/**
	 * Debugging tracker to ensure memoization export files have unique names
	 */
	private static final AtomicInteger pathExportCount = new AtomicInteger();
	/**
	 * Since reference kmers are not scored, calculating 
	 * highest weighted results in a preference for paths
	 * ending at a RP with sequencing errors over a path
	 * anchored to the reference. 
	 * 
	 * To ensure that the anchored paths are scored higher
	 * than the unanchored paths, paths anchored to the
	 * reference are given a score adjustment larger than
	 * the largest expected score.
	 */
	static final int ANCHORED_SCORE = Integer.MAX_VALUE >> 2;
	/**
	 * TODO: check to see if this is worth doing
	 * Simplication reduces the graph size, but may trigger
	 * additional rememoization so might turn out to be a more
	 * expensive approach overall
	 */
	private static final boolean SIMPLIFY_AFTER_REMOVAL = false;
	private Long2ObjectMap<Collection<KmerPathNodeKmerNode>> graphByKmerNode = new Long2ObjectOpenHashMap<Collection<KmerPathNodeKmerNode>>();
	private SortedSet<KmerPathNode> graphByPosition = new TreeSet<KmerPathNode>(KmerNodeUtil.ByFirstStartKmer);
	private SortedSet<KmerPathNode> nonReferenceGraphByPosition = new TreeSet<KmerPathNode>(KmerNodeUtil.ByFirstStartKmer);
	private final EvidenceTracker evidenceTracker;
	private final AssemblyEvidenceSource aes;
	/**
	 * Worst case scenario is a RP providing single kmer support for contig
	 * read length - (k-1) + max-min fragment size
	 *
	 * ========== contig
	 *          --------- read contributes single kmer to contig  
	 *           \       \  in the earliest position
	 *            \  RP   \
	 *             \       \
	 *              \       \
	 *               ---------
	 *                        ^
	 *                        |
	 * Last position supported by this RP is here. 
	 */
	private final int maxEvidenceSupportIntervalWidth;
	private final int maxAnchorLength;
	private final int k;
	private final int referenceIndex;
	private final ContigStats stats = new ContigStats();
	private final PeekingIterator<KmerPathNode> underlying;
	private final String contigName;
	private final Queue<SAMRecordAssemblyEvidence> called = new ArrayDeque<>();
	private int lastUnderlyingStartPosition = Integer.MIN_VALUE;
	private MemoizedContigCaller bestContigCaller;
	private int contigsCalled = 0;
	private long consumed = 0;
	private PositionalDeBruijnGraphTracker exportTracker = null;
	public int getReferenceIndex() { return referenceIndex; }
	/**
	 * Creates a new structural variant positional de Bruijn graph contig assembly for the given chromosome
	 * @param it reads
	 * @param referenceIndex evidence source
	 * @param maxEvidenceDistance maximum distance from the first position of the first kmer of a read,
	 *  to the last position of the last kmer of a read. This should be set to read length plus
	 *  the max-min concordant fragment size
	 * @param maxAnchorLength maximum number of reference-supporting anchor bases to assemble
	 * @param k
	 * @param source assembly source
	 * @param tracker evidence lookup
	 */
	public NonReferenceContigAssembler(
			Iterator<KmerPathNode> it,
			int referenceIndex,
			int maxEvidenceSupportIntervalWidth,
			int maxAnchorLength,
			int k,
			AssemblyEvidenceSource source,
			EvidenceTracker tracker,
			String contigName) {
		this.underlying = Iterators.peekingIterator(it);
		this.maxEvidenceSupportIntervalWidth = maxEvidenceSupportIntervalWidth;
		this.maxAnchorLength = maxAnchorLength;
		this.k = k;
		this.referenceIndex = referenceIndex;
		this.aes = source;
		this.evidenceTracker = tracker;
		this.contigName = contigName;
		initialiseBestCaller();
	}
	private void initialiseBestCaller() {
		this.bestContigCaller = new MemoizedContigCaller(ANCHORED_SCORE, maxEvidenceSupportIntervalWidth);
		for (KmerPathNode n : graphByPosition) {
			bestContigCaller.add(n);
		}
	}
	@Override
	public boolean hasNext() {
		ensureCalledContig();
		return !called.isEmpty();
	}
	@Override
	public SAMRecordAssemblyEvidence next() {
		ensureCalledContig();
		return called.remove();
	}
	private void ensureCalledContig() {
		if (!called.isEmpty()) return;
		while (called.isEmpty()) {
			// Safety calling to ensure loaded graph size is bounded
			if (!nonReferenceGraphByPosition.isEmpty()) {
				int fragmentSize = aes.getMaxConcordantFragmentSize();
				int retainWidth = (int)(aes.getContext().getAssemblyParameters().positional.retainWidthMultiple * fragmentSize);
				int flushWidth = (int)(aes.getContext().getAssemblyParameters().positional.flushWidthMultiple * fragmentSize);
				int loadedStart = nonReferenceGraphByPosition.first().firstStart();
				int frontierStart = bestContigCaller.frontierStart(nextPosition());
				if (loadedStart + retainWidth + flushWidth < frontierStart) {
					ArrayDeque<KmerPathSubnode> forcedContig = null;
					do {
						// keep calling until we have no more contigs left
						// even if we could be calling a suboptimal contig
						forcedContig = bestContigCaller.callBestContigBefore(nextPosition(), frontierStart - flushWidth);
						callContig(forcedContig);
					} while (forcedContig != null);
					flushReferenceNodes();
					if (!called.isEmpty()) return;
				}
			}
			// Call the next contig
			ArrayDeque<KmerPathSubnode> bestContig = bestContigCaller.bestContig(nextPosition());
			callContig(bestContig);
			if (called.isEmpty() && bestContig == null) {
				if (underlying.hasNext()) {
					advanceUnderlying();
					if (aes.getContext().getAssemblyParameters().removeMisassembledPartialContigsDuringAssembly) {
						removeMisassembledPartialContig();
					}
					flushReferenceNodes();
				} else {
					flushReferenceNodes();
					if (!graphByPosition.isEmpty()) {
						log.error("Sanity check failure: non-empty graph with no contigs called " + contigName);
					}
					return;
				}
			}
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION) {
			assert(bestContigCaller.sanityCheckFrontier(nextPosition()));
			verifyMemoization();
		}
	}
	private void flushReferenceNodes() {
		int position = nonReferenceGraphByPosition.isEmpty() ? nextPosition() : nonReferenceGraphByPosition.first().firstStart();
		int maxContigAnchorLength = Math.max((int)(aes.getContext().getAssemblyParameters().maxExpectedBreakendLengthMultiple * aes.getMaxConcordantFragmentSize()),
				aes.getContext().getAssemblyParameters().anchorLength);
		// first position at which we are guaranteed to not be involved in any contig anchor sequence
		position -= maxEvidenceSupportIntervalWidth + maxContigAnchorLength;
		if (!graphByPosition.isEmpty() && graphByPosition.first().firstStart() < position) {
			Collection<KmerPathSubnode> nodes = new ArrayList<>();
			for (KmerPathNode tn : graphByPosition) {
				if (tn.firstStart() >= position) {
					break;
				}
				if (tn.isReference()) {
					nodes.add(new KmerPathSubnode(tn));
				}
			}
			Set<KmerEvidence> toRemove = evidenceTracker.untrack(nodes);
			removeFromGraph(toRemove);
		}
	}
	/**
	 * Removes partial contigs that are longer than the maximum theoretical breakend contig length
	 */
	private void removeMisassembledPartialContig() {
		int loadedBefore = nextPosition();
		int positionalWidth = maxEvidenceSupportIntervalWidth - aes.getMaxReadLength();
		int misassemblyLength = (int)(aes.getContext().getAssemblyParameters().maxExpectedBreakendLengthMultiple * aes.getMaxConcordantFragmentSize() + positionalWidth);
		ArrayDeque<KmerPathSubnode> misassembly = bestContigCaller.frontierPath(loadedBefore, loadedBefore - misassemblyLength);
		if (misassembly == null) return;
		Set<KmerEvidence> evidence = evidenceTracker.untrack(misassembly.stream()
			// To be sure that all reads on the contig to remove have
			// been fully loaded, we don't remove nodes that could contain
			// a read that also contributed to an unprocessed node
			.filter(sn -> sn.lastEnd() + maxEvidenceSupportIntervalWidth < loadedBefore)
			.collect(Collectors.toList()));
		removeFromGraph(evidence);
	}
	private int nextPosition() {
		if (!underlying.hasNext()) return Integer.MAX_VALUE;
		return underlying.peek().firstStart();
	}
	/**
	 * Loads additional nodes into the graph
	 * 
	 * By loaded in batches, we reduce our memoization frontier advancement overhead
	 */
	private void advanceUnderlying() {
		int loadUntil = nextPosition();
		if (loadUntil < Integer.MAX_VALUE) {
			loadUntil += maxEvidenceSupportIntervalWidth + 1;
		}
		advanceUnderlying(loadUntil);
	}
	private void advanceUnderlying(int loadUntil) {
		while (underlying.hasNext() && nextPosition() <= loadUntil) {
			KmerPathNode node = underlying.next();
			assert(lastUnderlyingStartPosition <= node.firstStart());
			lastUnderlyingStartPosition = node.firstStart();
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				assert(evidenceTracker.matchesExpected(new KmerPathSubnode(node)));
			}
			addToGraph(node);
			consumed++;
		}
	}
	/**
	 * Verifies that the memoization matches a freshly calculated memoization 
	 * @param contig
	 */
	private boolean verifyMemoization() {
		int preGraphSize = graphByPosition.size();
		MemoizedContigCaller mcc = new MemoizedContigCaller(ANCHORED_SCORE, maxEvidenceSupportIntervalWidth);
		for (KmerPathNode n : graphByPosition) {
			mcc.add(n);
		}
		mcc.bestContig(nextPosition());
		bestContigCaller.sanityCheckMatches(mcc);
		int postGraphSize = graphByPosition.size();
		assert(preGraphSize == postGraphSize);
		return true;
	}
	private SAMRecordAssemblyEvidence callContig(ArrayDeque<KmerPathSubnode> rawcontig) {
		if (rawcontig == null) return null;
		ArrayDeque<KmerPathSubnode> contig = rawcontig;
		if (containsKmerRepeat(contig)) {
			// recalculate the called contig, this may break the contig at the repeated kmer
			MisassemblyFixer fixed = new MisassemblyFixer(contig);
			contig = new ArrayDeque<KmerPathSubnode>(fixed.correctMisassignedEvidence(evidenceTracker.support(contig)));
		}
		if (contig.isEmpty()) return null;
		Set<KmerEvidence> evidence = evidenceTracker.untrack(contig);
		
		int targetAnchorLength = Math.max(contig.stream().mapToInt(sn -> sn.length()).sum(), maxAnchorLength);
		KmerPathNodePath startAnchorPath = new KmerPathNodePath(contig.getFirst(), false, targetAnchorLength + maxEvidenceSupportIntervalWidth + contig.getFirst().length());
		startAnchorPath.greedyTraverse(true, false);
		ArrayDeque<KmerPathSubnode> startingAnchor = startAnchorPath.headNode().asSubnodes();
		startingAnchor.removeLast();
		// make sure we have enough of the graph loaded so that when
		// we traverse forward, our anchor sequence will be fully defined
		advanceUnderlying(contig.getLast().lastEnd() + targetAnchorLength + maxEvidenceSupportIntervalWidth);
		KmerPathNodePath endAnchorPath = new KmerPathNodePath(contig.getLast(), true, targetAnchorLength + maxEvidenceSupportIntervalWidth + contig.getLast().length());
		endAnchorPath.greedyTraverse(true, false);
		ArrayDeque<KmerPathSubnode> endingAnchor = endAnchorPath.headNode().asSubnodes();
		endingAnchor.removeFirst();
		
		List<KmerPathSubnode> fullContig = new ArrayList<KmerPathSubnode>(contig.size() + startingAnchor.size() + endingAnchor.size());
		fullContig.addAll(startingAnchor);
		fullContig.addAll(contig);
		fullContig.addAll(endingAnchor);
		
		byte[] bases = KmerEncodingHelper.baseCalls(fullContig.stream().flatMap(sn -> sn.node().pathKmers().stream()).collect(Collectors.toList()), k);
		byte[] quals = DeBruijnGraphBase.kmerWeightsToBaseQuals(k, fullContig.stream().flatMapToInt(sn -> sn.node().pathWeights().stream().mapToInt(Integer::intValue)).toArray());
		assert(quals.length == bases.length);
		// left aligned anchor position although it shouldn't matter since anchoring should be a single base wide
		int startAnchorPosition = startingAnchor.size() == 0 ? 0 : startingAnchor.getLast().lastStart() + k - 1;
		int endAnchorPosition = endingAnchor.size() == 0 ? 0 : endingAnchor.getFirst().firstStart();
		int startAnchorBaseCount = startingAnchor.size() == 0 ? 0 : startingAnchor.stream().mapToInt(n -> n.length()).sum() + k - 1;
		int endAnchorBaseCount = endingAnchor.size() == 0 ? 0 : endingAnchor.stream().mapToInt(n -> n.length()).sum() + k - 1;
		int startBasesToTrim = Math.max(0, startAnchorBaseCount - targetAnchorLength);
		int endingBasesToTrim = Math.max(0, endAnchorBaseCount - targetAnchorLength);
		bases = Arrays.copyOfRange(bases, startBasesToTrim, bases.length - endingBasesToTrim);
		quals = Arrays.copyOfRange(quals, startBasesToTrim, quals.length - endingBasesToTrim);
		
		List<String> evidenceIds = evidence.stream().map(e -> e.evidenceId()).collect(Collectors.toList());
		SAMRecordAssemblyEvidence assembledContig;
		if (startingAnchor.size() == 0 && endingAnchor.size() == 0) {
			assert(startBasesToTrim == 0);
			assert(endingBasesToTrim == 0);
			// unanchored
			BreakendSummary be = Models.calculateBreakend(aes.getContext().getLinear(),
					evidence.stream().map(e -> e.breakend()).collect(Collectors.toList()),
					evidence.stream().map(e -> ScalingHelper.toScaledWeight(e.evidenceQuality())).collect(Collectors.toList()));
			assembledContig = AssemblyFactory.createUnanchoredBreakend(aes.getContext(), aes,
					be,
					evidenceIds,
					bases, quals, new int[] { 0, 0 });
			if (evidence.stream().anyMatch(e -> e.isAnchored())) {
				log.debug(String.format("Unanchored assembly %s at %s:%d contains anchored evidence", assembledContig.getEvidenceID(), contigName, contig.getFirst().firstStart()));
			}
		} else if (startingAnchor.size() == 0) {
			// end anchored
			assembledContig = AssemblyFactory.createAnchoredBreakend(aes.getContext(), aes,
					BreakendDirection.Backward, evidenceIds,
					referenceIndex, endAnchorPosition, endAnchorBaseCount - endingBasesToTrim,
					bases, quals);
		} else if (endingAnchor.size() == 0) {
			// start anchored
			assembledContig = AssemblyFactory.createAnchoredBreakend(aes.getContext(), aes,
					BreakendDirection.Forward, evidenceIds,
					referenceIndex, startAnchorPosition, startAnchorBaseCount - startBasesToTrim,
					bases, quals);
		} else {
			if (startAnchorBaseCount + endAnchorBaseCount >= quals.length) {
				// no unanchored bases - not an SV assembly
				assembledContig = null;
			} else {
				assembledContig = AssemblyFactory.createAnchoredBreakpoint(aes.getContext(), aes, evidenceIds,
						referenceIndex, startAnchorPosition, startAnchorBaseCount - startBasesToTrim,
						referenceIndex, endAnchorPosition, endAnchorBaseCount - endingBasesToTrim,
						bases, quals);
			}
		}
		if (assembledContig != null) {
			if (aes.getContext().getConfig().getVisualisation().assemblyGraph) {
				try {
					PositionalExporter.exportDot(new File(aes.getContext().getConfig().getVisualisation().directory, "assembly." + contigName + "." + assembledContig.getEvidenceID() + ".dot"), k, graphByPosition, fullContig);
				} catch (Exception ex) {
					log.debug(ex, "Error exporting assembly ", assembledContig != null ? assembledContig.getEvidenceID() : "(null)", " ", contigName);
				}
			}
			if (aes.getContext().getConfig().getVisualisation().assemblyGraphFullSize) {
				try {
					PositionalExporter.exportNodeDot(new File(aes.getContext().getConfig().getVisualisation().directory, "assembly.fullsize." + contigName + "." + assembledContig.getEvidenceID() + ".dot"), k, graphByPosition, fullContig);
				} catch (Exception ex) {
					log.debug(ex, "Error exporting assembly ", assembledContig != null ? assembledContig.getEvidenceID() : "(null)", " ", contigName);
				}
			}
			if (aes.getContext().getConfig().getVisualisation().assemblyContigMemoization) {
				File file = new File(aes.getContext().getConfig().getVisualisation().directory, "assembly.path.memoization." + contigName + "." + Integer.toString(pathExportCount.incrementAndGet()) + ".csv");
				try {
					bestContigCaller.exportState(file);
				} catch (IOException e) {
					log.debug(e, " Unable to export assembly path memoization to ", file, " ", contigName);
				}
			}
		}
		stats.contigNodes = contig.size();
		stats.truncatedNodes = rawcontig.size() - contig.size();
		stats.contigStartPosition = contig.getFirst().firstStart();
		stats.startAnchorNodes = startingAnchor.size();
		stats.endAnchorNodes = endingAnchor.size();
		if (exportTracker != null) {
			exportTracker.trackAssembly(bestContigCaller);
		}
		// remove all evidence contributing to this assembly from the graph
		if (evidence.size() > 0) {
			removeFromGraph(evidence);
			if (Defaults.SANITY_CHECK_MEMOIZATION) {
				bestContigCaller.sanityCheck(graphByPosition);
			}
		} else {
			log.error("Sanity check failure: found path with no support. Attempting to recover by direct node removal ", contigName);
			for (KmerPathSubnode n : contig) {
				removeFromGraph(n.node(), true);
			}
		}
		contigsCalled++;
		if (assembledContig != null) {
			called.add(assembledContig);
		}
		return assembledContig;
	}
	private boolean containsKmerRepeat(Collection<KmerPathSubnode> contig) {
		LongSet existing = new LongOpenHashSet();
		for (KmerPathSubnode n : contig) {
			for (int i = 0; i < n.length(); i++) {
				if (!existing.add(n.node().kmer(i))) {
					return true;
				}
			}
			for (long kmer : n.node().collapsedKmers()) {
				if (!existing.add(kmer)) {
					return true;
				}
			}
		}
		return false;
	}
	/**
	 * Removes all evidence from the current graph
	 * @param evidence
	 */
	private void removeFromGraph(Set<KmerEvidence> evidence) {
		assert(!evidence.isEmpty());
		Map<KmerPathNode, List<List<KmerNode>>> toRemove = new IdentityHashMap<KmerPathNode, List<List<KmerNode>>>();
		for (KmerEvidence e : evidence) {
			for (int i = 0; i < e.length(); i++) {
				KmerSupportNode support = e.node(i);
				if (support != null) {
					if (support.lastEnd() >= nextPosition()) {
						log.error(String.format("Sanity check failure: %s extending to %d removed when input at %s:%d", e, support.lastEnd(), contigName, nextPosition()));
						// try to recover
					}
					updateRemovalList(toRemove, support);
				}
			}
		}
		if (bestContigCaller != null) {
			bestContigCaller.remove(toRemove.keySet());
		}
		Set<KmerPathNode> simplifyCandidates = new ObjectOpenCustomHashSet<KmerPathNode>(new KmerPathNode.HashByFirstKmerStartPositionKmer<KmerPathNode>());
		for (Entry<KmerPathNode, List<List<KmerNode>>> entry : toRemove.entrySet()) {
			removeWeight(entry.getKey(), entry.getValue(), simplifyCandidates, false);
		}
		if (SIMPLIFY_AFTER_REMOVAL) {
			simplify(simplifyCandidates);
		}
		if (Defaults.SANITY_CHECK_DE_BRUIJN) {
			assert(sanityCheck());
			assert(sanityCheckDisjointNodeIntervals());
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION && bestContigCaller != null) {
			// Force memoization recalculation now
			bestContigCaller.bestContig(nextPosition());
			// so we can check that our removal was correct
			verifyMemoization();
		}
	}
	/**
	 * Attempts to simplify the given nodes
	 * @param simplifyCandidates
	 */
	private void simplify(Set<KmerPathNode> simplifyCandidates) {
		while (!simplifyCandidates.isEmpty()) {
			simplify(simplifyCandidates.iterator().next(), simplifyCandidates);
		}
	}
	private void simplify(KmerPathNode node, Set<KmerPathNode> simplifyCandidates) {
		simplifyCandidates.remove(node);
		if (node.lastEnd() >= nextPosition() - 1) {
			// don't simplify graph if we haven't actually loaded all the relevant nodes
			return;
		}
		KmerPathNode prev = node.prevToMergeWith();
		if (prev != null && prev.lastEnd() < nextPosition() - 1) {
			simplifyCandidates.remove(prev);
			removeFromGraph(node, true);
			removeFromGraph(prev, true);
			node.prepend(prev);
			addToGraph(node);
		}
		KmerPathNode next = node.nextToMergeWith();
		if (next != null && next.lastEnd() < nextPosition() - 1) {
			simplifyCandidates.remove(next);
			removeFromGraph(node, true);
			removeFromGraph(next, true);
			next.prepend(node);
			addToGraph(next);
		}
	}
	private void updateRemovalList(Map<KmerPathNode, List<List<KmerNode>>> toRemove, KmerSupportNode support) {
		Collection<KmerPathNodeKmerNode> kpnknList = graphByKmerNode.get(support.lastKmer());
		if (kpnknList != null) {
			for (KmerPathNodeKmerNode n : kpnknList) {
				if (IntervalUtil.overlapsClosed(support.lastStart(), support.lastEnd(), n.lastStart(), n.lastEnd())) {
					updateRemovalList(toRemove, n, support);
				}
			}
		}
	}
	private void updateRemovalList(Map<KmerPathNode, List<List<KmerNode>>> toRemove, KmerPathNodeKmerNode node, KmerSupportNode support) {
		KmerPathNode pn = node.node();
		List<List<KmerNode>> list = toRemove.get(pn);
		if (list == null) {
			list = new ArrayList<List<KmerNode>>(pn.length());
			toRemove.put(pn, list);
		}
		int offset = node.offsetOfPrimaryKmer();
		while (list.size() <= offset) {
			list.add(null);
		}
		List<KmerNode> evidenceList = list.get(offset); 
		if (evidenceList == null) {
			evidenceList = new ArrayList<KmerNode>();
			list.set(offset, evidenceList);
		}
		evidenceList.add(support);
	}
	private void removeWeight(KmerPathNode node, List<List<KmerNode>> toRemove, Set<KmerPathNode> simplifyCandidates, boolean includeMemoizationRemoval) {
		if (node == null) return;
		assert(node.length() >= toRemove.size());
		// remove from graph
		removeFromGraph(node, includeMemoizationRemoval);
		simplifyCandidates.addAll(node.next());
		simplifyCandidates.addAll(node.prev());
		simplifyCandidates.remove(node);
		Collection<KmerPathNode> replacementNodes = KmerPathNode.removeWeight(node, toRemove);
		for (KmerPathNode split : replacementNodes) {
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				assert(evidenceTracker.matchesExpected(new KmerPathSubnode(split)));
			}
			addToGraph(split);
		}
		simplifyCandidates.addAll(replacementNodes);
	}
	private void addToGraph(KmerPathNode node) {
		boolean added = graphByPosition.add(node);
		assert(added);
		if (!node.isReference()) {
			nonReferenceGraphByPosition.add(node);
		}
		for (int i = 0; i < node.length(); i++) {
			addToGraph(new KmerPathNodeKmerNode(node, i));
		}
		for (int i = 0; i < node.collapsedKmers().size(); i++) {
			addToGraph(new KmerPathNodeKmerNode(i, node));
		}
		if (bestContigCaller != null) {
			bestContigCaller.add(node);
		}
	}
	private void removeFromGraph(KmerPathNode node, boolean includeMemoizationRemoval) {
		if (includeMemoizationRemoval) {
			if (bestContigCaller != null) {
				bestContigCaller.remove(node);
			}
		}
		boolean removed = graphByPosition.remove(node);
		nonReferenceGraphByPosition.remove(node);
		assert(removed);
		for (int i = 0; i < node.length(); i++) {
			removeFromGraph(new KmerPathNodeKmerNode(node, i));
		}
		for (int i = 0; i < node.collapsedKmers().size(); i++) {
			removeFromGraph(new KmerPathNodeKmerNode(i, node));
		}
	}
	private void addToGraph(KmerPathNodeKmerNode node) {
		Collection<KmerPathNodeKmerNode> list = graphByKmerNode.get(node.firstKmer());
		if (list == null) {
			list = new ArrayList<KmerPathNodeKmerNode>();
			graphByKmerNode.put(node.firstKmer(), list);
		}
		list.add(node);
	}
	private void removeFromGraph(KmerPathNodeKmerNode node) {
		Collection<KmerPathNodeKmerNode> list = graphByKmerNode.get(node.firstKmer());
		if (list == null) return;
		list.remove(node);
		if (list.size() == 0) {
			graphByKmerNode.remove(node.firstKmer());
		}
	}
	public boolean sanityCheck() {
		graphByKmerNode.entrySet().stream().flatMap(e -> e.getValue().stream()).forEach(kn -> { 
			assert(kn.node().isValid());
			assert(graphByPosition.contains(kn.node()));
		});
		for (KmerPathNode n : graphByPosition) {
			assert(n.isValid());
			assert(evidenceTracker.matchesExpected(new KmerPathSubnode(n)));
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION && MemoizedContigCaller.ASSERT_ALL_OPERATIONS) {
			if (bestContigCaller != null) assert(bestContigCaller.sanityCheck());
		}
		return true;
	}
	public boolean sanityCheckDisjointNodeIntervals() {
		Map<Long, List<KmerPathNode>> byKmer = graphByPosition
	            .stream()
	            .collect(Collectors.groupingBy(KmerPathNode::firstKmer));
		for (List<KmerPathNode> list : byKmer.values()) {
			if (list.size() == 1) continue;
			ArrayList<KmerPathNode> al = Lists.newArrayList(list);
			al.sort(KmerNodeUtil.ByFirstStart);
			for (int i = 1; i < al.size(); i++) {
				assert(al.get(i - 1).firstEnd() < al.get(i).firstStart());
			}
		}
		return true;
	}
	public int tracking_activeNodes() {
		return graphByPosition.size();
	}
	public int tracking_maxKmerActiveNodeCount() {
		return graphByKmerNode.values().stream().mapToInt(x -> x.size()).max().orElse(0);
	}
	public long tracking_underlyingConsumed() {
		return consumed;
	}
	public int tracking_inputPosition() {
		return nextPosition();
	}
	public int tracking_firstPosition() {
		if (graphByPosition.size() == 0) return Integer.MAX_VALUE;
		return graphByPosition.first().firstStart();
	}
	public PositionalDeBruijnGraphTracker getExportTracker() {
		return exportTracker;
	}
	public void setExportTracker(PositionalDeBruijnGraphTracker exportTracker) {
		this.exportTracker = exportTracker;
	}
	public ContigStats tracking_lastContig() {
		return stats;
	}
	public int tracking_contigsCalled() {
		return contigsCalled;
	}
}
