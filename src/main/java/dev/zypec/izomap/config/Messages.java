package dev.zypec.izomap.config;

import com.mojang.brigadier.Message;
import dev.zypec.izomap.Izomap;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Loads the MiniMessage strings from {@code messages.yml} and turns them into
 * {@link Component}s. Legacy {@code &} color codes are never used.
 */
public final class Messages {

    private final Izomap plugin;
    private MiniMessage mm = MiniMessage.miniMessage();

    private FileConfiguration messages;

    public Messages(Izomap plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists())
            plugin.saveResource("messages.yml", false);

        this.messages = YamlConfiguration.loadConfiguration(file);
        this.mm = MiniMessage.builder()
                .tags(Placeholder.parsed("prefix", messages.getString("prefix", "")))
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
        if (raw == null) {
            return Component.text("<missing: " + key + ">");
        }
        return mm.deserialize(raw, resolvers);
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
        List<Component> out = new java.util.ArrayList<>();
        for (String line : messages.getStringList(key)) {
            out.add(mm.deserialize(line, resolvers));
        }
        return out;
    }

    public MiniMessage mini() {
        return mm;
    }
}
