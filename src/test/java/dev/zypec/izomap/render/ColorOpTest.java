package dev.zypec.izomap.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter steps are written by server owners in a file, so the two things that must hold
 * whatever they write are that a channel never leaves 0-255 and that the steps run in
 * the order they were listed.
 */
class ColorOpTest {

    private static final int MID_GREY = 0x808080;

    @Test
    @DisplayName("an offset moves each channel by its own amount")
    void offsetsChannels() {
        var op = ColorOp.rgbOffset(25, 8, -20);
        assertEquals(0x99886C, op.apply(MID_GREY));
    }

    @Test
    @DisplayName("an offset cannot push a channel past the ends")
    void offsetClamps() {
        assertEquals(0xFFFFFF, ColorOp.rgbOffset(500, 500, 500).apply(MID_GREY));
        assertEquals(0x000000, ColorOp.rgbOffset(-500, -500, -500).apply(MID_GREY));
    }

    @Test
    @DisplayName("brightness scales, and cannot overflow into another channel")
    void brightnessScales() {
        assertEquals(0x404040, ColorOp.brightness(0.5).apply(MID_GREY));
        assertEquals(0xFFFFFF, ColorOp.brightness(10.0).apply(MID_GREY));
    }

    @Test
    @DisplayName("contrast leaves mid grey where it is")
    void contrastPivotsOnMidGrey() {
        assertEquals(MID_GREY, ColorOp.contrast(2.0).apply(MID_GREY));
        // A dark colour gets darker, a light one lighter.
        assertTrue(luma(ColorOp.contrast(1.5).apply(0x404040)) < luma(0x404040));
        assertTrue(luma(ColorOp.contrast(1.5).apply(0xC0C0C0)) > luma(0xC0C0C0));
    }

    @Test
    @DisplayName("saturation at zero leaves grey, and grey is unchanged by any of it")
    void saturationCollapsesToGrey() {
        var grey = ColorOp.saturation(0.0, 0.299, 0.587, 0.114).apply(0x4080C0);
        assertEquals((grey >> 16) & 0xFF, (grey >> 8) & 0xFF);
        assertEquals((grey >> 8) & 0xFF, grey & 0xFF);
        assertEquals(MID_GREY, ColorOp.saturation(2.0, 0.299, 0.587, 0.114).apply(MID_GREY));
    }

    @Test
    @DisplayName("grayscale weighs green heaviest, as the eye does")
    void grayscaleUsesLuma() {
        var fromGreen = ColorOp.grayscale(0.299, 0.587, 0.114).apply(0x00FF00);
        var fromBlue = ColorOp.grayscale(0.299, 0.587, 0.114).apply(0x0000FF);
        assertTrue(luma(fromGreen) > luma(fromBlue));
    }

    @Test
    @DisplayName("a tint at full strength is the tint, at none it is nothing")
    void tintInterpolates() {
        assertEquals(0xC0955F, ColorOp.tint(0xC0955F, 1.0).apply(MID_GREY));
        assertEquals(MID_GREY, ColorOp.tint(0xC0955F, 0.0).apply(MID_GREY));
    }

    @Test
    @DisplayName("invert twice is where it started")
    void invertIsItsOwnUndo() {
        var colour = 0x4080C0;
        assertEquals(colour, ColorOp.invert().apply(ColorOp.invert().apply(colour)));
    }

    @Test
    @DisplayName("posterize rounds to evenly spaced levels and keeps the ends")
    void posterizeSnapsToLevels() {
        var op = ColorOp.posterize(2);
        assertEquals(0x000000, op.apply(0x101010));
        assertEquals(0xFFFFFF, op.apply(0xF0F0F0));
    }

    @Test
    @DisplayName("a chain runs in the order it was written")
    void chainKeepsOrder() {
        var greyThenTint = new ColorFilter("A", List.of(
                ColorOp.grayscale(0.299, 0.587, 0.114),
                ColorOp.tint(0xFF0000, 1.0)));
        var tintThenGrey = new ColorFilter("B", List.of(
                ColorOp.tint(0xFF0000, 1.0),
                ColorOp.grayscale(0.299, 0.587, 0.114)));

        assertEquals(0xFF0000, greyThenTint.apply(0x4080C0));
        // The other way round the tint is flattened again, so red cannot survive.
        var flattened = tintThenGrey.apply(0x4080C0);
        assertEquals((flattened >> 16) & 0xFF, flattened & 0xFF);
    }

    @Test
    @DisplayName("a filter with no steps is the identity, and says so")
    void emptyChainIsIdentity() {
        assertTrue(ColorFilter.ORIGINAL.isIdentity());
        assertEquals(0x4080C0, ColorFilter.ORIGINAL.apply(0x4080C0));
    }

    private static int luma(int rgb) {
        return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
    }
}
