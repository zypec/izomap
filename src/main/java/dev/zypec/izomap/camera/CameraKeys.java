package dev.zypec.izomap.camera;

import dev.zypec.izomap.util.Ids;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * {@link NamespacedKey} and {@link PersistentDataContainer} helpers that identify
 * Izomap entities and items.
 */
public final class CameraKeys {

    /**
     * On a display/interaction entity: the UUID of the camera it belongs to.
     */
    private final NamespacedKey cameraId;
    /**
     * Marker on the camera placement item.
     */
    private final NamespacedKey cameraItem;

    public CameraKeys(Plugin plugin) {
        this.cameraId = new NamespacedKey(plugin, "camera_id");
        this.cameraItem = new NamespacedKey(plugin, "camera_item");
    }

    public void tagCamera(PersistentDataContainer pdc, UUID cameraId) {
        pdc.set(this.cameraId, PersistentDataType.STRING, cameraId.toString());
    }

    public UUID readCameraId(PersistentDataContainer pdc) {
        return Ids.parse(pdc.get(cameraId, PersistentDataType.STRING));
    }

    public void markItem(PersistentDataContainer pdc) {
        pdc.set(cameraItem, PersistentDataType.BOOLEAN, true);
    }

    public boolean isCameraItem(PersistentDataContainer pdc) {
        return Boolean.TRUE.equals(pdc.get(cameraItem, PersistentDataType.BOOLEAN));
    }
}
