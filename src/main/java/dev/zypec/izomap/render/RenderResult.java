package dev.zypec.izomap.render;

import java.awt.image.BufferedImage;

/**
 * Output of a render.
 *
 * <p>{@code argb} holds the palette-snapped color of every pixel (row-major,
 * length = w*h). Pixels no ray hit are transparent (ARGB = 0).</p>
 */
public final class RenderResult {

    private final int width;
    private final int height;
    private final int[] argb;

    public RenderResult(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Palette-snapped ARGB colors (row-major, length = w*h).
     */
    public int[] argb() {
        return argb;
    }

    /**
     * Builds a {@link BufferedImage} from the ARGB buffer.
     */
    public BufferedImage toImage() {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
