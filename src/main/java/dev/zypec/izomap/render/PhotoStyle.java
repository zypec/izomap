package dev.zypec.izomap.render;

/**
 * How a photo is drawn, as opposed to how it is colored.
 *
 * <p>A style decides how a pixel comes about; a {@link ColorFilter} decides how the
 * color it arrives at is shifted. They are separate axes and a photo carries one of
 * each.</p>
 *
 * <h2>Where these come from</h2>
 *
 * <p>The plugin's first build produced softer, more painted-looking photos than it does
 * now, and the reason was never an effect: its ray march point-sampled every quarter
 * block and took the first solid sample, so any block a ray merely clipped the corner
 * of was missed at random. Corner clips are what happen along silhouettes and surface
 * seams, so every edge came out broken up. The exact walk that replaced it never misses,
 * which is where the hard, poster-flat look comes from.</p>
 *
 * <p>Each style below reproduces that irregularity by a different mechanism, so the
 * three can be compared against {@link #SHARP} on the same view.</p>
 *
 * <p>Display names live under {@code style.<NAME>} in {@code messages.yml}; only the
 * constant name is ever written to disk.</p>
 */
public enum PhotoStyle {

    /**
     * The exact walk, unaltered.
     */
    SHARP,
    /**
     * Rendered below the photo's real size and scaled back up, so every block face
     * bleeds into its neighbours. The strongest of the three, and cheaper than
     * {@link #SHARP} because it casts fewer rays.
     */
    SOFT,
    /**
     * Sample positions scattered inside their pixel instead of sitting on a grid.
     * Closest in mechanism to what the old march did by accident: edges break into
     * stippled mixes rather than clean ramps. Costs nothing extra.
     */
    GRAINY,
    /**
     * The finished image blended with its own neighbours in one pass. Its strength is
     * set independently of how many rays were cast.
     */
    BLENDED;

    /**
     * Whether this style wants the image rendered smaller and scaled up.
     */
    public boolean scalesDown() {
        return this == SOFT;
    }

    public static PhotoStyle fromString(String raw, PhotoStyle fallback) {
        if (raw == null) return fallback;

        var trimmed = raw.trim();
        for (var style : values()) {
            if (style.name().equalsIgnoreCase(trimmed)) {
                return style;
            }
        }
        return fallback;
    }
}
