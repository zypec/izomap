package dev.zypec.izomap.render;

/**
 * How much of the photo is really traced, as opposed to filled in from what was.
 *
 * <p>Display names live under {@code style.<NAME>} in {@code messages.yml}; only the
 * constant name is ever written to disk.</p>
 */
public enum PhotoStyle {

    /**
     * A ray for every pixel of the finished photo.
     */
    SHARP,
    /**
     * Traced over a smaller image and scaled back up, so the ray count falls with the
     * square of {@code photo.style.fast-scale}: at half scale a photo costs a quarter of
     * the rays. Large grids are where it earns its place — the image is softer for it,
     * which is the trade being made rather than an effect being sought.
     */
    FAST;

    /**
     * Whether this style wants the image traced smaller and scaled up.
     */
    public boolean scalesDown() {
        return this == FAST;
    }

    public static PhotoStyle fromString(String raw, PhotoStyle fallback) {
        if (raw == null) return fallback;

        var trimmed = raw.trim();
        for (var style : values()) {
            if (style.name().equalsIgnoreCase(trimmed)) {
                return style;
            }
        }
        return fallback;
    }
}
