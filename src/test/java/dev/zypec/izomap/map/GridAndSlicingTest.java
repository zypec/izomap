package dev.zypec.izomap.map;

import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.RenderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("a 16:9 photo on one map is centred between transparent bars")
    void letterboxesOnOneMap() {
        var grid = GridOption.fitted(1, 1, AspectRatio.RATIO_16_9);
        assertEquals(128, grid.widthPx());
        assertEquals(72, grid.heightPx());
        assertTrue(grid.letterboxed());
        assertEquals(28, grid.offsetY());

        var pixels = new int[grid.widthPx() * grid.heightPx()];
        Arrays.fill(pixels, 0xFF123456);
        var tile = ImageSlicer.slice(new RenderResult(grid.widthPx(), grid.heightPx(), pixels), grid)
                .getFirst().argb();

        assertEquals(0, tile[27 * 128], "the bar above the picture is not transparent");
        assertEquals(0xFF123456, tile[28 * 128], "the picture does not start below the bar");
        assertEquals(0xFF123456, tile[99 * 128 + 127]);
        assertEquals(0, tile[100 * 128], "the bar below the picture is not transparent");
    }

    @Test
    @DisplayName("a letterboxed grid keeps its picture size through photos.yml")
    void letterboxedGridRoundTrips() {
        var grid = GridOption.fitted(1, 1, AspectRatio.RATIO_4_3);
        assertEquals(grid, GridOption.parse(grid.label(), grid.pictureLabel()));
        assertFalse(GridOption.parse("4x3", null).letterboxed());
        assertNull(GridOption.parse("1x1", "129x96"), "a picture larger than its grid");
    }
}
