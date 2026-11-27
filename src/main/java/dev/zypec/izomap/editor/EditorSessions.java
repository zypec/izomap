package dev.zypec.izomap.editor;

import dev.zypec.izomap.Izomap;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keys that open the editor, one per player.
 *
 * <p>A key is 128 random bits, handed out in the link {@code /izocam editor} prints and
 * sent back with every request. It acts as the player who asked for it while they are
 * online, and stops working after {@code editor.idle-minutes} without a request or when a
 * new link is asked for. Read from the editor's request threads, hence the concurrent
 * map.</p>
 */
final class EditorSessions {

    private record Session(UUID player, long touchedAt) {
    }

    private final Izomap plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    EditorSessions(Izomap plugin) {
        this.plugin = plugin;
    }

    /**
     * A fresh key for the player; any older key of theirs stops working.
     */
    String open(Player player) {
        sessions.values().removeIf(session -> session.player().equals(player.getUniqueId()));
        var bytes = new byte[16];
        random.nextBytes(bytes);
        var key = HexFormat.of().formatHex(bytes);
        sessions.put(key, new Session(player.getUniqueId(), System.currentTimeMillis()));
        return key;
    }

    /**
     * The online player a key acts for, or {@code null} when it is unknown, idle too long
     * or its player has left.
     */
    Player player(String key) {
        if (key == null) return null;
        var session = sessions.get(key);
        if (session == null) return null;

        var now = System.currentTimeMillis();
        if (now - session.touchedAt() > plugin.config().editorIdleMinutes() * 60_000L) {
            sessions.remove(key);
            return null;
        }
        var player = plugin.getServer().getPlayer(session.player());
        if (player == null) return null;

        sessions.put(key, new Session(session.player(), now));
        return player;
    }
}
