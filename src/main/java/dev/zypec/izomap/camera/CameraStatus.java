package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Locale;

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
     * <p>A preview is redrawn asynchronously, so an adjustment shows up a moment after
     * the click that made it. Without saying so the wait reads as a click that did
     * nothing, and the player clicks again.</p>
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
        return plugin.messages().get(rendering ? "preview.actionbar-rendering" : "preview.actionbar",
                Placeholder.component("entries", entries));
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
                    Placeholder.unparsed("zoom", String.format(Locale.ROOT, "%.2f", camera.zoom())),
                    Placeholder.unparsed("blocks", String.format(Locale.ROOT, "%.0f",
                            plugin.config().frameHeight() / camera.zoom())));
            // Movement has no value of its own, so it reports where the camera ended up.
            case MOVE_X -> plugin.messages().get("preview.value-position-xz",
                    Placeholder.unparsed("x", coordinate(camera.anchor().getX())),
                    Placeholder.unparsed("z", coordinate(camera.anchor().getZ())));
            case MOVE_Y -> plugin.messages().get("preview.value-position-y",
                    Placeholder.unparsed("y", coordinate(camera.anchor().getY())));
        };
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Component angle(Izomap plugin, float degrees) {
        return plugin.messages().get("preview.value-angle",
                Placeholder.unparsed("deg", String.format(Locale.ROOT, "%.0f", degrees)));
    }
}
