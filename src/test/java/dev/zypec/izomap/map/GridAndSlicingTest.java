package dev.zypec.izomap.map;

import dev.zypec.izomap.render.RenderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Slicing decides which pixel ends up on which map, so a mistake here shows up as a
 * photo whose tiles are shuffled — visible only once it is on a wall.
 */
class GridAndSlicingTest {

    @Test
    @DisplayName("a grid label round trips")
    void parsesLabels() {
        var grid = GridOption.parse("4x3");

        assertNotNull(grid);
        assertEquals(4, grid.cols());
        assertEquals(3, grid.rows());
        assertEquals("4x3", grid.label());
        assertEquals(4 * 128, grid.widthPx());
        assertEquals(3 * 128, grid.heightPx());
        assertEquals(12, grid.tileCount());
    }

    @Test
    @DisplayName("a label that is not a grid is refused rather than guessed")
    void refusesBadLabels() {
        assertNull(GridOption.parse(null));
        assertNull(GridOption.parse(""));
        assertNull(GridOption.parse("4"));
        assertNull(GridOption.parse("4x"));
        assertNull(GridOption.parse("0x3"), "a grid needs at least one column");
        assertNull(GridOption.parse("-1x3"));
        assertNull(GridOption.parse("axb"));
    }

    @Test
    @DisplayName("slicing hands every tile the pixels under it, in row-major order")
    void slicesInRowMajorOrder() {
        var grid = new GridOption(2, 2);
        var width = grid.widthPx();
        var height = grid.heightPx();

        // Each quadrant filled with its own value, so a swapped tile is obvious.
        var pixels = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var quadrant = (y < 128 ? 0 : 2) + (x < 128 ? 0 : 1);
                pixels[y * width + x] = 0xFF000000 | quadrant;
            }
        }

        var tiles = ImageSlicer.slice(new RenderResult(width, height, pixels), grid);

        assertEquals(4, tiles.size());
        for (var index = 0; index < tiles.size(); index++) {
            var tile = tiles.get(index);
            assertEquals(index / 2, tile.row(), "tile " + index + " came from the wrong row");
            assertEquals(index % 2, tile.col(), "tile " + index + " came from the wrong column");
            assertEquals(128 * 128, tile.argb().length);
            for (var pixel : tile.argb()) {
                assertEquals(0xFF000000 | index, pixel,
                        "tile " + index + " picked up a neighbour's pixels");
            }
        }
    }
}
