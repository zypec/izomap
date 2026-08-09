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
 * Manages the item frames of placed photos: breaking or attacking any frame takes
 * the whole photo down without dropping items, and rotating is blocked so the image
 * stays intact.
 */
public final class PhotoFrameListener implements Listener {

    private final PhotoManager photos;

    public PhotoFrameListener(PhotoManager photos) {
        this.photos = photos;
    }

    // HangingBreakByEntityEvent extends HangingBreakEvent, so one handler covers both.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        if (removePhotoOf(frame.getUniqueId())) {
            event.setCancelled(true); // suppress the default drop; removal is ours
        }
    }

    // Attacking a filled frame would pop the item out; block it and remove the photo.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        if (removePhotoOf(frame.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Right click would rotate the map; block it on photo frames.
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
