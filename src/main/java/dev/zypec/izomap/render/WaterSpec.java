package dev.zypec.izomap.render;

import java.util.Locale;

/**
 * How water is drawn: as one flat colour, shaded by how deep it is, or mixed with the
 * floor under it.
 *
 * <p>Water used to come out as a single tone of {@code WATER}, so a pond and an ocean
 * were the same blue and a coastline had no shape at all. Vanilla maps do not do that
 * either — there the colour follows the <b>depth</b> — which is why {@link Mode#DEPTH}
 * needs no defence and is the default.</p>
 *
 * <p>Frozen into {@link CaptureSpec} like the shading, so a photo re-rendered after the
 * server changed its mind still looks like the photo that was hung on the wall.</p>
 *
 * @param mode       what the water colour is allowed to depend on
 * @param dimDepth   cells of water after which the surface loses one brightness step
 * @param darkDepth  cells after which it loses two
 * @param surfaceMin opacity of the shallowest water in {@link Mode#TRANSLUCENT}, so the
 *                   surface never vanishes entirely over a bright floor
 * @param opaqueDepth cells of water after which the floor no longer shows through at all
 * @param glint      how far a glittering cell moves towards the sun's color, 0 for no
 *                   glitter at all; works in every mode
 * @param glintSpread how far waves tilt the surface, in degrees: the wider, the further
 *                   from the sun's reflection the glitter reaches
 */
public record WaterSpec(
        Mode mode,
        int dimDepth,
        int darkDepth,
        double surfaceMin,
        int opaqueDepth,
        double glint,
        double glintSpread) {

    /**
     * What the water colour may depend on.
     */
    public enum Mode {
        /**
         * One tone for every drop of water, as the renderer did before any of this.
         */
        FLAT,
        /**
         * The surface darkens with the depth beneath it, in whole palette steps. Costs
         * the walk the cells between the surface and the floor and nothing else.
         */
        DEPTH,
        /**
         * The water is mixed with the floor under it by depth, so sand and gravel show
         * through the shallows. Needs the walk's coverage blending, and pays for reaching
         * the floor and colouring it as well as the water.
         */
        TRANSLUCENT;

        /**
         * Whether the walk has to measure the water column at all.
         */
        public boolean measures() {
            return this != FLAT;
        }
    }

    /**
     * Water as it was before this existed: one flat tone, no measuring.
     */
    public static final WaterSpec FLAT = new WaterSpec(Mode.FLAT, 0, 0, 0.0, 0, 0.0, 0.0);

    /**
     * Whether any cell may glitter.
     */
    public boolean glints() {
        return glint > 0.0 && glintSpread > 0.0;
    }

    /**
     * The same water with this glitter, so a flat lake can still catch the sun.
     */
    public WaterSpec withGlint(double strength, double spread) {
        return new WaterSpec(mode, dimDepth, darkDepth, surfaceMin, opaqueDepth, strength, spread);
    }

    /**
     * Reads a mode name, falling back to {@code DEPTH} for anything unrecognized.
     */
    public static Mode modeFrom(String raw, Mode fallback) {
        if (raw == null || raw.isBlank()) return fallback;

        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
