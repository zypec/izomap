package dev.zypec.izomap.util;

import java.util.UUID;

/**
 * Parsing of the UUIDs that identify cameras, photos, worlds, and entities.
 *
 * <p>Every identifier reaches us as text from a YML record, a persistent data
 * container, or a file name, so a malformed value is an ordinary outcome rather than
 * an error: the caller drops the record it belongs to and keeps loading the rest.</p>
 */
public final class Ids {

    private Ids() {
    }

    /**
     * The UUID the text spells, or {@code null} when it is absent or malformed.
     */
    public static UUID parse(String raw) {
        if (raw == null) return null;

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
