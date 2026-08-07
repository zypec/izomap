package dev.zypec.izomap.render;

/**
 * Fotoğraf çekimine uygulanan renk efektleri.
 *
 * <p>Efekt, gölgelenmiş RGB'ye harita paletine snap'lenmeden önce uygulanır;
 * böylece sonuç harita renkleriyle tutarlı kalır.</p>
 */
public enum ColorFilter {

    ORIGINAL("Orijinal"),
    WARM("Sıcak"),
    COOL("Soğuk"),
    GRAYSCALE("Siyah-Beyaz");

    private final String label;

    ColorFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Verilen 0xRRGGBB rengine efekti uygular (alfa yok). */
    public int apply(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        switch (this) {
            case WARM -> {
                r = clamp(r + 25);
                g = clamp(g + 8);
                b = clamp(b - 20);
            }
            case COOL -> {
                r = clamp(r - 20);
                g = clamp(g + 6);
                b = clamp(b + 25);
            }
            case GRAYSCALE -> {
                int y = clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
                r = y;
                g = y;
                b = y;
            }
            case ORIGINAL -> {
                return rgb;
            }
        }
        return (r << 16) | (g << 8) | b;
    }

    public static ColorFilter fromString(String raw, ColorFilter fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        for (ColorFilter filter : values()) {
            if (filter.name().equalsIgnoreCase(trimmed) || filter.label.equalsIgnoreCase(trimmed)) {
                return filter;
            }
        }
        return fallback;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
