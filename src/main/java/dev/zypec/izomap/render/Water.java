package dev.zypec.izomap.render;

import org.bukkit.Material;

/**
 * Turns a {@link WaterSpec} into the two answers the walk needs about a water column:
 * how many brightness steps its surface loses, and — when the water is translucent — how
 * much of the floor still shows through it.
 *
 * <p>Built once per render and read from every render thread; it holds no mutable
 * state.</p>
 *
 * <h2>Depth is counted in cells, not in metres</h2>
 *
 * <p>The walk knows how many water cells a ray crossed, and that is what both answers are
 * based on. A grazing ray crosses more cells than the water is deep, so a shallow lake
 * seen from a low camera reads slightly deeper than it is — which is the same trade
 * vanilla makes, and cheaper than reconstructing a vertical depth the walk never
 * measured.</p>
 */
public final class Water {

    /**
     * Water as one flat tone; the walk skips every check below.
     */
    public static final Water FLAT = new Water(WaterSpec.FLAT, 0.0, 0.0, 0);

    /**
     * Glitter cells per block side: waves are smaller than blocks, and a whole block
     * lighting up reads as a white tile rather than as a glint.
     */
    private static final int GLINT_CELLS = 2;

    /**
     * Share of patches that glitter when looking straight into the sun's reflection.
     * More read as noise over the whole lake rather than as light on it.
     */
    private static final double GLINT_DENSITY = 0.3;

    private final WaterSpec spec;
    /**
     * Share of glitter cells that catch the sun from this view, 0 when none can.
     */
    private final double glintChance;
    private final double glintAmount;
    private final int sunRgb;

    private Water(WaterSpec spec, double glintChance, double glintAmount, int sunRgb) {
        this.spec = spec;
        this.glintChance = glintChance;
        this.glintAmount = glintAmount;
        this.sunRgb = sunRgb;
    }

    public static Water of(WaterSpec spec) {
        return spec.mode().measures() ? new Water(spec, 0.0, 0.0, 0) : FLAT;
    }

    /**
     * The water for one view, with its glitter worked out.
     *
     * <h2>Glint</h2>
     *
     * <p>Every ray of an orthographic camera is parallel, so a still surface would mirror
     * the sun into every pixel at once or into none. Waves are what break it up: each
     * small patch of water is tilted a little, and only the patches tilted just so send
     * the sun towards the camera. The tilt is a deterministic hash of the patch's place in
     * the world, up to {@code glintSpread} degrees, so the same lake glitters the same way
     * in every shot. The view decides how many patches can line up: looking into the
     * sun's reflection, many; at twice the spread away from it, none.</p>
     *
     * <p>A glittering patch moves towards the sun's colour by {@code glint}, scaled by how
     * light the sky is, so a night lake does not sparkle. The result leaves the palette
     * and is snapped back like any blended pixel.</p>
     *
     * @param dx       the view direction
     * @param sunX     unit vector towards the sun
     * @param daylight 0 to 1, how much of the day's light the sky has
     */
    public static Water of(WaterSpec spec, double dx, double dy, double dz,
                           double sunX, double sunY, double sunZ, int sunRgb, double daylight) {
        if (!spec.glints() || daylight <= 0.0 || sunY <= 0.0)
            return of(spec);

        // The view mirrored in a level surface, against the direction of the sun.
        var cos = dx * sunX - dy * sunY + dz * sunZ;
        var angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
        // A tilt of t turns the reflection by 2t.
        var reach = 1.0 - angle / (2.0 * spec.glintSpread());
        if (reach <= 0.0)
            return of(spec);

        return new Water(spec, GLINT_DENSITY * reach * reach * reach, spec.glint() * daylight, sunRgb & 0xFFFFFF);
    }

    /**
     * Whether the walk has to measure water columns at all.
     */
    boolean measures() {
        return spec.mode().measures();
    }

    /**
     * Whether the floor is allowed to show through the water.
     */
    boolean translucent() {
        return spec.mode() == WaterSpec.Mode.TRANSLUCENT;
    }

    /**
     * Whether this material counts as part of a water column.
     *
     * <p>Ice, packed ice and cauldrons are deliberately outside: they are their own
     * surfaces, not a depth to look into. A bubble column is water with air in it, and
     * reads as the water around it.</p>
     */
    /**
     * Whether any water in this view can glitter.
     */
    boolean glints() {
        return glintChance > 0.0;
    }

    /**
     * How far the water surface at this point moves towards the sun's colour, 0 for most
     * of it.
     */
    double glintAt(double x, double z) {
        var cellX = (long) Math.floor(x * GLINT_CELLS);
        var cellZ = (long) Math.floor(z * GLINT_CELLS);
        var hash = cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL;
        hash ^= hash >>> 31;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 29;
        var roll = (hash >>> 11) * 0x1.0p-53;
        return roll < glintChance ? glintAmount : 0.0;
    }

    /**
     * A water colour as it glitters, still to be snapped.
     */
    int glinted(int rgb, double amount) {
        var r = (rgb >> 16) & 0xFF;
        var g = (rgb >> 8) & 0xFF;
        var b = rgb & 0xFF;
        r += (int) Math.round((((sunRgb >> 16) & 0xFF) - r) * amount);
        g += (int) Math.round((((sunRgb >> 8) & 0xFF) - g) * amount);
        b += (int) Math.round(((sunRgb & 0xFF) - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    boolean isWater(Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    /**
     * Brightness steps the surface of a column this deep gives up.
     */
    int depthSteps(int cells) {
        if (spec.darkDepth() > 0 && cells >= spec.darkDepth()) return 2;

        return spec.dimDepth() > 0 && cells >= spec.dimDepth() ? 1 : 0;
    }

    /**
     * How much of the pixel a column this deep keeps for itself, the rest being left to
     * the floor beneath it. Ramps from {@code surface-min} at one cell to fully opaque at
     * {@code opaque-depth}.
     */
    double opacity(int cells) {
        var min = spec.surfaceMin();
        var full = spec.opaqueDepth();
        if (cells >= full || full <= 1) return 1.0;

        var ramp = (cells - 1) / (double) (full - 1);
        return min + (1.0 - min) * ramp;
    }
}
