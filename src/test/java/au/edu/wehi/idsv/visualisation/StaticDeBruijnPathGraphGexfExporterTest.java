package au.edu.wehi.idsv.visualisation;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.junit.Test;

import au.edu.wehi.idsv.AssemblyAlgorithm;
import au.edu.wehi.idsv.AssemblyEvidenceSource;
import au.edu.wehi.idsv.IntermediateFilesTest;
import au.edu.wehi.idsv.ProcessStep;
import au.edu.wehi.idsv.ProcessingContext;
import au.edu.wehi.idsv.SAMEvidenceSource;

import com.google.common.collect.ImmutableList;


public class StaticDeBruijnPathGraphGexfExporterTest extends IntermediateFilesTest {
	@Test
	public void positional_should_export_gexf() throws IOException {
		File output = new File(super.testFolder.getRoot(), "chr12-244000.vcf");
		setReference(new File("C:/dev/chr12.fa"));
		createInput(new File("src/test/resources/chr12-244000.bam"));
		SAMEvidenceSource ses = new SAMEvidenceSource(getCommandlineContext(), input, 0);
		ses.completeSteps(ProcessStep.ALL_STEPS);
		AssemblyEvidenceSource aes = new AssemblyEvidenceSource(getCommandlineContext(), ImmutableList.of(ses), output);
		aes.ensureAssembled();
		File dir = new File(new File(super.testFolder.getRoot(), "visualisation"), "chr12");
		File[] precollapse = dir.listFiles((FileFilter)new WildcardFileFilter("*.precollapse.gexf"));
		File[] subgraph = dir.listFiles((FileFilter)new WildcardFileFilter("*.subgraph.gexf"));
		assertTrue(precollapse != null && precollapse.length > 0);
		assertTrue(subgraph != null && subgraph.length > 0);
	}
	@Override
	public ProcessingContext getCommandlineContext(boolean perChr) {
		ProcessingContext pc = super.getCommandlineContext(perChr);
		pc.getAssemblyParameters().maxBaseMismatchForCollapse = 1;
		pc.getAssemblyParameters().collapseBubblesOnly = false;
		pc.getAssemblyParameters().debruijnGraphVisualisationDirectory = new File(super.testFolder.getRoot(), "visualisation");
		pc.getAssemblyParameters().visualiseAll = true;
		pc.getAssemblyParameters().method = AssemblyAlgorithm.Positional;
		pc.getAssemblyParameters().includeRemoteSoftClips = false;
		return pc;
	}
}
