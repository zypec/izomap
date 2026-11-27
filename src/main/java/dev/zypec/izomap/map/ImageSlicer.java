package dev.zypec.izomap.map;

import dev.zypec.izomap.render.RenderResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a render into 128x128 tiles according to the grid.
 */
public final class ImageSlicer {

    private ImageSlicer() {
    }

    /**
     * Slices the render into tiles. The render must already be
     * {@code grid.widthPx() x grid.heightPx()} in size; on a letterboxed grid it is
     * centred and the pixels around it stay transparent.
     */
    public static List<MapTile> slice(RenderResult result, GridOption grid) {
        int tile = GridOption.TILE;
        int width = result.width();
        int height = result.height();
        int[] source = result.argb();
        int offsetX = grid.offsetX();
        int offsetY = grid.offsetY();
        List<MapTile> tiles = new ArrayList<>(grid.tileCount());

        for (int row = 0; row < grid.rows(); row++) {
            for (int col = 0; col < grid.cols(); col++) {
                int[] pixels = new int[tile * tile];
                // The part of this tile the picture covers, in picture coordinates.
                int fromX = Math.max(0, col * tile - offsetX);
                int toX = Math.min(width, (col + 1) * tile - offsetX);
                int fromY = Math.max(0, row * tile - offsetY);
                int toY = Math.min(height, (row + 1) * tile - offsetY);
                for (int y = fromY; y < toY && fromX < toX; y++) {
                    int dest = (y + offsetY - row * tile) * tile + (fromX + offsetX - col * tile);
                    System.arraycopy(source, y * width + fromX, pixels, dest, toX - fromX);
                }
                tiles.add(new MapTile(col, row, pixels));
            }
        }
        return tiles;
    }
}
