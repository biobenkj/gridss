package gridss.cmdline;

import java.io.FileNotFoundException;

import au.edu.wehi.idsv.picard.ReferenceLookup;
import au.edu.wehi.idsv.picard.TwoBitBufferedReferenceSequenceFile;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import picard.cmdline.CommandLineProgram;

public abstract class ReferenceCommandLineProgram extends CommandLineProgram {
	private static final Log log = Log.getInstance(ReferenceCommandLineProgram.class);
	private ReferenceLookup reference;
	public ReferenceLookup getReference() {
		IOUtil.assertFileIsReadable(REFERENCE_SEQUENCE);
		if (reference == null) {
			try {
				reference = new TwoBitBufferedReferenceSequenceFile(new IndexedFastaSequenceFile(REFERENCE_SEQUENCE));
			} catch (FileNotFoundException e) {
				String msg = String.format("Missing reference genome %s", REFERENCE_SEQUENCE);
				log.error(msg);
				throw new RuntimeException(msg);
			}
		}
		return reference;
	}
	public void setReference(ReferenceLookup ref) {
		this.reference =  ref;
	}
	@Override
	protected String[] customCommandLineValidation() {
		String[] val = referenceCustomCommandLineValidation();
		if (val != null) return val;
		return super.customCommandLineValidation();
	}
	public String[] referenceCustomCommandLineValidation() {
		if (referenceRequired()) {
			if (REFERENCE_SEQUENCE == null) {
	            return new String[]{"Must have a non-null REFERENCE_SEQUENCE"};
	        }
		}
		return null;
	}
	public boolean referenceRequired() { return true; }
	/**
	 * Copies the command line inputs to the given program
	 * @param cmd program to set command line for
	 */
	public void copyInputs(CommandLineProgram cmd) {
		CommandLineProgramHelper.copyInputs(this, cmd);
	}
}
