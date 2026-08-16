package dev.zypec.izomap.util;

import java.util.Locale;

/**
 * Number formats shared by everything that shows a camera's settings.
 *
 * <p>The hologram, the preview's status line and the capture dialog describe the same
 * camera, so a value has to read the same in all three; keeping the format strings
 * apart is how one of them ends up saying 45 where another says 45.0. Always
 * {@link Locale#ROOT}: a decimal comma in a value the player compares against a config
 * number reads as a different number.</p>
 */
public final class Format {

    private Format() {
    }

    /**
     * An angle, whole degrees.
     */
    public static String degrees(float value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /**
     * A zoom multiplier, which is small enough to need its decimals.
     */
    public static String zoom(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * How tall the frame is in blocks at a zoom; the multiplier says little alone.
     */
    public static String blocks(double frameHeight, float zoom) {
        return String.format(Locale.ROOT, "%.0f", frameHeight / zoom);
    }

    /**
     * A world coordinate. Cameras sit on fractions of a block, so one decimal stays.
     */
    public static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
