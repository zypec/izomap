package dev.zypec.izomap.config;

import com.mojang.brigadier.Message;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.util.Failures;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the MiniMessage strings from {@code messages.yml} and turns them into
 * {@link Component}s. Legacy {@code &} color codes are never used.
 *
 * <p>Console output goes through here as well, under the {@code log} key tree, so a
 * server owner translates what the plugin prints alongside what it says in chat. Text
 * that stays in the code — exception messages, mostly — is written in English, because
 * it is read by whoever debugs the plugin rather than by whoever runs the server.</p>
 */
public final class Messages {

    /** The language a server gets unless it picks another, and the one every gap falls back to. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** Languages the jar carries. */
    private static final List<String> SHIPPED = List.of("en", "tr");

    private final Izomap plugin;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    private FileConfiguration messages;

    public Messages(Izomap plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads English. The language the server picked is only known once the config is
     * read, and reading the config already reports through here.
     */
    public void reload() {
        reload(DEFAULT_LANGUAGE);
    }

    /**
     * Loads {@code lang/<language>.yml} from the plugin folder.
     *
     * <p>Every shipped language is written out on first start, so a server owner finds
     * them all to edit. A key the server's copy lacks, such as one a newer version added,
     * reads the shipped text of the same language, then the English one: a missing
     * translation shows English rather than a placeholder. A language the plugin does not
     * ship works too, as long as its file is in the folder; anything missing from it is
     * English.</p>
     */
    public void reload(String language) {
        for (var shipped : SHIPPED)
            if (!new File(plugin.getDataFolder(), "lang/" + shipped + ".yml").exists())
                plugin.saveResource("lang/" + shipped + ".yml", false);

        var code = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim().toLowerCase(Locale.ROOT);
        var file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            plugin.getSLF4JLogger().warn("Language file lang/{}.yml was not found; using English", code);
            code = DEFAULT_LANGUAGE;
            file = new File(plugin.getDataFolder(), "lang/" + DEFAULT_LANGUAGE + ".yml");
        }

        this.messages = YamlConfiguration.loadConfiguration(file);
        var english = shipped(DEFAULT_LANGUAGE);
        var same = code.equals(DEFAULT_LANGUAGE) ? null : shipped(code);
        if (same != null) {
            same.setDefaults(english);
            messages.setDefaults(same);
        } else if (english != null) {
            messages.setDefaults(english);
        }
        this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.builder()
                        .resolver(TagResolver.standard())
                        .resolver(Placeholder.parsed("prefix", messages.getString("prefix", "")))
                        .build())
                .build();
    }

    /**
     * Resolves the message at the given key.
     *
     * @param key       translation key
     * @param resolvers placeholders
     * @return adventure component
     */
    /**
     * The jar's copy of a language, or {@code null} when it has none.
     */
    private YamlConfiguration shipped(String language) {
        try (var stream = plugin.getResource("lang/" + language + ".yml")) {
            return stream == null ? null
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            plugin.getSLF4JLogger().debug("The shipped lang/{}.yml could not be read", language, unreadable);
            return null;
        }
    }

    public Component get(String key, TagResolver... resolvers) {
        var raw = messages.getString(key);
        if (raw == null) return Component.text("<missing: " + key + ">");

        return miniMessage.deserialize(raw, resolvers);
    }

    /**
     * The message at the given key, or plain {@code fallback} text when the file has no
     * such key.
     *
     * <p>For names that come from a user-editable file: a filter somebody added to
     * {@code filters.yml} has no message of its own until they write one, and showing
     * its id reads better than announcing that a key is missing.</p>
     */
    public Component getOr(String key, String fallback, TagResolver... resolvers) {
        var raw = messages.getString(key);
        return raw != null ? miniMessage.deserialize(raw, resolvers) : Component.text(fallback);
    }

    /**
     * Resolves the message at the given key and returns it as a {@link Message}.
     *
     * @param key       translation key
     * @param resolvers placeholders
     * @return vanilla message
     */
    public Message asVanilla(String key, TagResolver... resolvers) {
        return PaperAdventure.asVanilla(get(key, resolvers));
    }

    /**
     * Sends the prefixed message straight to a receiver.
     */
    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(get(key, resolvers));
    }

    /**
     * Resolves a string list (e.g. lore) into a {@link Component} list.
     */
    public List<Component> list(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        for (var line : messages.getStringList(key)) {
            out.add(miniMessage.deserialize(line, resolvers));
        }
        return out;
    }

    /**
     * Resolves the message at the given key without any formatting, for the few APIs
     * that take a plain {@link String}.
     */
    public String plain(String key, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, resolvers));
    }

    // --- console ---

    public void info(String key, TagResolver... resolvers) {
        plugin.getComponentLogger().info(get(key, resolvers));
    }

    public void warn(String key, TagResolver... resolvers) {
        plugin.getComponentLogger().warn(get(key, resolvers));
    }

    public void error(String key, TagResolver... resolvers) {
        plugin.getComponentLogger().error(get(key, resolvers));
    }

    /**
     * Describes why an asynchronous stage failed, for the {@code <reason>} placeholder
     * of a log message.
     *
     * <p>Falls back to {@code log.no-reason} when the failure carries no message of its
     * own, so the log never trails off after the colon.</p>
     */
    public String reason(Throwable error) {
        var cause = Failures.unwrap(error);
        var message = cause != null ? cause.getMessage() : null;
        return message != null ? message : plain("log.no-reason");
    }

    public MiniMessage mini() {
        return miniMessage;
    }
}
