package dev.zypec.izomap.render;

/**
 * The colour grass and leaves take at a given climate — the answer a dedicated server
 * cannot give.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A biome only names its grass and foliage colours when it wants an unusual one
 * (swamp, badlands, cherry grove). Everywhere else the game samples a 256×256 colormap
 * texture by temperature and downfall — and that texture is a <b>client resource</b>. On
 * a dedicated server the array behind it is never filled, so the server's own
 * {@code getGrassColor} answers <b>0</b>: black. Asking it and believing the answer is
 * exactly how this plugin once turned every meadow black.</p>
 *
 * <h2>What is stored here</h2>
 *
 * <p>Not the texture: a 9×9 grid sampled from it, interpolated bilinearly. Measured
 * against the real colormap across the whole climate range the grid is within <b>3-4</b>
 * of it per channel (average under 1), which is far below what the 244-colour map palette
 * can express — the two disagree on no vanilla biome once the colour is snapped.</p>
 *
 * <p>Half of the square is unreachable: downfall is scaled by temperature before the
 * lookup, so no climate can land above the diagonal, and vanilla leaves that half of
 * {@code grass.png} white. Those nodes are stored as the diagonal edge below them rather
 * than as white, which is what keeps an interpolation near the diagonal from mixing in a
 * colour no biome has. Without that the grid is off by as much as 48 per channel; with
 * it, by 3.</p>
 */
final class Colormaps {

    /**
     * Node spacing of the grid, in colormap pixels.
     */
    private static final int STEP = 32;
    private static final int NODES = 9;

    /**
     * {@code grass.png} on the grid, row by row from the hot-and-wet corner.
     */
    private static final int[] GRASS = {
            0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33, 0x47CD33,
            0x56CA37, 0x59C842, 0x59C842, 0x59C842, 0x59C842, 0x59C842, 0x59C842, 0x59C842, 0x59C842,
            0x65C83C, 0x66C646, 0x67C451, 0x67C451, 0x67C451, 0x67C451, 0x67C451, 0x67C451, 0x67C451,
            0x73C540, 0x73C44A, 0x73C154, 0x73C05F, 0x73C05F, 0x73C05F, 0x73C05F, 0x73C05F, 0x73C05F,
            0x83C245, 0x81C14E, 0x7FBF58, 0x7EBE62, 0x7CBD6C, 0x7CBD6C, 0x7CBD6C, 0x7CBD6C, 0x7CBD6C,
            0x92BF49, 0x8FBF52, 0x8CBD5B, 0x88BC65, 0x85BB6F, 0x81BA78, 0x81BA78, 0x81BA78, 0x81BA78,
            0xA0BD4D, 0x9CBC55, 0x97BB5F, 0x92BA68, 0x8EB971, 0x89B97A, 0x84B783, 0x84B783, 0x84B783,
            0xB0BA51, 0xAABA5A, 0xA4B962, 0x9DB96B, 0x97B873, 0x90B77C, 0x8AB685, 0x83B68E, 0x83B68E,
            0xBFB755, 0xB7B75D, 0xAFB666, 0xA7B66E, 0x9FB676, 0x97B67F, 0x90B587, 0x88B58E, 0x80B497,
    };

    /**
     * {@code foliage.png} on the same grid.
     */
    private static final int[] FOLIAGE = {
            0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00, 0x1ABF00,
            0x2DBB05, 0x30B913, 0x30B913, 0x30B913, 0x30B913, 0x30B913, 0x30B913, 0x30B913, 0x30B913,
            0x40B80C, 0x41B618, 0x42B426, 0x42B426, 0x42B426, 0x42B426, 0x42B426, 0x42B426, 0x42B426,
            0x51B511, 0x51B31D, 0x51B12A, 0x50AE36, 0x50AE36, 0x50AE36, 0x50AE36, 0x50AE36, 0x50AE36,
            0x64B216, 0x62B022, 0x60AE2E, 0x5DAD3A, 0x5BAB47, 0x5BAB47, 0x5BAB47, 0x5BAB47, 0x5BAB47,
            0x77AE1C, 0x72AD27, 0x6FAB33, 0x6BAA3E, 0x67A94A, 0x62A756, 0x62A756, 0x62A756, 0x62A756,
            0x8AAB21, 0x84AA2B, 0x7EA936, 0x78A841, 0x72A74E, 0x6CA659, 0x65A464, 0x65A464, 0x65A464,
            0x9CA825, 0x95A830, 0x8CA73B, 0x84A646, 0x7DA550, 0x74A45B, 0x6DA366, 0x64A371, 0x64A371,
            0xAEA42A, 0xA4A435, 0x9AA33F, 0x91A349, 0x86A354, 0x7DA35E, 0x73A268, 0x69A272, 0x60A17B,
    };

    private Colormaps() {
    }

    /**
     * Grass colour for a climate, 0xRRGGBB.
     */
    static int grass(double temperature, double downfall) {
        return sample(GRASS, temperature, downfall);
    }

    /**
     * Foliage colour for a climate, 0xRRGGBB.
     */
    static int foliage(double temperature, double downfall) {
        return sample(FOLIAGE, temperature, downfall);
    }

    /**
     * Reads the grid where the game would read the texture: downfall scaled by
     * temperature, both inverted into pixel coordinates.
     */
    private static int sample(int[] table, double temperature, double downfall) {
        var temp = clamp01(temperature);
        var rain = clamp01(downfall) * temp;
        var x = (1.0 - temp) * 255.0;
        var y = (1.0 - rain) * 255.0;

        var ix = Math.min((int) (x / STEP), NODES - 2);
        var iy = Math.min((int) (y / STEP), NODES - 2);
        // The last interval is shorter: the grid ends at 255, not at 256.
        var fx = (x - ix * STEP) / (nodeAt(ix + 1) - nodeAt(ix));
        var fy = (y - iy * STEP) / (nodeAt(iy + 1) - nodeAt(iy));

        var top = lerp(table[iy * NODES + ix], table[iy * NODES + ix + 1], fx);
        var bottom = lerp(table[(iy + 1) * NODES + ix], table[(iy + 1) * NODES + ix + 1], fx);
        return lerp(top, bottom, fy);
    }

    private static int nodeAt(int index) {
        return Math.min(index * STEP, 255);
    }

    private static int lerp(int from, int to, double t) {
        var r = channel(from, 16) + (channel(to, 16) - channel(from, 16)) * t;
        var g = channel(from, 8) + (channel(to, 8) - channel(from, 8)) * t;
        var b = channel(from, 0) + (channel(to, 0) - channel(from, 0)) * t;
        return (round(r) << 16) | (round(g) << 8) | round(b);
    }

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    private static int round(double value) {
        return (int) Math.clamp(Math.round(value), 0, 255);
    }

    private static double clamp01(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}
