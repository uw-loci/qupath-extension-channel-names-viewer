package qupath.ext.channelnamesviewer.core;

/**
 * Display range for one fluorescence channel, set from the shape of its histogram rather
 * than from percentiles of all pixels.
 *
 * <p>Most of a multiplex channel is background: a tall, narrow peak at a low value whose
 * rising (left) edge is pure noise. Percentile auto-contrast puts the display minimum
 * below that peak, so every channel adds a little grey everywhere and many channels sum
 * to a haze. Here the minimum is set above the peak by a multiple of its noise width, so
 * background renders black, and the maximum from the brightest pixels above it.</p>
 *
 * <p>A channel whose tallest peak is not a narrow background peak (a dense nuclear stain
 * covering the whole field) falls back to percentiles and reports it.</p>
 */
public final class BackgroundContrast {

    /** Bins of the full-range histogram; width 1 for 16-bit data. */
    static final int FULL_BINS = 65536;
    /** Bins used to find and measure the background peak. */
    static final int PEAK_BINS = 2048;
    /** Moving-average width (bins) applied before finding the peak. */
    static final int SMOOTH = 5;
    /** Below this fraction of pixels above the minimum, no background peak was found. */
    static final double MIN_FOREGROUND_FRACTION = 0.002;
    /**
     * Largest background noise, as a fraction of the distance from the peak to the bright
     * end (99.9th percentile). Sparse markers measured 0.01-0.06; a dense nuclear stain 0.43.
     */
    static final double MAX_PEAK_WIDTH_FRACTION = 0.2;
    /** Half width at half maximum of a Gaussian, in standard deviations. */
    private static final double HWHM_PER_SIGMA = Math.sqrt(2 * Math.log(2));

    private BackgroundContrast() {
    }

    /**
     * @param min             display minimum
     * @param max             display maximum
     * @param background      value of the background peak; NaN if none was found
     * @param noise           background noise (standard deviation) from the peak's width; NaN if none
     * @param foreground      fraction of pixels above {@code min}
     * @param backgroundPeak  false if percentiles were used because there is no narrow background peak
     */
    public record Result(double min, double max, double background, double noise,
                         double foreground, boolean backgroundPeak) {
    }

