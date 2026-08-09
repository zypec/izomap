package dev.zypec.izomap.map;

import dev.zypec.izomap.render.AspectRatio;

import java.util.List;

/**
 * Grid options valid for each aspect ratio.
 *
 * <p>The chosen grid's pixel size is the render resolution; the photo fills the grid
 * exactly, with no letterboxing.</p>
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
                    new GridOption(4, 2),
                    new GridOption(8, 4),
                    new GridOption(16, 9));
            case RATIO_4_3 -> List.of(
                    new GridOption(4, 3),
                    new GridOption(8, 6));
        };
    }

    /** Whether the grid is valid for the ratio. */
    public static boolean isValid(AspectRatio ratio, GridOption option) {
        return optionsFor(ratio).contains(option);
    }
}
