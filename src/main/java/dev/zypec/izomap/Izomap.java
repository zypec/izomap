package dev.zypec.izomap;

import dev.zypec.izomap.camera.CameraCommand;
import dev.zypec.izomap.camera.CameraKeys;
import dev.zypec.izomap.camera.CameraListener;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.config.ConfigManager;
import dev.zypec.izomap.config.Messages;
import dev.zypec.izomap.map.MapService;
import dev.zypec.izomap.map.PhotoFrameListener;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.render.BlockColorTable;
import dev.zypec.izomap.render.PreviewManager;
import dev.zypec.izomap.render.RenderService;
import dev.zypec.izomap.ui.CameraDialogs;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Izomap ana eklenti sınıfı.
 *
 * <p>Sorumluluk alanı yalnızca yaşam döngüsü (enable/disable) ve alt sistemlerin
 * (config, mesajlar, depolama, kamera, render, ui) bir araya getirilmesidir. Ağır
 * işlemler ilgili yöneticilere ve asenkron zamanlayıcılara devredilir.</p>
 */
public final class Izomap extends JavaPlugin {

    private ConfigManager configManager;
    private Messages messages;
    private CameraManager cameraManager;
    private RenderService renderService;
    private MapService mapService;
    private PhotoManager photoManager;
    private PreviewManager previewManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.messages = new Messages(this);
        this.messages.reload();

        CameraKeys cameraKeys = new CameraKeys(this);
        this.cameraManager = new CameraManager(this, cameraKeys);

        BlockColorTable colorTable = BlockColorTable.load(this);
        this.renderService = new RenderService(this, colorTable);
        this.mapService = new MapService(this);
        this.previewManager = new PreviewManager(this, renderService, mapService);
        this.photoManager = new PhotoManager(this, cameraManager, renderService, mapService);

        CameraDialogs cameraDialogs = new CameraDialogs(this, cameraManager, photoManager);

        // Kameraları yükle; tamamlanınca fotoğrafları yükle (yeniden render kameralara bağlı).
        this.cameraManager.load().thenRun(() -> photoManager.load());

        getServer().getPluginManager().registerEvents(
                new CameraListener(this, cameraManager, cameraKeys, cameraDialogs), this);
        getServer().getPluginManager().registerEvents(new PhotoFrameListener(photoManager), this);
        getServer().getPluginManager().registerEvents(previewManager, this);
        CameraCommand.register(this, cameraManager, renderService, mapService, photoManager, cameraDialogs);

        getLogger().info("Izomap etkinleştirildi (Paper API 26.2, Java 25).");
    }

    @Override
    public void onDisable() {
        if (photoManager != null) {
            photoManager.saveSync();
        }
        if (cameraManager != null) {
            cameraManager.saveSync();
        }
        getLogger().info("Izomap devre dışı bırakıldı.");
    }

    /** Tüm alt sistemlerin yapılandırmasını yeniden yükler. */
    public void reloadAll() {
        this.configManager.reload();
        this.messages.reload();
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
