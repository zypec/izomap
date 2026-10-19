package dev.zypec.izomap.render;

/**
 * The color a ray gets when it reaches nothing.
 *
 * <h2>Why the sky is a lookup table</h2>
 *
 * <p>The map palette holds 244 colors and only a handful of them are blue, so a smooth
 * vertical gradient cannot be stored: snapping each row to the nearest palette entry
 * turns the gradient into a few wide bands. Ordered dithering trades that banding for a
 * fine checker of the two colors either side of the true one, which the eye reads as the
 * shade in between.</p>
 *
 * <p>Dithering means the color depends on the pixel's position within a 4x4 cell, and
 * snapping is a search over the whole palette — far too much to do per pixel. Both are
 * therefore resolved once per row into a 16-entry cell, so painting a sky pixel is one
 * array read.</p>
 *
 * <p>The sun, the moon and the stars sit on top of that table rather than in it: they
 * depend on where a pixel is in <b>both</b> directions and on the camera's angles, so
 * they are asked first and the table answers whatever they leave, see
 * {@link SkyBodies}.</p>
 */
public final class Sky {

    /**
     * No sky: every miss stays a hole.
     */
    public static final Sky NONE = new Sky(null, null, 0, SkyBodies.NONE);

    /**
     * Ordered dither thresholds, the standard 4x4 Bayer matrix scaled to -0.5..0.5.
     */
    private static final double[] BAYER = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
    };

    private static final int CELL = 4;
    private static final int CELL_SIZE = CELL * CELL;

    /**
     * Row-major {@code [row * 16 + (y % 4) * 4 + (x % 4)]} finished colors.
     */
    private final int[] table;
    /**
     * The undithered color of each row, off the palette, for whatever blends towards it.
     */
    private final int[] rows;
    private final int height;
    private final SkyBodies bodies;

    private Sky(int[] table, int[] rows, int height, SkyBodies bodies) {
        this.table = table;
        this.rows = rows;
        this.height = height;
        this.bodies = bodies;
    }

    /**
     * Builds the sky for an image of {@code height} rows.
     *
     * @param zenithRgb     color at the top of the frame (0xRRGGBB)
     * @param gradient      whether the color eases towards the horizon down the frame
     * @param horizonBlend  how far the bottom row moves towards white, 0 to 1
     * @param ditherSpread  how far a dithered pixel may stray from the true color, in
     *                      channel steps; 0 disables dithering
     * @param bodies        the sun, moon and stars this frame carries, or
     *                      {@link SkyBodies#NONE} for a bare sky
     */
    public static Sky of(int zenithRgb, boolean gradient, double horizonBlend, double ditherSpread,
                         int height, MapColorConverter converter, SkyBodies bodies) {
        if (height <= 0)
            return NONE;

        var table = new int[height * CELL_SIZE];
        var rows = new int[height];
        for (var row = 0; row < height; row++) {
            // Down the frame the sky pales towards the horizon, as it does in the world.
            var t = gradient && height > 1 ? (double) row / (height - 1) * horizonBlend : 0.0;
            var r = lerp((zenithRgb >> 16) & 0xFF, 0xFF, t);
            var g = lerp((zenithRgb >> 8) & 0xFF, 0xFF, t);
            var b = lerp(zenithRgb & 0xFF, 0xFF, t);
            rows[row] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);

            for (var cell = 0; cell < CELL_SIZE; cell++) {
                var offset = ditherSpread > 0.0
                        ? (BAYER[cell] / (CELL_SIZE - 1.0) - 0.5) * ditherSpread
                        : 0.0;
                var rgb = (clamp(r + offset) << 16) | (clamp(g + offset) << 8) | clamp(b + offset);
                table[row * CELL_SIZE + cell] = 0xFF000000 | converter.snap(rgb);
            }
        }
        return new Sky(table, rows, height, bodies);
    }

    /**
     * A sky with nothing in it but its gradient.
     */
    public static Sky of(int zenithRgb, boolean gradient, double horizonBlend, double ditherSpread,
                         int height, MapColorConverter converter) {
        return of(zenithRgb, gradient, horizonBlend, ditherSpread, height, converter, SkyBodies.NONE);
    }

    /**
     * The sky's color at a game time, eased between the four configured keyframes.
     *
     * <p>Time is a circle, so the last keyframe runs into the first: the stretch from
     * dawn to noon crosses tick 0 and is interpolated across it rather than snapping.</p>
     *
     * @param ticks game time, 0 to 23999
     * @return 0xRRGGBB
     */
    public static int colorAt(int ticks, int dawn, int day, int dusk, int night) {
        var at = Math.floorMod(ticks, 24_000);
        int[] keys = {SkyOption.DAY.ticks(), SkyOption.DUSK.ticks(),
                SkyOption.NIGHT.ticks(), SkyOption.DAWN.ticks()};
        int[] colors = {day, dusk, night, dawn};

        for (var i = 0; i < keys.length; i++) {
            var from = keys[i];
            var to = keys[(i + 1) % keys.length];
            var span = Math.floorMod(to - from, 24_000);
            var into = Math.floorMod(at - from, 24_000);
            if (into < span || span == 0) {
                return mix(colors[i], colors[(i + 1) % colors.length], span == 0 ? 0.0 : (double) into / span);
            }
        }
        return day;
    }

    private static int mix(int from, int to, double t) {
        var r = clamp(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t));
        var g = clamp(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t));
        var b = clamp(lerp(from & 0xFF, to & 0xFF, t));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Whether this sky paints anything.
     */
    public boolean draws() {
        return table != null;
    }

    /**
     * Color for a pixel the rays missed, or {@code 0} to leave it transparent.
     */
    public int argbAt(int x, int y) {
        if (table == null) return 0;

        if (bodies.draws()) {
            var body = bodies.argbAt(x, y);
            if (body != 0)
                return body;
        }

        var row = y < 0 ? 0 : Math.min(y, height - 1);
        return table[row * CELL_SIZE + (y & 3) * CELL + (x & 3)];
    }

    /**
     * The sky's true color at this row (0xRRGGBB), without dither or bodies; {@code 0}
     * when there is no sky.
     */
    public int rgbAt(int y) {
        if (rows == null) return 0;

        return rows[y < 0 ? 0 : Math.min(y, height - 1)];
    }

    private static double lerp(int from, int to, double t) {
        return from + (to - from) * t;
    }

    private static int clamp(double value) {
        var i = (int) Math.round(value);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
