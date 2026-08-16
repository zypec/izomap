package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.CaptureSpec;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.ShadingSpec;
import dev.zypec.izomap.storage.YamlStorage;
import dev.zypec.izomap.util.Ids;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous persistence of photos to {@code photos.yml}.
 *
 * <h2>Why one file and not two</h2>
 *
 * <p>A photo and the wall it hangs on are one record. Splitting them across two files
 * would mean every read and write had to join them and every failure could leave one
 * half behind, so the placement is an optional block <i>inside</i> the photo instead. A
 * photo with no {@code placement} section has been shot but not put up.</p>
 */
public final class PhotoStorage extends YamlStorage {

    public PhotoStorage(Izomap plugin) {
        super(plugin, "photos.yml");
    }

    public void saveAll(Collection<Photo> photos) {
        setData(serialize(photos));
        save();
    }

    public void saveAllSync(Collection<Photo> photos) {
        setData(serialize(photos));
        saveNow();
    }

    private FileConfiguration serialize(Collection<Photo> photos) {
        var cfg = new YamlConfiguration();
        for (var p : photos) {
            var base = "photos." + p.id();
            cfg.set(base + ".owner", p.owner().toString());
            cfg.set(base + ".name", p.name());
            cfg.set(base + ".camera", p.cameraName());
            cfg.set(base + ".grid", p.grid().label());
            if (p.frameId() != null) {
                cfg.set(base + ".frame.id", p.frameId());
                cfg.set(base + ".frame.embedded", p.frameEmbedded());
            }
            writeSpec(cfg, base + ".capture", p.spec());
            writePlacement(cfg, base + ".placement", p.placement());
        }
        return cfg;
    }

    private static void writePlacement(FileConfiguration cfg, String base, Placement placement) {
        if (placement == null)
            return;

        cfg.set(base + ".world", placement.worldId().toString());
        cfg.set(base + ".map-ids", new ArrayList<>(placement.mapIds()));
        cfg.set(base + ".frame-ids", placement.frameIds().stream().map(UUID::toString).toList());
        cfg.set(base + ".base-x", placement.baseX());
        cfg.set(base + ".base-y", placement.baseY());
        cfg.set(base + ".base-z", placement.baseZ());
    }

    private static Placement readPlacement(ConfigurationSection s) {
        if (s == null) return null;

        var world = Ids.parse(s.getString("world"));
        if (world == null) return null;

        List<UUID> frameIds = new ArrayList<>();
        for (var raw : s.getStringList("frame-ids")) {
            var frameId = Ids.parse(raw);
            if (frameId != null) {
                frameIds.add(frameId);
            }
        }
        return new Placement(world, s.getIntegerList("map-ids"), frameIds,
                s.getInt("base-x"), s.getInt("base-y"), s.getInt("base-z"));
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
        cfg.set(base + ".color-filter", spec.colorFilter().id());
        cfg.set(base + ".style", spec.style().name());
        cfg.set(base + ".sky-argb", spec.skyArgb());
        cfg.set(base + ".shading.sun-shadow", spec.shading().sunShadow());
        cfg.set(base + ".shading.sun-yaw", spec.shading().sunYaw());
        cfg.set(base + ".shading.sun-pitch", spec.shading().sunPitch());
        cfg.set(base + ".shading.shadow-distance", spec.shading().shadowDistance());
        cfg.set(base + ".shading.ambient-occlusion", spec.shading().ambientOcclusion());
        cfg.set(base + ".shading.block-light", spec.shading().blockLight());
        cfg.set(base + ".shading.light-dim-below", spec.shading().dimBelow());
        cfg.set(base + ".shading.light-dark-below", spec.shading().darkBelow());
        cfg.set(base + ".frame-height", spec.frameHeight());
        cfg.set(base + ".frame-shift", spec.frameShift());
        cfg.set(base + ".supersampling", spec.supersampling());
        cfg.set(base + ".max-capture-area", spec.maxCaptureArea());
        cfg.set(base + ".render-depth", spec.renderDepth());
    }

    /**
     * Reads the shading; photos written before it existed had none.
     */
    private static ShadingSpec readShading(ConfigurationSection s) {
        if (s == null) return ShadingSpec.NONE;

        return new ShadingSpec(
                s.getBoolean("sun-shadow", false),
                (float) s.getDouble("sun-yaw", 135.0),
                (float) s.getDouble("sun-pitch", 60.0),
                s.getInt("shadow-distance", 24),
                s.getBoolean("ambient-occlusion", false),
                s.getBoolean("block-light", false),
                s.getInt("light-dim-below", 8),
                s.getInt("light-dark-below", 4));
    }

    /**
     * Reads a capture spec; {@code null} for records written before it existed.
     */
    private CaptureSpec readSpec(ConfigurationSection s) {
        if (s == null) return null;

        var world = Ids.parse(s.getString("world"));
        if (world == null) return null;

        return new CaptureSpec(world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"),
                (float) s.getDouble("zoom", 1.0),
                plugin.filters().byId(s.getString("color-filter"), ColorFilter.ORIGINAL),
                PhotoStyle.fromString(s.getString("style"), PhotoStyle.SHARP),
                s.getInt("sky-argb", 0),
                readShading(s.getConfigurationSection("shading")),
                s.getDouble("frame-height", 48.0), s.getDouble("frame-shift", 0.0),
                s.getInt("supersampling", 1), s.getInt("max-capture-area", 512),
                s.getInt("render-depth", 64));
    }

    /**
     * Every photo in the file.
     */
    public List<Photo> readAll() {
        Map<UUID, Photo> byId = new LinkedHashMap<>();
        collect(data(), byId);
        return new ArrayList<>(byId.values());
    }

    private void collect(FileConfiguration cfg, Map<UUID, Photo> into) {
        if (cfg == null) return;

        var root = cfg.getConfigurationSection("photos");
        if (root == null) return;

        for (var key : root.getKeys(false)) {
            var s = root.getConfigurationSection(key);
            if (s == null) continue;

            var id = Ids.parse(key);
            if (id == null || into.containsKey(id)) continue;

            var photo = readOne(id, s);
            if (photo != null) {
                into.put(id, photo);
            }
        }
    }

    private Photo readOne(UUID id, ConfigurationSection s) {
        var owner = Ids.parse(s.getString("owner"));
        var grid = GridOption.parse(s.getString("grid"));
        if (owner == null || grid == null)
            return null;

        var frame = s.getConfigurationSection("frame");
        return new Photo(id, owner, s.getString("name", "photo"), s.getString("camera", ""),
                readSpec(s.getConfigurationSection("capture")), grid,
                readPlacement(s.getConfigurationSection("placement")),
                frame == null ? null : frame.getString("id"),
                frame != null && frame.getBoolean("embedded", false));
    }
}
