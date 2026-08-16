package dev.zypec.izomap.render;

/**
 * One step of a colour filter: takes a 0xRRGGBB and returns one.
 *
 * <p>Steps are deliberately small and independent so a server owner can describe a look
 * as a list rather than as a formula. They run in the order they are written.</p>
 *
 * <p>Every factory here clamps its own output, so a chain can never hand the next step
 * a channel outside 0-255 and no step has to defend against one.</p>
 */
@FunctionalInterface
public interface ColorOp {

    int apply(int rgb);

    // --- steps ---

    /**
     * Scales every channel. Above 1 brightens, below 1 darkens.
     */
    static ColorOp brightness(double factor) {
        return rgb -> pack(
                red(rgb) * factor,
                green(rgb) * factor,
                blue(rgb) * factor);
    }

    /**
     * Pushes channels away from mid grey, or towards it below 1.
     */
    static ColorOp contrast(double factor) {
        return rgb -> pack(
                128 + (red(rgb) - 128) * factor,
                128 + (green(rgb) - 128) * factor,
                128 + (blue(rgb) - 128) * factor);
    }

    /**
     * Pushes channels away from the colour's own brightness, or towards it below 1;
     * zero leaves grey.
     */
    static ColorOp saturation(double factor, double lumaRed, double lumaGreen, double lumaBlue) {
        return rgb -> {
            var luma = red(rgb) * lumaRed + green(rgb) * lumaGreen + blue(rgb) * lumaBlue;
            return pack(
                    luma + (red(rgb) - luma) * factor,
                    luma + (green(rgb) - luma) * factor,
                    luma + (blue(rgb) - luma) * factor);
        };
    }

    /**
     * Adds a fixed amount to each channel; a warm or cool cast is this and nothing else.
     */
    static ColorOp rgbOffset(int r, int g, int b) {
        return rgb -> pack(red(rgb) + r, green(rgb) + g, blue(rgb) + b);
    }

    /**
     * Replaces the colour with its own brightness.
     */
    static ColorOp grayscale(double lumaRed, double lumaGreen, double lumaBlue) {
        return rgb -> {
            var luma = red(rgb) * lumaRed + green(rgb) * lumaGreen + blue(rgb) * lumaBlue;
            return pack(luma, luma, luma);
        };
    }

    /**
     * Moves the colour a fraction of the way towards one colour, keeping its brightness
     * differences; a sepia or moonlight cast.
     */
    static ColorOp tint(int tintRgb, double strength) {
        var tr = red(tintRgb);
        var tg = green(tintRgb);
        var tb = blue(tintRgb);
        return rgb -> pack(
                red(rgb) + (tr - red(rgb)) * strength,
                green(rgb) + (tg - green(rgb)) * strength,
                blue(rgb) + (tb - blue(rgb)) * strength);
    }

    static ColorOp invert() {
        return rgb -> pack(255 - red(rgb), 255 - green(rgb), 255 - blue(rgb));
    }

    /**
     * Rounds each channel to a number of evenly spaced levels.
     *
     * <p>Blunter than it sounds against this palette: the render snaps to 244 colours
     * afterwards anyway, so posterizing first decides <i>which</i> of them a region
     * collapses onto.</p>
     */
    static ColorOp posterize(int levels) {
        var steps = Math.max(2, levels) - 1;
        return rgb -> pack(
                Math.round(red(rgb) * steps / 255.0) * 255.0 / steps,
                Math.round(green(rgb) * steps / 255.0) * 255.0 / steps,
                Math.round(blue(rgb) * steps / 255.0) * 255.0 / steps);
    }

    // --- helpers ---

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static int pack(double r, double g, double b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
