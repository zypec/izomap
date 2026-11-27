package dev.zypec.izomap.config;

import dev.zypec.izomap.camera.EditProperty;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every message the code asks for is in the shipped English file, read the way the server reads
 * it, and every other shipped language carries the same keys with the same placeholders.
 *
 * <p>Read with Bukkit's own YAML loader on purpose: it follows YAML 1.1, where a bare {@code on}
 * key is the boolean {@code true}, and {@code focus.on} quietly went missing that way once.</p>
 */
class MessagesFileTest {

    private static final YamlConfiguration MESSAGES = YamlConfiguration.loadConfiguration(
            new File("src/main/resources/lang/en.yml"));
    private static final YamlConfiguration CONFIG = YamlConfiguration.loadConfiguration(
            new File("src/main/resources/config.yml"));

    /** A dotted key in quotes; config.yml keys share some of these sections and are told apart below. */
    private static final Pattern KEY = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");

    @Test
    void everyKeyTheCodeNamesExists() throws IOException {
        var sections = MESSAGES.getKeys(false);
        var missing = new TreeSet<String>();
        List<Path> sources;
        try (var walk = Files.walk(Path.of("src/main/java"))) {
            sources = walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("ConfigManager.java")).toList();
        }
        for (var source : sources) {
            var matcher = KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                var key = matcher.group(1);
                if (sections.contains(key.substring(0, key.indexOf('.'))) && !MESSAGES.contains(key) && !CONFIG.contains(key))
                    missing.add(key + " (" + source.getFileName() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "messages.yml is missing " + missing);
    }

    @Test
    void everyKeyBuiltFromAValueExists() throws IOException {
        var wanted = new ArrayList<String>(List.of("focus.on", "fog.on", "sky-bodies.on"));
        for (var where : List.of("behind", "front", "side")) wanted.add("dialog.sun-where." + where);
        for (var height : List.of("low", "mid", "high")) wanted.add("dialog.sun-height." + height);
        for (var sky : SkyOption.values()) wanted.add("sky." + sky.name());
        for (var style : PhotoStyle.values()) wanted.add("style." + style.name());
        for (var property : EditProperty.values()) wanted.add("preview.property." + property.name());
        for (var file : List.of("filters", "frames")) {
            var section = YamlConfiguration.loadConfiguration(new File("src/main/resources/" + file + ".yml"));
            var root = section.getConfigurationSection(file);
            if (root != null)
                for (var id : root.getKeys(false)) wanted.add(file.substring(0, file.length() - 1) + "." + id);
        }

        var missing = wanted.stream().filter(key -> !MESSAGES.isString(key)).toList();
        assertTrue(missing.isEmpty(), "messages.yml is missing " + missing);
    }

    /** MiniMessage's own tags, told apart from the placeholders a translation must keep. */
    private static final java.util.Set<String> TAGS = java.util.Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z_-]*)>");

    @Test
    void translationsCarryEveryKeyAndPlaceholder() throws IOException {
        var problems = new TreeSet<String>();
        try (var files = Files.list(Path.of("src/main/resources/lang"))) {
            for (var file : files.filter(path -> !path.endsWith("en.yml")).toList()) {
                var other = YamlConfiguration.loadConfiguration(file.toFile());
                var name = file.getFileName().toString();
                for (var key : MESSAGES.getKeys(true)) {
                    if (MESSAGES.isConfigurationSection(key)) continue;
                    if (!other.contains(key)) {
                        problems.add(name + " lacks " + key);
                        continue;
                    }
                    if (!placeholders(MESSAGES, key).equals(placeholders(other, key)))
                        problems.add(name + " changes the placeholders of " + key);
                }
                for (var key : other.getKeys(true))
                    if (!other.isConfigurationSection(key) && !MESSAGES.contains(key))
                        problems.add(name + " has " + key + ", which English does not");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static java.util.Set<String> placeholders(YamlConfiguration file, String key) {
        var text = String.join("\n", file.isList(key) ? file.getStringList(key) : List.of(String.valueOf(file.getString(key))));
        var found = new TreeSet<String>();
        var matcher = PLACEHOLDER.matcher(text);
        while (matcher.find())
            if (!TAGS.contains(matcher.group(1))) found.add(matcher.group(1));
        return found;
    }
}
