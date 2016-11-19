package au.edu.wehi.idsv.configuration;

import org.apache.commons.configuration.Configuration;

import au.edu.wehi.idsv.AssemblyAlgorithm;

public class AssemblyConfiguration {
	public static final String CONFIGURATION_PREFIX = "assembly";
	public AssemblyConfiguration(Configuration config) {
		config = config.subset(CONFIGURATION_PREFIX);
		errorCorrection = new ErrorCorrectionConfiguration(config);
		downsampling = new DownsamplingConfiguration(config);
		positional = new PositionalAssemblyConfiguration(config);
		method = AssemblyAlgorithm.valueOf(config.getString("method"));
		k = config.getInt("k");
		minReads = config.getInt("minReads");
		includePairAnchors = config.getBoolean("includePairAnchors");
		pairAnchorMismatchIgnoreEndBases = config.getInt("pairAnchorMismatchIgnoreEndBases");
		includeRemoteSplitReads = config.getBoolean("includeRemoteSplitReads");
		writeFiltered = config.getBoolean("writeFiltered");
		excludeNonSupportingEvidence = config.getBoolean("excludeNonSupportingEvidence");
		anchorLength = config.getInt("anchorLength");
		removeMisassembledPartialContigsDuringAssembly = config.getBoolean("removeMisassembledPartialContigsDuringAssembly");
		maxExpectedBreakendLengthMultiple = config.getFloat("maxExpectedBreakendLengthMultiple");
		trackEvidenceID = config.getBoolean("trackEvidenceID");
	}
	public ErrorCorrectionConfiguration errorCorrection;
	public DownsamplingConfiguration downsampling;
	public PositionalAssemblyConfiguration positional;
	/**
	 * Assembly algorithm to use
	 */
	public AssemblyAlgorithm method;
	/**
	 * De Bruijn graph kmer size
	 */
	public int k;
	/**
	 * Minimum number of reads contributing the the assembly
	 */
	public int minReads;
	/**
	 * Include reads fully supporting the reference whose mate is not mapped to the expected location.
	 * These are useful as they can extend the length of the assembly anchor 
	 */
	public boolean includePairAnchors;
	/**
	 * Base mismatches within this many bases from the end of an anchoring mate are
	 * considered to be not reference supporting.
	 * 
	 * This stops breakpoint positions being shifted due to aligners preferring to fully
	 * align reads with small mismatches at the end (that is, when the soft clip penatly
	 * is more than the base mismatch penalty, the aligner will align SV bases when we
	 * don't want it to).   
	 */
	public int pairAnchorMismatchIgnoreEndBases;
	/**
	 * Include reads with a soft clip that maps to this location
	 */
	public boolean includeRemoteSplitReads;
	/**
	 * Determines whether filtered assemblies are written to intermediate files
	 */
	public boolean writeFiltered;
	public boolean excludeNonSupportingEvidence;
	/**
	 * Default minimum length in bases of reference sequence anchor assembly. A breakend assembly longer than this
	 * length will cause reference sequence assembly to be at least as long as the breakend 
	 */
	public int anchorLength = 100;
	/**
	 * Determine whether to remove excessively long contigs in increments as each
	 * increment exceeds maxExpectedBreakendLengthMultiple, or after assembly. Waiting
	 * until after assembly is complete is computationally prohibitive
	 */
	public boolean removeMisassembledPartialContigsDuringAssembly = true;
	/**
	 * Maximum expected length of a breakend assembly.
	 * Assemblies larger than this size are extremely likely to be missassemblies
	 * 
	 * Expected max size is 1.0 for single-sided assembly and 2.0 for assembly from both directions 
	 */
	public float maxExpectedBreakendLengthMultiple = 3.0f;
	/**
	 * Retains evidenceID tracking information after evidence rehydration
	 */
	public boolean trackEvidenceID;
}
