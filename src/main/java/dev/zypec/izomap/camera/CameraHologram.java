package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.util.Format;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;

/**
 * Builds the text floating above a camera.
 *
 * <p>Which facts show up is the server owner's choice: the lines come from
 * {@code camera.hologram.lines} in {@code messages.yml} and every value is offered as
 * a placeholder, so dropping a line drops it from the hologram.</p>
 */
public final class CameraHologram {

    private CameraHologram() {
    }

    /**
     * The configured lines, filled in and joined into one component.
     */
    public static Component text(Izomap plugin, Camera camera, int photoCount) {
        var lines = plugin.messages().list("camera.hologram.lines",
                Placeholder.unparsed("name", camera.name()),
                Placeholder.unparsed("owner", ownerName(camera)),
                Placeholder.unparsed("ratio", camera.aspectRatio().label()),
                Placeholder.unparsed("zoom", Format.zoom(camera.zoom())),
                Placeholder.unparsed("blocks",
                        Format.blocks(plugin.config().frameHeight(), camera.zoom())),
                Placeholder.unparsed("yaw", Format.degrees(camera.camYaw())),
                Placeholder.unparsed("pitch", Format.degrees(camera.camPitch())),
                Placeholder.component("filter",
                        plugin.messages().get("filter." + camera.colorFilter().name())),
                Placeholder.component("style",
                        plugin.messages().get("style." + camera.style().name())),
                Placeholder.component("sky",
                        plugin.messages().get("sky." + camera.sky().name())),
                Placeholder.unparsed("photos", String.valueOf(photoCount)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * Falls back to the head of the owner's UUID: a player who has never joined this
     * server has no name to show, and an empty line reads like a bug.
     */
    private static String ownerName(Camera camera) {
        var online = Bukkit.getPlayer(camera.owner());
        if (online != null)
            return online.getName();

        var name = Bukkit.getOfflinePlayer(camera.owner()).getName();
        return name != null ? name : camera.owner().toString().substring(0, 8);
    }
}
