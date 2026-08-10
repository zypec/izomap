package dev.zypec.izomap.map;

/**
 * Grid of map tiles a photo is laid out on. Each tile is one 128x128 Minecraft map,
 * so the total resolution is {@code (cols*128) x (rows*128)}.
 */
public record GridOption(int cols, int rows) {

    /**
     * Side length of one map tile, in pixels.
     */
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

    /**
     * Parses a label such as "4x2"; {@code null} when invalid.
     */
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
