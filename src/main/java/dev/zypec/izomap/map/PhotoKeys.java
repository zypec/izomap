package dev.zypec.izomap.map;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Marks the item frames of a placed photo on the entity itself.
 *
 * <p>Frame protection used to depend on the in-memory record, which leaves the frames
 * unprotected until {@code maps.yml} has loaded, and permanently so if that load ever
 * fails. The tag travels with the entity, so a frame is recognizable as ours before
 * any record exists — and an orphaned frame stays recognizable after its record is
 * gone.</p>
 */
public final class PhotoKeys {

    private final NamespacedKey photoId;
    private final NamespacedKey tileIndex;

    public PhotoKeys(Plugin plugin) {
        this.photoId = new NamespacedKey(plugin, "photo_id");
        this.tileIndex = new NamespacedKey(plugin, "tile_index");
    }

    public void tagFrame(PersistentDataContainer pdc, UUID photoId, int tileIndex) {
        pdc.set(this.photoId, PersistentDataType.STRING, photoId.toString());
        pdc.set(this.tileIndex, PersistentDataType.INTEGER, tileIndex);
    }

    /**
     * Whether the entity belongs to a photo, whatever its record says.
     */
    public boolean isPhotoFrame(PersistentDataContainer pdc) {
        return pdc.has(photoId, PersistentDataType.STRING);
    }

    /**
     * Photo the frame belongs to, or {@code null} when untagged or malformed.
     */
    public UUID readPhotoId(PersistentDataContainer pdc) {
        var raw = pdc.get(photoId, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Tile position of the frame within the grid, or {@code -1}.
     */
    public int readTileIndex(PersistentDataContainer pdc) {
        var value = pdc.get(tileIndex, PersistentDataType.INTEGER);
        return value != null ? value : -1;
    }
}
