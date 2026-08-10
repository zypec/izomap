package dev.zypec.izomap.render;

import dev.zypec.izomap.render.MapBaseColor.Shade;

import java.util.HashMap;
import java.util.Map;

/**
 * Snaps an arbitrary RGB color to the nearest real map color, and converts between
 * that color and the byte the map format stores.
 *
 * <p>The palette is every {@link MapBaseColor} in its four brightness variants;
 * {@link MapBaseColor#NONE} is excluded because it is transparent. Distance uses the
 * same weighted "redmean" formula as Bukkit's {@code MapPalette}, so the result
 * matches the game's own mapping.</p>
 *
 * <p>Every pixel a render produces is already a palette entry (see
 * {@link IsometricRenderer}), so {@link #packedId(int)} is an exact reverse lookup
 * rather than a second color match, and {@link #argbOf(byte)} is a table read. That
 * is what makes the photo cache cheap in both directions.</p>
 *
 * <p>The tables are static and read-only, so they are safe to use from render
 * threads.</p>
 */
public final class MapColorConverter {

    /**
     * Every valid (non-transparent) map color, 0xRRGGBB.
     */
    private static final int[] PALETTE;

    /**
     * 0xRRGGBB of a palette entry to its map byte.
     */
    private static final Map<Integer, Byte> ID_BY_RGB = new HashMap<>();

    /**
     * Map byte to 0xAARRGGBB; entries with no color stay transparent.
     */
    private static final int[] ARGB_BY_ID = new int[256];

    static {
        var bases = MapBaseColor.values();
        var shades = Shade.values();
        var palette = new int[(bases.length - 1) * shades.length];
        var index = 0;
        for (var base : bases) {
            if (base == MapBaseColor.NONE)
                continue; // transparent

            for (var shade : shades) {
                var rgb = base.rgb(shade);
                var id = base.packedId(shade);
                palette[index++] = rgb;
                // Distinct bases can scale down to the same color; either byte paints
                // the same pixel, so the first one wins.
                ID_BY_RGB.putIfAbsent(rgb, id);
                ARGB_BY_ID[id & 0xFF] = 0xFF000000 | rgb;
            }
        }
        PALETTE = palette;
    }

    /**
     * Map format byte of an ARGB pixel: {@code baseId * 4 + shadeId}, or {@code 0}
     * (transparent) when the pixel is transparent. Colors off the palette are snapped
     * first; a render never produces one, but a corrupted buffer should not throw.
     */
    public byte packedId(int argb) {
        if ((argb >>> 24) == 0)
            return 0;

        var rgb = argb & 0xFFFFFF;
        var exact = ID_BY_RGB.get(rgb);
        if (exact != null)
            return exact;

        return ID_BY_RGB.getOrDefault(snap(rgb), (byte) 0);
    }

    /**
     * ARGB color of a map format byte; transparent for {@code 0} and unused ids.
     */
    public static int argbOf(byte packedId) {
        return ARGB_BY_ID[packedId & 0xFF];
    }

    /**
     * Maps a 0xRRGGBB color to the closest palette entry.
     */
    public int snap(int rgb) {
        var r = (rgb >> 16) & 0xFF;
        var g = (rgb >> 8) & 0xFF;
        var b = rgb & 0xFF;

        var best = PALETTE[0];
        var bestDistance = Long.MAX_VALUE;
        for (var candidate : PALETTE) {
            var distance = distance(r, g, b, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                if (distance == 0) {
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Same weighted squared distance as Bukkit {@code MapPalette}.
     */
    private static long distance(int r1, int g1, int b1, int rgb2) {
        var r2 = (rgb2 >> 16) & 0xFF;
        var g2 = (rgb2 >> 8) & 0xFF;
        var b2 = rgb2 & 0xFF;

        int sum = r1 + r2;
        var dr = r1 - r2;
        var dg = g1 - g2;
        var db = b1 - b2;

        var weightR = 1024L + sum;
        var weightG = 2048L;
        var weightB = 1024L + (255 * 2 - sum);

        return weightR * dr * dr + weightG * dg * dg + weightB * db * db;
    }
}
