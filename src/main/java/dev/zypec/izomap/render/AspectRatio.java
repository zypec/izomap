package dev.zypec.izomap.render;

/**
 * Desteklenen fotoğraf en-boy oranları.
 *
 * <p>FAZ 4'te bu oranlara göre uygun grid (karo) seçenekleri türetilecek
 * (ör. 1:1 -&gt; 1x1, 2x2, 3x3 | 16:9 -&gt; 4x2, 16x9).</p>
 */
public enum AspectRatio {

    RATIO_1_1(1, 1),
    RATIO_16_9(16, 9),
    RATIO_4_3(4, 3);

    private final int width;
    private final int height;

    AspectRatio(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** En-boy oranının sayısal değeri (width / height). */
    public double value() {
        return (double) width / height;
    }

    /** İnsan tarafından okunabilir etiket (ör. "16:9"). */
    public String label() {
        return width + ":" + height;
    }

    public static AspectRatio fromString(String raw, AspectRatio fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /** Etiket ("16:9") veya enum adından ("RATIO_16_9") çözümler; bulunamazsa null. */
    public static AspectRatio fromLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (AspectRatio ratio : values()) {
            if (ratio.label().equalsIgnoreCase(trimmed) || ratio.name().equalsIgnoreCase(trimmed)) {
                return ratio;
            }
        }
        return null;
    }
}
