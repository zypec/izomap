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
    public static final Water FLAT = new Water(WaterSpec.FLAT);

    private final WaterSpec spec;

    private Water(WaterSpec spec) {
        this.spec = spec;
    }

    public static Water of(WaterSpec spec) {
        return spec.mode().measures() ? new Water(spec) : FLAT;
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
