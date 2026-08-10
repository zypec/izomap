package dev.zypec.izomap.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Base colors of the Minecraft map palette, matching the vanilla {@code MapColor}
 * table and
 * <a href="https://minecraft.wiki/w/Map_item_format#Base_colors">Map item format &rarr; Base colors</a>.
 *
 * <p>Each base color has four brightness variants ({@link Shade}), and the actual
 * color on the map is:</p>
 *
 * <pre>channel = baseChannel * shade.modifier / 255 (integer, rounded down)</pre>
 *
 * <p>The byte stored in map data is {@code baseColorId * 4 + shadeId}, see
 * {@link #packedId(Shade)}. Rendering against this table means every output pixel
 * is a real map color rather than an RGB approximation.</p>
 */
public enum MapBaseColor {

    NONE(0, 0x000000),
    GRASS(1, 0x7FB238),
    SAND(2, 0xF7E9A3),
    WOOL(3, 0xC7C7C7),
    FIRE(4, 0xFF0000),
    ICE(5, 0xA0A0FF),
    METAL(6, 0xA7A7A7),
    PLANT(7, 0x007C00),
    SNOW(8, 0xFFFFFF),
    CLAY(9, 0xA4A8B8),
    DIRT(10, 0x976D4D),
    STONE(11, 0x707070),
    WATER(12, 0x4040FF),
    WOOD(13, 0x8F7748),
    QUARTZ(14, 0xFFFCF5),
    COLOR_ORANGE(15, 0xD87F33),
    COLOR_MAGENTA(16, 0xB24CD8),
    COLOR_LIGHT_BLUE(17, 0x6699D8),
    COLOR_YELLOW(18, 0xE5E533),
    COLOR_LIGHT_GREEN(19, 0x7FCC19),
    COLOR_PINK(20, 0xF27FA5),
    COLOR_GRAY(21, 0x4C4C4C),
    COLOR_LIGHT_GRAY(22, 0x999999),
    COLOR_CYAN(23, 0x4C7F99),
    COLOR_PURPLE(24, 0x7F3FB2),
    COLOR_BLUE(25, 0x334CB2),
    COLOR_BROWN(26, 0x664C33),
    COLOR_GREEN(27, 0x667F33),
    COLOR_RED(28, 0x993333),
    COLOR_BLACK(29, 0x191919),
    GOLD(30, 0xFAEE4D),
    DIAMOND(31, 0x5CDBD5),
    LAPIS(32, 0x4A80FF),
    EMERALD(33, 0x00D93A),
    PODZOL(34, 0x815631),
    NETHER(35, 0x700200),
    TERRACOTTA_WHITE(36, 0xD1B1A1),
    TERRACOTTA_ORANGE(37, 0x9F5224),
    TERRACOTTA_MAGENTA(38, 0x95576C),
    TERRACOTTA_LIGHT_BLUE(39, 0x706C8A),
    TERRACOTTA_YELLOW(40, 0xBA8524),
    TERRACOTTA_LIGHT_GREEN(41, 0x677535),
    TERRACOTTA_PINK(42, 0xA04D4E),
    TERRACOTTA_GRAY(43, 0x392923),
    TERRACOTTA_LIGHT_GRAY(44, 0x876B62),
    TERRACOTTA_CYAN(45, 0x575C5C),
    TERRACOTTA_PURPLE(46, 0x7A4958),
    TERRACOTTA_BLUE(47, 0x4C3E5C),
    TERRACOTTA_BROWN(48, 0x4C3223),
    TERRACOTTA_GREEN(49, 0x4C522A),
    TERRACOTTA_RED(50, 0x8E3C2E),
    TERRACOTTA_BLACK(51, 0x251610),
    CRIMSON_NYLIUM(52, 0xBD3031),
    CRIMSON_STEM(53, 0x943F61),
    CRIMSON_HYPHAE(54, 0x5C191D),
    WARPED_NYLIUM(55, 0x167E86),
    WARPED_STEM(56, 0x3A8E8C),
    WARPED_HYPHAE(57, 0x562C3E),
    WARPED_WART_BLOCK(58, 0x14B485),
    DEEPSLATE(59, 0x646464),
    RAW_IRON(60, 0xD8AF93),
    GLOW_LICHEN(61, 0x7FA796);

    /**
     * Brightness variants of a base color; the modifiers match vanilla
     * {@code MapColor.Brightness}.
     */
    public enum Shade {

        /**
         * Surfaces descending northwards (180/255).
         */
        LOW(0, 180),
        /**
         * Flat surfaces (220/255).
         */
        NORMAL(1, 220),
        /**
         * Surfaces rising northwards (255/255).
         */
        HIGH(2, 255),
        /**
         * Darkest variant, used only for deep water (135/255).
         */
        LOWEST(3, 135);

        private final int id;
        private final int modifier;

        Shade(int id, int modifier) {
            this.id = id;
            this.modifier = modifier;
        }

        /**
         * Lower two bits of the map byte.
         */
        public int id() {
            return id;
        }

        /**
         * Brightness modifier out of 255.
         */
        public int modifier() {
            return modifier;
        }
    }

    private static final MapBaseColor[] BY_ID = new MapBaseColor[64];
    private static final Map<Integer, MapBaseColor> BY_RGB = new HashMap<>();

    static {
        for (var color : values()) {
            BY_ID[color.id] = color;
            BY_RGB.putIfAbsent(color.baseRgb, color);
        }
    }

    private final int id;
    private final int baseRgb;
    private final int[] shaded = new int[4];

    MapBaseColor(int id, int baseRgb) {
        this.id = id;
        this.baseRgb = baseRgb;
        for (Shade shade : Shade.values()) {
            shaded[shade.id()] = scale(baseRgb, shade.modifier());
        }
    }

    /**
     * Base color id in the map format (0-63).
     */
    public int id() {
        return id;
    }

    /**
     * Unshaded base color (0xRRGGBB).
     */
    public int baseRgb() {
        return baseRgb;
    }

    /**
     * Actual map color at the given brightness (0xRRGGBB).
     */
    public int rgb(Shade shade) {
        return shaded[shade.id()];
    }

    /**
     * The byte stored in map data: {@code id * 4 + shadeId}.
     */
    public byte packedId(Shade shade) {
        return (byte) ((id << 2) | shade.id());
    }

    /**
     * Base color by id; {@link #NONE} when undefined.
     */
    public static MapBaseColor byId(int id) {
        if (id < 0 || id >= BY_ID.length)
            return NONE;

        var color = BY_ID[id];
        return color != null ? color : NONE;
    }

    /**
     * Base color matching an unshaded RGB exactly, or {@code null}.
     */
    public static MapBaseColor byBaseRgb(int rgb) {
        return BY_RGB.get(rgb & 0xFFFFFF);
    }

    /**
     * Base color by name, case-insensitive, or {@code null}.
     */
    public static MapBaseColor byName(String name) {
        if (name == null) return null;

        var normalized = name.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        for (var color : values())
            if (color.name().equals(normalized))
                return color;
        return null;
    }

    /**
     * Same integer arithmetic as vanilla {@code ARGB.scaleRGB}.
     */
    private static int scale(int rgb, int modifier) {
        var r = ((rgb >> 16) & 0xFF) * modifier / 255;
        var g = ((rgb >> 8) & 0xFF) * modifier / 255;
        var b = (rgb & 0xFF) * modifier / 255;
        return (r << 16) | (g << 8) | b;
    }
}
