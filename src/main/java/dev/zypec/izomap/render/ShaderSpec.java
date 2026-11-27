package dev.zypec.izomap.render;

/**
 * The look of the {@link PhotoStyle#SHADER} style, frozen into the photo like every
 * other setting that decides its pixels.
 *
 * @param grading  how far shadows lean blue and light leans warm, 0 to 1
 * @param bloom    how much the brightest parts glow onto their surroundings, 0 to 1
 * @param haze     how far the far ground fades into the sky, 0 to 1
 * @param vignette how much darker the corners are than the middle, 0 to 1
 * @param dither   how far a pixel may stray from its true color before the palette snap,
 *                 so the soft gradients do not band
 */
public record ShaderSpec(double grading, double bloom, double haze, double vignette, double dither) {

    public static final ShaderSpec NONE = new ShaderSpec(0.0, 0.0, 0.0, 0.0, 0.0);

    public boolean draws() {
        return grading > 0.0 || bloom > 0.0 || haze > 0.0 || vignette > 0.0;
    }
}
