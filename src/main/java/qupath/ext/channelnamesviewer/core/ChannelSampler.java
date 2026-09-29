package qupath.ext.channelnamesviewer.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * Samples full-resolution pixels of every channel from a grid of tiles spread over the
 * image. Full resolution keeps the background noise at the width the viewer shows when
 * zoomed in; a downsampled read averages it away and would put the minimum too low.
 */
public final class ChannelSampler {

    /** Tiles per side of the sampling grid. */
    static final int GRID = 6;
    /** Tile side in pixels; 6 x 6 tiles of 170 px is about a million pixels per channel. */
    static final int TILE = 170;

    private ChannelSampler() {
    }

    /**
     * @param cancelled polled between tiles
     * @return values[channel][pixel], or null if cancelled
     */
    public static float[][] sample(ImageServer<BufferedImage> server, int z, int t,
                                   BooleanSupplier cancelled) throws IOException {
        int w = server.getWidth();
        int h = server.getHeight();
        int nc = server.nChannels();
        int[][] tiles = tiles(w, h);
        long total = 0;
        for (int[] r : tiles) {
            total += (long) r[2] * r[3];
        }
        float[][] values = new float[nc][(int) total];
        int offset = 0;
        for (int[] r : tiles) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return null;
            }
            BufferedImage img = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), 1.0, r[0], r[1], r[2], r[3], z, t));
            int n = img.getWidth() * img.getHeight();
            var raster = img.getRaster();
            float[] buf = new float[n];
            for (int c = 0; c < nc; c++) {
                raster.getSamples(0, 0, img.getWidth(), img.getHeight(), c, buf);
                System.arraycopy(buf, 0, values[c], offset, Math.min(n, values[c].length - offset));
            }
            offset += n;
        }
        return values;
    }

    /** Tile rectangles {x, y, w, h}: the whole image if small, else an evenly spaced grid. */
    static int[][] tiles(int width, int height) {
        if ((long) width * height <= (long) GRID * GRID * TILE * TILE) {
            return new int[][] {{0, 0, width, height}};
        }
        int nx = width >= GRID * TILE ? GRID : Math.max(1, width / TILE);
        int ny = height >= GRID * TILE ? GRID : Math.max(1, height / TILE);
        int tw = Math.min(TILE, width);
        int th = Math.min(TILE, height);
        int[][] out = new int[nx * ny][];
        for (int gy = 0; gy < ny; gy++) {
            for (int gx = 0; gx < nx; gx++) {
                int x = nx == 1 ? (width - tw) / 2 : (int) ((long) (width - tw) * gx / (nx - 1));
                int y = ny == 1 ? (height - th) / 2 : (int) ((long) (height - th) * gy / (ny - 1));
                out[gy * nx + gx] = new int[] {x, y, tw, th};
            }
        }
        return out;
    }
}
