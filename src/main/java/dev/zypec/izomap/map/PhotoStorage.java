package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.CaptureSpec;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.storage.YamlStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous persistence of placed photos to {@code maps.yml}.
 */
public final class PhotoStorage extends YamlStorage {

    public PhotoStorage(Izomap plugin) {
        super(plugin, "maps.yml");
    }

    public void saveAll(Collection<PlacedPhoto> photos) {
        setData(serialize(photos));
        save();
    }

    public void saveAllSync(Collection<PlacedPhoto> photos) {
        setData(serialize(photos));
        saveNow();
    }

    private FileConfiguration serialize(Collection<PlacedPhoto> photos) {
        var cfg = new YamlConfiguration();
        for (var p : photos) {
            String base = "photos." + p.id();
            cfg.set(base + ".owner", p.owner().toString());
            cfg.set(base + ".name", p.name());
            cfg.set(base + ".camera", p.cameraName());
            cfg.set(base + ".world", p.worldId().toString());
            cfg.set(base + ".grid", p.grid().label());
            cfg.set(base + ".map-ids", new ArrayList<>(p.mapIds()));
            cfg.set(base + ".frame-ids", p.frameIds().stream().map(UUID::toString).toList());
            cfg.set(base + ".base-x", p.baseX());
            cfg.set(base + ".base-y", p.baseY());
            cfg.set(base + ".base-z", p.baseZ());
            writeSpec(cfg, base + ".capture", p.spec());
        }
        return cfg;
    }

    private static void writeSpec(FileConfiguration cfg, String base, CaptureSpec spec) {
        if (spec == null || spec.worldId() == null)
            return;

        cfg.set(base + ".world", spec.worldId().toString());
        cfg.set(base + ".x", spec.x());
        cfg.set(base + ".y", spec.y());
        cfg.set(base + ".z", spec.z());
        cfg.set(base + ".yaw", spec.yaw());
        cfg.set(base + ".pitch", spec.pitch());
        cfg.set(base + ".zoom", spec.zoom());
        cfg.set(base + ".color-filter", spec.colorFilter().name());
        cfg.set(base + ".frame-height", spec.frameHeight());
        cfg.set(base + ".frame-shift", spec.frameShift());
        cfg.set(base + ".supersampling", spec.supersampling());
        cfg.set(base + ".max-capture-area", spec.maxCaptureArea());
        cfg.set(base + ".render-depth", spec.renderDepth());
    }

    /**
     * Reads a capture spec; {@code null} for records written before it existed.
     */
    private static CaptureSpec readSpec(ConfigurationSection s) {
        if (s == null) return null;

        var world = parseUUID(s.getString("world"));
        if (world == null) return null;

        return new CaptureSpec(world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"),
                (float) s.getDouble("zoom", 1.0),
                ColorFilter.fromString(s.getString("color-filter"), ColorFilter.ORIGINAL),
                s.getDouble("frame-height", 48.0), s.getDouble("frame-shift", 0.0),
                s.getInt("supersampling", 1), s.getInt("max-capture-area", 512),
                s.getInt("render-depth", 64));
    }

    /**
     * Turns loaded data into {@link PlacedPhoto} objects.
     */
    public List<PlacedPhoto> readAll() {
        List<PlacedPhoto> result = new ArrayList<>();
        var cfg = data();
        if (cfg == null) return result;

        var root = cfg.getConfigurationSection("photos");
        if (root == null) return result;

        for (var key : root.getKeys(false)) {
            var s = root.getConfigurationSection(key);
            if (s == null) continue;

            var photo = readOne(key, s);
            if (photo != null)
                result.add(photo);
        }
        return result;
    }

    private PlacedPhoto readOne(String key, ConfigurationSection s) {
        var id = parseUUID(key);
        var owner = parseUUID(s.getString("owner"));
        var world = parseUUID(s.getString("world"));
        var grid = GridOption.parse(s.getString("grid"));
        if (id == null || owner == null || world == null || grid == null)
            return null;

        var mapIds = s.getIntegerList("map-ids");
        List<UUID> frameIds = new ArrayList<>();
        for (var raw : s.getStringList("frame-ids")) {
            var frameId = parseUUID(raw);
            if (frameId != null) {
                frameIds.add(frameId);
            }
        }
        return new PlacedPhoto(id, owner, s.getString("name", "photo"),
                s.getString("camera", ""), readSpec(s.getConfigurationSection("capture")),
                world, grid, mapIds, frameIds,
                s.getInt("base-x"), s.getInt("base-y"), s.getInt("base-z"));
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
