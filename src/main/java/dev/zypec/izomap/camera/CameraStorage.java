package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
import dev.zypec.izomap.storage.YamlStorage;
import dev.zypec.izomap.util.Ids;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Asynchronous persistence of cameras to {@code cameras.yml}.
 *
 * <p>Camera counts stay low per player, so every save serializes the whole
 * collection, which is simpler and safer than partial updates.</p>
 */
public final class CameraStorage extends YamlStorage {

    public CameraStorage(Izomap plugin) {
        super(plugin, "cameras.yml");
    }

    /**
     * Serializes the whole collection and saves it asynchronously.
     */
    public void saveAll(Collection<Camera> cameras) {
        setData(serialize(cameras));
        save();
    }

    /**
     * Saves synchronously, for shutdown.
     */
    public void saveAllSync(Collection<Camera> cameras) {
        setData(serialize(cameras));
        saveNow();
    }

    private FileConfiguration serialize(Collection<Camera> cameras) {
        var cfg = new YamlConfiguration();
        for (var c : cameras) {
            var base = "cameras." + c.id();
            var a = c.anchor();
            cfg.set(base + ".owner", c.owner().toString());
            cfg.set(base + ".name", c.name());
            cfg.set(base + ".world", a.getWorld() != null ? a.getWorld().getUID().toString() : null);
            cfg.set(base + ".x", a.getX());
            cfg.set(base + ".y", a.getY());
            cfg.set(base + ".z", a.getZ());
            cfg.set(base + ".display-entity",
                    c.displayEntityId() != null ? c.displayEntityId().toString() : null);
            cfg.set(base + ".interaction-entity",
                    c.interactionEntityId() != null ? c.interactionEntityId().toString() : null);
            cfg.set(base + ".hologram-entity",
                    c.hologramEntityId() != null ? c.hologramEntityId().toString() : null);
            cfg.set(base + ".cam-yaw", c.camYaw());
            cfg.set(base + ".cam-pitch", c.camPitch());
            cfg.set(base + ".zoom", c.zoom());
            cfg.set(base + ".aspect-ratio", c.aspectRatio().name());
            cfg.set(base + ".color-filter", c.colorFilter().id());
            cfg.set(base + ".style", c.style().name());
            cfg.set(base + ".sky", c.sky().name());
            cfg.set(base + ".from-item", c.placedFromItem());
            cfg.set(base + ".thirds-guide", c.thirdsGuide());
            cfg.set(base + ".focus-enabled", c.focusEnabled());
            cfg.set(base + ".focus-distance", c.focusDistance());
            cfg.set(base + ".preview-map-id", c.previewMapId());
        }
        return cfg;
    }

    /**
     * Turns loaded data into {@link Camera} objects. Must run on the main thread
     * because it resolves worlds.
     */
    public List<Camera> readAll() {
        List<Camera> result = new ArrayList<>();
        var cfg = data();
        if (cfg == null) return result;

        var root = cfg.getConfigurationSection("cameras");
        if (root == null) return result;

        for (var key : root.getKeys(false)) {
            var s = root.getConfigurationSection(key);
            if (s == null) continue;

            var camera = readOne(key, s);
            if (camera != null)
                result.add(camera);
        }
        return result;
    }

    private Camera readOne(String key, ConfigurationSection s) {
        var id = Ids.parse(key);
        var owner = Ids.parse(s.getString("owner"));
        var worldId = Ids.parse(s.getString("world"));
        if (id == null || owner == null || worldId == null)
            return null;

        var world = Bukkit.getWorld(worldId);
        if (world == null) {
            plugin.messages().warn("log.camera-world-missing",
                    Placeholder.unparsed("camera", key),
                    Placeholder.unparsed("world", worldId.toString()));
            return null;
        }

        var anchor = new Location(
                world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("cam-yaw"), (float) s.getDouble("cam-pitch"));

        var camera = new Camera(id, owner, s.getString("name", "camera"), anchor);
        camera.displayEntityId(Ids.parse(s.getString("display-entity")));
        camera.interactionEntityId(Ids.parse(s.getString("interaction-entity")));
        camera.hologramEntityId(Ids.parse(s.getString("hologram-entity")));
        camera.camYaw((float) s.getDouble("cam-yaw"));
        camera.camPitch((float) s.getDouble("cam-pitch"));
        camera.zoom((float) s.getDouble("zoom", 1.0));
        camera.aspectRatio(AspectRatio.fromString(s.getString("aspect-ratio"), AspectRatio.RATIO_1_1));
        camera.colorFilter(plugin.filters().byId(s.getString("color-filter"), ColorFilter.ORIGINAL));
        camera.style(PhotoStyle.fromString(s.getString("style"), PhotoStyle.SHARP));
        camera.sky(SkyOption.fromString(s.getString("sky"), SkyOption.NONE));
        camera.placedFromItem(s.getBoolean("from-item", false));
        camera.thirdsGuide(s.getBoolean("thirds-guide", false));
        camera.focusEnabled(s.getBoolean("focus-enabled", false));
        camera.focusDistance((float) s.getDouble("focus-distance", 0.0));
        camera.previewMapId(s.getInt("preview-map-id", Camera.NO_PREVIEW_MAP));
        return camera;
    }
}
