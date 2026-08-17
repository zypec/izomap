package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grass a server cannot name for itself. This grid replaced a call that answered
 * <b>black</b> for every ordinary biome — the colormap it reads lives only on the client
 * — so the test that matters is against real biome climates and the colours the game is
 * known to draw them in.
 */
class ColormapsTest {

    /**
     * A channel may sit this far from the published colour: the grid is nine nodes wide,
     * and everything here is snapped to a 244-colour palette afterwards.
     */
    private static final int TOLERANCE = 2;

    private static void assertColor(int expected, int actual, String what) {
        for (var shift : new int[]{16, 8, 0}) {
            var e = (expected >> shift) & 0xFF;
            var a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= TOLERANCE,
                    () -> "%s: expected #%06X, got #%06X".formatted(what, expected, actual));
        }
    }

    @Test
    @DisplayName("the ordinary biomes come out the colour the game draws them")
    void knownBiomesMatchTheGame() {
        // temperature and downfall as the vanilla biomes declare them.
        assertColor(0x91BD59, Colormaps.grass(0.8, 0.4), "plains");
        assertColor(0xBFB755, Colormaps.grass(2.0, 0.0), "desert");
        assertColor(0x59C93C, Colormaps.grass(0.95, 0.9), "jungle");
        assertColor(0x86B783, Colormaps.grass(0.25, 0.8), "taiga");
        assertColor(0x80B497, Colormaps.grass(0.0, 0.5), "snowy plains");
        assertColor(0x79C05A, Colormaps.grass(0.7, 0.8), "forest");
    }

    @Test
    @DisplayName("leaves have their own map and their own colours")
    void foliageIsNotGrass() {
        assertColor(0x77AB2F, Colormaps.foliage(0.8, 0.4), "plains");
        assertColor(0xAEA42A, Colormaps.foliage(2.0, 0.0), "desert");
        assertColor(0x30BB0B, Colormaps.foliage(0.95, 0.9), "jungle");
        assertColor(0x60A17B, Colormaps.foliage(0.0, 0.5), "snowy plains");
    }

    @Test
    @DisplayName("nothing on the map is black, whatever the climate")
    void noClimateReadsAsBlack() {
        // The bug this grid exists for: a black grass colour is what a missing colormap
        // looks like, and it must not be reachable from any climate.
        for (var t = 0; t <= 100; t++) {
            for (var r = 0; r <= 100; r++) {
                var temperature = t / 100.0;
                var downfall = r / 100.0;
                var grass = Colormaps.grass(temperature, downfall);
                var foliage = Colormaps.foliage(temperature, downfall);
                assertTrue(green(grass) > 100 && green(foliage) > 100,
                        () -> "climate %.2f/%.2f gave #%06X and #%06X"
                                .formatted(temperature, downfall, grass, foliage));
            }
        }
    }

    @Test
    @DisplayName("out-of-range climates are clamped rather than wrapped")
    void extremesAreClamped() {
        assertEquals(Colormaps.grass(1.0, 1.0), Colormaps.grass(9.0, 9.0));
        assertEquals(Colormaps.grass(0.0, 0.0), Colormaps.grass(-3.0, -3.0));
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }
}
