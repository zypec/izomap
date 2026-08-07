package dev.zypec.izomap.map;

/**
 * Bir fotoğrafın yerleştirileceği harita karosu ızgarası.
 *
 * <p>Her karo 128×128 pikseldir (bir Minecraft haritası). Izgara {@code cols}
 * sütun ve {@code rows} satırdan oluşur; toplam çözünürlük
 * {@code (cols*128) × (rows*128)}'dir.</p>
 */
public record GridOption(int cols, int rows) {

    /** Bir harita karosunun kenar uzunluğu (piksel). */
    public static final int TILE = 128;

    public int widthPx() {
        return cols * TILE;
    }

    public int heightPx() {
        return rows * TILE;
    }

    public int tileCount() {
        return cols * rows;
    }

    public double ratioValue() {
        return (double) cols / rows;
    }

    public String label() {
        return cols + "x" + rows;
    }

    /** "4x2" gibi bir etiketi çözer; geçersizse {@code null}. */
    public static GridOption parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.toLowerCase().split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            int c = Integer.parseInt(parts[0].trim());
            int r = Integer.parseInt(parts[1].trim());
            if (c < 1 || r < 1) {
                return null;
            }
            return new GridOption(c, r);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
