package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The photo cache stores one palette byte per pixel, so the trip
 * {@code colour -> byte -> colour} has to be lossless for every colour the renderer can
 * produce. If it ever stops being, cached photos come back subtly wrong after a restart
 * and nothing else in the plugin would notice.
 */
class MapColorConverterTest {

    private final MapColorConverter converter = new MapColorConverter();

    @Test
    @DisplayName("every palette colour survives the cache round trip")
    void roundTripsEveryPaletteColour() {
        for (var base : MapBaseColor.values()) {
            if (base == MapBaseColor.NONE) continue;

            for (var shade : MapBaseColor.Shade.values()) {
                var argb = 0xFF000000 | base.rgb(shade);
                var packed = converter.packedId(argb);

                assertEquals(base.packedId(shade), packed,
                        () -> base + "/" + shade + " packed to the wrong byte");
                assertEquals(argb, converter.argbOf(packed),
                        () -> base + "/" + shade + " came back as a different colour");
            }
        }
    }

    @Test
    @DisplayName("a transparent pixel stays transparent both ways")
    void keepsTransparency() {
        assertEquals(0, converter.packedId(0));
        assertEquals(0, converter.argbOf((byte) 0));
        // Only the alpha is zero; the colour underneath must not rescue it.
        assertEquals(0, converter.packedId(0x0078A7FF));
    }

    @Test
    @DisplayName("a colour off the palette snaps to a palette colour, not to itself")
    void snapsOffPaletteColours() {
        var offPalette = 0x123456;
        var snapped = converter.snap(offPalette);

        assertNotEquals(offPalette, snapped, "an off-palette colour cannot be its own answer");
        assertEquals(snapped, converter.snap(snapped), "snapping twice must not move again");
        assertTrue(converter.packedId(0xFF000000 | snapped) != 0,
                "a snapped colour must be storable as a map byte");
    }

    @Test
    @DisplayName("the shade modifiers match the vanilla map palette")
    void appliesVanillaShadeModifiers() {
        // 0x7FB238 * 180 / 255 per channel, integer division, as vanilla does it.
        assertEquals(0x597D27, MapBaseColor.GRASS.rgb(MapBaseColor.Shade.LOW));
        assertEquals(MapBaseColor.GRASS.baseRgb(), MapBaseColor.GRASS.rgb(MapBaseColor.Shade.HIGH));
    }
}
