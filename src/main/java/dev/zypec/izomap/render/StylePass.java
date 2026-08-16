package dev.zypec.izomap.render;

/**
 * The image-wide half of a {@link PhotoStyle}: what happens to the finished pixels.
 *
 * <p>Both passes end by snapping back to the map palette. Anything else would leave
 * colors the maps cannot store, and the cache writes palette bytes, so a color off the
 * palette would be lost on the first restart anyway.</p>
 *
 * <p>Transparency is a state, not a value to average towards: the palette has no
 * translucency, so a pixel is either a color or a hole. Both passes weigh only the
 * neighbours that have a color and decide the hole by majority.</p>
 */
public final class StylePass {

    private StylePass() {
    }

    /**
     * Scales a smaller render up to the photo's real size, blending as it goes.
     *
     * <p>Bilinear on purpose: the softness is the point. Every source pixel spreads
     * across the block boundaries around it, which is the closest thing here to a
     * brush stroke.</p>
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
     * Blends every pixel towards its four neighbours.
     *
     * <p>Reads from a copy, so a blended pixel cannot feed the next one and smear the
     * image along the scan order.</p>
     *
     * @param strength how far towards the neighbourhood a pixel moves, 0 to 1
     */
    public static RenderResult blend(RenderResult image, double strength,
                                     MapColorConverter converter) {
        if (strength <= 0.0)
            return image;

        var width = image.width();
        var height = image.height();
        var source = image.argb();
        var out = new int[source.length];
        var mix = Math.min(1.0, strength);

        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var self = source[y * width + x];
                if ((self >>> 24) == 0) {
                    out[y * width + x] = 0; // a hole stays a hole
                    continue;
                }
                // The pixel itself weighs as much as its four neighbours together, so
                // full strength softens rather than dissolves.
                double r = ((self >> 16) & 0xFF) * 4.0;
                double g = ((self >> 8) & 0xFF) * 4.0;
                double b = (self & 0xFF) * 4.0;
                var weight = 4.0;

                for (var side = 0; side < 4; side++) {
                    var nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
                    var ny = y + (side == 2 ? -1 : side == 3 ? 1 : 0);
                    var argb = at(source, width, height, nx, ny);
                    if ((argb >>> 24) == 0) continue;

                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += (argb >> 0) & 0xFF;
                    weight += 1.0;
                }

                var blended = mixToward(self, r / weight, g / weight, b / weight, mix);
                out[y * width + x] = 0xFF000000 | converter.snap(blended);
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

    private static int mixToward(int argb, double r, double g, double b, double mix) {
        var sr = (argb >> 16) & 0xFF;
        var sg = (argb >> 8) & 0xFF;
        var sb = argb & 0xFF;
        return (round(sr + (r - sr) * mix) << 16)
               | (round(sg + (g - sg) * mix) << 8)
               | round(sb + (b - sb) * mix);
    }

    private static int round(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
