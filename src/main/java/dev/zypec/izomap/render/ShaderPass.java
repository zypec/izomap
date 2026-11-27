package dev.zypec.izomap.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The finishing pass of the {@link PhotoStyle#SHADER} style: what makes a photo look
 * shot through a shader pack rather than printed from a map.
 *
 * <p>Four effects, in the order a shader stacks them:</p>
 *
 * <ol>
 *   <li><b>Aerial haze.</b> Ground fades towards the sky's own color with distance, from
 *       {@link #HAZE_START} blocks to the capture's reach, so far hills turn soft and blue
 *       without the near ground losing anything. Needs the depth the walk records.</li>
 *   <li><b>Grading.</b> Contrast and saturation a little up, then split toning: dark tones
 *       lean towards blue, light ones towards a warm sun. It is what makes shadows read as
 *       shade under an open sky rather than as darker paint.</li>
 *   <li><b>Bloom.</b> Everything brighter than a threshold is blurred at a quarter of the
 *       size and added back, so the sun, lit snow and glittering water glow onto what is
 *       around them.</li>
 *   <li><b>Vignette.</b> The corners darken smoothly towards the edge.</li>
 * </ol>
 *
 * <p>The palette has four shades per color, so every one of these is a gradient the maps
 * cannot store. Each pixel is dithered (4x4 ordered) before it is snapped back, which
 * turns the gradients into a fine texture instead of bands. Holes stay holes. The style
 * also turns the sun shadow and ambient occlusion on for its photos; see
 * {@code RenderService#specFor}.</p>
 */
public final class ShaderPass {

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    private static final double BLOOM_THRESHOLD = 0.72;
    private static final int BLOOM_SCALE = 4;
    private static final int BLOOM_RADIUS = 2;

    /**
     * Blocks from the camera plane where the haze begins.
     */
    private static final double HAZE_START = 96.0;

    private static final int SHADOW_TONE = 0x3B5A9A;
    private static final int LIGHT_TONE = 0xFFD7A0;

    private ShaderPass() {
    }

    /**
     * @param reach the furthest the capture's rays may travel, where the haze is full
     */
    public static RenderResult apply(RenderResult source, ShaderSpec spec, Sky sky, double reach,
                                     MapColorConverter converter, Executor executor, int threads) {
        if (!spec.draws())
            return source;

        var width = source.width();
        var height = source.height();
        var in = source.argb();
        var rgb = new float[width * height * 3];
        for (var i = 0; i < in.length; i++) {
            rgb[i * 3] = ((in[i] >> 16) & 0xFF) / 255f;
            rgb[i * 3 + 1] = ((in[i] >> 8) & 0xFF) / 255f;
            rgb[i * 3 + 2] = (in[i] & 0xFF) / 255f;
        }

        var depth = source.depth();
        if (spec.haze() > 0.0 && depth != null && sky.draws())
            haze(rgb, depth, width, height, sky, spec.haze(), reach);
        if (spec.grading() > 0.0)
            grade(rgb, spec.grading());
        if (spec.bloom() > 0.0)
            bloom(rgb, in, width, height, spec.bloom());

        var out = new int[in.length];
        var bands = Math.max(1, Math.min(threads, height));
        var rowsPerBand = (height + bands - 1) / bands;
        var pending = new CompletableFuture<?>[bands - 1];
        for (var band = 0; band < bands - 1; band++) {
            final var from = band * rowsPerBand;
            final var to = Math.min(height, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> finish(rgb, in, out, width, height, spec, converter, from, to), executor);
        }
        finish(rgb, in, out, width, height, spec, converter, (bands - 1) * rowsPerBand, height);
        CompletableFuture.allOf(pending).join();
        return new RenderResult(width, height, out, depth);
    }

    /**
     * Fades ground towards the sky's color at its row, from {@link #HAZE_START} blocks to
     * the furthest a capture reaches.
     *
     * <p>Measured in blocks rather than as a share of what the frame sees: a camera
     * looking down sees its ground a hundred blocks either way, and a relative haze
     * washed out the top half of every such shot. Like a shader's fog, it is the distance
     * that hazes, so a view down stays clear and a view at the horizon fades.</p>
     */
    private static void haze(float[] rgb, float[] depth, int width, int height, Sky sky, double amount,
                             double reach) {
        var start = (float) HAZE_START;
        var span = (float) Math.max(1.0, reach - HAZE_START);
        for (var y = 0; y < height; y++) {
            var skyRgb = sky.rgbAt(y);
            var sr = ((skyRgb >> 16) & 0xFF) / 255f;
            var sg = ((skyRgb >> 8) & 0xFF) / 255f;
            var sb = (skyRgb & 0xFF) / 255f;
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var d = depth[i];
                if (d == RenderResult.SKY_DEPTH || d <= start) continue;

                var t = Math.min(1f, (d - start) / span);
                var f = (float) (amount * t * t * (3 - 2 * t));
                rgb[i * 3] += (sr - rgb[i * 3]) * f;
                rgb[i * 3 + 1] += (sg - rgb[i * 3 + 1]) * f;
                rgb[i * 3 + 2] += (sb - rgb[i * 3 + 2]) * f;
            }
        }
    }

    private static void grade(float[] rgb, double amount) {
        var contrast = 1.0 + 0.25 * amount;
        var saturation = 1.0 + 0.35 * amount;
        float[] shadow = unit(SHADOW_TONE);
        float[] light = unit(LIGHT_TONE);
        for (var i = 0; i < rgb.length; i += 3) {
            double r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
            var luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            r = luma + (r - luma) * saturation;
            g = luma + (g - luma) * saturation;
            b = luma + (b - luma) * saturation;
            r = 0.5 + (r - 0.5) * contrast;
            g = 0.5 + (g - 0.5) * contrast;
            b = 0.5 + (b - 0.5) * contrast;

            // Split toning, weighted so mid-grey is left alone.
            var toward = luma < 0.5 ? shadow : light;
            var weight = amount * 0.35 * Math.abs(luma - 0.5) * 2.0;
            r += (toward[0] * luma * 1.6 - r) * weight * 0.5;
            g += (toward[1] * luma * 1.6 - g) * weight * 0.5;
            b += (toward[2] * luma * 1.6 - b) * weight * 0.5;
            rgb[i] = (float) r;
            rgb[i + 1] = (float) g;
            rgb[i + 2] = (float) b;
        }
    }

    /**
     * Adds a blurred copy of the brightest pixels back over the image. Blurred at a
     * quarter of the size, so a wide glow costs a few box passes over a small image.
     */
    private static void bloom(float[] rgb, int[] argb, int width, int height, double amount) {
        var sw = Math.max(1, width / BLOOM_SCALE);
        var sh = Math.max(1, height / BLOOM_SCALE);
        var small = new float[sw * sh * 3];
        for (var y = 0; y < sh; y++) {
            for (var x = 0; x < sw; x++) {
                float r = 0, g = 0, b = 0;
                var n = 0;
                for (var dy = 0; dy < BLOOM_SCALE; dy++) {
                    for (var dx = 0; dx < BLOOM_SCALE; dx++) {
                        var px = Math.min(width - 1, x * BLOOM_SCALE + dx);
                        var py = Math.min(height - 1, y * BLOOM_SCALE + dy);
                        var i = py * width + px;
                        n++;
                        if ((argb[i] >>> 24) == 0) continue;
                        var luma = 0.2126 * rgb[i * 3] + 0.7152 * rgb[i * 3 + 1] + 0.0722 * rgb[i * 3 + 2];
                        if (luma <= BLOOM_THRESHOLD) continue;
                        var k = (float) ((luma - BLOOM_THRESHOLD) / (1.0 - BLOOM_THRESHOLD));
                        r += rgb[i * 3] * k;
                        g += rgb[i * 3 + 1] * k;
                        b += rgb[i * 3 + 2] * k;
                    }
                }
                var o = (y * sw + x) * 3;
                small[o] = r / n;
                small[o + 1] = g / n;
                small[o + 2] = b / n;
            }
        }
        // Two box passes each way come close to a gaussian.
        for (var pass = 0; pass < 2; pass++) {
            small = boxBlur(small, sw, sh, true);
            small = boxBlur(small, sw, sh, false);
        }
        for (var y = 0; y < height; y++) {
            var sy = Math.min(sh - 1, y / BLOOM_SCALE);
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                if ((argb[i] >>> 24) == 0) continue;
                var o = (sy * sw + Math.min(sw - 1, x / BLOOM_SCALE)) * 3;
                rgb[i * 3] += (float) (small[o] * amount * 2.0);
                rgb[i * 3 + 1] += (float) (small[o + 1] * amount * 2.0);
                rgb[i * 3 + 2] += (float) (small[o + 2] * amount * 2.0);
            }
        }
    }

    private static float[] boxBlur(float[] in, int w, int h, boolean horizontal) {
        var out = new float[in.length];
        var span = BLOOM_RADIUS * 2 + 1;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (var k = -BLOOM_RADIUS; k <= BLOOM_RADIUS; k++) {
                    var sx = horizontal ? Math.max(0, Math.min(w - 1, x + k)) : x;
                    var sy = horizontal ? y : Math.max(0, Math.min(h - 1, y + k));
                    var o = (sy * w + sx) * 3;
                    r += in[o];
                    g += in[o + 1];
                    b += in[o + 2];
                }
                var o = (y * w + x) * 3;
                out[o] = r / span;
                out[o + 1] = g / span;
                out[o + 2] = b / span;
            }
        }
        return out;
    }

    /**
     * Applies the vignette, dithers and snaps the row range {@code [yFrom, yTo)}.
     */
    private static void finish(float[] rgb, int[] in, int[] out, int width, int height, ShaderSpec spec,
                               MapColorConverter converter, int yFrom, int yTo) {
        var cx = (width - 1) / 2.0;
        var cy = (height - 1) / 2.0;
        var maxR2 = cx * cx + cy * cy;
        for (var y = yFrom; y < yTo; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                if ((in[i] >>> 24) == 0) {
                    out[i] = in[i];
                    continue;
                }
                var dx = x - cx;
                var dy = y - cy;
                var edge = (dx * dx + dy * dy) / maxR2;
                var shade = 1.0 - spec.vignette() * edge * edge;
                var noise = (BAYER[(y & 3) * 4 + (x & 3)] / 16.0 - 0.5) * spec.dither();
                var r = channel(rgb[i * 3] * shade * 255.0 + noise);
                var g = channel(rgb[i * 3 + 1] * shade * 255.0 + noise);
                var b = channel(rgb[i * 3 + 2] * shade * 255.0 + noise);
                out[i] = 0xFF000000 | converter.snap((r << 16) | (g << 8) | b);
            }
        }
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static float[] unit(int rgb) {
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }
}
