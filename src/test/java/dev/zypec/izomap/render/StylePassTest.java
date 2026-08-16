package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scale-up touches every pixel of a FAST photo, and two of its rules are easy to
 * break without anything looking obviously wrong: it must not leave colours the map
 * cannot store, and it must not average a hole into a colour, since the palette has no
 * translucency to average with.
 */
class StylePassTest {

    private final MapColorConverter converter = new MapColorConverter();

    private static RenderResult filled(int width, int height, int argb) {
        var pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return new RenderResult(width, height, pixels);
    }

    private static int palette(MapBaseColor base) {
        return 0xFF000000 | base.rgb(MapBaseColor.Shade.NORMAL);
    }

    @Test
    @DisplayName("upscaling to the same size returns the image untouched")
    void upscaleIsIdentityAtSameSize() {
        var image = filled(8, 8, palette(MapBaseColor.GRASS));
        assertSame(image, StylePass.upscale(image, 8, 8, converter));
    }

    @Test
    @DisplayName("upscaling produces the requested size")
    void upscaleResizes() {
        var result = StylePass.upscale(filled(4, 4, palette(MapBaseColor.WATER)), 16, 12, converter);

        assertEquals(16, result.width());
        assertEquals(12, result.height());
        assertEquals(16 * 12, result.argb().length);
    }

    @Test
    @DisplayName("upscaling a single colour cannot invent a second one")
    void upscaleKeepsUniformImagesUniform() {
        var colour = palette(MapBaseColor.STONE);
        var result = StylePass.upscale(filled(4, 4, colour), 16, 16, converter);

        for (var pixel : result.argb()) {
            assertEquals(colour, pixel, "a uniform image gained a colour while scaling");
        }
    }

    @Test
    @DisplayName("a fully transparent image scales up to a fully transparent one")
    void upscaleKeepsHoles() {
        var result = StylePass.upscale(filled(4, 4, 0), 16, 16, converter);

        for (var pixel : result.argb()) {
            assertEquals(0, pixel, "a hole became a colour while scaling");
        }
    }

    @Test
    @DisplayName("upscaling blends the boundary between two colours")
    void upscaleBlendsAcrossEdges() {
        // Left half water, right half sand: the seam has to gain intermediate pixels.
        var pixels = new int[4 * 4];
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                pixels[y * 4 + x] = x < 2 ? palette(MapBaseColor.WATER) : palette(MapBaseColor.SAND);
            }
        }
        var result = StylePass.upscale(new RenderResult(4, 4, pixels), 32, 32, converter);

        var distinct = new java.util.HashSet<Integer>();
        for (var x = 0; x < 32; x++) {
            distinct.add(result.argb()[16 * 32 + x]);
        }
        assertTrue(distinct.size() > 2, "the seam should gain colours between the two, got " + distinct.size());
    }

    @Test
    @DisplayName("a scaled up photo never leaves a colour off the palette")
    void upscaleStaysOnThePalette() {
        var pixels = new int[6 * 6];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = i % 3 == 0 ? palette(MapBaseColor.WATER)
                    : i % 3 == 1 ? palette(MapBaseColor.SAND)
                    : palette(MapBaseColor.COLOR_RED);
        }
        var result = StylePass.upscale(new RenderResult(6, 6, pixels), 24, 24, converter);

        for (var pixel : result.argb()) {
            assertEquals(pixel, converter.argbOf(converter.packedId(pixel)),
                    "scaling produced a colour a map cannot store");
        }
    }
}
