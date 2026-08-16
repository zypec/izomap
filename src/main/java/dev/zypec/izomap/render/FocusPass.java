package dev.zypec.izomap.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Blurs what is not at the focus distance, using the depth the ray walk recorded.
 *
 * <h2>Gather, with the scatter's rule</h2>
 *
 * <p>Blur is really a scatter: every surface throws a disc of light the size of its own
 * defocus, and a pixel is the sum of the discs that reach it. Scattering cannot be
 * written that way at these sizes, so the pass gathers instead — but it keeps the
 * scatter's rule, which is what stops the classic artefact. A neighbour that lies
 * <i>in front of</i> this pixel only contributes when its own blur disc is wide enough
 * to reach here; a sharp figure therefore never smears itself over the soft background
 * behind it, while a defocused one properly spills over what it hides.</p>
 *
 * <h2>Why the disc is sampled, not swept</h2>
 *
 * <p>A full disc costs r² reads per pixel, and at photo sizes r is tens of pixels: a
 * 2048px render would run into billions of reads. The disc is therefore sampled at a
 * fixed number of taps laid out on a golden-angle spiral, which covers it evenly at any
 * radius. Cost is then a constant per pixel and {@code photo.focus.samples} is the one
 * dial that moves it, rather than the focus distance the player is dragging.</p>
 *
 * <p>The taps are precomputed on the unit disc in sixteen rotations, one per position in
 * a 4x4 tile. Rotating per pixel would be two trigonometric calls per pixel and the
 * pattern has to vary somehow, or the same spiral prints itself over every blurred
 * region.</p>
 *
 * <h2>Dithering, again</h2>
 *
 * <p>Averaging produces colors the map palette does not have, and snapping them turns a
 * soft gradient into a few wide bands — the same problem the sky had. The same answer
 * is used: an ordered 4x4 offset before the snap, so the eye reads the tone between two
 * palette entries. Pixels that are already sharp never enter the pass at all, so they
 * keep the exact palette color the walk gave them.</p>
 */
public final class FocusPass {

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

    /**
     * Golden angle: consecutive taps land on the disc without ever repeating a spoke.
     */
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    /**
     * Hard ceiling on the blur radius, whatever the config asks for. The pass is a
     * constant cost per pixel, but the reads still stray this far from it and a runaway
     * value would thrash the cache for no visible gain.
     */
    private static final int MAX_RADIUS_PX = 96;

    /**
     * Below this the disc fits inside the pixel itself, so the pixel is left exactly as
     * the walk drew it — on the palette, undithered, free.
     */
    private static final double MIN_RADIUS_PX = 1.0;

    private FocusPass() {
    }

    /**
     * Returns the image with everything away from the focus distance softened, or the
     * image itself when there is nothing to do.
     *
     * @param spanHeight world-space height of the frame, which is what
     *                   {@link FocusSpec#rangeRatio()} is measured against
     * @param executor   pool the row bands are dispatched to
     * @param threads    how many bands, and therefore threads, to use
     */
    public static RenderResult apply(RenderResult source, FocusSpec focus, double spanHeight,
                                     MapColorConverter converter, Executor executor, int threads) {
        var depth = source.depth();
        if (depth == null || !focus.draws())
            return source;

        var height = source.height();
        var radius = Math.min(focus.maxRadius() * height, MAX_RADIUS_PX);
        if (radius < MIN_RADIUS_PX)
            return source;

        // A range of zero would put every pixel at full blur, focus distance and all.
        var invRange = 1.0 / Math.max(1.0e-6, focus.rangeRatio() * spanHeight);
        var taps = discTaps(focus.samples());
        var out = new int[source.argb().length];

        var bands = Math.max(1, Math.min(threads, height));
        if (bands == 1) {
            blurBand(source, focus, converter, taps, radius, invRange, out, 0, height);
            return new RenderResult(source.width(), height, out);
        }

        // The last band runs on the calling thread, as the ray walk does.
        var rowsPerBand = (height + bands - 1) / bands;
        var pending = new CompletableFuture<?>[bands - 1];
        for (var band = 0; band < bands - 1; band++) {
            final var from = band * rowsPerBand;
            final var to = Math.min(height, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> blurBand(source, focus, converter, taps, radius, invRange, out, from, to), executor);
        }
        blurBand(source, focus, converter, taps, radius, invRange, out, (bands - 1) * rowsPerBand, height);
        CompletableFuture.allOf(pending).join();

        return new RenderResult(source.width(), height, out);
    }

