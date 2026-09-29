package qupath.ext.channelnamesviewer.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BackgroundContrastTest {

    /** Background N(mean, sd) with a fraction of brighter signal, as integers. */
    private static float[] sparseChannel(double bgMean, double bgSd, double signalFraction, long seed) {
        Random r = new Random(seed);
        float[] v = new float[400_000];
        for (int i = 0; i < v.length; i++) {
            double x = r.nextDouble() < signalFraction
                    ? 2000 + r.nextGaussian() * 600
                    : bgMean + r.nextGaussian() * bgSd;
            v[i] = (float) Math.max(0, Math.round(x));
        }
        return v;
    }

    @Test
    void minimumSitsAboveTheBackgroundPeak() {
        var res = BackgroundContrast.compute(sparseChannel(300, 30, 0.1, 1), 3, 0.005, 0.001);
        assertTrue(res.backgroundPeak());
        assertEquals(300, res.background(), 5);
        assertEquals(30, res.noise(), 4);
        assertEquals(390, res.min(), 15);
        // About 10% signal is above the minimum, plus the 0.13% Gaussian tail
        assertEquals(0.10, res.foreground(), 0.01);
        assertTrue(res.max() > 2000 && res.max() < 4000, "max " + res.max());
    }

    @Test
    void noiseMultipleMovesTheMinimum() {
        float[] v = sparseChannel(300, 30, 0.1, 2);
        var k0 = BackgroundContrast.compute(v, 0, 0.005, 0.001);
        var k5 = BackgroundContrast.compute(v, 5, 0.005, 0.001);
        assertEquals(300, k0.min(), 5);
        assertEquals(450, k5.min(), 20);
    }

    @Test
    void percentileMinimumWouldLeaveBackgroundLit() {
        // The haze: with a 0.1% percentile minimum the background peak maps well above black
        float[] v = sparseChannel(300, 30, 0.1, 3);
        var res = BackgroundContrast.compute(v, 3, 0.005, 0.001);
        java.util.Arrays.sort(v);
        double pctMin = v[(int) (0.001 * v.length)];
        assertTrue(pctMin < res.background(), "percentile min " + pctMin);
        assertTrue(res.min() > res.background());
    }

    @Test
    void denseStainWithoutBackgroundPeakFallsBackToPercentiles() {
        Random r = new Random(4);
        float[] v = new float[200_000];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) Math.max(0, Math.round(2500 + r.nextGaussian() * 1000));
        }
        var res = BackgroundContrast.compute(v, 3, 0.005, 0.001);
        assertFalse(res.backgroundPeak());
        assertTrue(res.max() > res.min());
        assertTrue(res.min() < 500, "min " + res.min());
    }

    @Test
    void zeroPaddingSpikeIsIgnored() {
        float[] v = sparseChannel(300, 30, 0.1, 5);
        for (int i = 0; i < v.length / 2; i++) {
            v[i] = 0;
        }
        var res = BackgroundContrast.compute(v, 3, 0.005, 0.001);
        assertTrue(res.backgroundPeak());
        assertEquals(300, res.background(), 5);
    }

    @Test
    void backgroundClippedAtZeroUsesTheRightSide() {
        // Background-subtracted data: peak at 0-ish, left edge clipped
        Random r = new Random(6);
        float[] v = new float[300_000];
        for (int i = 0; i < v.length; i++) {
            double x = r.nextDouble() < 0.1 ? 1500 + r.nextGaussian() * 300 : Math.abs(r.nextGaussian() * 20) + 1;
            v[i] = (float) Math.round(x);
        }
        var res = BackgroundContrast.compute(v, 3, 0.005, 0.001);
        assertTrue(res.backgroundPeak());
        assertTrue(res.min() > 20 && res.min() < 150, "min " + res.min());
    }

    @Test
    void floatDataIsBinned() {
        float[] v = sparseChannel(300, 30, 0.1, 7);
        for (int i = 0; i < v.length; i++) {
            v[i] = v[i] / 1000f + 0.0003f * (i % 3);
        }
        var res = BackgroundContrast.compute(v, 3, 0.005, 0.001);
        assertTrue(res.backgroundPeak());
        assertEquals(0.39, res.min(), 0.02);
    }

    @Test
    void constantChannel() {
        var res = BackgroundContrast.compute(new float[] {5, 5, 5}, 3, 0.005, 0.001);
        assertTrue(res.max() > res.min());
        assertFalse(res.backgroundPeak());
    }
}
