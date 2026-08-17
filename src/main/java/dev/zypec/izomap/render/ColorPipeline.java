package dev.zypec.izomap.render;

import dev.zypec.izomap.render.MapBaseColor.Shade;

/**
 * Turns a {@link RayHit} into a map color. The stages run in the order
 * base color &rarr; shading &rarr; filter &rarr; palette snap.
 *
 * <p>Shading picks a brightness variant from the face the ray entered through; vanilla
 * derives that brightness from height differences, and face orientation is the
 * isometric equivalent.</p>
 *
 * <h2>Why the tail of the pipeline is a table</h2>
 *
 * <p>A ray always lands on a palette entry, and there are only 244 of them, so the
 * stages behind the base color are precomputed for every entry while the pipeline is
 * built: {@link #argbOf} already carries the filter and the snap. A pixel whose
 * samples all agreed — which is nearly all of them — is therefore a single array read,
 * and a filtered render runs the same code as an unfiltered one.</p>
 *
 * <p>Only a pixel averaged from disagreeing samples leaves the palette, and only that
 * pixel walks the stages for real, in {@link #blend}. Those are the antialiased edges,
 * a couple of percent of the image, plus the pixels a see-through block mixed itself
 * into — see {@link #compositeRgb}.</p>
 *
 * <p>Built once per render and read from every render thread; it holds no mutable
 * state.</p>
 */
public final class ColorPipeline {

    /**
     * Brightness per {@link RayHit.Face}, in that enum's order.
     */
    private static final Shade[] SHADE_BY_FACE = {
            Shade.HIGH,   // TOP
            Shade.LOWEST, // BOTTOM
            Shade.NORMAL, // SIDE_X
            Shade.LOW,    // SIDE_Z
    };

    /**
     * The four shades from darkest to brightest, which is not their declaration order.
     */
    private static final Shade[] BY_BRIGHTNESS = {Shade.LOWEST, Shade.LOW, Shade.NORMAL, Shade.HIGH};

    /**
     * Where each shade sits on {@link #BY_BRIGHTNESS}, indexed by its own ordinal.
     */
    private static final int[] LADDER_INDEX = new int[Shade.values().length];

    static {
        for (var i = 0; i < BY_BRIGHTNESS.length; i++) {
            LADDER_INDEX[BY_BRIGHTNESS[i].ordinal()] = i;
        }
    }

    private final ColorFilter filter;
    private final MapColorConverter converter;

    /**
     * Map byte to its plain palette color (0xRRGGBB), the value samples average over.
     */
    private final int[] paletteRgb = new int[256];

    /**
     * Map byte to the finished color (0xAARRGGBB), filtered and snapped in advance.
     */
    private final int[] finalArgb = new int[256];

    private ColorPipeline(ColorFilter filter, MapColorConverter converter) {
        this.filter = filter;
        this.converter = converter;
        for (var base : MapBaseColor.values()) {
            if (base == MapBaseColor.NONE)
                continue; // transparent

            for (var shade : Shade.values()) {
                var id = base.packedId(shade) & 0xFF;
                var rgb = base.rgb(shade);
                paletteRgb[id] = rgb;
                finalArgb[id] = 0xFF000000
                        | (filter.isIdentity() ? rgb : converter.snap(filter.apply(rgb)));
            }
        }
    }

    public static ColorPipeline of(ColorFilter filter, MapColorConverter converter) {
        return new ColorPipeline(filter, converter);
    }

    /**
     * Map byte of a hit: its base color at the brightness the entered face takes, less
     * whatever the shading took off it.
     */
    int packedIdOf(RayHit hit) {
        return packedIdOf(hit.base, hit.face, hit.darken);
    }

    private int packedIdOf(MapBaseColor base, RayHit.Face face, int darken) {
        return base.packedId(darker(SHADE_BY_FACE[face.ordinal()], darken)) & 0xFF;
    }

    /**
     * Colour of a hit that has see-through blocks in front of it: each layer over the one
     * behind it, and all of them over the surface the ray stopped on.
     *
     * <p>The result is off the palette, so the caller has to take it through
     * {@link #blend}. Dividing by the weight that was actually laid down is what makes a
     * ray that gathered layers and then reached nothing come out as the layers' own
     * colour rather than as a colour faded towards black.</p>
     */
    int compositeRgb(RayHit hit) {
        var r = 0.0;
        var g = 0.0;
        var b = 0.0;
        var weight = 0.0;
        for (var i = 0; i < hit.layers; i++) {
            var rgb = paletteRgb[packedIdOf(hit.layerBase[i], hit.layerFace[i], hit.darken)];
            var w = hit.layerWeight[i];
            r += w * ((rgb >> 16) & 0xFF);
            g += w * ((rgb >> 8) & 0xFF);
            b += w * (rgb & 0xFF);
            weight += w;
        }
        if (hit.opaque) {
            var rgb = paletteRgb[packedIdOf(hit)];
            var w = hit.transmittance;
            r += w * ((rgb >> 16) & 0xFF);
            g += w * ((rgb >> 8) & 0xFF);
            b += w * (rgb & 0xFF);
            weight += w;
        }
        if (weight <= 0.0)
            return 0;

        return (round(r / weight) << 16) | (round(g / weight) << 8) | round(b / weight);
    }

    private static int round(double value) {
        return Math.clamp(Math.round(value), 0, 255);
    }

    /**
     * The shade {@code steps} below this one, stopping at the darkest.
     *
     * <p>There are four and no more, so a technique that wanted a tenth of a step has
     * nowhere to put it; see {@link ShadingSpec}.</p>
     */
    private static Shade darker(Shade shade, int steps) {
        if (steps <= 0) return shade;

        var index = LADDER_INDEX[shade.ordinal()] - steps;
        return BY_BRIGHTNESS[Math.max(0, index)];
    }

    /**
     * Plain palette color of a map byte, before the filter.
     */
    int paletteRgbOf(int packedId) {
        return paletteRgb[packedId];
    }

    /**
     * Finished color of a map byte, always an exact palette entry.
     */
    int argbOf(int packedId) {
        return finalArgb[packedId];
    }

    /**
     * Runs the filter and the snap on a color averaged from disagreeing samples.
     */
    int blend(int r, int g, int b) {
        var rgb = (r << 16) | (g << 8) | b;
        if (!filter.isIdentity()) {
            rgb = filter.apply(rgb);
        }
        return 0xFF000000 | converter.snap(rgb);
    }
}
