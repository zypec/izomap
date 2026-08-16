package dev.zypec.izomap.config;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.ShadingSpec;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed access to {@code config.yml}, keeping key names and defaults in one place.
 */
public final class ConfigManager {

    private final Izomap plugin;

    /**
     * A {@code frame-shift} at or above these lifts the whole frame above the camera,
     * which yields fully empty photos on cameras that look horizontal.
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
        var shift = frameShift();
        if (shift >= RISKY_FRAME_SHIFT) {
            plugin.messages().warn("log.risky-frame-shift",
                    Placeholder.unparsed("value", String.valueOf(shift)));
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
     */
    public int maxCaptureArea() {
        return clamp(cfg().getInt("settings.max-capture-area", 512), 64, 4096);
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
     * Whether every capture logs how long it took, split into copying the chunks and
     * walking the rays. The measurement new render stages are compared against; off by
     * default because a live preview renders continuously.
     */
    public boolean renderTiming() {
        return cfg().getBoolean("settings.render-timing", false);
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

    /**
     * Whether the few blocks whose vanilla map colour does not resemble them are
     * corrected. Off makes a photo match a vanilla map exactly, tuff included.
     */
    public boolean correctVanillaColors() {
        return cfg().getBoolean("settings.correct-vanilla-colors", true);
    }

    public int maxCamerasPerPlayer() {
        return cfg().getInt("settings.max-cameras-per-player", 5);
    }

    public int maxPhotosPerCamera() {
        return cfg().getInt("settings.max-photos-per-camera", 5);
    }

    /**
     * Map tiles one photo may cover, for players holding no
     * {@code izomap.max_map_tiles.<n>} of their own.
     *
     * <p>The render cost scales with this and nothing else scales as fast: a 16x9 grid is
     * 144 tiles against a 1x1's one. The default admits every small grid (up to 4x3) and
     * keeps the three big ones behind a permission.</p>
     */
    public int maxMapTiles() {
        return clamp(cfg().getInt("settings.max-map-tiles", 12), 1, 4096);
    }

    // --- camera ---

    public String displayType() {
        return cfg().getString("camera.display-type", "ITEM_DISPLAY");
    }

    public String modelMaterial() {
        return cfg().getString("camera.model-material", "SPYGLASS");
    }

    /**
     * How an {@code ITEM_DISPLAY} model is posed, as an
     * {@code ItemDisplay.ItemDisplayTransform} name. Ignored by {@code BLOCK_DISPLAY}.
     */
    public String itemDisplayTransform() {
        return cfg().getString("camera.item-display-transform", "NONE");
    }

    /**
     * Side of the click box at {@code model-scale} 1.0; the real box scales with the
     * model so a resized camera stays clickable exactly where it looks.
     */
    public double interactionSize() {
        return clamp(cfg().getDouble("camera.interaction-size", 0.6), 0.1, 3.0);
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

    /** Distance one click moves the camera in the movement edit modes, in blocks. */
    public double moveStep() {
        return clamp(cfg().getDouble("camera.move-step", 1.0), 0.05, 16.0);
    }

    /**
     * Pitch of a newly placed camera (degrees, positive looks down). 30 is the
     * classic isometric angle, 0 is horizontal.
     */
    public double defaultPitch() {
        return clamp(cfg().getDouble("camera.default-pitch", 30.0), -90.0, 90.0);
    }

    /** Model rotation offset around X (pitch), degrees. */
    public double modelRotationX() {
        return cfg().getDouble("camera.model-rotation.x", 0.0);
    }

    /** Model rotation offset around Y (yaw), degrees. */
    public double modelRotationY() {
        return cfg().getDouble("camera.model-rotation.y", 0.0);
    }

    /** Model rotation offset around Z (roll), degrees. */
    public double modelRotationZ() {
        return cfg().getDouble("camera.model-rotation.z", 0.0);
    }

    /**
     * How long a camera stays locked to its editor after their last click.
     *
     * <p>Only one player may adjust a camera at a time; without an expiry, someone who
     * clicked once and walked away would keep it locked for good.</p>
     */
    public int editLockSeconds() {
        return clamp(cfg().getInt("camera.edit-lock-seconds", 30), 1, 3600);
    }

    // --- camera hologram ---

    /** Whether cameras carry an info hologram above their model. */
    public boolean hologramEnabled() {
        return cfg().getBoolean("camera.hologram.enabled", true);
    }

    /**
     * Height of the hologram above the anchor at {@code model-scale} 1.0; the real
     * offset scales with the model, as the click box does.
     */
    public double hologramOffsetY() {
        return clamp(cfg().getDouble("camera.hologram.offset-y", 0.6), -4.0, 8.0);
    }

    public double hologramViewRange() {
        return clamp(cfg().getDouble("camera.hologram.view-range", 1.0), 0.1, 10.0);
    }

    /** How the hologram turns, as a {@code Display.Billboard} name. */
    public String hologramBillboard() {
        return cfg().getString("camera.hologram.billboard", "CENTER");
    }

    /**
     * Hologram background: {@code default}, {@code none}/{@code transparent}, or an
     * {@code #AARRGGBB}/{@code #RRGGBB} color.
     */
    public String hologramBackground() {
        return cfg().getString("camera.hologram.background", "default");
    }

    // --- dialog ---

    /**
     * How wide the capture screen's body and inputs are drawn.
     *
     * <p>The info line names the camera and lists five settings, which wraps to three
     * rows at the vanilla width and reads badly. Minecraft accepts up to 1024.</p>
     */
    public int dialogBodyWidth() {
        return clamp(cfg().getInt("dialog.body-width", 380), 100, 1024);
    }

    // --- photo ---

    /**
     * How small a {@code FAST} photo is traced before being scaled back up. The ray
     * count falls with its square, and the image softens by the same amount.
     *
     * <p>The {@code photo.style.soft-scale} key is read as the default; it named the
     * same number while the style was called SOFT.</p>
     */
    public double styleFastScale() {
        return clamp(cfg().getDouble("photo.style.fast-scale",
                cfg().getDouble("photo.style.soft-scale", 0.5)), 0.1, 1.0);
    }

    /**
     * Whether the sky pales towards the horizon down the frame instead of being flat.
     */
    public boolean skyGradient() {
        return cfg().getBoolean("photo.sky.gradient", true);
    }

    /**
     * How far the bottom row of a gradient sky moves towards white.
     */
    public double skyHorizonBlend() {
        return clamp(cfg().getDouble("photo.sky.horizon-blend", 0.45), 0.0, 1.0);
    }

    /**
     * How far a dithered sky pixel may stray from the true color, in channel steps.
     * Zero paints flat bands instead.
     */
    public double skyDither() {
        return clamp(cfg().getDouble("photo.sky.dither", 24.0), 0.0, 128.0);
    }

    /** Sky color at the given keyframe, as 0xRRGGBB. */
    public int skyColor(String keyframe, int fallback) {
        return parseRgb(cfg().getString("photo.sky.colors." + keyframe), fallback);
    }

    /**
     * Reads a {@code #RRGGBB} value; a malformed one falls back rather than failing a
     * capture over a colour.
     */
    private static int parseRgb(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;

        var hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * What may darken a surface beyond the face it shows. Every technique defaults off:
     * they change the picture, so they are the server's taste rather than a default.
     *
     * <p>The two light thresholds are clamped into order as well as into range: with
     * {@code dark-below} above {@code dim-below} the second one could never be reached
     * and half the setting would quietly do nothing.</p>
     */
    public ShadingSpec shading() {
        var dimBelow = clamp(cfg().getInt("photo.shading.light-dim-below", 8), 0, 15);
        var darkBelow = clamp(cfg().getInt("photo.shading.light-dark-below", 4), 0, 15);
        return new ShadingSpec(
                cfg().getBoolean("photo.shading.sun-shadow", false),
                (float) cfg().getDouble("photo.shading.sun-yaw", 135.0),
                (float) clamp(cfg().getDouble("photo.shading.sun-pitch", 60.0), 1.0, 89.0),
                clamp(cfg().getInt("photo.shading.shadow-distance", 24), 1, 256),
                cfg().getBoolean("photo.shading.ambient-occlusion", false),
                cfg().getBoolean("photo.shading.block-light", false),
                dimBelow,
                Math.min(darkBelow, dimBelow));
    }

    public String defaultAspectRatio() {
        return cfg().getString("photo.default-aspect-ratio", "RATIO_1_1");
    }

    /**
     * Vertical area the frame covers (blocks), i.e. zoom.
     *
     * <p>Under orthographic projection this alone sets object size; distance to the
     * subject does not. The camera's own scale divides it.</p>
     */
    public double frameHeight() {
        return clamp(cfg().getDouble("photo.frame-height", 48.0), 4.0, 512.0);
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

    /**
     * How long a ghost preview waits for the player to commit before giving up. A
     * session that outlives the player's attention would keep a wall of entities alive
     * for nothing.
     */
    public int placementTimeoutSeconds() {
        return clamp(cfg().getInt("placement.timeout-seconds", 60), 5, 600);
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
