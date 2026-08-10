package dev.zypec.izomap;

import dev.zypec.izomap.camera.CameraCommand;
import dev.zypec.izomap.camera.CameraKeys;
import dev.zypec.izomap.camera.CameraListener;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.config.ConfigManager;
import dev.zypec.izomap.config.Messages;
import dev.zypec.izomap.map.MapService;
import dev.zypec.izomap.map.PhotoFrameListener;
import dev.zypec.izomap.map.PhotoKeys;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.render.BlockColorTable;
import dev.zypec.izomap.render.PreviewManager;
import dev.zypec.izomap.render.RenderService;
import dev.zypec.izomap.ui.CameraDialogs;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Executor;

/**
 * Plugin entry point.
 *
 * <p>Owns only the enable/disable lifecycle and the wiring between subsystems;
 * all real work is delegated to the managers it creates.</p>
 */
public final class Izomap extends JavaPlugin {

    public static final String PLUGIN_ID = "izomap";

    /**
     * Runs work on Paper's async scheduler, for {@link java.util.concurrent.CompletableFuture}
     * chains that must stay off the main thread.
     */
    private final Executor asyncExecutor = task ->
            getServer().getAsyncScheduler().runNow(this, scheduled -> task.run());

    private ConfigManager configManager;
    private Messages messages;
    private CameraManager cameraManager;
    private RenderService renderService;
    private MapService mapService;
    private PhotoManager photoManager;
    private PreviewManager previewManager;

    @Override
    public void onEnable() {
        // Messages first: every subsystem below, ConfigManager included, reports through it.
        this.messages = new Messages(this);
        this.messages.reload();
        this.configManager = new ConfigManager(this);

        var cameraKeys = new CameraKeys(this);
        this.cameraManager = new CameraManager(this, cameraKeys);

        var colorTable = BlockColorTable.load(this);
        this.renderService = new RenderService(this, colorTable);
        this.mapService = new MapService(this);
        this.previewManager = new PreviewManager(this, renderService, mapService);
        var photoKeys = new PhotoKeys(this);
        this.photoManager = new PhotoManager(this, cameraManager, renderService, mapService, photoKeys);

        var cameraDialogs = new CameraDialogs(this, cameraManager, photoManager);

        // Photos load from their own cache, but a lost cache falls back to the source
        // camera, so cameras still have to be there first.
        this.cameraManager.load().thenRun(() -> photoManager.load());

        getServer().getPluginManager().registerEvents(
                new CameraListener(this, cameraManager, cameraKeys, cameraDialogs), this);
        getServer().getPluginManager().registerEvents(
                new PhotoFrameListener(this, photoManager, photoKeys), this);
        getServer().getPluginManager().registerEvents(previewManager, this);
        CameraCommand.register(this, cameraManager, renderService, mapService, photoManager, cameraDialogs);

        messages.info("log.enabled");
    }

    @Override
    public void onDisable() {
        // Plugins are disabled before players are kicked, so the quit handler never
        // runs on shutdown; the preview maps have to be collected here.
        if (previewManager != null)
            previewManager.closeAll();

        if (photoManager != null)
            photoManager.saveSync();

        if (cameraManager != null)
            cameraManager.saveSync();

        if (renderService != null)
            renderService.shutdown();

        messages.info("log.disabled");
    }

    /**
     * Reloads the configuration of every subsystem.
     */
    public void reloadAll() {
        this.messages.reload();
        this.configManager.reload();
        // Visual settings such as the model rotation offset take effect immediately.
        if (cameraManager != null)
            cameraManager.refreshTransforms();
    }

    /**
     * Hops back to the main thread, where block, entity, and {@code MapView} access is
     * the only safe option.
     */
    public void runOnMain(Runnable runnable) {
        getServer().getGlobalRegionScheduler().run(this, task -> runnable.run());
    }

    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    public ConfigManager config() {
        return configManager;
    }

    public Messages messages() {
        return messages;
    }

    public CameraManager cameras() {
        return cameraManager;
    }

    public RenderService render() {
        return renderService;
    }

    public MapService maps() {
        return mapService;
    }

    public PhotoManager photos() {
        return photoManager;
    }

    public PreviewManager preview() {
        return previewManager;
    }
}
