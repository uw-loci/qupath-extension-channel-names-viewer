/*
 * Colour wheel maths ported from Sara McArdle's Channel Color Chooser
 * (https://saramcardle.github.io/ColorWheelPicker/, MIT License, Copyright (c) Sara McArdle).
 */
package qupath.ext.channelnamesviewer.core;

/**
 * Evenly spaced channel colours around an HSV or CIELAB colour wheel.
 */
public final class WheelColors {

    /** Colour model of the wheel. */
    public enum Mode {
        HSV("HSV"),
        CIELAB("CIELAB (perceptually uniform)");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Rotates the CIELAB wheel so red sits near the top, as on the HSV wheel. */
    static final double LAB_HUE_OFFSET = 40;

    private static final int CHROMA_STEPS = 180;

    private final Mode mode;
    private final double lightness;
    private final double[] maxChroma;

    /**
     * @param lightness HSV value (0-1) or CIELAB L* (0-100)
     */
    public WheelColors(Mode mode, double lightness) {
        this.mode = mode;
        this.lightness = lightness;
        this.maxChroma = mode == Mode.CIELAB ? maxChromaTable(lightness) : null;
    }

    public Mode mode() {
        return mode;
    }

    public double lightness() {
        return lightness;
    }

    /**
     * Spoke angles in degrees, clockwise from the top.
     *
     * @param n    number of spokes
     * @param base angle of the first spoke
     */
    public static double[] spokeAngles(int n, double base) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = normalize(base + i * 360.0 / n);
        }
        return a;
    }

    /**
     * Which spoke each channel takes. In order, channel i gets spoke i; spread, neighbouring
     * channels are about 137 degrees apart so channels listed together differ most.
     */
    public static int[] spokeOrder(int n, boolean spread) {
        int[] order = new int[n];
        int stride = 1;
        if (spread && n > 3) {
            stride = (int) Math.round(n * 0.382);
            while (gcd(stride, n) != 1) {
                stride++;
            }
        }
        for (int i = 0; i < n; i++) {
            order[i] = (int) ((long) i * stride % n);
        }
        return order;
    }

    /**
     * The colour at a point on the wheel.
     *
     * @param angleDeg   angle clockwise from the top
     * @param saturation distance from the centre, 0-1
     * @return packed RGB
     */
    public int rgb(double angleDeg, double saturation) {
        int[] c;
        if (mode == Mode.CIELAB) {
            double hue = normalize(angleDeg + LAB_HUE_OFFSET);
            c = labHueToRgb(hue, saturation * maxChromaForHue(hue), lightness);
        } else {
            c = hsvToRgb(normalize(angleDeg), saturation, lightness);
        }
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    static double normalize(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    static int[] hsvToRgb(double h, double s, double v) {
        double c = v * s;
        double hp = h / 60;
        double x = c * (1 - Math.abs((hp % 2) - 1));
        double r = 0;
        double g = 0;
        double b = 0;
        if (hp < 1) {
            r = c;
            g = x;
        } else if (hp < 2) {
            r = x;
            g = c;
        } else if (hp < 3) {
            g = c;
            b = x;
        } else if (hp < 4) {
            g = x;
            b = c;
        } else if (hp < 5) {
            r = x;
            b = c;
        } else {
            r = c;
            b = x;
        }
        double m = v - c;
        return new int[] {(int) Math.round((r + m) * 255), (int) Math.round((g + m) * 255),
                (int) Math.round((b + m) * 255)};
    }

    private static double[] labToLinearSrgb(double l, double a, double b) {
        double fy = (l + 16) / 116;
        double fx = fy + a / 500;
        double fz = fy - b / 200;
        double x = 0.95047 * finv(fx);
        double y = finv(fy);
        double z = 1.08883 * finv(fz);
        return new double[] {
                3.2406 * x - 1.5372 * y - 0.4986 * z,
                -0.9689 * x + 1.8758 * y + 0.0415 * z,
                0.0557 * x - 0.2040 * y + 1.0570 * z};
    }

    private static double finv(double t) {
        double d = 6.0 / 29;
        return t > d ? t * t * t : 3 * d * d * (t - 4.0 / 29);
    }

    private static double gammaCompand(double c) {
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    private static boolean inGamut(double[] lin) {
        double eps = 0.0008;
        for (double v : lin) {
            if (v < -eps || v > 1 + eps) {
                return false;
            }
        }
        return true;
    }

    static int[] labHueToRgb(double hueDeg, double chroma, double l) {
        double h = Math.toRadians(hueDeg);
        double[] lin = labToLinearSrgb(l, chroma * Math.cos(h), chroma * Math.sin(h));
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = (int) Math.round(gammaCompand(Math.min(1, Math.max(0, lin[i]))) * 255);
        }
        return out;
    }

    /** Largest in-gamut chroma for each hue at lightness {@code l}. */
    private static double[] maxChromaTable(double l) {
        double[] table = new double[CHROMA_STEPS + 1];
        for (int i = 0; i <= CHROMA_STEPS; i++) {
            double h = Math.toRadians(i * 360.0 / CHROMA_STEPS);
            double lo = 0;
            double hi = 200;
            for (int iter = 0; iter < 24; iter++) {
                double mid = (lo + hi) / 2;
                if (inGamut(labToLinearSrgb(l, mid * Math.cos(h), mid * Math.sin(h)))) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            table[i] = lo;
        }
        return table;
    }

    private double maxChromaForHue(double hueDeg) {
        double pos = normalize(hueDeg) / 360 * CHROMA_STEPS;
        int i0 = (int) Math.floor(pos) % CHROMA_STEPS;
        int i1 = (i0 + 1) % CHROMA_STEPS;
        double frac = pos - Math.floor(pos);
        return maxChroma[i0] * (1 - frac) + maxChroma[i1] * frac;
    }
}
