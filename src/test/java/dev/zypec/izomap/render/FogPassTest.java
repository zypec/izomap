package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fog rewrites most of a landscape, so the rules that keep it honest are checked: what
 * is near and what is sky stay as the walk drew them, the fade only ever grows with
 * depth, and nothing leaves the map palette.
 */
class FogPassTest {

    private static final int SIZE = 16;
    private static final int FOG_RGB = 0xC8D0D8;

    private final MapColorConverter converter = new MapColorConverter();

    private static FogSpec fog(double strength) {
        return new FogSpec(true, 100.0, 1000.0, strength, FOG_RGB, 24.0);
    }

    private static FogSpec undithered(double strength) {
        return new FogSpec(true, 100.0, 1000.0, strength, FOG_RGB, 0.0);
    }

    private static int palette(MapBaseColor base) {
        return 0xFF000000 | base.rgb(MapBaseColor.Shade.NORMAL);
    }

    private static RenderResult flat(int argb, float depth) {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        Arrays.fill(pixels, argb);
        Arrays.fill(depths, depth);
        return new RenderResult(SIZE, SIZE, pixels, depths);
    }

    private RenderResult apply(RenderResult source, FogSpec fog) {
        return FogPass.apply(source, fog, Sky.NONE, converter, Runnable::run, 1);
    }

    private static double distance(int a, int b) {
        var dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        var dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        var db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * Mean distance of the image from the fog color, so dithering averages out.
     */
    private static double meanDistance(RenderResult result) {
        var sum = 0.0;
        for (var pixel : result.argb())
            sum += distance(pixel & 0xFFFFFF, FOG_RGB);
        return sum / result.argb().length;
    }

    @Test
    @DisplayName("no depth buffer, no fog, or a sky fog with no sky is handed back untouched")
    void nothingToDo() {
        var image = flat(palette(MapBaseColor.GRASS), 500.0f);
        assertSame(image, apply(image, FogSpec.NONE));
        assertSame(image, apply(image, fog(0.0)));
        assertSame(image, apply(image, new FogSpec(true, 100.0, 1000.0, 1.0, FogSpec.SKY, 24.0)));

        var noDepth = new RenderResult(SIZE, SIZE, new int[SIZE * SIZE]);
        assertSame(noDepth, apply(noDepth, fog(1.0)));
    }

    @Test
    @DisplayName("nearer than the start and sky pixels come out exactly as they went in")
    void nearAndSkyAreUntouched() {
        var grass = palette(MapBaseColor.GRASS);
        var near = apply(flat(grass, 50.0f), fog(1.0));
        assertTrue(Arrays.stream(near.argb()).allMatch(pixel -> pixel == grass));

        var sky = apply(flat(grass, RenderResult.SKY_DEPTH), fog(1.0));
        assertTrue(Arrays.stream(sky.argb()).allMatch(pixel -> pixel == grass));
    }

    @Test
    @DisplayName("the further a surface, the closer it comes to the fog color")
    void fadeGrowsWithDepth() {
        var grass = palette(MapBaseColor.GRASS);
        var previous = Double.MAX_VALUE;
        for (var depth : new float[]{300.0f, 600.0f, 1200.0f}) {
            var mean = meanDistance(apply(flat(grass, depth), fog(1.0)));
            assertTrue(mean < previous, "depth " + depth + " was no foggier than the one before it");
            previous = mean;
        }
    }

    @Test
    @DisplayName("full strength past the end leaves the fog color, a lower one a trace of the surface")
    void strengthCapsTheFade() {
        var grass = palette(MapBaseColor.GRASS);
        var full = apply(flat(grass, 2000.0f), undithered(1.0));
        var snappedFog = 0xFF000000 | converter.snap(FOG_RGB);
        assertEquals(snappedFog, full.argb()[0]);

        var partial = apply(flat(grass, 2000.0f), undithered(0.5));
        assertNotEquals(snappedFog, partial.argb()[0]);
        assertNotEquals(grass, partial.argb()[0]);
    }

    @Test
    @DisplayName("a fogged photo never leaves a color off the palette")
    void staysOnPalette() {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = i % 2 == 0 ? palette(MapBaseColor.WATER) : palette(MapBaseColor.SAND);
            depths[i] = 100.0f + i * 4.0f;
        }
        var result = apply(new RenderResult(SIZE, SIZE, pixels, depths), fog(1.0));
        for (var pixel : result.argb()) {
            assertEquals(pixel, converter.argbOf(converter.packedId(pixel)),
                    "off-palette pixel " + Integer.toHexString(pixel));
        }
    }
}
