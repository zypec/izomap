package dev.zypec.izomap.camera;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Izomap entity'lerini ve eşyalarını tanımlamak için kullanılan
 * {@link NamespacedKey} ve {@link PersistentDataContainer} yardımcıları.
 */
public final class CameraKeys {

    /** Display/Interaction entity üzerinde: ait olduğu kameranın UUID'si. */
    private final NamespacedKey cameraId;
    /** Kamera yerleştirme eşyası üzerinde işaretleyici. */
    private final NamespacedKey cameraItem;

    public CameraKeys(Plugin plugin) {
        this.cameraId = new NamespacedKey(plugin, "camera_id");
        this.cameraItem = new NamespacedKey(plugin, "camera_item");
    }

    public void tagCamera(PersistentDataContainer pdc, UUID cameraId) {
        pdc.set(this.cameraId, PersistentDataType.STRING, cameraId.toString());
    }

    public UUID readCameraId(PersistentDataContainer pdc) {
        String raw = pdc.get(cameraId, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void markItem(PersistentDataContainer pdc) {
        pdc.set(cameraItem, PersistentDataType.BOOLEAN, true);
    }

    public boolean isCameraItem(PersistentDataContainer pdc) {
        return Boolean.TRUE.equals(pdc.get(cameraItem, PersistentDataType.BOOLEAN));
    }
}
