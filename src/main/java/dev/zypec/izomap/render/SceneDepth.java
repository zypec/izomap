package dev.zypec.izomap.render;

import java.util.Arrays;

/**
 * How far a frame sees: where its nearest, typical and furthest ground lie, and how
 * much of it is sky. Taken from the depth the walk records, for the hint that suggests
 * a fog range.
 *
 * @param near     the nearest ground, in blocks from the camera plane, ignoring the
 *                 closest twentieth of the pixels
 * @param median   the depth half the ground is nearer than
 * @param far      the furthest ground, ignoring the furthest fiftieth
 * @param skyShare how much of the frame is sky, 0 to 1
 * @param center   what the middle of the frame is aimed at: the median ground in its
 *                 central tenth, or {@code -1} when that is all sky
 */
public record SceneDepth(double near, double median, double far, double skyShare, double center) {

    /**
     * The scene's depths, or {@code null} when the frame holds no ground at all.
     */
    public static SceneDepth of(float[] depth, int width, int height) {
        if (depth == null || depth.length == 0)
            return null;

        var ground = new float[depth.length];
        var count = 0;
        for (var d : depth) {
            if (d != RenderResult.SKY_DEPTH)
                ground[count++] = d;
        }
        if (count == 0)
            return null;

        Arrays.sort(ground, 0, count);
        return new SceneDepth(
                ground[(int) (count * 0.05)],
                ground[count / 2],
                ground[Math.min(count - 1, (int) (count * 0.98))],
                1.0 - count / (double) depth.length,
                centerOf(depth, width, height));
    }

    private static double centerOf(float[] depth, int width, int height) {
        var halfW = Math.max(1, width / 20);
        var halfH = Math.max(1, height / 20);
        var middle = new float[(halfW * 2 + 1) * (halfH * 2 + 1)];
        var count = 0;
        for (var y = height / 2 - halfH; y <= height / 2 + halfH; y++) {
            for (var x = width / 2 - halfW; x <= width / 2 + halfW; x++) {
                if (x < 0 || y < 0 || x >= width || y >= height) continue;
                var d = depth[y * width + x];
                if (d != RenderResult.SKY_DEPTH)
                    middle[count++] = d;
            }
        }
        if (count == 0)
            return -1.0;
        Arrays.sort(middle, 0, count);
        return middle[count / 2];
    }

    /**
     * Whether the frame reaches far enough for fog to say anything: a camera looking down
     * sees its ground at nearly one depth, and fog would veil all of it evenly rather
     * than fade what lies behind.
     */
    public boolean deep() {
        return far > 0.0 && (far - near) >= far * 0.5;
    }

    /**
     * A fog range, in blocks from the camera plane: where it begins and where it is full.
     */
    public record FogRange(int start, int end) {
    }

    /**
     * Only the distance softens: fog begins most of the way from the typical ground to the
     * furthest and is full well beyond it, so even the far edge keeps its shapes.
     */
    public FogRange haze() {
        return range(median + 0.6 * (far - median), far * 1.3);
    }

    /**
     * The back half fades: fog begins at the typical ground and is full a little past the
     * furthest, so what lies far off is pale but still there. This was once fog full at the
     * furthest ground itself, which whited the far edge out altogether.
     */
    public FogRange soft() {
        return range(median, far * 1.15);
    }

    /**
     * All but the foreground fades: fog begins between the nearest and the typical ground
     * and is full before the furthest.
     */
    public FogRange thick() {
        return range(near + 0.5 * (median - near), median + 0.6 * (far - median));
    }

    private static FogRange range(double start, double end) {
        var from = (int) Math.round(Math.max(0.0, start));
        return new FogRange(from, Math.max(from + 8, (int) Math.round(end)));
    }

    /** The fog the capture screen offers: {@link #soft}. */
    public int suggestedStart() {
        return soft().start();
    }

    public int suggestedEnd() {
        return soft().end();
    }
}
