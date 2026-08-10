package dev.zypec.izomap.render;

/**
 * Color effects applied to a capture. The effect runs on the shaded RGB before it
 * snaps to the map palette, so the result stays consistent with map colors.
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

    /**
     * Applies the effect to a 0xRRGGBB color (no alpha).
     */
    public int apply(int rgb) {
        var r = (rgb >> 16) & 0xFF;
        var g = (rgb >> 8) & 0xFF;
        var b = rgb & 0xFF;
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
        if (raw == null) return fallback;

        var trimmed = raw.trim();
        for (var filter : values()) {
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
