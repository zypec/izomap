package dev.zypec.izomap.config;

import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

/**
 * Every permission node the plugin asks about, and what each one lets through.
 *
 * <h2>Whole area, or one option</h2>
 *
 * <p>Settings that offer a list — filters, skies, aspect ratios — are asked about twice:
 * {@code izomap.filter} grants <b>all</b> of them, {@code izomap.filter.WARM} grants that
 * one. A player passes on either, so a server can hand out the whole area to its staff
 * and a single option to everyone else without writing two systems.</p>
 *
 * <h2>One value of each setting is always free</h2>
 *
 * <p>{@code ORIGINAL}, the {@code NONE} sky and the {@code FAST} style need no permission
 * at all. Each is the cheapest member of its group and the value a camera starts on, so a
 * player who holds nothing can still take a photo — the permissions decide how expensive
 * a photo may get, not whether one may be taken.</p>
 *
 * <h2>Resolution is a number, not a list</h2>
 *
 * <p>Grid size is the one setting where the cost is continuous, so it uses the numeric
 * form ({@link PermissionLimit}): {@code izomap.max_map_tiles.32} allows any grid up to
 * 32 map tiles. A 16:9 photo starts at 8 tiles and a 16x9 grid is 144, so this is the
 * node that actually decides what a render costs the server.</p>
 */
public final class Permissions {

    /** Creating and adjusting cameras, and taking photos. */
    public static final String CAMERA = "izomap.camera";
    /** Acting on other players' cameras and photos. */
    public static final String ADMIN = "izomap.admin";
    /** Writing a photo out as a PNG. */
    public static final String EXPORT = "izomap.export";
    /** The expensive style; {@code FAST} is free. */
    public static final String STYLE_SHARP = "izomap.style.sharp";

    /** Area nodes; a single option is {@code <area>.<NAME>}. */
    public static final String FILTER = "izomap.filter";
    public static final String SKY = "izomap.sky";
    public static final String RATIO = "izomap.ratio";

    /**
     * Numeric prefix for the map tiles one photo may cover. Deliberately not declared in
     * {@code paper-plugin.yml}; see {@link PermissionLimit}.
     */
    public static final String MAX_MAP_TILES = "izomap.max_map_tiles";

    private Permissions() {
    }

    /**
     * Whether the holder may use one option of an area, by holding either the area or
     * that option.
     */
    public static boolean allows(Permissible who, String area, String option) {
        return who.hasPermission(area) || who.hasPermission(area + "." + option);
    }

    /**
     * {@code FAST} is free; {@code SHARP} traces every pixel and is not.
     */
    public static boolean style(Permissible who, PhotoStyle style) {
        return style != PhotoStyle.SHARP || who.hasPermission(STYLE_SHARP);
    }

    public static boolean filter(Permissible who, ColorFilter filter) {
        return ColorFilter.ORIGINAL.id().equals(filter.id()) || allows(who, FILTER, filter.id());
    }

    public static boolean sky(Permissible who, SkyOption sky) {
        return sky == SkyOption.NONE || allows(who, SKY, sky.name());
    }

    public static boolean ratio(Permissible who, AspectRatio ratio) {
        return allows(who, RATIO, ratio.name());
    }

    /**
     * Map tiles this player's photos may cover, from {@code izomap.max_map_tiles.<n>} or
     * the configured default.
     */
    public static int mapTiles(Player player, int configured) {
        return PermissionLimit.resolve(player, MAX_MAP_TILES, configured);
    }

    /**
     * Whether a grid fits the player's tile allowance.
     */
    public static boolean grid(Player player, GridOption grid, int configured) {
        var limit = mapTiles(player, configured);
        return limit == PermissionLimit.UNLIMITED || grid.tileCount() <= limit;
    }
}
