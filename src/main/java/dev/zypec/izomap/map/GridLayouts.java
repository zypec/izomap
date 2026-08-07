package dev.zypec.izomap.map;

import dev.zypec.izomap.render.AspectRatio;

import java.util.List;

/**
 * Her en-boy oranı için sunulan geçerli ızgara seçenekleri.
 *
 * <p>Dialog UI (FAZ 5) bu listeyi kullanarak yalnızca uygun yerleşim boyutlarını
 * gösterir. Seçilen ızgaranın piksel boyutu render çözünürlüğünü belirler; fotoğraf
 * ızgarayı tam dolduracak şekilde çekilir (letterbox yok).</p>
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

    /** Verilen ızgaranın bu oran için geçerli olup olmadığı. */
    public static boolean isValid(AspectRatio ratio, GridOption option) {
        return optionsFor(ratio).contains(option);
    }
}
