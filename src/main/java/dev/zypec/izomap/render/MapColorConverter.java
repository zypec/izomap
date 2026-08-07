package dev.zypec.izomap.render;

/**
 * Rastgele bir RGB rengi, harita paletindeki en yakın gerçek renge "snap"ler.
 *
 * <p>Palet, {@link MapBaseColor} temel renklerinin 4 parlaklık varyantından
 * (61 temel renk x 4 = 244 geçerli renk) oluşur; {@link MapBaseColor#NONE}
 * şeffaf olduğu için eşleşmeye dahil edilmez. Mesafe ölçütü Minecraft'ın ve
 * Bukkit {@code MapPalette}'inin kullandığı ağırlıklı ("redmean") formülüdür,
 * böylece sonuç oyunun kendi eşlemesiyle aynıdır.</p>
 *
 * <p>Tablo statik ve salt-okunur olduğundan asenkron render sırasında güvenle
 * kullanılabilir.</p>
 */
public final class MapColorConverter {

    /** Geçerli (şeffaf olmayan) tüm harita renkleri, 0xRRGGBB. */
    private static final int[] PALETTE;

    static {
        MapBaseColor[] bases = MapBaseColor.values();
        MapBaseColor.Shade[] shades = MapBaseColor.Shade.values();
        int[] palette = new int[(bases.length - 1) * shades.length];
        int index = 0;
        for (MapBaseColor base : bases) {
            if (base == MapBaseColor.NONE) {
                continue; // şeffaf
            }
            for (MapBaseColor.Shade shade : shades) {
                palette[index++] = base.rgb(shade);
            }
        }
        PALETTE = palette;
    }

    /** Verilen 0xRRGGBB rengini paletteki en yakın renge eşler. */
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

    /** Bukkit {@code MapPalette} ile aynı ağırlıklı kare mesafe (tam sayı aritmetiği). */
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
