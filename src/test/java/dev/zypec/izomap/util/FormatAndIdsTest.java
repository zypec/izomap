package dev.zypec.izomap.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Both of these are small enough to look obviously right and are exactly the kind that
 * break somewhere else: a decimal comma in a value the player compares against a config
 * number, or a malformed id taking a whole file down with it.
 */
class FormatAndIdsTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    @DisplayName("numbers read the same under a comma-decimal locale")
    void formatsIndependentlyOfLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertEquals("45", Format.degrees(45.0f));
        assertEquals("1.25", Format.zoom(1.25f));
        assertEquals("48", Format.blocks(48.0, 1.0f));
        assertEquals("-12.5", Format.coordinate(-12.5));
    }

    @Test
    @DisplayName("zoom divides the frame into the blocks it covers")
    void blocksFollowZoom() {
        assertEquals("192", Format.blocks(48.0, 0.25f));
        assertEquals("24", Format.blocks(48.0, 2.0f));
    }

    @Test
    @DisplayName("angles round to whole degrees")
    void degreesAreWhole() {
        assertEquals("30", Format.degrees(29.6f));
        assertEquals("0", Format.degrees(0.4f));
    }

    @Test
    @DisplayName("a well formed id parses and a broken one is null, not an exception")
    void parsesIds() {
        var id = UUID.randomUUID();

        assertEquals(id, Ids.parse(id.toString()));
        assertNull(Ids.parse(null));
        assertNull(Ids.parse(""));
        assertNull(Ids.parse("not-a-uuid"));
        // UUID.fromString accepts groups of any length, so a truncated id parses into a
        // different UUID unless the canonical form is insisted on.
        assertNull(Ids.parse(id.toString().substring(1)));
        assertNull(Ids.parse(id + "0"));
    }
}
