package dev.zypec.izomap.render;

/**
 * Supported photo aspect ratios.
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

    /**
     * The ratio as a number (width / height).
     */
    public double value() {
        return (double) width / height;
    }

    /**
     * Human-readable label, e.g. "16:9".
     */
    public String label() {
        return width + ":" + height;
    }

    public static AspectRatio fromString(String raw, AspectRatio fallback) {
        if (raw == null) return fallback;

        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /**
     * Resolves a label ("16:9") or enum name ("RATIO_16_9"); null when unknown.
     */
    public static AspectRatio fromLabel(String raw) {
        if (raw == null) return null;

        var trimmed = raw.trim();
        for (var ratio : values()) {
            if (ratio.label().equalsIgnoreCase(trimmed) || ratio.name().equalsIgnoreCase(trimmed)) {
                return ratio;
            }
        }
        return null;
    }
}
