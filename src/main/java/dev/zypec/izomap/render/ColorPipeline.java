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
 * a couple of percent of the image.</p>
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
        return hit.base.packedId(darker(SHADE_BY_FACE[hit.face.ordinal()], hit.darken)) & 0xFF;
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
