package dev.zypec.izomap.map;

/**
 * Izgaradaki tek bir 128×128 karo.
 *
 * @param col  sütun indeksi (0 = en sol)
 * @param row  satır indeksi (0 = en üst)
 * @param argb 128*128 uzunluğunda, satır-öncelikli ARGB renkler
 */
public record MapTile(int col, int row, int[] argb) {
}
