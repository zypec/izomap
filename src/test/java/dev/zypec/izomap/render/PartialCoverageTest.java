package dev.zypec.izomap.render;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block that fills part of its cell has to end up as part of a colour, and the three
 * rules that decide how much of one are all the kind that break quietly: the share must
 * be the block's own coverage, the stack must be capped so a curtain of vines cannot walk
 * a ray off the map, and layers with nothing behind them must lose the pixel to the sky
 * rather than paint it.
 */
class PartialCoverageTest {

    private static final int MIN_Y = 0;
    private static final int MAX_Y = 128;
    /**
     * Where the single ray of these tests starts, straight above the column it walks.
     */
    private static final double EYE_Y = 100.0;

    private static final float TUFT = 0.3f;

    private final MapColorConverter converter = new MapColorConverter();
    private final ColorPipeline pipeline = ColorPipeline.of(ColorFilter.ORIGINAL, converter);

    /**
     * Grass over dirt, both drawing on a map, with the grass filling a third of its cell.
     */
    private static BlockColorTable table(boolean coverage) {
        var colors = Map.of(
                Material.SHORT_GRASS, MapBaseColor.PLANT,
                Material.DIRT, MapBaseColor.DIRT);
        return BlockColorTable.of(colors,
                coverage ? Map.of(Material.SHORT_GRASS, TUFT) : Map.of());
    }

    /**
     * Renders one pixel straight down the column at x=8, z=8.
     */
    private int pixel(BlockColorTable table, FakeChunk.Blocks blocks) {
        var snapshot = FakeChunk.world(MIN_Y, MAX_Y, blocks);
        var geometry = new RenderGeometry(
                new Vector(8.5, EYE_Y, 8.5),
                new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(0, -1, 0),
                4.0, 4.0, 200.0, EYE_Y, 0.0, 1, 1);
        var result = new IsometricRenderer(table).render(
                snapshot, geometry, pipeline, Sky.NONE, Shading.NONE, Water.FLAT,
                1, false, null, Runnable::run, 1);
        return result.argb()[0];
    }

    /**
     * The colour a full-cube block of this base would have printed, top face, unshaded.
     */
    private int solid(MapBaseColor base) {
        return 0xFF000000 | base.rgb(MapBaseColor.Shade.HIGH);
    }

    /**
     * What the pipeline should make of {@code near} laid over {@code far} at this weight.
     */
    private int mixed(MapBaseColor near, MapBaseColor far, double weight) {
        var a = near.rgb(MapBaseColor.Shade.HIGH);
        var b = far.rgb(MapBaseColor.Shade.HIGH);
        var r = channel(a, 16) * weight + channel(b, 16) * (1.0 - weight);
        var g = channel(a, 8) * weight + channel(b, 8) * (1.0 - weight);
        var blue = channel(a, 0) * weight + channel(b, 0) * (1.0 - weight);
        var rgb = (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(blue);
        return 0xFF000000 | converter.snap((int) rgb);
    }

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    @Test
    @DisplayName("a tuft takes its own share of the pixel and leaves the rest to the ground")
    void tuftBlendsWithTheGroundBehindIt() {
        var pixel = pixel(table(true), grassOver(1));

        assertEquals(mixed(MapBaseColor.PLANT, MapBaseColor.DIRT, TUFT), pixel);
        assertNotEquals(solid(MapBaseColor.PLANT), pixel, "the tuft must not paint the whole cell");
        assertNotEquals(solid(MapBaseColor.DIRT), pixel, "and it must not vanish either");
    }

    @Test
    @DisplayName("without coverage the tuft paints its whole cell, as it always did")
    void coverageOffKeepsTheOldBehaviour() {
        assertEquals(solid(MapBaseColor.PLANT), pixel(table(false), grassOver(1)));
    }

    @Test
    @DisplayName("the ground still decides where there is no tuft at all")
    void bareGroundIsUntouched() {
        assertEquals(solid(MapBaseColor.DIRT), pixel(table(true), grassOver(0)));
    }

    @Test
    @DisplayName("past the layer cap the next block counts as solid, so the ground stops showing")
    void stackedTuftsAreCapped() {
        // Four layers of grass: three are collected, the fourth is taken as a surface, so
        // the dirt underneath contributes nothing and the pixel is plain plant green.
        assertEquals(solid(MapBaseColor.PLANT), pixel(table(true), grassOver(4)));
    }

    @Test
    @DisplayName("a lone tuft over nothing loses the pixel to the sky")
    void tuftAloneIsNotEnoughToDraw() {
        // A third of a pixel is a minority, and the palette cannot store the rest of it.
        assertEquals(0, pixel(table(true), (x, y, z) -> y == 65 ? Material.SHORT_GRASS : Material.AIR));
    }

    @Test
    @DisplayName("three tufts over nothing are a majority and do draw")
    void tuftsAloneCanStillCarryAPixel() {
        var blocks = (FakeChunk.Blocks) (x, y, z) ->
                y >= 65 && y <= 67 ? Material.SHORT_GRASS : Material.AIR;

        // 1 - 0.7³ = 0.657 of the pixel, and the layers are all one colour.
        assertEquals(solid(MapBaseColor.PLANT), pixel(table(true), blocks));
    }

    @Test
    @DisplayName("a thin block casts no shadow and boxes nothing in")
    void thinBlocksDoNotOcclude() {
        var table = table(true);

        assertTrue(table.occludes(Material.DIRT));
        assertFalse(table.occludes(Material.SHORT_GRASS));
        assertFalse(table.occludes(Material.AIR), "air never did");
    }

    /**
     * Dirt at y=64 with {@code tufts} cells of grass stacked on top of it.
     */
    private static FakeChunk.Blocks grassOver(int tufts) {
        return (x, y, z) -> {
            if (y == 64) return Material.DIRT;

            return y > 64 && y <= 64 + tufts ? Material.SHORT_GRASS : Material.AIR;
        };
    }
}
