package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The filters a server offers, read from {@code filters.yml}.
 *
 * <p>Order is the file's order: it decides how the capture screen's button cycles, so a
 * server owner arranges the list the way they want to click through it.</p>
 *
 * <p>{@code ORIGINAL} is always present whatever the file says. It is the fallback for
 * an id nobody recognises, the value a camera starts on, and the only one that can be
 * relied upon to leave a photo alone; a file that deleted it would leave those with
 * nothing to name.</p>
 *
 * <p>Read-only once loaded, so render threads may use it.</p>
 */
public final class ColorFilters {

    private static final String FILE_NAME = "filters.yml";

    private final Map<String, ColorFilter> byId = new LinkedHashMap<>();

    private ColorFilters() {
        byId.put(ColorFilter.ORIGINAL.id(), ColorFilter.ORIGINAL);
    }

    public static ColorFilters load(Izomap plugin) {
        var filters = new ColorFilters();
        var file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists())
            plugin.saveResource(FILE_NAME, false);

        var cfg = YamlConfiguration.loadConfiguration(file);
        var root = cfg.getConfigurationSection("filters");
        if (root != null) {
            for (var key : root.getKeys(false)) {
                var section = root.getConfigurationSection(key);
                if (section == null) continue;

                var id = key.toUpperCase(Locale.ROOT);
                var ops = readOps(plugin, id, section.getMapList("ops"));
                // An ORIGINAL redefined in the file is ignored: everything that falls
                // back to it expects it to leave colours alone.
                if (!ColorFilter.ORIGINAL.id().equals(id)) {
                    filters.byId.put(id, new ColorFilter(id, ops));
                }
            }
        }
        plugin.messages().info("log.filters-loaded",
                Placeholder.unparsed("count", String.valueOf(filters.byId.size())));
        return filters;
    }

    /**
     * The filter with this id, or {@code fallback} when the file no longer defines it.
     */
    public ColorFilter byId(String id, ColorFilter fallback) {
        if (id == null || id.isBlank()) return fallback;

        var filter = byId.get(id.trim().toUpperCase(Locale.ROOT));
        return filter != null ? filter : fallback;
    }

    /**
     * Every filter, in file order.
     */
    public List<ColorFilter> all() {
        return List.copyOf(byId.values());
    }

    /**
     * The next filter after this one, wrapping; how the capture screen's button steps.
     */
    public ColorFilter next(ColorFilter current) {
        var all = all();
        var index = all.indexOf(current);
        return all.get(index < 0 ? 0 : (index + 1) % all.size());
    }

    /**
     * Turns the {@code ops} list into steps. Each entry is a one-key map, so the file
     * reads as a sequence of instructions and the order survives.
     *
     * <p>An unreadable step is skipped with a warning rather than failing the load: one
     * mistyped line should cost its own effect, not every filter on the server.</p>
     */
    private static List<ColorOp> readOps(Izomap plugin, String id, List<Map<?, ?>> raw) {
        List<ColorOp> ops = new ArrayList<>();
        for (var entry : raw) {
            for (var key : entry.keySet()) {
                var name = String.valueOf(key).toLowerCase(Locale.ROOT);
                var value = entry.get(key);
                var op = readOp(name, value);
                if (op != null) {
                    ops.add(op);
                } else {
                    plugin.messages().warn("log.filter-unknown-op",
                            Placeholder.unparsed("file", FILE_NAME),
                            Placeholder.unparsed("filter", id),
                            Placeholder.unparsed("op", name));
                }
            }
        }
        return ops;
    }

    private static ColorOp readOp(String name, Object value) {
        return switch (name) {
            case "brightness" -> ColorOp.brightness(number(value, 1.0));
            case "contrast" -> ColorOp.contrast(number(value, 1.0));
            case "saturation" -> ColorOp.saturation(number(value, 1.0), 0.299, 0.587, 0.114);
            case "invert" -> bool(value) ? ColorOp.invert() : null;
            case "posterize" -> ColorOp.posterize((int) number(value, 8));
            case "grayscale" -> readGrayscale(value);
            case "rgb-offset" -> readOffset(value);
            case "tint" -> readTint(value);
            default -> null;
        };
    }

    /**
     * {@code grayscale: true}, or a map naming its own luma weights.
     */
    private static ColorOp readGrayscale(Object value) {
        if (value instanceof Map<?, ?> map) {
            return ColorOp.grayscale(number(map.get("r"), 0.299),
                    number(map.get("g"), 0.587), number(map.get("b"), 0.114));
        }
        return bool(value) ? ColorOp.grayscale(0.299, 0.587, 0.114) : null;
    }

    private static ColorOp readOffset(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;

        return ColorOp.rgbOffset((int) number(map.get("r"), 0),
                (int) number(map.get("g"), 0), (int) number(map.get("b"), 0));
    }

    /**
     * {@code tint: {color: "#RRGGBB", strength: 0.3}}.
     */
    private static ColorOp readTint(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;

        var raw = String.valueOf(map.get("color")).trim();
        var hex = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            return ColorOp.tint(Integer.parseInt(hex, 16) & 0xFFFFFF,
                    number(map.get("strength"), 0.3));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
}
