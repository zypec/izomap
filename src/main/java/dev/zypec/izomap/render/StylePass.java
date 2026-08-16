package dev.zypec.izomap.render;

/**
 * Scales a {@link PhotoStyle#FAST} render up to the size the photo really is.
 *
 * <p>It ends by snapping back to the map palette. Anything else would leave colors the
 * maps cannot store, and the cache writes palette bytes, so a color off the palette
 * would be lost on the first restart anyway.</p>
 *
 * <p>Transparency is a state, not a value to average towards: the palette has no
 * translucency, so a pixel is either a color or a hole. Only neighbours that have a
 * color are weighed, and the hole is decided by majority.</p>
 */
public final class StylePass {

    private StylePass() {
    }

    /**
     * Scales a smaller render up to the photo's real size, blending as it goes.
     *
     * <p>Bilinear rather than nearest: a photo assembled from repeated pixels reads as
     * a mistake, while a blended one reads as a softer photo. The softness is the cost
     * of the rays that were not cast, not a look being aimed at.</p>
     */
    public static RenderResult upscale(RenderResult small, int width, int height,
                                       MapColorConverter converter) {
        if (small.width() == width && small.height() == height)
            return small;

        var out = new int[width * height];
        var source = small.argb();
        var sw = small.width();
        var sh = small.height();
        // Map pixel centres, so the edges of the image do not drift half a pixel.
        var scaleX = (double) sw / width;
        var scaleY = (double) sh / height;

        for (var y = 0; y < height; y++) {
            var sy = (y + 0.5) * scaleY - 0.5;
            var y0 = (int) Math.floor(sy);
            var fy = sy - y0;
            for (var x = 0; x < width; x++) {
                var sx = (x + 0.5) * scaleX - 0.5;
                var x0 = (int) Math.floor(sx);
                var fx = sx - x0;

                double weight = 0.0, r = 0.0, g = 0.0, b = 0.0, solid = 0.0;
                for (var dy = 0; dy <= 1; dy++) {
                    for (var dx = 0; dx <= 1; dx++) {
                        var w = (dx == 0 ? 1.0 - fx : fx) * (dy == 0 ? 1.0 - fy : fy);
                        if (w <= 0.0) continue;

                        var argb = at(source, sw, sh, x0 + dx, y0 + dy);
                        weight += w;
                        if ((argb >>> 24) == 0) continue;

                        solid += w;
                        r += ((argb >> 16) & 0xFF) * w;
                        g += ((argb >> 8) & 0xFF) * w;
                        b += (argb & 0xFF) * w;
                    }
                }
                out[y * width + x] = resolve(r, g, b, solid, weight, converter);
            }
        }
        return new RenderResult(width, height, out);
    }

    /**
     * Clamped edge sampling: past the border the nearest pixel stands in, so the frame
     * does not darken or dissolve along its own edge.
     */
    private static int at(int[] pixels, int width, int height, int x, int y) {
        var cx = x < 0 ? 0 : Math.min(x, width - 1);
        var cy = y < 0 ? 0 : Math.min(y, height - 1);
        return pixels[cy * width + cx];
    }

    /**
     * Turns the weighted sums into a palette color, or a hole when most of what went
     * into the pixel was one.
     */
    private static int resolve(double r, double g, double b, double solid, double weight,
                               MapColorConverter converter) {
        if (solid <= 0.0 || solid * 2.0 < weight)
            return 0;

        var rgb = (round(r / solid) << 16) | (round(g / solid) << 8) | round(b / solid);
        return 0xFF000000 | converter.snap(rgb);
    }

    private static int round(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
