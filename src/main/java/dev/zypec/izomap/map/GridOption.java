package dev.zypec.izomap.map;

import dev.zypec.izomap.render.AspectRatio;

/**
 * Grid of map tiles a photo is laid out on, and the size of the picture on it. Each tile
 * is one 128x128 Minecraft map.
 *
 * <p>The picture usually fills the grid exactly. A grid that does not have the photo's
 * ratio, such as a single map for a 16:9 photo, holds the largest picture of that ratio
 * that fits, centred, and the rest of its maps stays transparent: the frame's own
 * background shows through as bars, the way the live preview letterboxes.</p>
 *
 * @param widthPx  width of the picture in pixels, at most {@code cols * 128}
 * @param heightPx height of the picture in pixels, at most {@code rows * 128}
 */
public record GridOption(int cols, int rows, int widthPx, int heightPx) {

    /**
     * Side length of one map tile, in pixels.
     */
    public static final int TILE = 128;

    public GridOption {
        if (cols < 1 || rows < 1)
            throw new IllegalArgumentException("A grid needs at least one tile: " + cols + "x" + rows);
        if (widthPx < 1 || heightPx < 1 || widthPx > cols * TILE || heightPx > rows * TILE)
            throw new IllegalArgumentException("Picture " + widthPx + "x" + heightPx
                                               + " does not fit a " + cols + "x" + rows + " grid");
    }

    /**
     * A grid the picture fills.
     */
    public GridOption(int cols, int rows) {
        this(cols, rows, cols * TILE, rows * TILE);
    }

    /**
     * A grid holding the largest picture of the ratio that fits on it.
     */
    public static GridOption fitted(int cols, int rows, AspectRatio ratio) {
        var width = cols * TILE;
        var height = rows * TILE;
        if ((double) width / height > ratio.value()) {
            width = Math.max(1, (int) Math.round(height * ratio.value()));
        } else {
            height = Math.max(1, (int) Math.round(width / ratio.value()));
        }
        return new GridOption(cols, rows, width, height);
    }

    public int tileCount() {
        return cols * rows;
    }

    /**
     * Whether the picture leaves part of the grid empty.
     */
    public boolean letterboxed() {
        return widthPx != cols * TILE || heightPx != rows * TILE;
    }

    /**
     * Where the picture starts on the grid, in pixels from its left edge.
     */
    public int offsetX() {
        return (cols * TILE - widthPx) / 2;
    }

    /**
     * Where the picture starts on the grid, in pixels from its top edge.
     */
    public int offsetY() {
        return (rows * TILE - heightPx) / 2;
    }

    public String label() {
        return cols + "x" + rows;
    }

    /**
     * Parses a label such as "4x2" into a grid the picture fills; {@code null} when
     * invalid.
     */
    public static GridOption parse(String raw) {
        var size = parseSize(raw);
        return size == null ? null : new GridOption(size[0], size[1]);
    }

    /**
     * The same label read as a grid of the given picture size, as {@code photos.yml}
     * stores a letterboxed one; {@code null} when either is invalid.
     */
    public static GridOption parse(String raw, String picture) {
        var grid = parseSize(raw);
        if (grid == null)
            return null;
        if (picture == null)
            return new GridOption(grid[0], grid[1]);

        var size = parseSize(picture);
        if (size == null || size[0] > grid[0] * TILE || size[1] > grid[1] * TILE)
            return null;

        return new GridOption(grid[0], grid[1], size[0], size[1]);
    }

    /**
     * The picture size as a label, for storing a letterboxed grid.
     */
    public String pictureLabel() {
        return widthPx + "x" + heightPx;
    }

    private static int[] parseSize(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.toLowerCase().split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            if (a < 1 || b < 1) {
                return null;
            }
            return new int[]{a, b};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
