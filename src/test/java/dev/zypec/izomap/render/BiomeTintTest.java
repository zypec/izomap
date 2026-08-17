package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tint has one job and two ways to get it wrong: it must carry the biome's <i>hue</i>
 * onto the block without carrying the biome's <i>brightness</i>, and it must leave the
 * reference biome looking like it always did. A model that failed either would repaint
 * every photo ever taken in a plain field.
 *
 * <p>The colours below are vanilla's own, so a change of formula shows up as a change in
 * a real landscape rather than in an invented one.</p>
 */
class BiomeTintTest {

    private static final int PLAINS_GRASS = 0x91BD59;
    private static final int SWAMP_GRASS = 0x6A7039;
    private static final int DESERT_GRASS = 0xBFB755;
    private static final int SNOWY_GRASS = 0x80B497;

    private static final int PLAINS = 0;
    private static final int SWAMP = 1;
    private static final int DESERT = 2;
    private static final int SNOWY = 3;

    private static final int[] TINTS = {PLAINS_GRASS, SWAMP_GRASS, DESERT_GRASS, SNOWY_GRASS};

    private final MapColorConverter converter = new MapColorConverter();

    private ColorPipeline pipelineAt(double strength) {
        return ColorPipeline.of(ColorFilter.ORIGINAL, converter,
                BiomeTints.of(PLAINS_GRASS, TINTS, strength));
    }

    /**
     * The unshaded colour a grass block takes in this biome.
     */
    private int grassIn(int biome, double strength) {
        return pipelineAt(strength).rgbOf(topFaceId(MapBaseColor.GRASS), biome);
    }

    /**
     * The map byte of a block seen from above and unshadowed, which is what a tint is
     * applied to before the brightness goes back on.
     */
    private static int topFaceId(MapBaseColor base) {
        return base.packedId(MapBaseColor.Shade.HIGH) & 0xFF;
    }

    private static int r(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int g(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int b(int rgb) {
        return rgb & 0xFF;
    }

    private static double luma(int rgb) {
        return 0.299 * r(rgb) + 0.587 * g(rgb) + 0.114 * b(rgb);
    }

    @Test
    @DisplayName("the reference biome comes out at the brightness it always had")
    void plainsKeepsItsBrightness() {
        var plains = grassIn(PLAINS, 1.0);

        // Matched by construction: the reference cancels, leaving the block's own luma.
        assertEquals(luma(MapBaseColor.GRASS.rgb(MapBaseColor.Shade.HIGH)), luma(plains), 1.0);
        assertTrue(g(plains) > r(plains) && g(plains) > b(plains), "and it is still green");
    }

    @Test
    @DisplayName("a swamp is darker than a meadow, a desert is yellower, a snowfield is paler")
    void eachBiomePullsItsOwnWay() {
        var plains = grassIn(PLAINS, 1.0);
        var swamp = grassIn(SWAMP, 1.0);
        var desert = grassIn(DESERT, 1.0);
        var snowy = grassIn(SNOWY, 1.0);

        assertTrue(luma(swamp) < luma(plains), "swamp grass is a darker green");
        assertTrue(r(desert) > g(desert), "desert grass leans yellow, past green");
        assertTrue(r(plains) < g(plains), "where plains grass does not");
        assertTrue(b(snowy) > b(plains), "snowy grass carries the blue of the snow");
    }

    @Test
    @DisplayName("blocks keep telling each other apart inside one biome")
    void darkerBlocksStayDarker() {
        var pipeline = pipelineAt(1.0);
        var block = pipeline.rgbOf(topFaceId(MapBaseColor.GRASS), SWAMP);
        var tuft = pipeline.rgbOf(topFaceId(MapBaseColor.PLANT), SWAMP);

        // PLANT is vanilla's darker green, and the tint borrows brightness rather than
        // handing it out, so a tuft is still darker than the block it grows on.
        assertTrue(luma(tuft) < luma(block));
    }

    @Test
    @DisplayName("strength decides how much of the biome comes through")
    void strengthIsAFader() {
        var untinted = MapBaseColor.GRASS.rgb(MapBaseColor.Shade.HIGH);

        assertEquals(untinted, grassIn(SWAMP, 0.0), "nothing at zero");

        var half = grassIn(SWAMP, 0.5);
        var full = grassIn(SWAMP, 1.0);
        assertTrue(luma(full) < luma(half) && luma(half) < luma(untinted),
                "and half of the way there at a half");
    }

    @Test
    @DisplayName("shading still applies on top of a tinted colour")
    void tintedFacesStillTakeTheirBrightness() {
        var pipeline = pipelineAt(1.0);
        var top = pipeline.rgbOf(MapBaseColor.GRASS.packedId(MapBaseColor.Shade.HIGH) & 0xFF, SWAMP);
        var side = pipeline.rgbOf(MapBaseColor.GRASS.packedId(MapBaseColor.Shade.LOW) & 0xFF, SWAMP);

        // 180/255 of the top face, as every other block's side is.
        assertEquals(r(top) * 180 / 255, r(side));
        assertEquals(g(top) * 180 / 255, g(side));
    }

    @Test
    @DisplayName("an untinted block is not touched at all")
    void noTintMeansThePaletteEntry() {
        var pipeline = pipelineAt(1.0);
        var id = topFaceId(MapBaseColor.STONE);

        assertEquals(pipeline.paletteRgbOf(id), pipeline.rgbOf(id, RayHit.NO_TINT));
        assertEquals(pipeline.argbOf(id), pipeline.argbOf(id, RayHit.NO_TINT));
    }

    @Test
    @DisplayName("a tinted pixel still lands on the map palette")
    void tintedColoursAreSnapped() {
        var pipeline = pipelineAt(1.0);
        var argb = pipeline.argbOf(topFaceId(MapBaseColor.GRASS), SWAMP);

        assertEquals(0xFF000000 | converter.snap(argb & 0xFFFFFF), argb,
                "snapping it again must change nothing");
        // The cache has to answer the same thing the second time round.
        assertEquals(argb, pipeline.argbOf(topFaceId(MapBaseColor.GRASS), SWAMP));
    }
}
