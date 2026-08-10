package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.storage.YamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
            cfg.set(base + ".cam-yaw", c.camYaw());
            cfg.set(base + ".cam-pitch", c.camPitch());
            cfg.set(base + ".zoom", c.zoom());
            cfg.set(base + ".aspect-ratio", c.aspectRatio().name());
            cfg.set(base + ".color-filter", c.colorFilter().name());
            cfg.set(base + ".thirds-guide", c.thirdsGuide());
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
        var id = parseUUID(key);
        var owner = parseUUID(s.getString("owner"));
        var worldRaw = s.getString("world");
        if (id == null || owner == null || worldRaw == null)
            return null;

        var world = Bukkit.getWorld(UUID.fromString(worldRaw));
        if (world == null) {
            plugin.getLogger().warning(
                    "Kamera '" + key + "' atlandı: dünya yüklü değil (" + worldRaw + ").");
            return null;
        }

        var anchor = new Location(
                world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("cam-yaw"), (float) s.getDouble("cam-pitch"));

        var camera = new Camera(id, owner, s.getString("name", "camera"), anchor);
        camera.displayEntityId(parseUUID(s.getString("display-entity")));
        camera.interactionEntityId(parseUUID(s.getString("interaction-entity")));
        camera.camYaw((float) s.getDouble("cam-yaw"));
        camera.camPitch((float) s.getDouble("cam-pitch"));
        // Older records used the key "scale" with the same meaning.
        camera.zoom((float) s.getDouble("zoom", s.getDouble("scale", 1.0)));
        camera.aspectRatio(AspectRatio.fromString(s.getString("aspect-ratio"), AspectRatio.RATIO_1_1));
        camera.colorFilter(ColorFilter.fromString(s.getString("color-filter"), ColorFilter.ORIGINAL));
        camera.thirdsGuide(s.getBoolean("thirds-guide", false));
        camera.previewMapId(s.getInt("preview-map-id", Camera.NO_PREVIEW_MAP));
        return camera;
    }

    private static UUID parseUUID(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
