package au.edu.wehi.idsv;

import static org.junit.Assert.assertEquals;
import htsjdk.samtools.SAMRecord;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;


public class SAMRecordAssemblyEvidenceReadPairIteratorTest extends TestHelper {	
	private List<SAMRecordAssemblyEvidence> in;
	private List<SAMRecordAssemblyEvidence> out;
	@Before
	public void setup() {
		in = Lists.newArrayList();
		out = Lists.newArrayList();
	}
	public static ProcessingContext getContext() {
		ProcessingContext pc = TestHelper.getContext();
		return pc;
	}
	public void go(boolean includeFiltered) { go(getContext(), includeFiltered); }
	public void go(ProcessingContext pc, boolean includeFiltered) {
		out = Lists.newArrayList(new SAMRecordAssemblyEvidenceReadPairIterator(pc, AES(),
			Iterators.transform(in.iterator(), new Function<SAMRecordAssemblyEvidence, SAMRecord>() {
				@Override
				public SAMRecord apply(SAMRecordAssemblyEvidence input) {
					return input.getSAMRecord();
				} }),
				Iterators.transform(in.iterator(), new Function<SAMRecordAssemblyEvidence, SAMRecord>() {
					@Override
					public SAMRecord apply(SAMRecordAssemblyEvidence input) {
						return input.getRemoteSAMRecord();
					} })
			, true, includeFiltered));
	}
	@Test
	public void should_filter_reference_breakend() {
		Set<DirectedEvidence> evidence = Sets.<DirectedEvidence>newHashSet();
		evidence.add(SCE(FWD, Read(0, 5, "5M5S")));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchored(getContext(), AES(), FWD, evidence,
				0, 10, 5, B("AAAAAAAAAA"), B("AAAAAAAAAA"), 5, 6);
		in.add(e);
		go(true);
		assertEquals("precondition - breakend should exist", 1, out.size());
		go(false);
		assertEquals(0, out.size());
	}
	@Test
	public void should_filter_reference_breakpoint() {
		Set<DirectedEvidence> evidence = Sets.<DirectedEvidence>newHashSet();
		evidence.add(SCE(FWD, Read(0, 5, "5M5S")));
		evidence.add(SCE(FWD, Read(0, 5, "5M6S")));
		evidence.add(SCE(FWD, Read(0, 5, "5M7S")));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchored(getContext(), AES(), FWD, evidence,
				0, 10, 5, B("AAAAAAAAAA"), B("AAAAAAAAAA"), 5, 6);		
		in.add(e);
		go(false);
		assertEquals("precondition - breakend should not be filtered", 1, out.size());
		setup(); // reset
		SAMRecord r = Read(1, 11, "5M");
		r.setReadName(BreakpointFastqEncoding.getRealignmentFastq(e).getReadHeader());
		in.add(AssemblyFactory.incorporateRealignment(getContext(), e, r));
		go(false);
		assertEquals(0, out.size());
	}
}
