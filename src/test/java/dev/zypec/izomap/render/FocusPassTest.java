package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Depth of field touches every pixel of the image and three of its rules are the kind
 * that break quietly: a sharp surface must come out exactly as the walk drew it, a
 * <i>sharp</i> neighbour must not smear over a blurred one behind it, and nothing may
 * leave the map palette on the way out.
 */
class FocusPassTest {

    private static final int SIZE = 32;
    private static final double SPAN_HEIGHT = 48.0;

    /**
     * Depth of the near half in the split images below, and of everything in the flat
     * ones; the far half sits well beyond any focus range.
     */
    private static final float NEAR = 8.0f;
    private static final float FAR = 400.0f;

    private final MapColorConverter converter = new MapColorConverter();

    /**
     * A focus wide enough to blur visibly at this size: 0.2 of 32 rows is a 6px disc.
     */
    private static FocusSpec focusAt(double distance) {
        return new FocusSpec(true, distance, 0.5, 0.2, 24, 24.0);
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

    /**
     * Left half one colour at {@code nearDepth}, right half another at {@code farDepth}.
     */
    private static RenderResult split(int near, float nearDepth, int far, float farDepth) {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        for (var y = 0; y < SIZE; y++) {
            for (var x = 0; x < SIZE; x++) {
                var left = x < SIZE / 2;
                pixels[y * SIZE + x] = left ? near : far;
                depths[y * SIZE + x] = left ? nearDepth : farDepth;
            }
        }
        return new RenderResult(SIZE, SIZE, pixels, depths);
    }

    private RenderResult apply(RenderResult source, FocusSpec focus) {
        return FocusPass.apply(source, focus, SPAN_HEIGHT, converter, Runnable::run, 1);
    }

    @Test
    @DisplayName("a render with no depth buffer is handed back untouched")
    void withoutDepthNothingHappens() {
        var image = new RenderResult(SIZE, SIZE, new int[SIZE * SIZE]);
        assertSame(image, apply(image, focusAt(NEAR)));
    }

    @Test
    @DisplayName("focus off is handed back untouched")
    void disabledFocusNothingHappens() {
        var image = flat(palette(MapBaseColor.GRASS), NEAR);
        assertSame(image, apply(image, FocusSpec.NONE));
    }

    @Test
    @DisplayName("a zero radius is handed back untouched, whatever the distance says")
    void zeroRadiusNothingHappens() {
        var image = flat(palette(MapBaseColor.GRASS), FAR);
        assertSame(image, apply(image, new FocusSpec(true, NEAR, 0.5, 0.0, 24, 24.0)));
    }

    @Test
    @DisplayName("everything at the focus distance comes out exactly as it went in")
    void inFocusPixelsAreUntouched() {
        var colour = palette(MapBaseColor.STONE);
        var result = apply(split(colour, NEAR, palette(MapBaseColor.WATER), NEAR), focusAt(NEAR));

        for (var x = 0; x < SIZE / 2; x++) {
            assertEquals(colour, result.argb()[16 * SIZE + x],
                    "a sharp pixel was rewritten at x=" + x);
        }
    }

    @Test
    @DisplayName("a defocused subject spills over the edge it used to end at")
    void defocusedForegroundSpills() {
        // Focus on the far half; the near half is out of focus and must bleed both ways.
        var result = apply(split(palette(MapBaseColor.WATER), NEAR,
                palette(MapBaseColor.SAND), FAR), focusAt(FAR));

        var seam = new HashSet<Integer>();
        for (var x = SIZE / 2 - 6; x < SIZE / 2 + 6; x++) {
            seam.add(result.argb()[16 * SIZE + x]);
        }
        assertTrue(seam.size() > 2, "the seam should soften into more than the two colours, got " + seam.size());
    }

    @Test
    @DisplayName("a sharp subject does not smear over the blur behind it")
    void sharpForegroundDoesNotSpill() {
        var background = palette(MapBaseColor.SAND);
        // Focus on the near half: it is sharp, so its light lands on its own pixels only.
        var result = apply(split(palette(MapBaseColor.WATER), NEAR, background, FAR), focusAt(NEAR));

        for (var y = 0; y < SIZE; y++) {
            for (var x = SIZE / 2; x < SIZE; x++) {
                assertEquals(background, result.argb()[y * SIZE + x],
                        "a sharp foreground reached a background pixel at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("an untouched stretch of sky keeps its dither pattern")
    void skyIsLeftAlone() {
        // Two alternating blues, as the dithered sky writes them, and nothing else in
        // reach: re-averaging them would flatten the checker back into a band.
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        Arrays.fill(depths, RenderResult.SKY_DEPTH);
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = i % 2 == 0 ? palette(MapBaseColor.WATER) : palette(MapBaseColor.COLOR_LIGHT_BLUE);
        }
        var result = apply(new RenderResult(SIZE, SIZE, pixels, depths), focusAt(NEAR));

        assertEquals(SIZE * SIZE, result.argb().length);
        for (var i = 0; i < pixels.length; i++) {
            assertEquals(pixels[i], result.argb()[i], "the sky was blurred into itself at " + i);
        }
    }

    @Test
    @DisplayName("a flat defocused surface does not gain dither speckle")
    void flatBlurStaysFlat() {
        var colour = palette(MapBaseColor.COLOR_RED);
        var result = apply(flat(colour, FAR), focusAt(NEAR));

        for (var pixel : result.argb()) {
            assertEquals(colour, pixel, "blurring one colour produced a second one");
        }
    }

    @Test
    @DisplayName("holes stay holes rather than averaging into a colour")
    void transparencySurvives() {
        var result = apply(flat(0, FAR), focusAt(NEAR));

        for (var pixel : result.argb()) {
            assertEquals(0, pixel, "a hole was blurred into a colour");
        }
    }

    @Test
    @DisplayName("a blurred photo never leaves a colour off the palette")
    void blurStaysOnThePalette() {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = i % 3 == 0 ? palette(MapBaseColor.WATER)
                    : i % 3 == 1 ? palette(MapBaseColor.SAND)
                    : palette(MapBaseColor.COLOR_RED);
            depths[i] = FAR;
        }
        var result = apply(new RenderResult(SIZE, SIZE, pixels, depths), focusAt(NEAR));

        for (var pixel : result.argb()) {
            assertEquals(pixel, converter.argbOf(converter.packedId(pixel)),
                    "the blur produced a colour a map cannot store");
        }
    }

    @Test
    @DisplayName("the blur really is a blur: mixed colours at one depth average together")
    void mixedColoursAverage() {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        for (var y = 0; y < SIZE; y++) {
            for (var x = 0; x < SIZE; x++) {
                pixels[y * SIZE + x] = (x / 4 + y / 4) % 2 == 0
                        ? palette(MapBaseColor.SNOW) : palette(MapBaseColor.COLOR_BLACK);
                depths[y * SIZE + x] = FAR;
            }
        }
        var source = new RenderResult(SIZE, SIZE, pixels, depths);
        var result = apply(source, focusAt(NEAR));

        var changed = 0;
        for (var i = 0; i < pixels.length; i++) {
            if (result.argb()[i] != pixels[i]) {
                changed++;
            }
        }
        assertNotEquals(0, changed, "a checkerboard at full defocus came out unchanged");
    }

    @Test
    @DisplayName("bands and threads draw the same image as a single pass")
    void bandsMatchSingleThread() {
        var pixels = new int[SIZE * SIZE];
        var depths = new float[SIZE * SIZE];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = i % 2 == 0 ? palette(MapBaseColor.GRASS) : palette(MapBaseColor.DIRT);
            depths[i] = i % 5 == 0 ? NEAR : FAR;
        }
        var source = new RenderResult(SIZE, SIZE, pixels, depths);
        var focus = focusAt(NEAR);

        var single = FocusPass.apply(source, focus, SPAN_HEIGHT, converter, Runnable::run, 1);
        var banded = FocusPass.apply(source, focus, SPAN_HEIGHT, converter, Runnable::run, 4);

        assertArrayEquals(single.argb(), banded.argb());
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual,
                "splitting the image into bands changed it");
    }
}
