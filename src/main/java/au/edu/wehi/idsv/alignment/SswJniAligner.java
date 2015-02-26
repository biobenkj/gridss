package au.edu.wehi.idsv.alignment;

import htsjdk.samtools.util.SequenceUtil;

public class SswJniAligner implements Aligner {
	private static final int MATRIX_SIZE = 128;
	private final int gapOpen;
	private final int gapExtend;
	private final int[][] matrix;
	public SswJniAligner(int match, int mismatch, int ambiguous, int gapOpen, int gapExtend) {
		this.gapOpen = gapOpen;
		this.gapExtend = gapExtend;
		this.matrix = createMatrix(match, mismatch, ambiguous);
	}
	private static int[][] createMatrix(int match, int mismatch, int ambiguous) {
		int[][] scores = new int[MATRIX_SIZE][MATRIX_SIZE];
        // Fill the matrix with the scores
        for (int i = 0; i < MATRIX_SIZE; i++) {
            for (int j = 0; j < MATRIX_SIZE; j++) {
                if (Character.toUpperCase(i) == Character.toUpperCase(j)) {
                    scores[i][j] = match;
                } else if (SequenceUtil.isValidBase((byte) i) && SequenceUtil.isValidBase((byte) j)) {
                    scores[i][j] = mismatch;
                } else {
                	scores[i][j] = ambiguous;
                }
            }
        }
        return scores;
	}
	@Override
	public Alignment align_smith_waterman(byte[] seq, byte[] ref) {
		ssw.Alignment result = ssw.Aligner.align(seq, ref, matrix, gapOpen, gapExtend, true);
		String cigar = result.cigar;
		if (result.read_begin1 != 0) {
			cigar = Integer.toString(result.read_begin1) + "S" + cigar;
		}
		int endOffset = seq.length - result.read_end1 - 1;
		if (endOffset != 0) {
			cigar += Integer.toString(endOffset) + "S";
		}
		return new Alignment(result.ref_begin1, cigar);
	}
}