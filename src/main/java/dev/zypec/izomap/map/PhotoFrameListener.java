package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.ui.PhotoDialogs;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Manages the item frames of placed photos: breaking or attacking any frame takes
 * the whole photo down without dropping items, and rotating is blocked so the image
 * stays intact.
 *
 * <p>A frame is recognized by its {@link PhotoKeys} tag, not by the in-memory record,
 * so protection holds during the window before {@code photos.yml} finishes loading and
 * survives a failed load. Three cases follow from the tag:</p>
 *
 * <ul>
 *   <li><b>Record known</b> – the whole photo comes down, as before.</li>
 *   <li><b>Records aren't loaded yet</b> – the frame is protected and the player is told
 *       to wait, so a half-drawn photo cannot be knocked apart.</li>
 *   <li><b>Records loaded, no match</b> – an orphan left over from a lost record; it is
 *       quietly removed without dropping its map.</li>
 * </ul>
 */
public final class PhotoFrameListener implements Listener {

    private final Izomap plugin;
    private final PhotoManager photos;
    private final PhotoKeys keys;
    private final PhotoDialogs dialogs;

    public PhotoFrameListener(Izomap plugin, PhotoManager photos, PhotoKeys keys, PhotoDialogs dialogs) {
        this.plugin = plugin;
        this.photos = photos;
        this.keys = keys;
        this.dialogs = dialogs;
    }

    // HangingBreakByEntityEvent extends HangingBreakEvent, so one handler covers both.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        var remover = event instanceof HangingBreakByEntityEvent byEntity ? byEntity.getRemover() : null;
        if (handleDamage(frame, remover))
            event.setCancelled(true); // suppress the default drop; removal is ours
    }

    // Attacking a filled frame would pop the item out; block it and remove the photo.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame))
            return;

        if (handleDamage(frame, event.getDamager()))
            event.setCancelled(true);
    }

    /**
     * Right-clicking a photo opens its screen, for the owner and for admins. The click is
     * cancelled either way: it would otherwise rotate the map and turn the picture.
     *
     * <p>Anyone else gets what they got before — the click stops, nothing opens. A menu
     * appearing on every picture in a public gallery is noise, and there is nothing on it
     * a passer-by is allowed to do.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRotate(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !isPhotoFrame(frame))
            return;

        event.setCancelled(true);
        var photo = resolve(frame);
        if (photo == null || !dialogs.mayOpen(event.getPlayer(), photo))
            return;

        // The event fires for both hands; opening on each would show the screen twice.
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        dialogs.openWallDialog(event.getPlayer(), photo);
    }

    /**
     * Reacts to a frame being broken or hit. Returns whether the event should be
     * canceled, i.e., whether the frame was ours.
     */
    private boolean handleDamage(ItemFrame frame, Entity source) {
        if (!isPhotoFrame(frame)) return false;

        var photo = resolve(frame);
        if (photo != null) {
            // Breaking a frame takes the photo off the wall; it stays in the camera's
            // list, so a misplaced swing costs a re-hang rather than the picture.
            photos.unplace(photo);
            if (source instanceof Player player)
                plugin.messages().send(player, "map.photo-taken-down",
                        Placeholder.unparsed("name", photo.name()));
            return true;
        }

        if (!photos.isLoaded()) {
            if (source instanceof Player player) {
                plugin.messages().send(player, "map.still-loading");
            }
            return true;
        }

        // No record can ever claim this frame again; take it down instead of leaving
        // an indestructible leftover on the wall.
        frame.remove();
        if (source instanceof Player player)
            plugin.messages().send(player, "map.orphan-frame");
        return true;
    }

    private boolean isPhotoFrame(ItemFrame frame) {
        return keys.isPhotoFrame(frame.getPersistentDataContainer())
               || photos.findByFrame(frame.getUniqueId()).isPresent();
    }

    /**
     * The photo a frame belongs to: by tag first, then by the frame's own id.
     */
    private Photo resolve(ItemFrame frame) {
        var taggedId = keys.readPhotoId(frame.getPersistentDataContainer());
        if (taggedId != null) {
            var tagged = photos.byId(taggedId).orElse(null);
            if (tagged != null) {
                return tagged;
            }
        }
        // Frames placed before tagging existed are only findable through their record.
        return photos.findByFrame(frame.getUniqueId()).orElse(null);
    }
}