    /**
     * Blurs the row range {@code [yFrom, yTo)}.
     */
    private static void blurBand(RenderResult source, FocusSpec focus, MapColorConverter converter,
                                 double[][] taps, double radius, double invRange,
                                 int[] out, int yFrom, int yTo) {
        var width = source.width();
        var height = source.height();
        var argb = source.argb();
        var depth = source.depth();
        var count = taps[0].length / 2;

        for (var y = yFrom; y < yTo; y++) {
            for (var x = 0; x < width; x++) {
                var index = y * width + x;
                var centerDepth = depth[index];
                var centerRadius = radiusFor(centerDepth, focus.distance(), invRange, radius);
                if (centerRadius < MIN_RADIUS_PX) {
                    out[index] = argb[index];
                    continue;
                }

                double sumR = 0.0, sumG = 0.0, sumB = 0.0, solid = 0.0, weight = 0.0;
                // A blurred stretch of one colour is still that colour. Averaging and
                // dithering it anyway would snap half its pixels to the palette entry
                // next door and speckle a flat wall that has nothing to blur with.
                var first = 0;
                var uniform = true;
                // Whether anything but sky ended up in the pixel. An untouched stretch of
                // sky is left alone: blurring an already dithered gradient and snapping it
                // again would only undo the dither and hand the banding back.
                var anyTerrain = false;

                var spiral = taps[(y & 3) * CELL + (x & 3)];
                // The pixel itself always counts, so a pixel every tap rejects still has
                // a colour of its own to keep.
                for (var tap = -1; tap < count; tap++) {
                    var sx = x;
                    var sy = y;
                    var reach = 0.0;
                    if (tap >= 0) {
                        reach = radiusAt(tap, count) * centerRadius;
                        sx = x + (int) Math.round(spiral[tap * 2] * centerRadius);
                        sy = y + (int) Math.round(spiral[tap * 2 + 1] * centerRadius);
                        sx = sx < 0 ? 0 : Math.min(sx, width - 1);
                        sy = sy < 0 ? 0 : Math.min(sy, height - 1);
                    }

                    var sample = sy * width + sx;
                    var sampleDepth = depth[sample];
                    // In front of this pixel, and sharp enough that its own disc never
                    // reaches here: a foreground that must not smear over the background.
                    if (sampleDepth < centerDepth
                            && radiusFor(sampleDepth, focus.distance(), invRange, radius) < reach) {
                        continue;
                    }

                    anyTerrain |= sampleDepth != RenderResult.SKY_DEPTH;
                    var color = argb[sample];
                    weight += 1.0;
                    if ((color >>> 24) == 0)
                        continue; // a hole votes, but has no colour to average

                    if (solid == 0.0) {
                        first = color;
                    } else if (color != first) {
                        uniform = false;
                    }
                    solid += 1.0;
                    sumR += (color >> 16) & 0xFF;
                    sumG += (color >> 8) & 0xFF;
                    sumB += color & 0xFF;
                }

                if (!anyTerrain) {
                    out[index] = argb[index];
                    continue;
                }
                if (solid <= 0.0 || solid * 2.0 < weight) {
                    out[index] = 0;
                    continue;
                }
                if (uniform) {
                    out[index] = first;
                    continue;
                }

                var offset = focus.dither() > 0.0
                        ? (BAYER[(y & 3) * CELL + (x & 3)] / (CELL_SIZE - 1.0) - 0.5) * focus.dither()
                        : 0.0;
                var rgb = (clamp(sumR / solid + offset) << 16)
                        | (clamp(sumG / solid + offset) << 8)
                        | clamp(sumB / solid + offset);
                out[index] = 0xFF000000 | converter.snap(rgb);
            }
        }
    }

    /**
     * Blur radius of a surface at this depth, in pixels: how far its defocus has grown
     * away from the focus distance, capped at the widest the spec allows.
     *
     * <p>Takes the reciprocal of the range because it runs once per tap per pixel, which
     * is tens of millions of divisions on a photo-sized image.</p>
     */
    private static double radiusFor(float depth, double focus, double invRange, double max) {
        var offset = Math.abs(depth - focus) * invRange;
        return (offset < 1.0 ? offset : 1.0) * max;
    }

    /**
     * Where a tap sits along the spiral, as a fraction of the radius. The square root
     * spreads the taps by area rather than by radius, so the disc fills evenly instead
     * of crowding its middle.
     */
    private static double radiusAt(int tap, int count) {
        return Math.sqrt((tap + 0.5) / count);
    }

    /**
     * Golden-angle spiral on the unit disc, in sixteen rotations — one per position in a
     * 4x4 tile, so neighbouring pixels never sample the same spokes.
     */
    private static double[][] discTaps(int count) {
        var taps = new double[CELL_SIZE][count * 2];
        for (var cell = 0; cell < CELL_SIZE; cell++) {
            var turn = cell * (2.0 * Math.PI / CELL_SIZE);
            for (var tap = 0; tap < count; tap++) {
                var angle = tap * GOLDEN_ANGLE + turn;
                var r = radiusAt(tap, count);
                taps[cell][tap * 2] = r * Math.cos(angle);
                taps[cell][tap * 2 + 1] = r * Math.sin(angle);
            }
        }
        return taps;
    }

    private static int clamp(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
