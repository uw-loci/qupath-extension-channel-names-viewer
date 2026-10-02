package qupath.ext.channelnamesviewer.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TissueMaskTest {

    /** Six channels; the first {@code glass} pixels are dark in all of them. */
    private static float[][] image(int n, int glass, long seed) {
        Random r = new Random(seed);
        float[][] s = new float[6][n];
        for (int c = 0; c < 6; c++) {
            for (int i = 0; i < n; i++) {
                double v = i < glass
                        ? 170 + r.nextGaussian() * 8                     // glass: camera offset + read noise
                        : 450 + r.nextGaussian() * 60                     // tissue autofluorescence
                                + (r.nextDouble() < 0.3 ? 1500 * r.nextDouble() : 0); // some positive cells
                s[c][i] = (float) Math.max(0, Math.round(v));
            }
        }
        return s;
    }

    @Test
    void glassDarkInEveryChannelIsLeftOut() {
        int n = 200_000;
        float[][] s = image(n, 30_000, 1);
        boolean[] tissue = TissueMask.compute(s);
        assertNotNull(tissue);
        int glassKept = 0;
        int tissueDropped = 0;
        for (int i = 0; i < n; i++) {
            if (i < 30_000 && tissue[i]) {
                glassKept++;
            }
            if (i >= 30_000 && !tissue[i]) {
                tissueDropped++;
            }
        }
        assertTrue(glassKept < 300, "glass kept " + glassKept);
        assertTrue(tissueDropped < 0.02 * (n - 30_000), "tissue dropped " + tissueDropped);
    }

    @Test
    void backgroundIsMeasuredOnTissueOnceGlassIsLeftOut() {
        float[][] s = image(200_000, 40_000, 2);
        var before = BackgroundContrast.compute(s[0], 3, 0.005, 0.001);
        var after = BackgroundContrast.compute(TissueMask.select(s[0], TissueMask.compute(s)), 3, 0.005, 0.001);
        assertEquals(170, before.background(), 10);
        assertEquals(450, after.background(), 25);
    }

    @Test
    void allTissueImageIsLeftAlone() {
        assertNull(TissueMask.compute(image(200_000, 0, 3)));
    }

    @Test
    void negativeCellsInOneChannelAreNotTakenForGlass() {
        // Channel 0 is low on a third of the tissue (negative cells), the others are not
        float[][] s = image(200_000, 0, 4);
        for (int i = 0; i < 70_000; i++) {
            s[0][i] = 170;
        }
        assertNull(TissueMask.compute(s));
    }

    @Test
    void tooFewChannels() {
        float[][] s = image(50_000, 10_000, 5);
        assertNull(TissueMask.compute(new float[][] {s[0], s[1]}));
    }
}
