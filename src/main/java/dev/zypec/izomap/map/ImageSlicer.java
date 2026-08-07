package dev.zypec.izomap.map;

import dev.zypec.izomap.render.RenderResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Render çıktısını ızgaraya göre 128×128 karolara böler.
 */
public final class ImageSlicer {

    private ImageSlicer() {
    }

    /**
     * Render sonucunu karolara böler. Render, ızgara boyutunda
     * ({@code grid.widthPx() × grid.heightPx()}) yapılmış olmalıdır.
     */
    public static List<MapTile> slice(RenderResult result, GridOption grid) {
        int tile = GridOption.TILE;
        int width = result.width();
        int[] source = result.argb();
        List<MapTile> tiles = new ArrayList<>(grid.tileCount());

        for (int row = 0; row < grid.rows(); row++) {
            for (int col = 0; col < grid.cols(); col++) {
                int[] pixels = new int[tile * tile];
                int baseX = col * tile;
                int baseY = row * tile;
                for (int y = 0; y < tile; y++) {
                    int srcIndex = (baseY + y) * width + baseX;
                    System.arraycopy(source, srcIndex, pixels, y * tile, tile);
                }
                tiles.add(new MapTile(col, row, pixels));
            }
        }
        return tiles;
    }
}
