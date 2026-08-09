package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.storage.YamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Kameraların {@code cameras.yml} dosyasına asenkron kalıcılığı.
 *
 * <p>Kamera sayısı oyuncu başına düşük tutulduğu için tüm koleksiyon
 * her kaydetmede toplu olarak serialize edilir; bu, kısmi güncelleme
 * mantığından daha basit ve daha az hataya açıktır.</p>
 */
public final class CameraStorage extends YamlStorage {

    public CameraStorage(Izomap plugin) {
        super(plugin, "cameras.yml");
    }

    /** Tüm koleksiyonu belleğe serialize edip asenkron kaydeder. */
    public void saveAll(Collection<Camera> cameras) {
        setData(serialize(cameras));
        save();
    }

    /** Kapanışta senkron kaydeder. */
    public void saveAllSync(Collection<Camera> cameras) {
        setData(serialize(cameras));
        saveNow();
    }

    private FileConfiguration serialize(Collection<Camera> cameras) {
        FileConfiguration cfg = new YamlConfiguration();
        for (Camera c : cameras) {
            String base = "cameras." + c.id();
            Location a = c.anchor();
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
        }
        return cfg;
    }

    /**
     * Yüklenmiş veriyi {@link Camera} nesnelerine dönüştürür.
     * <b>Ana iş parçacığında</b> çağrılmalıdır (dünya çözümlemesi için).
     */
    public List<Camera> readAll() {
        List<Camera> result = new ArrayList<>();
        FileConfiguration cfg = data();
        if (cfg == null) {
            return result;
        }
        ConfigurationSection root = cfg.getConfigurationSection("cameras");
        if (root == null) {
            return result;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Camera camera = readOne(key, s);
            if (camera != null) {
                result.add(camera);
            }
        }
        return result;
    }

    private Camera readOne(String key, ConfigurationSection s) {
        UUID id = parseUuid(key);
        UUID owner = parseUuid(s.getString("owner"));
        String worldRaw = s.getString("world");
        if (id == null || owner == null || worldRaw == null) {
            return null;
        }
        World world = Bukkit.getWorld(UUID.fromString(worldRaw));
        if (world == null) {
            plugin.getLogger().warning(
                    "Kamera '" + key + "' atlandı: dünya yüklü değil (" + worldRaw + ").");
            return null;
        }
        Location anchor = new Location(
                world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("cam-yaw"), (float) s.getDouble("cam-pitch"));

        Camera camera = new Camera(id, owner, s.getString("name", "camera"), anchor);
        camera.displayEntityId(parseUuid(s.getString("display-entity")));
        camera.interactionEntityId(parseUuid(s.getString("interaction-entity")));
        camera.camYaw((float) s.getDouble("cam-yaw"));
        camera.camPitch((float) s.getDouble("cam-pitch"));
        // Eski kayıtlarda anahtar "scale" idi; anlamı aynı olduğu için doğrudan okunur.
            camera.zoom((float) s.getDouble("zoom", s.getDouble("scale", 1.0)));
        camera.aspectRatio(AspectRatio.fromString(s.getString("aspect-ratio"), AspectRatio.RATIO_1_1));
        camera.colorFilter(ColorFilter.fromString(s.getString("color-filter"), ColorFilter.ORIGINAL));
        return camera;
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
