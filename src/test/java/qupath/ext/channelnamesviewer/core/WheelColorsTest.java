package qupath.ext.channelnamesviewer.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WheelColorsTest {

    @Test
    void spokesAreEvenlySpaced() {
        assertArrayEquals(new double[] {30, 150, 270}, WheelColors.spokeAngles(3, 30), 1e-9);
        assertArrayEquals(new double[] {350, 80, 170, 260}, WheelColors.spokeAngles(4, 350), 1e-9);
    }

    @Test
    void hsvPrimaries() {
        var w = new WheelColors(WheelColors.Mode.HSV, 1.0);
        assertEquals(0xff0000, w.rgb(0, 1));
        assertEquals(0x00ff00, w.rgb(120, 1));
        assertEquals(0x0000ff, w.rgb(240, 1));
        assertEquals(0xffffff, w.rgb(77, 0));
    }

    @Test
    void cielabKeepsLightnessAcrossHues() {
        var w = new WheelColors(WheelColors.Mode.CIELAB, 65);
        // Same L*: relative luminance of every hue within a narrow band (sRGB Y for L*=65 is ~0.34)
        for (double a = 0; a < 360; a += 30) {
            int c = w.rgb(a, 1);
            double y = 0.2126 * lin((c >> 16) & 255) + 0.7152 * lin((c >> 8) & 255) + 0.0722 * lin(c & 255);
            assertEquals(0.34, y, 0.02, "angle " + a);
        }
    }

    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    @Test
    void spreadOrderVisitsEverySpokeOnce() {
        for (int n = 1; n <= 20; n++) {
            int[] order = WheelColors.spokeOrder(n, true);
            int[] sorted = order.clone();
            Arrays.sort(sorted);
            for (int i = 0; i < n; i++) {
                assertEquals(i, sorted[i], "n=" + n);
            }
        }
        // Neighbours in the list are at least (n-1)/2 spokes apart (the most possible)
        for (int n = 5; n <= 20; n++) {
            int[] order = WheelColors.spokeOrder(n, true);
            for (int i = 1; i < n; i++) {
                int d = Math.abs(order[i] - order[i - 1]);
                assertTrue(Math.min(d, n - d) >= (n - 1) / 2, "n=" + n + " " + Arrays.toString(order));
            }
        }
        assertArrayEquals(new int[] {0, 2, 1, 3}, WheelColors.spokeOrder(4, true));
        assertArrayEquals(new int[] {0, 3, 1, 4, 2, 5}, WheelColors.spokeOrder(6, true));
    }
}
