package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Game time is a circle and the keyframes are not evenly spaced around it, so the
 * interpolation is the part worth pinning down: the stretch from dawn to noon crosses
 * tick 0, which is exactly where an interval search goes wrong.
 */
class SkyTest {

    private static final int DAWN = 0xFF0000;
    private static final int DAY = 0x00FF00;
    private static final int DUSK = 0x0000FF;
    private static final int NIGHT = 0x000000;

    private final MapColorConverter converter = new MapColorConverter();

    private int at(int ticks) {
        return Sky.colorAt(ticks, DAWN, DAY, DUSK, NIGHT);
    }

    @Test
    @DisplayName("each keyframe returns its own colour exactly")
    void hitsKeyframesExactly() {
        assertEquals(DAWN, at(SkyOption.DAWN.ticks()));
        assertEquals(DAY, at(SkyOption.DAY.ticks()));
        assertEquals(DUSK, at(SkyOption.DUSK.ticks()));
        assertEquals(NIGHT, at(SkyOption.NIGHT.ticks()));
    }

    @Test
    @DisplayName("the dawn to noon stretch interpolates across tick 0")
    void wrapsAroundMidnight() {
        // 23000 -> 6000 spans 7000 ticks through 0; tick 500 sits 1500 into it.
        var wrapped = at(500);

        assertNotEquals(DAWN, wrapped, "tick 500 is past dawn");
        assertNotEquals(DAY, wrapped, "tick 500 is well before noon");
        var red = (wrapped >> 16) & 0xFF;
        var green = (wrapped >> 8) & 0xFF;
        assertTrue(red > 0 && green > 0, "a wrapped tick must mix both ends, got " + Integer.toHexString(wrapped));
        // Still nearer dawn than noon this early in the stretch.
        assertTrue(red > green, "tick 500 should still be mostly dawn");
    }

    @Test
    @DisplayName("time outside a day folds back into one")
    void foldsTimeIntoOneDay() {
        assertEquals(at(SkyOption.DAY.ticks()), at(SkyOption.DAY.ticks() + 24_000));
        assertEquals(at(SkyOption.DAY.ticks()), at(SkyOption.DAY.ticks() - 24_000));
    }

    @Test
    @DisplayName("a flat sky paints one palette colour on every row")
    void flatSkyIsUniform() {
        var sky = Sky.of(0x78A7FF, false, 0.45, 0.0, 64, converter);
        var first = sky.argbAt(0, 0);

        assertTrue(sky.draws());
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 4; x++) {
                assertEquals(first, sky.argbAt(x, y), "flat sky changed at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("a gradient sky pales downwards")
    void gradientPalesTowardsHorizon() {
        var sky = Sky.of(0x2050C0, true, 1.0, 0.0, 64, converter);

        var top = luma(sky.argbAt(0, 0));
        var bottom = luma(sky.argbAt(0, 63));
        assertTrue(bottom > top, "the horizon row should be lighter than the zenith row");
    }

    @Test
    @DisplayName("dithering varies within the cell but not between cells")
    void ditherRepeatsEveryFourPixels() {
        var sky = Sky.of(0x2050C0, false, 0.0, 64.0, 8, converter);

        assertEquals(sky.argbAt(0, 0), sky.argbAt(4, 0), "the cell must repeat every 4 pixels");
        assertEquals(sky.argbAt(1, 2), sky.argbAt(9, 2));

        var distinct = new java.util.HashSet<Integer>();
        for (var x = 0; x < 4; x++) {
            distinct.add(sky.argbAt(x, 0));
        }
        assertTrue(distinct.size() > 1, "dithering must produce more than one colour in a row");
    }

    @Test
    @DisplayName("no sky paints nothing")
    void noneStaysTransparent() {
        assertFalse(Sky.NONE.draws());
        assertEquals(0, Sky.NONE.argbAt(3, 9));
    }

    @Test
    @DisplayName("every sky pixel is a real palette colour")
    void staysOnThePalette() {
        var sky = Sky.of(0x2050C0, true, 0.6, 32.0, 32, converter);

        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 4; x++) {
                var argb = sky.argbAt(x, y);
                assertEquals(argb, converter.argbOf(converter.packedId(argb)),
                        "sky pixel at " + x + "," + y + " is not storable in a map");
            }
        }
    }

    private static int luma(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
