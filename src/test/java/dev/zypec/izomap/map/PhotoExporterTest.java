package dev.zypec.izomap.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An export file name is player input that becomes a path, so the interesting cases are
 * the ones that try to leave the folder or to name something the filesystem treats
 * specially.
 */
class PhotoExporterTest {

    @Test
    @DisplayName("an ordinary name is left alone")
    void keepsOrdinaryNames() {
        assertEquals("manzara-2", PhotoExporter.sanitize("manzara-2"));
        assertEquals("Kule_1.v2", PhotoExporter.sanitize("Kule_1.v2"));
    }

    @Test
    @DisplayName("path separators cannot survive")
    void stripsPathSeparators() {
        assertFalse(PhotoExporter.sanitize("../../server").contains("/"));
        assertFalse(PhotoExporter.sanitize("..\\..\\server").contains("\\"));
        assertFalse(PhotoExporter.sanitize("/etc/passwd").contains("/"));
    }

    @Test
    @DisplayName("a name cannot start with a dot and hide the file")
    void stripsLeadingDots() {
        assertFalse(PhotoExporter.sanitize(".gitignore").startsWith("."));
        assertFalse(PhotoExporter.sanitize("...").startsWith("."));
    }

    @Test
    @DisplayName("a name that sanitizes away falls back rather than emptying")
    void neverReturnsBlank() {
        assertEquals("photo", PhotoExporter.sanitize(""));
        assertEquals("photo", PhotoExporter.sanitize("..."));
        // Whitespace turns into underscores rather than nothing, and never reaches here
        // anyway: a blank request is answered with the photo's own name upstream.
        assertEquals("___", PhotoExporter.sanitize("   "));
    }

    @Test
    @DisplayName("a long name is cut to a length every filesystem accepts")
    void limitsLength() {
        var name = PhotoExporter.sanitize("a".repeat(500));

        assertEquals(64, name.length());
        assertTrue(name.chars().allMatch(c -> c == 'a'));
    }

    @Test
    @DisplayName("characters that differ between filesystems are replaced, not dropped")
    void replacesAwkwardCharacters() {
        // Replacing rather than dropping keeps two different names from colliding.
        assertEquals("a_b", PhotoExporter.sanitize("a b"));
        assertEquals("ge_it", PhotoExporter.sanitize("geçit"));
        assertEquals("a_b", PhotoExporter.sanitize("a:b"));
    }
}
