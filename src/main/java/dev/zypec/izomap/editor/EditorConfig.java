package dev.zypec.izomap.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.zypec.izomap.Izomap;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code config.yml} as the web editor shows and changes it: every key the shipped file has,
 * with its value, its default, what kind of value it takes, and the wiki's words for it
 * ({@code reference/config.yml}, copied into the jar from {@code wiki/reference}).
 *
 * <p>A change is checked against the key's kind, range and choices, written into the
 * server's own file (its comments kept) and applied with the same reload
 * {@code /izocam reload} does. Main thread for {@link #apply}.</p>
 */
final class EditorConfig {

    private static final Pattern RANGE = Pattern.compile("(-?\\d+(?:\\.\\d+)?) to (-?\\d+(?:\\.\\d+)?)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_#.:-]+");

    private record Entry(String key, Object fallback, String kind, Double min, Double max, List<String> choices,
                         String summary, String detail) {
    }

    private final Izomap plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    EditorConfig(Izomap plugin) {
        this.plugin = plugin;
        var defaults = read("config.yml", '.');
        var reference = read("reference/config.yml", '|');
        for (var key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || key.equals("version")) continue;
            var fallback = defaults.get(key);
            var about = reference.getConfigurationSection(key);
            var range = about == null ? null : about.getString("range");
            Double min = null, max = null;
            var matched = range == null ? null : RANGE.matcher(range);
            if (matched != null && matched.find()) {
                min = Double.parseDouble(matched.group(1));
                max = Double.parseDouble(matched.group(2));
            }
            var kind = fallback instanceof Boolean ? "boolean"
                    : fallback instanceof Integer && (range == null || !range.contains(".")) ? "integer"
                    : fallback instanceof Number ? "number" : "text";
            List<String> choices = new ArrayList<>();
            var values = about == null ? null : about.getString("values");
            if (values != null && kind.equals("text"))
                for (var choice : values.split(",")) {
                    var word = choice.trim().replace("`", "");
                    if (WORD.matcher(word).matches()) choices.add(word);
                }
            entries.put(key, new Entry(key, fallback, kind, min, max, List.copyOf(choices),
                    about == null ? "" : about.getString("summary", ""), about == null ? "" : about.getString("detail", "")));
        }
    }

    private YamlConfiguration read(String resource, char separator) {
        var yaml = new YamlConfiguration();
        yaml.options().pathSeparator(separator);
        try (var in = plugin.getResource(resource)) {
            if (in != null) yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException unreadable) {
            plugin.getLogger().warning("Could not read the editor's copy of " + resource + ": " + unreadable.getMessage());
        }
        return yaml;
    }

    /** Every key in the shipped file's order, with what the server has for it now. */
    JsonObject describe() {
        var live = plugin.getConfig();
        var out = new JsonObject();
        var list = new JsonArray();
        for (var entry : entries.values()) {
            var row = new JsonObject();
            row.addProperty("key", entry.key());
            row.addProperty("kind", entry.kind());
            row.add("value", json(live.get(entry.key(), entry.fallback())));
            row.add("default", json(entry.fallback()));
            if (entry.min() != null) row.addProperty("min", entry.min());
            if (entry.max() != null) row.addProperty("max", entry.max());
            if (!entry.choices().isEmpty()) {
                var choices = new JsonArray();
                entry.choices().forEach(choices::add);
                row.add("choices", choices);
            }
            row.addProperty("summary", entry.summary());
            row.addProperty("detail", entry.detail());
            list.add(row);
        }
        out.add("settings", list);
        return out;
    }

    /**
     * Writes the values into the server's {@code config.yml} and reloads. Nothing is written
     * when any of them will not do.
     *
     * @throws IllegalArgumentException naming the first value that will not do
     */
    JsonObject apply(JsonObject values) throws IOException {
        var checked = new LinkedHashMap<String, Object>();
        for (var change : values.entrySet()) {
            var entry = entries.get(change.getKey());
            if (entry == null) throw new IllegalArgumentException("There is no setting " + change.getKey() + ".");
            checked.put(entry.key(), check(entry, change.getValue()));
        }
        var file = new File(plugin.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        checked.forEach(yaml::set);
        yaml.save(file);
        plugin.reloadConfig();
        plugin.reloadAll();
        var out = describe();
        var applied = new JsonArray();
        checked.keySet().forEach(applied::add);
        out.add("applied", applied);
        return out;
    }

    private static Object check(Entry entry, JsonElement value) {
        var key = entry.key();
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive())
            throw new IllegalArgumentException(key + " needs a value.");
        var primitive = value.getAsJsonPrimitive();
        switch (entry.kind()) {
            case "boolean" -> {
                if (!primitive.isBoolean()) throw new IllegalArgumentException(key + " is on or off.");
                return primitive.getAsBoolean();
            }
            case "integer", "number" -> {
                double number;
                try {
                    number = primitive.isNumber() ? primitive.getAsDouble() : Double.parseDouble(primitive.getAsString().trim());
                } catch (NumberFormatException wrong) {
                    throw new IllegalArgumentException(key + " is a number.");
                }
                if (entry.kind().equals("integer") && number != Math.rint(number))
                    throw new IllegalArgumentException(key + " is a whole number.");
                if ((entry.min() != null && number < entry.min()) || (entry.max() != null && number > entry.max()))
                    throw new IllegalArgumentException(key + " goes from " + plain(entry.min()) + " to " + plain(entry.max()) + ".");
                return entry.kind().equals("integer") ? (Object) (int) number : (Object) number;
            }
            default -> {
                var text = primitive.getAsString();
                if (!entry.choices().isEmpty() && entry.choices().stream().noneMatch(text::equalsIgnoreCase))
                    throw new IllegalArgumentException(key + " is one of " + String.join(", ", entry.choices()) + ".");
                return text;
            }
        }
    }

    private static String plain(Double number) {
        return number == null ? "" : number == Math.rint(number) ? String.valueOf(number.longValue()) : String.valueOf(number);
    }

    private static JsonElement json(Object value) {
        var out = new JsonObject();
        if (value instanceof Boolean flag) out.addProperty("v", flag);
        else if (value instanceof Number number) out.addProperty("v", number);
        else out.addProperty("v", value == null ? "" : String.valueOf(value));
        return out.get("v");
    }
}
