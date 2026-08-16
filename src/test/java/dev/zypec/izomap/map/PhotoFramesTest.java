package dev.zypec.izomap.map;

import dev.zypec.izomap.render.RenderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Frame drawing is geometry over a pixel buffer, so it can be checked exactly: which
 * pixels a ring lands on, that the picture inside is untouched, and that a frame too
 * thick for its photo is trimmed rather than allowed to swallow it.
 */
class PhotoFramesTest {

    private static final int BLANK = 0xFF000000;
    private static final int OUTER = 0xFFAA0000;
    private static final int INNER = 0xFF00AA00;

    private static RenderResult blank(int width, int height) {
        var pixels = new int[width * height];
        java.util.Arrays.fill(pixels, BLANK);
        return new RenderResult(width, height, pixels);
    }

    private static int at(RenderResult image, int x, int y) {
        return image.argb()[y * image.width() + x];
    }

    @Test
    @DisplayName("rings are drawn inward, in the order they are listed")
    void ringsGoInward() {
        var frame = new PhotoFrames.Frame("TEST",
                List.of(new PhotoFrames.Ring(OUTER, 2), new PhotoFrames.Ring(INNER, 1)));
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(OUTER, at(framed, 0, 0));
        assertEquals(OUTER, at(framed, 1, 1));
        assertEquals(INNER, at(framed, 2, 2));
        assertEquals(BLANK, at(framed, 3, 3));
        // All four edges, not just the corner it starts from.
        assertEquals(OUTER, at(framed, 39, 20));
        assertEquals(OUTER, at(framed, 20, 39));
        assertEquals(INNER, at(framed, 37, 20));
    }

    @Test
    @DisplayName("the picture inside the frame is left alone")
    void insideIsUntouched() {
        var frame = new PhotoFrames.Frame("TEST", List.of(new PhotoFrames.Ring(OUTER, 3)));
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(BLANK, at(framed, 20, 20));
        assertEquals(BLANK, at(framed, 3, 3));
    }

    @Test
    @DisplayName("the original image is not written over")
    void originalIsCopied() {
        var original = blank(40, 40);
        var frame = new PhotoFrames.Frame("TEST", List.of(new PhotoFrames.Ring(OUTER, 3)));
        var framed = PhotoFrames.draw(original, frame);

        assertEquals(BLANK, at(original, 0, 0));
        assertNotEquals(BLANK, at(framed, 0, 0));
    }

    @Test
    @DisplayName("a frame thicker than the photo can bear is trimmed, not refused")
    void thicknessIsCapped() {
        // 40 px shorter side, 40% of it across two edges: eight pixels each.
        var frame = new PhotoFrames.Frame("TEST", List.of(new PhotoFrames.Ring(OUTER, 30)));
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(OUTER, at(framed, 7, 20));
        assertEquals(BLANK, at(framed, 8, 20));
        assertEquals(BLANK, at(framed, 20, 20));
    }

    @Test
    @DisplayName("a photo wider than it is tall is bounded by its shorter side")
    void nonSquareUsesShorterSide() {
        var frame = new PhotoFrames.Frame("TEST", List.of(new PhotoFrames.Ring(OUTER, 30)));
        var framed = PhotoFrames.draw(blank(200, 40), frame);

        assertEquals(OUTER, at(framed, 100, 7));
        assertEquals(BLANK, at(framed, 100, 8));
    }

    @Test
    @DisplayName("no frame means the same image, not a copy of it")
    void noFrameIsNoWork() {
        var original = blank(8, 8);
        assertSame(original, PhotoFrames.draw(original, null));
        assertSame(original, PhotoFrames.draw(original, new PhotoFrames.Frame("EMPTY", List.of())));
    }
}
