package dev.zypec.izomap.map;

import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.UUID;

/**
 * Yerleştirilmiş fotoğrafların ItemFrame'lerini yönetir:
 * <ul>
 *   <li>Bir çerçeve kırıldığında (patlama/fizik/oyuncu) <b>tüm fotoğraf</b> kalkar
 *       ve hiçbir eşya düşmez.</li>
 *   <li>Çerçeveye saldırı (eşya çıkarma) engellenir ve fotoğraf kaldırılır.</li>
 *   <li>Sağ tık ile döndürme engellenir (görsel bozulmasın).</li>
 * </ul>
 */
public final class PhotoFrameListener implements Listener {

    private final PhotoManager photos;

    public PhotoFrameListener(PhotoManager photos) {
        this.photos = photos;
    }

    // HangingBreakByEntityEvent, HangingBreakEvent'i genişletir; tek dinleyici ikisini de yakalar.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        if (removePhotoOf(frame.getUniqueId())) {
            event.setCancelled(true); // varsayılan drop'u engelle; kaldırmayı kendimiz yaparız
        }
    }

    // Çerçeveye saldırı: dolu çerçevede eşyayı çıkarır. Engelle ve fotoğrafı kaldır.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        if (removePhotoOf(frame.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Sağ tık: normalde haritayı döndürür. Fotoğraf çerçevelerinde engelle.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRotate(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame
                && photos.findByFrame(frame.getUniqueId()).isPresent()) {
            event.setCancelled(true);
        }
    }

    private boolean removePhotoOf(UUID frameId) {
        PlacedPhoto photo = photos.findByFrame(frameId).orElse(null);
        if (photo == null) {
            return false;
        }
        photos.remove(photo);
        return true;
    }
}
