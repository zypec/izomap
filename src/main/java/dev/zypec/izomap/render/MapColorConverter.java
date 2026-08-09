package dev.zypec.izomap.render;

/**
 * Snaps an arbitrary RGB color to the nearest real map color.
 *
 * <p>The palette is every {@link MapBaseColor} in its four brightness variants;
 * {@link MapBaseColor#NONE} is excluded because it is transparent. Distance uses the
 * same weighted "redmean" formula as Bukkit's {@code MapPalette}, so the result
 * matches the game's own mapping.</p>
 *
 * <p>The table is static and read-only, so it is safe to use from render threads.</p>
 */
public final class MapColorConverter {

    /** Every valid (non-transparent) map color, 0xRRGGBB. */
    private static final int[] PALETTE;

    static {
        MapBaseColor[] bases = MapBaseColor.values();
        MapBaseColor.Shade[] shades = MapBaseColor.Shade.values();
        int[] palette = new int[(bases.length - 1) * shades.length];
        int index = 0;
        for (MapBaseColor base : bases) {
            if (base == MapBaseColor.NONE) {
                continue; // transparent
            }
            for (MapBaseColor.Shade shade : shades) {
                palette[index++] = base.rgb(shade);
            }
        }
        PALETTE = palette;
    }

    /** Maps a 0xRRGGBB color to the closest palette entry. */
    public int snap(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int best = PALETTE[0];
        long bestDistance = Long.MAX_VALUE;
        for (int candidate : PALETTE) {
            long distance = distance(r, g, b, candidate);
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

    /** Same weighted squared distance as Bukkit {@code MapPalette}. */
    private static long distance(int r1, int g1, int b1, int rgb2) {
        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;

        int rsum = r1 + r2;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;

        long weightR = 1024L + rsum;
        long weightG = 2048L;
        long weightB = 1024L + (255 * 2 - rsum);

        return weightR * dr * dr + weightG * dg * dg + weightB * db * db;
    }
}
