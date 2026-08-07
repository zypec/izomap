package dev.zypec.izomap.config;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * messages.yml içindeki MiniMessage tabanlı mesajları yükler ve
 * {@link Component} olarak üretir.
 *
 * <p>Tüm görünür metinler MiniMessage üzerinden {@link Component}'e çevrilir;
 * hiçbir yerde legacy renk kodu ({@code &}) kullanılmaz.</p>
 */
public final class Messages {

    private final Izomap plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private FileConfiguration messages;
    private String prefix = "";

    public Messages(Izomap plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.prefix = messages.getString("prefix", "");
    }

    /** Verilen anahtardaki mesajı önek olmadan {@link Component}'e çevirir. */
    public Component get(String key, TagResolver... resolvers) {
        String raw = messages.getString(key);
        if (raw == null) {
            return Component.text("<missing: " + key + ">");
        }
        return mm.deserialize(raw, resolvers);
    }

    /** Verilen anahtardaki mesajı önekle birlikte {@link Component}'e çevirir. */
    public Component prefixed(String key, TagResolver... resolvers) {
        String raw = messages.getString(key, "");
        return mm.deserialize(prefix + raw, resolvers);
    }

    /** Önekli mesajı doğrudan bir alıcıya gönderir. */
    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(prefixed(key, resolvers));
    }

    /** Bir string listesini (ör. lore) {@link Component} listesine çevirir. */
    public java.util.List<Component> list(String key, TagResolver... resolvers) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (String line : messages.getStringList(key)) {
            out.add(mm.deserialize(line, resolvers));
        }
        return out;
    }

    public MiniMessage mini() {
        return mm;
    }
}
