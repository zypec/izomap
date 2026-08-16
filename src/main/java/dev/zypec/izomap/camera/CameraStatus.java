package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.util.Format;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Renders a camera's adjustable properties as the one-line status shown while it is
 * being edited or previewed.
 *
 * <p>Kept apart from both the listener and the preview, so the line a click produces
 * and the line the preview repeats stay identical.</p>
 */
public final class CameraStatus {

    private CameraStatus() {
    }

    /**
     * Every property with its value, the active one highlighted.
     */
    public static Component line(Izomap plugin, Camera camera) {
        return line(plugin, camera, false);
    }

    /**
     * The same line, told whether a render for this camera is still running.
     *
     * <p>Both waits are asynchronous, so what the player did shows up a moment after
     * they did it. Without saying so the pause reads as a click that did nothing, and
     * they do it again. A running capture outranks a preview render on the line.</p>
     */
    public static Component line(Izomap plugin, Camera camera, boolean rendering) {
        var separator = plugin.messages().get("preview.actionbar-separator");
        var entries = Component.empty();
        boolean first = true;
        for (var property : EditProperty.values()) {
            if (!first) {
                entries = entries.append(separator);
            }
            entries = entries.append(entry(plugin, camera, property));
            first = false;
        }
        // A capture takes longer than a preview render and matters more, so it has the
        // line to itself while it runs.
        var key = camera.capturing() ? "preview.actionbar-capturing"
                : rendering ? "preview.actionbar-rendering"
                : "preview.actionbar";
        var progress = camera.captureProgress();
        return plugin.messages().get(key,
                Placeholder.component("entries", entries),
                Placeholder.unparsed("percent", progress != null ? String.valueOf(progress.percent()) : "0"),
                Placeholder.unparsed("bar", progress != null ? progress.bar() : ""));
    }

    private static Component entry(Izomap plugin, Camera camera, EditProperty property) {
        var active = property == camera.editProperty();
        return plugin.messages().get(active ? "preview.entry-active" : "preview.entry",
                Placeholder.component("label", plugin.messages().get("preview.property." + property.name())),
                Placeholder.component("value", value(plugin, camera, property)));
    }

    /**
     * Value of a single property. Zoom also states the frame width in blocks: the bare
     * multiplier says little on its own.
     */
    public static Component value(Izomap plugin, Camera camera, EditProperty property) {
        return switch (property) {
            case YAW -> angle(plugin, camera.camYaw());
            case PITCH -> angle(plugin, camera.camPitch());
            case ZOOM -> plugin.messages().get("preview.value-zoom",
                    Placeholder.unparsed("zoom", Format.zoom(camera.zoom())),
                    Placeholder.unparsed("blocks",
                            Format.blocks(plugin.config().frameHeight(), camera.zoom())));
            // Movement has no value of its own, so it reports where the camera ended up.
            case MOVE -> plugin.messages().get("preview.value-position",
                    Placeholder.unparsed("x", Format.coordinate(camera.anchor().getX())),
                    Placeholder.unparsed("y", Format.coordinate(camera.anchor().getY())),
                    Placeholder.unparsed("z", Format.coordinate(camera.anchor().getZ())));
        };
    }

    private static Component angle(Izomap plugin, float degrees) {
        return plugin.messages().get("preview.value-angle",
                Placeholder.unparsed("deg", Format.degrees(degrees)));
    }
}
