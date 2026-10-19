package dev.zypec.izomap.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fades each surface towards the fog color by how far it is, using the depth the ray
 * walk recorded.
 *
 * <p>The fog color defaults to the sky's own, row by row, so the far edge of the world
 * dissolves into the sky behind it instead of ending on a hard line. The blend leaves
 * the palette, so it is dithered and snapped back the same way the sky and the focus
 * blur are: the eye reads the tone between two palette entries.</p>
 *
 * <p>Sky pixels and holes are left alone, and so is anything nearer than the fog's
 * start, so those keep the exact color the walk gave them.</p>
 */
public final class FogPass {

    /**
     * Ordered dither thresholds, the standard 4x4 Bayer matrix.
     */
    private static final double[] BAYER = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
    };

    private static final int CELL = 4;
    private static final int CELL_SIZE = CELL * CELL;

    private FogPass() {
    }

    /**
     * Returns the image with distance fog over it, or the image itself when there is
     * nothing to do.
     *
     * @param sky      the frame's sky, which the fog takes its color from unless the
     *                 spec names one
     * @param executor pool the row bands are dispatched to
     * @param threads  how many bands, and therefore threads, to use
     */
    public static RenderResult apply(RenderResult source, FogSpec fog, Sky sky,
                                     MapColorConverter converter, Executor executor, int threads) {
        var depth = source.depth();
        if (depth == null || !fog.draws() || (fog.rgb() == FogSpec.SKY && !sky.draws()))
            return source;

        var height = source.height();
        var out = new int[source.argb().length];

        var bands = Math.max(1, Math.min(threads, height));
        var rowsPerBand = (height + bands - 1) / bands;
        var pending = new CompletableFuture<?>[bands - 1];
        for (var band = 0; band < bands - 1; band++) {
            final var from = band * rowsPerBand;
            final var to = Math.min(height, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> fogBand(source, fog, sky, converter, out, from, to), executor);
        }
        fogBand(source, fog, sky, converter, out, (bands - 1) * rowsPerBand, height);
        CompletableFuture.allOf(pending).join();

        return new RenderResult(source.width(), height, out, depth);
    }

    /**
     * Fogs the row range {@code [yFrom, yTo)}.
     */
    private static void fogBand(RenderResult source, FogSpec fog, Sky sky, MapColorConverter converter,
                                int[] out, int yFrom, int yTo) {
        var width = source.width();
        var argb = source.argb();
        var depth = source.depth();
        var invSpan = 1.0 / Math.max(1.0e-6, fog.end() - fog.start());

        for (var y = yFrom; y < yTo; y++) {
            var fogRgb = fog.rgb() == FogSpec.SKY ? sky.rgbAt(y) : fog.rgb();
            var fogR = (fogRgb >> 16) & 0xFF;
            var fogG = (fogRgb >> 8) & 0xFF;
            var fogB = fogRgb & 0xFF;

            for (var x = 0; x < width; x++) {
                var index = y * width + x;
                var color = argb[index];
                var d = depth[index];
                if (d == RenderResult.SKY_DEPTH || (color >>> 24) == 0 || d <= fog.start()) {
                    out[index] = color;
                    continue;
                }

                var amount = fog.strength() * smoothstep((d - fog.start()) * invSpan);
                var offset = fog.dither() > 0.0
                        ? (BAYER[(y & 3) * CELL + (x & 3)] / (CELL_SIZE - 1.0) - 0.5) * fog.dither() * amount
                        : 0.0;
                var r = lerp((color >> 16) & 0xFF, fogR, amount) + offset;
                var g = lerp((color >> 8) & 0xFF, fogG, amount) + offset;
                var b = lerp(color & 0xFF, fogB, amount) + offset;
                out[index] = 0xFF000000 | converter.snap((clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
    }

    /**
     * Eases in and out, so the fog neither starts on a visible line nor stops on one.
     */
    private static double smoothstep(double t) {
        var c = t < 0.0 ? 0.0 : Math.min(t, 1.0);
        return c * c * (3.0 - 2.0 * c);
    }

    private static double lerp(int from, int to, double t) {
        return from + (to - from) * t;
    }

    private static int clamp(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
