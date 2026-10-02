package qupath.ext.channelnamesviewer.core;

import java.util.Arrays;

/**
 * Finds sampled pixels off the tissue -- empty glass or unscanned area -- so they can be
 * left out of each channel's histogram. Off-tissue pixels are dark in every channel at
 * once, so their rank within each channel is low in nearly all channels; a negative cell
 * is low in one marker but not in the nuclear or autofluorescence channels. The median
 * rank across channels therefore shows off-tissue area as a separate low peak.
 *
 * <p>Without such a peak (an image that is all tissue) nothing is excluded, so the
 * background of each channel is still measured on its darkest tissue.</p>
 */
public final class TissueMask {

    /** Fewer channels than this cannot show "dark in every channel". */
    static final int MIN_CHANNELS = 3;
    static final int BINS = 100;
    /** The off-tissue peak is looked for below this median rank. */
    static final double LOW_REGION = 0.4;
    /** The valley above it must fall below this fraction of both peaks. */
    static final double MAX_VALLEY_FRACTION = 0.35;
    /** Smallest off-tissue fraction worth excluding. */
    static final double MIN_OFF_TISSUE = 0.01;

    private TissueMask() {
    }

    /**
     * @param samples values[channel][pixel], all channels the same length
     * @return true for each pixel on tissue, or null if no off-tissue area was found
     */
    public static boolean[] compute(float[][] samples) {
        if (samples == null || samples.length < MIN_CHANNELS || samples[0].length == 0) {
            return null;
        }
        int nc = samples.length;
        int n = samples[0].length;
        // Rank within each channel by a cumulative histogram: one lookup per value
        RankTable[] tables = new RankTable[nc];
        for (int c = 0; c < nc; c++) {
            tables[c] = new RankTable(samples[c]);
        }
        float[] median = new float[n];
        java.util.stream.IntStream.range(0, (n + 4095) / 4096).parallel().forEach(block -> {
            float[] ranks = new float[nc];
            int end = Math.min(n, (block + 1) * 4096);
            for (int i = block * 4096; i < end; i++) {
                for (int c = 0; c < nc; c++) {
                    ranks[c] = tables[c].rank(samples[c][i]);
                }
                Arrays.sort(ranks);
                median[i] = nc % 2 == 1 ? ranks[nc / 2] : 0.5f * (ranks[nc / 2 - 1] + ranks[nc / 2]);
            }
        });
        double cut = offTissueCut(median);
        if (Double.isNaN(cut)) {
            return null;
        }
        boolean[] tissue = new boolean[n];
        int off = 0;
        for (int i = 0; i < n; i++) {
            tissue[i] = median[i] >= cut;
            if (!tissue[i]) {
                off++;
            }
        }
        return off >= MIN_OFF_TISSUE * n ? tissue : null;
    }

    /**
     * The median rank separating a low off-tissue peak from the tissue, or NaN if the
     * histogram has no low peak with a deep valley above it.
     */
    static double offTissueCut(float[] median) {
        double[] h = new double[BINS];
        for (float m : median) {
            h[Math.min(BINS - 1, Math.max(0, (int) (m * BINS)))]++;
        }
        double[] s = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            double sum = 0;
            int k = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(BINS - 1, i + 1); j++) {
                sum += h[j];
                k++;
            }
            s[i] = sum / k;
        }
        int lowEnd = (int) (LOW_REGION * BINS);
        int low = 0;
        for (int i = 1; i < lowEnd; i++) {
            if (s[i] > s[low]) {
                low = i;
            }
        }
        int high = lowEnd;
        for (int i = lowEnd; i < BINS; i++) {
            if (s[i] > s[high]) {
                high = i;
            }
        }
        int valley = low;
        for (int i = low; i <= high; i++) {
            if (s[i] < s[valley]) {
                valley = i;
            }
        }
        if (valley == low || valley == high) {
            return Double.NaN;
        }
        double limit = MAX_VALLEY_FRACTION * Math.min(s[low], s[high]);
        return s[valley] < limit ? (valley + 0.5) / BINS : Double.NaN;
    }

    /** Fraction of a channel's values below a value, from a 65,536-bin cumulative histogram. */
    private static final class RankTable {
        private static final int TABLE_BINS = 65536;
        private final double lo;
        private final double scale;
        private final float[] below;

        RankTable(float[] values) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (float v : values) {
                if (Float.isFinite(v)) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            lo = Double.isFinite(min) ? min : 0;
            scale = max > lo ? (TABLE_BINS - 1) / (max - lo) : 0;
            long[] counts = new long[TABLE_BINS];
            for (float v : values) {
                counts[bin(v)]++;
            }
            below = new float[TABLE_BINS];
            long cum = 0;
            for (int i = 0; i < TABLE_BINS; i++) {
                below[i] = (float) cum / values.length;
                cum += counts[i];
            }
        }

        private int bin(float v) {
            if (!Float.isFinite(v)) {
                return 0;
            }
            return (int) Math.max(0, Math.min(TABLE_BINS - 1, (v - lo) * scale));
        }

        float rank(float v) {
            return below[bin(v)];
        }
    }

    /** The values of {@code values} at the pixels marked true. */
    public static float[] select(float[] values, boolean[] keep) {
        int count = 0;
        for (boolean k : keep) {
            if (k) {
                count++;
            }
        }
        float[] out = new float[count];
        int j = 0;
        for (int i = 0; i < values.length; i++) {
            if (keep[i]) {
                out[j++] = values[i];
            }
        }
        return out;
    }
}
