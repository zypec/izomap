package dev.zypec.izomap.config;

import dev.zypec.izomap.Izomap;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed access to {@code config.yml}, keeping key names and defaults in one place.
 */
public final class ConfigManager {

    private final Izomap plugin;

    /**
     * A {@code frame-shift} at or above this lifts the whole frame above the camera,
     * which yields fully empty photos on cameras that look horizontally.
     */
    private static final double RISKY_FRAME_SHIFT = 0.25;

    public ConfigManager(Izomap plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        warnRiskySettings();
    }

    public void reload() {
        plugin.reloadConfig();
        warnRiskySettings();
    }

    /**
     * Reports settings that can silently produce empty photos. An existing
     * {@code config.yml} is never overwritten, so an old install keeps running with
     * risky values unless it is told.
     */
    private void warnRiskySettings() {
        double shift = frameShift();
        if (shift >= RISKY_FRAME_SHIFT) {
            plugin.getLogger().warning("photo.frame-shift = " + shift
                    + ": kadraj kameranın üstüne kaydırılmış. Eğimi düşük (yatay ya da yukarı bakan)"
                    + " kameralarda hiçbir ışın araziye inmez ve fotoğraflar BOŞ çıkar."
                    + " Önerilen değer 0.0 (kameranın baktığı nokta kadrajın merkezi olur).");
        }
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // --- settings ---

    /**
     * Side length (blocks) of the widest area a capture may cover.
     *
     * <p>The single cost setting: it bounds both the number of copied chunks and the
     * distance rays may travel. Exceeding it rejects the capture.</p>
     *
     * <p>The legacy {@code settings.max-chunks-per-capture} key is still read and
     * converted to a side length ({@code √chunks × 16}).</p>
     */
    public int maxCaptureArea() {
        int legacyChunks = cfg().getInt("settings.max-chunks-per-capture", 1024);
        int legacyArea = (int) Math.round(Math.sqrt(Math.max(1, legacyChunks)) * 16.0);
        return clamp(cfg().getInt("settings.max-capture-area", legacyArea), 64, 4096);
    }

    /**
     * How many blocks below the ground under the camera the rays still reach; the
     * margin for terrain at the far end of the frame that sits lower than the camera.
     */
    public int renderDepth() {
        return clamp(cfg().getInt("settings.render-depth", 64), 0, 1024);
    }

    /** Number of threads the render is split across (the image is cut into bands). */
    public int renderThreads() {
        return clamp(cfg().getInt("settings.render-threads", 4), 1, 16);
    }

    /**
     * Whether unloaded chunks inside the frame are loaded from disk. Loading is
     * asynchronous and never stalls the main thread.
     */
    public boolean loadMissingChunks() {
        return cfg().getBoolean("settings.load-missing-chunks", true);
    }

    /**
     * Whether chunks that were never generated get generated for a capture.
     * Defaults to {@code false}: taking a photo should not grow the world.
     */
    public boolean generateMissingChunks() {
        return cfg().getBoolean("settings.generate-missing-chunks", false);
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

    /** Zoom step as a <b>multiplier</b>, not an addend: 1.25 means 25% per tick. */
    public double zoomStep() {
        return clamp(cfg().getDouble("camera.zoom-step", 1.25), 1.01, 4.0);
    }

    /**
     * Visual size of the camera model in the world. Unrelated to photo zoom.
     */
    public double modelScale() {
        return clamp(cfg().getDouble("camera.model-scale", 1.0), 0.1, 8.0);
    }

    public double angleStep() {
        return cfg().getDouble("camera.angle-step", 15.0);
    }

    /**
     * Pitch of a newly placed camera (degrees, positive looks down). 30 is the
     * classic isometric angle, 0 is horizontal.
     */
    public double defaultPitch() {
        return clamp(cfg().getDouble("camera.default-pitch", 30.0), -90.0, 90.0);
    }

    /**
     * Model rotation offset around X (pitch), degrees. The legacy
     * {@code camera.model-pitch-offset} key is read as the default.
     */
    public double modelRotationX() {
        return cfg().getDouble("camera.model-rotation.x", cfg().getDouble("camera.model-pitch-offset", 0.0));
    }

    /**
     * Model rotation offset around Y (yaw), degrees. The legacy
     * {@code camera.model-yaw-offset} key is read as the default.
     */
    public double modelRotationY() {
        return cfg().getDouble("camera.model-rotation.y", cfg().getDouble("camera.model-yaw-offset", 0.0));
    }

    /** Model rotation offset around Z (roll), degrees. */
    public double modelRotationZ() {
        return cfg().getDouble("camera.model-rotation.z", 0.0);
    }

    // --- photo ---

    public String defaultAspectRatio() {
        return cfg().getString("photo.default-aspect-ratio", "RATIO_1_1");
    }

    /**
     * Vertical area the frame covers (blocks), i.e. zoom.
     *
     * <p>Under orthographic projection this alone sets object size; distance to the
     * subject does not. The camera's own scale divides it.</p>
     *
     * <p>The legacy {@code photo.region-size} key is read as the default.</p>
     */
    public double frameHeight() {
        double legacy = cfg().getDouble("photo.region-size", 48.0);
        return clamp(cfg().getDouble("photo.frame-height", legacy), 4.0, 512.0);
    }

    /**
     * Vertical shift of the frame relative to the camera, as a ratio of frame height.
     *
     * <p>{@code 0.0} (default) centers the camera in the frame. Positive values move
     * the frame up; {@code 0.5} lifts it entirely above the camera, which produces a
     * fully empty photo on a horizontal camera because no orthographic ray ever
     * reaches the terrain.</p>
     *
     * <p>Dirt slabs from the part of the frame below the camera are solved by ray
     * backoff instead, see {@link dev.zypec.izomap.render.RenderGeometry}.</p>
     */
    public double frameShift() {
        return clamp(cfg().getDouble("photo.frame-shift", 0.0), -1.0, 1.0);
    }

    /** Antialiasing: NxN rays per pixel. 1 disables it; cost grows by N². */
    public int supersampling() {
        return clamp(cfg().getInt("photo.supersampling", 2), 1, 4);
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

    // --- helpers ---

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
