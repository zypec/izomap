package dev.zypec.izomap.config;

import dev.zypec.izomap.Izomap;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml için tipli erişim sağlayan sarmalayıcı.
 *
 * <p>Değerlere doğrudan string anahtarlarla erişmek yerine bu sınıf üzerinden
 * erişilir; böylece anahtar isimleri ve varsayılanlar tek yerde toplanır.</p>
 */
public final class ConfigManager {

    private final Izomap plugin;

    public ConfigManager(Izomap plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    public void reload() {
        plugin.reloadConfig();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // --- settings ---

    public int maxRenderDistance() {
        return cfg().getInt("settings.max-render-distance", 128);
    }

    public int renderThreads() {
        return cfg().getInt("settings.render-threads", 4);
    }

    public int maxCamerasPerPlayer() {
        return cfg().getInt("settings.max-cameras-per-player", 5);
    }

    // --- camera ---

    public String displayType() {
        return cfg().getString("camera.display-type", "ITEM_DISPLAY");
    }

    public String modelMaterial() {
        return cfg().getString("camera.model-material", "SPYGLASS");
    }

    public double scaleStep() {
        return cfg().getDouble("camera.scale-step", 0.25);
    }

    public double angleStep() {
        return cfg().getDouble("camera.angle-step", 15.0);
    }

    /** Model rotasyonuna eklenen yaw düzeltmesi (farklı modeller için kalibrasyon). */
    public double modelYawOffset() {
        return cfg().getDouble("camera.model-yaw-offset", 0.0);
    }

    /** Model rotasyonuna eklenen pitch düzeltmesi. */
    public double modelPitchOffset() {
        return cfg().getDouble("camera.model-pitch-offset", 0.0);
    }

    // --- photo ---

    public String defaultAspectRatio() {
        return cfg().getString("photo.default-aspect-ratio", "RATIO_1_1");
    }

    public int regionSize() {
        return cfg().getInt("photo.region-size", 48);
    }

    public int resolution() {
        return cfg().getInt("photo.resolution", 128);
    }

    public double stepSize() {
        return cfg().getDouble("photo.step-size", 0.25);
    }

    // --- placement ---

    public int placementDistance() {
        return cfg().getInt("placement.distance", 3);
    }

    public boolean invisibleFrames() {
        return cfg().getBoolean("placement.invisible-frames", true);
    }

    public boolean buildBackingWall() {
        return cfg().getBoolean("placement.build-backing-wall", true);
    }

    public String backingMaterial() {
        return cfg().getString("placement.backing-material", "STONE");
    }
}
