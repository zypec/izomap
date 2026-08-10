package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import org.bukkit.Material;
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

    private BlockColorTable() {
    }

    /**
     * Reads real map colors from the server, then applies user overrides.
     */
    public static BlockColorTable load(Izomap plugin) {
        var table = new BlockColorTable();
        table.readFromServer(plugin);
        table.applyOverrides(plugin, loadFile(plugin));
        plugin.getLogger().info(table.colors.size() + " blok için harita temel rengi hazır.");
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
     * Reads the map color of each material's default block state.
     */
    private void readFromServer(Izomap plugin) {
        var unknown = 0;
        for (var material : Material.values()) {
            if (material.isLegacy() || !material.isBlock()) continue;

            int rgb;
            try {
                rgb = material.createBlockData().getMapColor().asRGB();
            } catch (RuntimeException ex) {
                // Materials without a usable block state do not show on maps either.
                continue;
            }

            var base = MapBaseColor.byBaseRgb(rgb);
            if (base == null) {
                // A base color this build's table does not know: fall back to the nearest.
                base = nearestBase(rgb);
                unknown++;
            }
            colors.put(material, base);
        }
        if (unknown > 0) {
            plugin.getLogger().warning(unknown + " blok, bilinmeyen bir harita temel rengi bildirdi; "
                                       + "en yakın renge eşlendi. (MapBaseColor tablosu güncellenmeli.)");
        }
    }

    /**
     * Applies the overrides from {@code block-colors.yml}.
     */
    private void applyOverrides(Izomap plugin, YamlConfiguration cfg) {
        var section = cfg.getConfigurationSection("overrides");
        if (section == null) return;

        int applied = 0;
        for (var key : section.getKeys(false)) {
            var material = Material.matchMaterial(key);
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning(FILE_NAME + ": bilinmeyen blok '" + key + "' atlandı.");
                continue;
            }
            var raw = section.getString(key);
            var base = parseBaseColor(raw);
            if (base == null) {
                plugin.getLogger().warning(FILE_NAME + ": '" + key + "' için geçersiz renk '" + raw + "' atlandı.");
                continue;
            }
            colors.put(material, base);
            applied++;
        }
        if (applied > 0) {
            plugin.getLogger().info(applied + " blok rengi override edildi.");
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
                plugin.getLogger().warning(FILE_NAME + " eski sürümde ve yedeklenemedi; override'lar yok sayıldı.");
                return new YamlConfiguration();
            }
            if (!file.renameTo(backup)) {
                plugin.getLogger().warning(FILE_NAME + " eski sürümde ve yedeklenemedi; override'lar yok sayıldı.");
                return new YamlConfiguration();
            }
            plugin.getLogger().info(FILE_NAME + " eski sürümdeydi; " + backup.getName()
                                    + " olarak yedeklendi ve yeni varsayılan yazıldı.");
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
