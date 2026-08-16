package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.CaptureSpec;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.storage.YamlStorage;
import dev.zypec.izomap.util.Ids;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
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
 * <p>A photo and the wall it hangs on used to be the same record in {@code maps.yml}.
 * Splitting them across two files would mean every read and write had to join them and
 * every failure could leave one half behind, so the placement became an optional block
 * <i>inside</i> the photo instead. A photo with no {@code placement} section has been
 * shot but not put up.</p>
 *
 * <p>{@code maps.yml} is still read at startup for photos this file does not know yet,
 * so an existing install keeps its walls. The old file is left on disk untouched;
 * merging by id makes rereading it harmless.</p>
 */
public final class PhotoStorage extends YamlStorage {

    private static final String LEGACY_FILE = "maps.yml";

    /**
     * Old {@code maps.yml} contents, read alongside the load and merged in
     * {@link #readAll()}.
     */
    private volatile FileConfiguration legacy;

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

    /**
     * Runs on the load's own thread, which is where the legacy file may be read.
     */
    @Override
    protected void onLoaded(FileConfiguration loaded) {
        var file = new File(plugin.getDataFolder(), LEGACY_FILE);
        this.legacy = file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
    }

    private FileConfiguration serialize(Collection<Photo> photos) {
        var cfg = new YamlConfiguration();
        for (var p : photos) {
            var base = "photos." + p.id();
            cfg.set(base + ".owner", p.owner().toString());
            cfg.set(base + ".name", p.name());
            cfg.set(base + ".camera", p.cameraName());
            cfg.set(base + ".grid", p.grid().label());
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
        cfg.set(base + ".frame-height", spec.frameHeight());
        cfg.set(base + ".frame-shift", spec.frameShift());
        cfg.set(base + ".supersampling", spec.supersampling());
        cfg.set(base + ".max-capture-area", spec.maxCaptureArea());
        cfg.set(base + ".render-depth", spec.renderDepth());
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
                s.getDouble("frame-height", 48.0), s.getDouble("frame-shift", 0.0),
                s.getInt("supersampling", 1), s.getInt("max-capture-area", 512),
                s.getInt("render-depth", 64));
    }

    /**
     * Every known photo, with anything only {@code maps.yml} still knows merged in.
     */
    public List<Photo> readAll() {
        Map<UUID, Photo> byId = new LinkedHashMap<>();
        collect(data(), false, byId);

        var before = byId.size();
        collect(legacy, true, byId);
        var migrated = byId.size() - before;
        if (migrated > 0) {
            plugin.messages().info("log.photos-migrated",
                    Placeholder.unparsed("count", String.valueOf(migrated)),
                    Placeholder.unparsed("file", LEGACY_FILE));
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Reads one file into the map, keeping whatever is already there. Photos read from
     * the legacy layout always carry a placement: back then a photo only existed
     * because it hung somewhere.
     */
    private void collect(FileConfiguration cfg, boolean legacyLayout, Map<UUID, Photo> into) {
        if (cfg == null) return;

        var root = cfg.getConfigurationSection("photos");
        if (root == null) return;

        for (var key : root.getKeys(false)) {
            var s = root.getConfigurationSection(key);
            if (s == null) continue;

            var id = Ids.parse(key);
            if (id == null || into.containsKey(id)) continue;

            var photo = readOne(id, s, legacyLayout);
            if (photo != null) {
                into.put(id, photo);
            }
        }
    }

    private Photo readOne(UUID id, ConfigurationSection s, boolean legacyLayout) {
        var owner = Ids.parse(s.getString("owner"));
        var grid = GridOption.parse(s.getString("grid"));
        if (owner == null || grid == null)
            return null;

        var placement = legacyLayout ? readPlacement(s) : readPlacement(s.getConfigurationSection("placement"));
        if (legacyLayout && placement == null)
            return null; // a legacy record without a wall is a broken one

        return new Photo(id, owner, s.getString("name", "photo"), s.getString("camera", ""),
                readSpec(s.getConfigurationSection("capture")), grid, placement);
    }
}
