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
     *
     * <p>Only the canonical form counts. {@link UUID#fromString} accepts groups of any
     * length, so a truncated identifier parses happily into a <b>different</b> UUID —
     * a corrupted record would then bind to somebody else's camera instead of being
     * skipped, which is the one outcome this class exists to prevent. Comparing the
     * result back against the text is what rules that out.</p>
     */
    public static UUID parse(String raw) {
        if (raw == null) return null;

        try {
            var id = UUID.fromString(raw);
            return id.toString().equalsIgnoreCase(raw) ? id : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
