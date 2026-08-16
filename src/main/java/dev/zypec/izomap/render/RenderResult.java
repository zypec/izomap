package dev.zypec.izomap.render;

import java.awt.image.BufferedImage;

/**
 * Output of a render.
 *
 * <p>{@code argb} holds the palette-snapped color of every pixel (row-major,
 * length = w*h). Pixels no ray hit are transparent (ARGB = 0).</p>
 *
 * <p>{@code depth} is the optional companion buffer {@link FocusPass} reads: how far
 * the camera plane is from what each pixel shows, in blocks. It is {@code null} on
 * everything that never asked for it — a cached photo, a scaled image, any render taken
 * with depth of field off — because at photo sizes it is as big as the picture.</p>
 */
public final class RenderResult {

    /**
     * Depth of a pixel the rays missed. Nothing is behind the sky, so it is the far end
     * of every comparison rather than a distance that could be focused on.
     */
    public static final float SKY_DEPTH = Float.MAX_VALUE;

    private final int width;
    private final int height;
    private final int[] argb;
    private final float[] depth;

    public RenderResult(int width, int height, int[] argb) {
        this(width, height, argb, null);
    }

    public RenderResult(int width, int height, int[] argb, float[] depth) {
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.depth = depth;
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
     * Distance from the camera plane per pixel (row-major, length = w*h), or
     * {@code null} when the render was not asked to keep it.
     */
    public float[] depth() {
        return depth;
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
