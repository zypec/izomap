package dev.zypec.izomap.map;

/**
 * A single 128x128 tile of the grid.
 *
 * @param col  column index, 0 is leftmost
 * @param row  row index, 0 is topmost
 * @param argb row-major ARGB colors, 128*128 long
 */
public record MapTile(int col, int row, int[] argb) {
}
