package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.storage.YamlStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Asynchronous persistence of placed photos to {@code maps.yml}. */
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
        FileConfiguration cfg = new YamlConfiguration();
        for (PlacedPhoto p : photos) {
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
        }
        return cfg;
    }

    /** Turns loaded data into {@link PlacedPhoto} objects. */
    public List<PlacedPhoto> readAll() {
        List<PlacedPhoto> result = new ArrayList<>();
        FileConfiguration cfg = data();
        if (cfg == null) {
            return result;
        }
        ConfigurationSection root = cfg.getConfigurationSection("photos");
        if (root == null) {
            return result;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            PlacedPhoto photo = readOne(key, s);
            if (photo != null) {
                result.add(photo);
            }
        }
        return result;
    }

    private PlacedPhoto readOne(String key, ConfigurationSection s) {
        UUID id = parseUuid(key);
        UUID owner = parseUuid(s.getString("owner"));
        UUID world = parseUuid(s.getString("world"));
        GridOption grid = GridOption.parse(s.getString("grid"));
        if (id == null || owner == null || world == null || grid == null) {
            return null;
        }
        List<Integer> mapIds = s.getIntegerList("map-ids");
        List<UUID> frameIds = new ArrayList<>();
        for (String raw : s.getStringList("frame-ids")) {
            UUID frameId = parseUuid(raw);
            if (frameId != null) {
                frameIds.add(frameId);
            }
        }
        return new PlacedPhoto(id, owner, s.getString("name", "photo"),
                s.getString("camera", ""), world, grid, mapIds, frameIds,
                s.getInt("base-x"), s.getInt("base-y"), s.getInt("base-z"));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
