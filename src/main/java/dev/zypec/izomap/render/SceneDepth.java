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
 */
public record SceneDepth(double near, double median, double far, double skyShare) {

    /**
     * The scene's depths, or {@code null} when the frame holds no ground at all.
     */
    public static SceneDepth of(float[] depth) {
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
                1.0 - count / (double) depth.length);
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
     * Where fog should begin: the typical ground, so the near half stays clear.
     */
    public int suggestedStart() {
        return (int) Math.round(median);
    }

    /**
     * Where fog should be full: the furthest ground, so the far edge melts into the sky.
     */
    public int suggestedEnd() {
        return Math.max(suggestedStart() + 1, (int) Math.round(far));
    }
}
