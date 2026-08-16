package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@link Material} to map {@link MapBaseColor} table.
 *
 * <p>Nothing is guessed: every block's color comes from the server via
 * {@code BlockData#getMapColor()}, which is exactly the
 * <a href="https://minecraft.wiki/w/Map_item_format#Base_colors">base color</a>
 * vanilla maps use, so stair/slab/wall variants and blocks added in newer versions
 * are all correct.</p>
 *
 * <h2>Blocks whose color depends on their state</h2>
 *
 * <p>A material is not always one color. Wheat is green while it grows and yellow once
 * ripe, so reading the default state alone paints a ripe field the color of seedlings.
 * The states that matter are the ages, and rather than keep a list of which blocks have
 * age-dependent colors — a list that would rot with every version — the load
 * <b>asks</b>: every ageable block is probed at each of its ages, and only the ones
 * that actually answer differently get a table. On a vanilla server that is wheat and
 * whatever else grows into a new color.</p>
 *
 * <p>The ray walk pays for this only where it applies: the extra state lookup happens
 * on a hit, and only for the handful of materials that came back varying.</p>
 *
 * <p>{@code block-colors.yml} exists only for overrides.</p>
 *
 * <p>The table is read-only once loaded, so render threads may use it.</p>
 */
public final class BlockColorTable {

    private static final String FILE_NAME = "block-colors.yml";
    /**
     * File format version; older files are backed up and replaced.
     */
    private static final int FILE_VERSION = 2;

    private final Map<Material, MapBaseColor> colors = new EnumMap<>(Material.class);
    /**
     * Colors per age, for the materials that have more than one. Absent means the
     * material's color is its own, whatever state it is in.
     */
    private final Map<Material, MapBaseColor[]> byAge = new EnumMap<>(Material.class);

    /**
     * Blocks reporting a base color this build's table does not know, counted across
     * the whole load so one warning covers them all.
     */
    private int unknown;

    private BlockColorTable() {
    }

    /**
     * Reads real map colors from the server, then applies user overrides.
     */
    public static BlockColorTable load(Izomap plugin) {
        var table = new BlockColorTable();
        table.readFromServer(plugin);
        table.applyOverrides(plugin, loadFile(plugin));
        plugin.messages().info("log.block-colors-ready",
                Placeholder.unparsed("count", String.valueOf(table.colors.size())));
        return table;
    }

    /**
     * Map base color of a material. Blocks that do not show on maps (air, glass,
     * torches, saplings) return {@link MapBaseColor#NONE} and the render must treat
     * them as transparent and continue the ray, as vanilla maps do.
     */
    public MapBaseColor baseColorOf(Material material) {
        var color = colors.get(material);
        return color != null ? color : MapBaseColor.NONE;
    }

    /**
     * Whether this material's color depends on the state of the individual block, and
     * the walk therefore has to look that state up.
     */
    public boolean variesByState(Material material) {
        return byAge.containsKey(material);
    }

    /**
     * Color of one particular block. Falls back to the material's own color for a
     * state that carries no age, so a caller may always ask.
     */
    public MapBaseColor baseColorOf(Material material, BlockData data) {
        var ages = byAge.get(material);
        if (ages == null || !(data instanceof Ageable ageable))
            return baseColorOf(material);

        var age = ageable.getAge();
        return age >= 0 && age < ages.length ? ages[age] : baseColorOf(material);
    }

    /**
     * Reads the map color of every material, and of every age of the ones that grow
     * into a different one.
     */
    private void readFromServer(Izomap plugin) {
        var varying = 0;
        for (var material : Material.values()) {
            if (material.isLegacy() || !material.isBlock()) continue;

            BlockData data;
            try {
                data = material.createBlockData();
            } catch (RuntimeException ex) {
                // Materials without a usable block state do not show on maps either.
                continue;
            }
            var base = colorOf(data);
            if (base == null) continue;

            colors.put(material, base);

            var ages = readAgedColors(data, base);
            if (ages != null) {
                byAge.put(material, ages);
                varying++;
            }
        }
        if (unknown > 0) {
            plugin.messages().warn("log.unknown-base-colors",
                    Placeholder.unparsed("count", String.valueOf(unknown)));
        }
        if (varying > 0) {
            plugin.messages().info("log.state-colors-ready",
                    Placeholder.unparsed("count", String.valueOf(varying)));
        }
    }

    /**
     * One color per age, or {@code null} when the block has no age or wears the same
     * color at all of them — which is nearly every ageable block, and the case the ray
     * walk must not pay for.
     */
    private MapBaseColor[] readAgedColors(BlockData data, MapBaseColor base) {
        if (!(data instanceof Ageable ageable))
            return null;

        var ages = new MapBaseColor[ageable.getMaximumAge() + 1];
        var varies = false;
        for (var age = 0; age < ages.length; age++) {
            var probe = (Ageable) data.clone();
            probe.setAge(age);
            var color = colorOf((BlockData) probe);
            ages[age] = color != null ? color : base;
            varies |= ages[age] != base;
        }
        return varies ? ages : null;
    }

    /**
     * The base color a block state reports, or {@code null} when it has none to give.
     * An unrecognized one falls back to the nearest and is counted for the warning.
     */
    private MapBaseColor colorOf(BlockData data) {
        int rgb;
        try {
            rgb = data.getMapColor().asRGB();
        } catch (RuntimeException ex) {
            return null;
        }
        var base = MapBaseColor.byBaseRgb(rgb);
        if (base != null)
            return base;

        unknown++;
        return nearestBase(rgb);
    }

    /**
     * Applies the overrides from {@code block-colors.yml}.
     */
    private void applyOverrides(Izomap plugin, YamlConfiguration cfg) {
        var section = cfg.getConfigurationSection("overrides");
        if (section == null) return;

        var applied = 0;
        for (var key : section.getKeys(false)) {
            var material = Material.matchMaterial(key);
            if (material == null || !material.isBlock()) {
                plugin.messages().warn("log.override-unknown-block",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key));
                continue;
            }
            var raw = section.getString(key);
            var base = parseBaseColor(raw);
            if (base == null) {
                plugin.messages().warn("log.override-invalid-color",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key),
                        Placeholder.unparsed("color", String.valueOf(raw)));
                continue;
            }
            colors.put(material, base);
            // An override is one color for the whole material, ages included; keeping
            // the age table would let it win back over what the owner asked for.
            byAge.remove(material);
            applied++;
        }
        if (applied > 0) {
            plugin.messages().info("log.block-colors-overridden",
                    Placeholder.unparsed("count", String.valueOf(applied)));
        }
    }

    /**
     * Loads the file, backing up and replacing older versions so that approximated
     * colors from v1 cannot overwrite the correct vanilla ones.
     */
    private static YamlConfiguration loadFile(Izomap plugin) {
        var file = new File(plugin.getDataFolder(), FILE_NAME);
        if (file.exists()) {
            var existing = YamlConfiguration.loadConfiguration(file);
            if (existing.getInt("version", 1) >= FILE_VERSION) {
                return existing;
            }
            var backup = new File(plugin.getDataFolder(), FILE_NAME + ".v1.bak");
            if (backup.exists() && !backup.delete()) {
                plugin.messages().warn("log.block-colors-backup-failed",
                        Placeholder.unparsed("file", FILE_NAME));
                return new YamlConfiguration();
            }
            if (!file.renameTo(backup)) {
                plugin.messages().warn("log.block-colors-backup-failed",
                        Placeholder.unparsed("file", FILE_NAME));
                return new YamlConfiguration();
            }
            plugin.messages().info("log.block-colors-upgraded",
                    Placeholder.unparsed("file", FILE_NAME),
                    Placeholder.unparsed("backup", backup.getName()));
        }
        plugin.saveResource(FILE_NAME, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Accepts a base color name ("GRASS") or hex ("#7FB238").
     */
    private static MapBaseColor parseBaseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var value = raw.trim();
        var named = MapBaseColor.byName(value);
        if (named != null) return named;

        var hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            var rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
            var exact = MapBaseColor.byBaseRgb(rgb);
            return exact != null ? exact : nearestBase(rgb);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Nearest base color to an arbitrary RGB, excluding transparent NONE.
     */
    private static MapBaseColor nearestBase(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        var best = MapBaseColor.STONE;
        var bestDistance = Long.MAX_VALUE;
        for (var candidate : MapBaseColor.values()) {
            if (candidate == MapBaseColor.NONE) continue;

            var cr = (candidate.baseRgb() >> 16) & 0xFF;
            var cg = (candidate.baseRgb() >> 8) & 0xFF;
            var cb = candidate.baseRgb() & 0xFF;
            var dr = r - cr;
            var dg = g - cg;
            var db = b - cb;
            var distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
