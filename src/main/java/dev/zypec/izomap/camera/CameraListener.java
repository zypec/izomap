package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.ui.CameraDialogs;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Handles camera interactions: right click raises the active property, left click
 * lowers it, sneak switches which property is active or opens the capture dialog,
 * and right clicking a block with the camera item places a new camera.
 *
 * <p>Every gesture on a camera first claims its editor seat, since all of them change
 * shared camera state; a player who cannot get the seat becomes a watcher instead.</p>
 */
public final class CameraListener implements Listener {

    /**
     * Below this pitch orthographic rays cannot reach the terrain and the photo
     * splits into sky and a dirt slab, so the player is warned.
     */
    private static final float SHALLOW_PITCH = 10.0f;

    private final Izomap plugin;
    private final CameraManager manager;
    private final CameraKeys keys;
    private final CameraDialogs dialogs;

    public CameraListener(Izomap plugin, CameraManager manager, CameraKeys keys, CameraDialogs dialogs) {
        this.plugin = plugin;
        this.manager = manager;
        this.keys = keys;
        this.dialogs = dialogs;
    }

    // Right click: raise, or switch property while sneaking.
    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        Camera camera = manager.byInteractionEntity(interaction.getUniqueId());
        if (camera == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.preview().claimEditor(player, camera)) {
            return;
        }

        if (player.isSneaking()) {
            camera.editProperty(camera.editProperty().next());
            plugin.preview().showStatus(camera, player);
            return;
        }
        adjust(camera, +1, player);
    }

    // Left click: lower.
    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction interaction)) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Camera camera = manager.byInteractionEntity(interaction.getUniqueId());
        if (camera == null) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.preview().claimEditor(player, camera)) {
            return;
        }

        // Sneaking opens the capture dialog instead of lowering the property.
        if (player.isSneaking()) {
            dialogs.openCaptureDialog(player, camera);
            return;
        }
        adjust(camera, -1, player);
    }

    /**
     * Reapplies the transform of cameras whose chunk loads later.
     *
     * <p>Most cameras' chunks are unloaded while {@code cameras.yml} is read, so
     * {@code applyTransform} skips them. Without this hook the entity stays frozen
     * with the transform it was created with and {@code model-scale} never takes
     * effect.</p>
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof Display display)) {
                continue;
            }
            Camera camera = manager.byId(keys.readCameraId(entity.getPersistentDataContainer()));
            if (camera != null) {
                manager.applyTransform(camera, display);
            }
        }
    }

    // Right clicking a block with the camera item places a new camera.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (clicked == null) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || !keys.isCameraItem(held.getItemMeta().getPersistentDataContainer())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        Location anchor = clicked.getRelative(face).getLocation().add(0.5, 0.0, 0.5);
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        Camera camera = manager.create(player, defaultName(player), anchor);
        if (camera == null) {
            plugin.messages().send(player, "camera.limit-reached",
                    Placeholder.unparsed("limit", String.valueOf(plugin.config().maxCamerasPerPlayer())));
            return;
        }
        held.subtract();
        plugin.messages().send(player, "camera.created",
                Placeholder.unparsed("name", camera.name()));
    }

    private void adjust(Camera camera, int direction, Player player) {
        switch (camera.editProperty()) {
            case YAW -> camera.camYaw((float) (camera.camYaw() + direction * plugin.config().angleStep()));
            case PITCH -> {
                camera.camPitch((float) (camera.camPitch() + direction * plugin.config().angleStep()));
                if (camera.camPitch() < SHALLOW_PITCH) {
                    plugin.messages().send(player, "camera.shallow-pitch",
                            Placeholder.unparsed("pitch",
                                    String.format(Locale.ROOT, "%.0f", camera.camPitch())));
                }
            }
            // Zoom steps multiplicatively so each tick changes it by the same
            // percentage across the whole range.
            case ZOOM -> {
                double step = plugin.config().zoomStep();
                camera.zoom((float) (direction > 0 ? camera.zoom() * step : camera.zoom() / step));
            }
        }
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.preview().showStatus(camera, player);
    }

    private String defaultName(Player player) {
        return "cam-" + (manager.ownedCount(player.getUniqueId()) + 1);
    }
}
