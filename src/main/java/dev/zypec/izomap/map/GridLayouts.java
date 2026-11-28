package dev.zypec.izomap.map;

import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.render.AspectRatio;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid options valid for each aspect ratio.
 *
 * <p>The chosen grid's picture size is the render resolution. Every ratio can be shot
 * on a single map, letterboxed when it is not square; the larger grids are filled
 * exactly.</p>
 */
public final class GridLayouts {

    private GridLayouts() {
    }

    public static List<GridOption> optionsFor(AspectRatio ratio) {
        return switch (ratio) {
            case RATIO_1_1 -> List.of(
                    new GridOption(1, 1),
                    new GridOption(2, 2),
                    new GridOption(3, 3));
            case RATIO_16_9 -> List.of(
                    GridOption.fitted(1, 1, ratio),
                    new GridOption(4, 2),
                    new GridOption(8, 4),
                    new GridOption(16, 9));
            case RATIO_4_3 -> List.of(
                    GridOption.fitted(1, 1, ratio),
                    new GridOption(4, 3),
                    new GridOption(8, 6));
            case RATIO_3_4 -> List.of(
                    GridOption.fitted(1, 1, ratio),
                    new GridOption(3, 4),
                    new GridOption(6, 8));
            case RATIO_9_16 -> List.of(
                    GridOption.fitted(1, 1, ratio),
                    new GridOption(2, 4),
                    new GridOption(4, 8),
                    new GridOption(9, 16));
        };
    }

    /**
     * The options a player may actually shoot at this ratio.
     *
     * <p>The smallest one is kept whatever the allowance says. A limit below one tile
     * would otherwise leave a ratio with no grid at all, and the capture screen would
     * offer an empty dropdown rather than an explanation.</p>
     */
    public static List<GridOption> allowedFor(Player player, AspectRatio ratio, int configuredLimit) {
        var all = optionsFor(ratio);
        List<GridOption> allowed = new ArrayList<>(all.size());
        for (var option : all) {
            if (Permissions.grid(player, option, configuredLimit))
                allowed.add(option);
        }
        if (allowed.isEmpty())
            allowed.add(all.getFirst());

        return allowed;
    }

    /**
     * The ratio's grid with this label, or {@code null} when it has none. Looked up
     * rather than parsed: the same label is a filled grid for one ratio and a
     * letterboxed one for another.
     */
    public static GridOption find(AspectRatio ratio, String label) {
        for (var option : optionsFor(ratio)) {
            if (option.label().equalsIgnoreCase(label == null ? "" : label.trim()))
                return option;
        }
        return null;
    }
}
