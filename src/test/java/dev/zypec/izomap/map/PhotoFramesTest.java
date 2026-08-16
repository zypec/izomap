package dev.zypec.izomap.map;

import dev.zypec.izomap.render.RenderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Frame drawing is geometry over a pixel buffer, so it can be checked exactly: where a
 * ring lands, where a repeating pattern lands, that a corner sprite reaches all four
 * corners, and that art too thick for its photo is trimmed rather than allowed to
 * swallow it.
 */
class PhotoFramesTest {

    private static final int BLANK = 0xFF000000;
    private static final int OUTER = 0xFFAA0000;
    private static final int INNER = 0xFF00AA00;
    private static final int MARK = 0xFF0000AA;
    private static final int CLEAR = 0;

    private static RenderResult blank(int width, int height) {
        var pixels = new int[width * height];
        Arrays.fill(pixels, BLANK);
        return new RenderResult(width, height, pixels);
    }

    private static int at(RenderResult image, int x, int y) {
        return image.argb()[y * image.width() + x];
    }

    private static PhotoFrames.Frame rings(PhotoFrames.Ring... rings) {
        return PhotoFrames.ringFrame("TEST", List.of(rings), 1);
    }

    // --- rings ---

    @Test
    @DisplayName("rings are drawn inward, in the order they are listed")
    void ringsGoInward() {
        var framed = PhotoFrames.draw(blank(40, 40),
                rings(new PhotoFrames.Ring(OUTER, 2), new PhotoFrames.Ring(INNER, 1)));

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
        var framed = PhotoFrames.draw(blank(40, 40), rings(new PhotoFrames.Ring(OUTER, 3)));

        assertEquals(BLANK, at(framed, 20, 20));
        assertEquals(BLANK, at(framed, 3, 3));
    }

    @Test
    @DisplayName("the original image is not written over")
    void originalIsCopied() {
        var original = blank(40, 40);
        var framed = PhotoFrames.draw(original, rings(new PhotoFrames.Ring(OUTER, 3)));

        assertEquals(BLANK, at(original, 0, 0));
        assertNotEquals(BLANK, at(framed, 0, 0));
    }

    @Test
    @DisplayName("a frame thicker than the photo can bear is trimmed, not refused")
    void thicknessIsCapped() {
        // 40 px shorter side, 40% of it across two edges: eight pixels each.
        var framed = PhotoFrames.draw(blank(40, 40), rings(new PhotoFrames.Ring(OUTER, 30)));

        assertEquals(OUTER, at(framed, 7, 20));
        assertEquals(BLANK, at(framed, 8, 20));
        assertEquals(BLANK, at(framed, 20, 20));
    }

    @Test
    @DisplayName("a photo wider than it is tall is bounded by its shorter side")
    void nonSquareUsesShorterSide() {
        var framed = PhotoFrames.draw(blank(200, 40), rings(new PhotoFrames.Ring(OUTER, 30)));

        assertEquals(OUTER, at(framed, 100, 7));
        assertEquals(BLANK, at(framed, 100, 8));
    }

    @Test
    @DisplayName("no frame means the same image, not a copy of it")
    void noFrameIsNoWork() {
        var original = blank(8, 8);
        assertSame(original, PhotoFrames.draw(original, null));
        assertSame(original, PhotoFrames.draw(original,
                new PhotoFrames.Frame("EMPTY", 0, new int[0], 1, null, 1)));
    }

    // --- pixel art ---

    @Test
    @DisplayName("an edge pattern repeats along the side")
    void patternRepeats() {
        // One row deep, four pixels long: mark, blank, blank, blank.
        var edge = new int[]{MARK, OUTER, OUTER, OUTER};
        var frame = new PhotoFrames.Frame("PATTERN", 1, edge, 4, null, 1);
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(MARK, at(framed, 0, 0));
        assertEquals(OUTER, at(framed, 1, 0));
        assertEquals(MARK, at(framed, 4, 0));
        assertEquals(MARK, at(framed, 8, 0));
        // The vertical sides run the same strip, so the pattern turns the corner.
        assertEquals(MARK, at(framed, 0, 4));
        assertEquals(OUTER, at(framed, 0, 5));
    }

    @Test
    @DisplayName("a transparent art pixel leaves the photo showing through")
    void clearPixelsAreLeftAlone() {
        var frame = new PhotoFrames.Frame("HOLE", 1, new int[]{CLEAR, OUTER}, 2, null, 1);
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(BLANK, at(framed, 0, 0));
        assertEquals(OUTER, at(framed, 1, 0));
    }

    @Test
    @DisplayName("the corner sprite reaches all four corners, mirrored")
    void cornersAreMirrored() {
        // 2x2 corner with the mark in its own outer corner only.
        var corner = new int[]{MARK, INNER, INNER, INNER};
        var frame = new PhotoFrames.Frame("CORNER", 2, new int[]{OUTER, OUTER}, 1, corner, 1);
        var framed = PhotoFrames.draw(blank(40, 40), frame);

        assertEquals(MARK, at(framed, 0, 0));
        assertEquals(MARK, at(framed, 39, 0));
        assertEquals(MARK, at(framed, 0, 39));
        assertEquals(MARK, at(framed, 39, 39));
        assertEquals(INNER, at(framed, 1, 1));
        // Between the corners the edge strip still rules.
        assertEquals(OUTER, at(framed, 20, 0));
    }

    // --- scale ---

    @Test
    @DisplayName("scale makes every art pixel a block that size")
    void scaleGrowsTheArt() {
        var frame = new PhotoFrames.Frame("SCALED", 1, new int[]{MARK, OUTER}, 2, null, 3);
        var framed = PhotoFrames.draw(blank(60, 60), frame);

        // One art pixel now covers three, in both directions.
        assertEquals(MARK, at(framed, 0, 0));
        assertEquals(MARK, at(framed, 2, 0));
        assertEquals(OUTER, at(framed, 3, 0));
        assertEquals(MARK, at(framed, 0, 2));
        // Three pixels deep on every side, so the first untouched pixel is (3, 3);
        // (0, 3) still belongs to the left edge, where the same strip runs downward.
        assertEquals(BLANK, at(framed, 3, 3));
        assertEquals(OUTER, at(framed, 0, 3));
    }

    @Test
    @DisplayName("auto scale follows the photo's shorter side")
    void autoScaleFollowsSize() {
        var frame = new PhotoFrames.Frame("AUTO", 1, new int[]{OUTER}, 1, null,
                PhotoFrames.Frame.SCALE_AUTO);

        assertEquals(1, frame.scaleFor(128, 128));   // a 1x1 photo: as written
        assertEquals(1, frame.scaleFor(512, 256));   // a 4x2: still one
        assertEquals(3, frame.scaleFor(1024, 768));  // an 8x6: three times over
        assertEquals(4, frame.scaleFor(2048, 1152)); // a 16x9
    }
}