    /**
     * @param values          sampled pixel values of one channel
     * @param noiseMultiple   how many noise widths above the background peak the minimum sits
     * @param saturated       fraction of the pixels above the minimum allowed to saturate at the maximum
     * @param fallbackSaturated fraction saturated at each end when percentiles are used
     */
    public static Result compute(float[] values, double noiseMultiple, double saturated,
                                 double fallbackSaturated) {
        if (values == null || values.length == 0) {
            return new Result(0, 1, Double.NaN, Double.NaN, 0, false);
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (float v : values) {
            if (Float.isFinite(v)) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        if (!(hi > lo)) {
            double v = Double.isFinite(lo) ? lo : 0;
            return new Result(v, v + 1, v, 0, 0, false);
        }
        Hist full = new Hist(values, lo, hi, FULL_BINS);
        // Unscanned / padded regions show as a spike in the first bin; QuPath skips it too
        if (full.counts[0] > full.counts[1]) {
            full.total -= full.counts[0];
            full.counts[0] = 0;
        }
        if (full.total <= 0) {
            return new Result(lo, hi, Double.NaN, Double.NaN, 0, false);
        }
        double p999 = full.quantile(0.999);
        Result peak = fromPeak(full, lo, p999, noiseMultiple, saturated);
        if (peak != null) {
            return peak;
        }
        double fMin = full.quantile(fallbackSaturated);
        double fMax = full.quantile(1 - fallbackSaturated);
        if (!(fMax > fMin)) {
            fMax = fMin + full.binWidth;
        }
        return new Result(fMin, fMax, Double.NaN, Double.NaN, full.fractionAbove(fMin), false);
    }

    /** The background-peak range, or null if the tallest peak is not a narrow background peak. */
    private static Result fromPeak(Hist full, double lo, double p999, double noiseMultiple,
                                   double saturated) {
        if (!(p999 > lo)) {
            return null;
        }
        double width = (p999 - lo) / PEAK_BINS;
        double[] counts = new double[PEAK_BINS];
        for (int i = 0; i < full.counts.length; i++) {
            double centre = full.lo + (i + 0.5) * full.binWidth;
            int j = (int) ((centre - lo) / width);
            if (j >= 0 && j < PEAK_BINS) {
                counts[j] += full.counts[i];
            }
        }
        double[] smooth = smooth(counts);
        int mode = 0;
        for (int i = 1; i < PEAK_BINS; i++) {
            if (smooth[i] > smooth[mode]) {
                mode = i;
            }
        }
        double half = smooth[mode] / 2;
        int left = mode;
        while (left > 0 && smooth[left] > half) {
            left--;
        }
        int right = mode;
        while (right < PEAK_BINS - 1 && smooth[right] > half) {
            right++;
        }
        // The rising edge is noise only; the falling edge carries signal. Use the right
        // side only when the peak sits against the lowest value (background clipped at 0).
        boolean leftClipped = smooth[left] > half;
        int halfBins = Math.max(1, leftClipped ? right - mode : mode - left);
        double noise = halfBins * width / HWHM_PER_SIGMA;
        // The top of a noisy peak is flat; its centroid over a window symmetric about
        // the peak is steadier than the tallest bin
        double sum = 0;
        double weighted = 0;
        for (int i = Math.max(0, mode - halfBins); i <= Math.min(PEAK_BINS - 1, mode + halfBins); i++) {
            sum += counts[i];
            weighted += counts[i] * i;
        }
        double background = lo + ((sum > 0 ? weighted / sum : mode) + 0.5) * width;
        if (noise > MAX_PEAK_WIDTH_FRACTION * (p999 - background)) {
            return null;
        }
        double min = background + noiseMultiple * noise;
        double foreground = full.fractionAbove(min);
        if (foreground < MIN_FOREGROUND_FRACTION) {
            return null;
        }
        double max = full.quantile(1 - saturated * foreground);
        if (!(max > min)) {
            return null;
        }
        return new Result(min, max, background, noise, foreground, true);
    }

    private static double[] smooth(double[] counts) {
        double[] out = new double[counts.length];
        int r = SMOOTH / 2;
        for (int i = 0; i < counts.length; i++) {
            double sum = 0;
            int n = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(counts.length - 1, i + r); j++) {
                sum += counts[j];
                n++;
            }
            out[i] = sum / n;
        }
        return out;
    }

    /** Fixed-width histogram over [lo, hi]. */
    private static final class Hist {
        final long[] counts;
        final double lo;
        final double binWidth;
        long total;

        Hist(float[] values, double lo, double hi, int maxBins) {
            this.lo = lo;
            double range = hi - lo;
            // Width 1 for integer data that fits, so each value has its own bin
            boolean unitBins = range + 1 <= maxBins && isInteger(values);
            int bins = unitBins ? (int) Math.ceil(range) + 1 : maxBins;
            this.binWidth = unitBins ? 1.0 : range / (maxBins - 1);
            this.counts = new long[Math.max(2, bins)];
            for (float v : values) {
                if (Float.isFinite(v)) {
                    int i = (int) ((v - lo) / binWidth);
                    counts[Math.min(counts.length - 1, Math.max(0, i))]++;
                    total++;
                }
            }
        }

        private static boolean isInteger(float[] values) {
            for (float v : values) {
                if (Float.isFinite(v) && v != Math.rint(v)) {
                    return false;
                }
            }
            return true;
        }

        /** Value below which fraction {@code q} of the counted pixels lie. */
        double quantile(double q) {
            double target = Math.max(0, Math.min(1, q)) * total;
            long cum = 0;
            for (int i = 0; i < counts.length; i++) {
                if (cum + counts[i] >= target && counts[i] > 0) {
                    double within = (target - cum) / counts[i];
                    return lo + (i + within) * binWidth;
                }
                cum += counts[i];
            }
            return lo + counts.length * binWidth;
        }

        double fractionAbove(double value) {
            int i = (int) Math.floor((value - lo) / binWidth);
            long above = 0;
            for (int j = Math.max(0, i + 1); j < counts.length; j++) {
                above += counts[j];
            }
            return total > 0 ? (double) above / total : 0;
        }
    }
}
