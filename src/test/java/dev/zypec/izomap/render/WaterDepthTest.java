package dev.zypec.izomap.render;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Water is the one surface whose colour depends on what is <i>under</i> it, and both ways
 * of saying so can fail without looking broken: the depth thresholds can miss a step, and
 * the translucent mix can lose either the water or the floor entirely.
 */
class WaterDepthTest {

    private static final int MIN_Y = 0;
    private static final int MAX_Y = 128;
    private static final double EYE_Y = 100.0;

    /**
     * One step down from two cells of water, two from five; a translucent surface keeps a
     * third of the pixel at one cell and all of it at eight.
     */
    private static final WaterSpec DEPTH =
            new WaterSpec(WaterSpec.Mode.DEPTH, 2, 5, 0.35, 8);
    private static final WaterSpec TRANSLUCENT =
            new WaterSpec(WaterSpec.Mode.TRANSLUCENT, 2, 5, 0.35, 8);

    private final MapColorConverter converter = new MapColorConverter();
    private final ColorPipeline pipeline = ColorPipeline.of(ColorFilter.ORIGINAL, converter);
    private final BlockColorTable table = BlockColorTable.of(
            Map.of(Material.WATER, MapBaseColor.WATER, Material.SAND, MapBaseColor.SAND),
            Map.of());

    /**
     * Renders one pixel straight down a column of {@code depth} water cells over sand.
     */
    private int pixel(WaterSpec spec, int depth) {
        var floor = 64;
        FakeChunk.Blocks blocks = (x, y, z) -> {
            if (y == floor) return Material.SAND;

            return y > floor && y <= floor + depth ? Material.WATER : Material.AIR;
        };
        var geometry = new RenderGeometry(
                new Vector(8.5, EYE_Y, 8.5),
                new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(0, -1, 0),
                4.0, 4.0, 200.0, EYE_Y, 0.0, 1, 1);
        var result = new IsometricRenderer(table).render(
                FakeChunk.world(MIN_Y, MAX_Y, blocks), geometry, pipeline,
                Sky.NONE, Shading.NONE, Water.of(spec), 1, false, null, Runnable::run, 1);
        return result.argb()[0];
    }

    /**
     * Water's own colour, {@code steps} brightnesses below the top face's.
     */
    private static int water(MapBaseColor.Shade shade) {
        return 0xFF000000 | MapBaseColor.WATER.rgb(shade);
    }

    @Test
    @DisplayName("shallow water keeps the surface brightness")
    void oneCellIsShallow() {
        assertEquals(water(MapBaseColor.Shade.HIGH), pixel(DEPTH, 1));
    }

    @Test
    @DisplayName("water darkens a step at the first threshold and two at the second")
    void depthWalksDownThePalette() {
        assertEquals(water(MapBaseColor.Shade.NORMAL), pixel(DEPTH, 2));
        assertEquals(water(MapBaseColor.Shade.NORMAL), pixel(DEPTH, 4));
        assertEquals(water(MapBaseColor.Shade.LOW), pixel(DEPTH, 5));
        assertEquals(water(MapBaseColor.Shade.LOW), pixel(DEPTH, 30));
    }

    @Test
    @DisplayName("flat water is one tone however deep it is")
    void flatIgnoresDepth() {
        assertEquals(water(MapBaseColor.Shade.HIGH), pixel(WaterSpec.FLAT, 1));
        assertEquals(water(MapBaseColor.Shade.HIGH), pixel(WaterSpec.FLAT, 30));
    }

    @Test
    @DisplayName("translucent shallows show the sand under them")
    void shallowTranslucentWaterMixesInTheFloor() {
        var pixel = pixel(TRANSLUCENT, 1);

        assertEquals(mixed(0.35), pixel);
        assertNotEquals(water(MapBaseColor.Shade.HIGH), pixel, "the sand must come through");
    }

    @Test
    @DisplayName("translucent water closes over the floor as it deepens")
    void deepTranslucentWaterIsWaterAlone() {
        // Half way to opaque-depth the floor still counts for something...
        assertEquals(mixed(0.35 + 0.65 * 3.0 / 7.0), pixel(TRANSLUCENT, 4));
        // ...and at opaque-depth it counts for nothing.
        assertEquals(water(MapBaseColor.Shade.HIGH), pixel(TRANSLUCENT, 8));
        assertEquals(water(MapBaseColor.Shade.HIGH), pixel(TRANSLUCENT, 30));
    }

    /**
     * Water over sand at this opacity, as the pipeline should compose it.
     */
    private int mixed(double opacity) {
        var a = MapBaseColor.WATER.rgb(MapBaseColor.Shade.HIGH);
        var b = MapBaseColor.SAND.rgb(MapBaseColor.Shade.HIGH);
        var rgb = (channel(a, b, 16, opacity) << 16)
                  | (channel(a, b, 8, opacity) << 8)
                  | channel(a, b, 0, opacity);
        return 0xFF000000 | converter.snap(rgb);
    }

    private static int channel(int near, int far, int shift, double opacity) {
        var value = ((near >> shift) & 0xFF) * opacity + ((far >> shift) & 0xFF) * (1.0 - opacity);
        return (int) Math.round(value);
    }
}
