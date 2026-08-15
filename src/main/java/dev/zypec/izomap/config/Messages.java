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
import java.util.ArrayList;
import java.util.List;

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

    private final Izomap plugin;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    private FileConfiguration messages;

    public Messages(Izomap plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists())
            plugin.saveResource("messages.yml", false);

        this.messages = YamlConfiguration.loadConfiguration(file);
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
    public Component get(String key, TagResolver... resolvers) {
        var raw = messages.getString(key);
        if (raw == null) return Component.text("<missing: " + key + ">");

        return miniMessage.deserialize(raw, resolvers);
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
