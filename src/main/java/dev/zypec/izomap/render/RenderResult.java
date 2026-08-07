package dev.zypec.izomap.render;

import java.awt.image.BufferedImage;

/**
 * Bir render işleminin çıktısı.
 *
 * <p>{@code argb} her piksel için harita paletine snap'lenmiş rengi tutar
 * (satır-öncelikli, uzunluk = w*h). Bu değerler hem PNG önizlemesi hem de
 * FAZ 4'te {@code MapCanvas.setPixelColor} ile karolara aktarım için kullanılır.
 * İsabetsiz pikseller şeffaftır (ARGB = 0).</p>
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

    /** Harita paletine snap'lenmiş ARGB renkler (satır-öncelikli, uzunluk = w*h). */
    public int[] argb() {
        return argb;
    }

    /** Belirli bir pikselin ARGB değeri. */
    public int pixel(int x, int y) {
        return argb[y * width + x];
    }

    /** Önizleme/PNG için ARGB tamponundan bir {@link BufferedImage} üretir. */
    public BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
