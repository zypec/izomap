package dev.zypec.izomap.render;

/**
 * What sky a photo is shot against, as the player picks it.
 *
 * <p>One control rather than two. The player answers <i>which sky</i>, and the server
 * owner answers <i>how a sky is drawn</i> in {@code photo.sky}; asking a player for a
 * tick count and a rendering mode to get a sunset would be two questions for one
 * decision.</p>
 *
 * <p>Display names live under {@code sky.<NAME>} in {@code messages.yml}; only the
 * constant name is ever written to disk.</p>
 */
public enum SkyOption {

    /**
     * No sky at all: rays that reach nothing stay transparent, as they always have.
     * Still the default, since a photo with a hole in it can be hung over anything.
     */
    NONE(-1),
    /**
     * Whatever time it was when the shutter went; resolved at capture and frozen, so a
     * re-render years later still shows that evening.
     */
    WORLD(-1),
    DAWN(23_000),
    DAY(6_000),
    DUSK(12_500),
    NIGHT(18_000);

    private final int ticks;

    SkyOption(int ticks) {
        this.ticks = ticks;
    }

    /**
     * Game time this option stands for, or {@code -1} when it has none of its own.
     */
    public int ticks() {
        return ticks;
    }

    /**
     * Whether the photo gets a sky at all.
     */
    public boolean draws() {
        return this != NONE;
    }

    public static SkyOption fromString(String raw, SkyOption fallback) {
        if (raw == null) return fallback;

        var trimmed = raw.trim();
        for (var option : values()) {
            if (option.name().equalsIgnoreCase(trimmed)) {
                return option;
            }
        }
        return fallback;
    }
}
