package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.PreviewManager;
import dev.zypec.izomap.ui.CameraDialogs;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
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

import java.util.Locale;

/**
 * Handles camera interactions: right-click raises the active property, left click
 * lowers it, sneak switches which property is active or opens the capture dialog,
 * and right-clicking a block with the camera item places a new camera.
 *
 * <p>Every gesture on a camera first claims its editor seat, since all of them change
 * shared camera state; a player who cannot get the seat becomes a watcher instead.
 * The gesture itself is only carried out once the player is already watching the
 * camera — see {@link #openedPreview(Player, Camera)}.</p>
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

    // Right-click: raise or switch property while sneaking.
    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction))
            return;

        var camera = manager.byInteractionEntity(interaction.getUniqueId());
        if (camera == null) return;

        event.setCancelled(true);
        var player = event.getPlayer();
        if (!plugin.preview().claimEditor(player, camera)) return;
        if (openedPreview(player, camera)) return;

        if (player.isSneaking()) {
            camera.editProperty(camera.editProperty().next());
            plugin.preview().showStatus(camera, player);
            return;
        }
        adjust(camera, +1, player);
    }

    // Left-click: lower.
    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction interaction)) return;

        if (!(event.getDamager() instanceof Player player)) return;

        var camera = manager.byInteractionEntity(interaction.getUniqueId());
        if (camera == null) return;

        event.setCancelled(true);
        if (!plugin.preview().claimEditor(player, camera)) return;
        if (openedPreview(player, camera)) return;

        // Sneaking opens the capture dialog instead of lowering the property.
        if (player.isSneaking()) {
            dialogs.openCaptureDialog(player, camera);
            return;
        }
        adjust(camera, -1, player);
    }

    /**
     * Reapplies the transform, click box and hologram of cameras whose chunk loads later.
     *
     * <p>Most cameras' chunks are unloaded while {@code cameras.yml} is read, so
     * {@code applyTransform} skips them. Without this hook the entities stay frozen
     * as they were created and {@code model-scale} never takes effect.</p>
     *
     * <p>Entities are matched by id rather than by type: a camera's hologram is a
     * {@link Display} too, and giving it the model's rotation and scale would tip the
     * text over and blow it up.</p>
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        var changed = false;
        for (Entity entity : event.getEntities()) {
            var camera = manager.byId(keys.readCameraId(entity.getPersistentDataContainer()));
            if (camera == null) continue;

            var id = entity.getUniqueId();
            if (id.equals(camera.displayEntityId()) && entity instanceof Display display) {
                manager.applyTransform(camera, display);
                // The model resolving proves the chunk is loaded, so a hologram that
                // cannot be found now is really gone and may be replaced.
                changed |= manager.syncHologram(camera);
            } else if (id.equals(camera.interactionEntityId()) && entity instanceof Interaction interaction) {
                manager.applyInteractionSize(interaction);
            }
        }
        if (changed)
            manager.persist();
    }

    // Right-clicking a block with the camera item places a new camera.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND)
            return;

        var clicked = event.getClickedBlock();
        var face = event.getBlockFace();
        if (clicked == null) return;

        var held = event.getItem();
        if (held == null || !keys.isCameraItem(held.getItemMeta().getPersistentDataContainer()))
            return;

        event.setCancelled(true);
        var player = event.getPlayer();

        var anchor = clicked.getRelative(face).getLocation().add(0.5, 0.0, 0.5);
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        var camera = manager.create(player, defaultName(player), anchor, true);
        if (camera == null) {
            plugin.messages().send(player, "camera.limit-reached",
                    Placeholder.unparsed("limit", String.valueOf(manager.cameraLimitFor(player))));
            return;
        }

        held.subtract();
        plugin.messages().send(player, "camera.created",
                Placeholder.unparsed("name", camera.name()));
        // The hand that placed it is now free, so the live view can go straight into it.
        if (plugin.preview().join(player, camera) == PreviewManager.JoinResult.JOINED) {
            plugin.preview().refresh(camera);
            plugin.messages().send(player, "preview.started",
                    Placeholder.unparsed("camera", camera.name()));
        }
    }

    /**
     * Opens the live view when the player is not watching this camera yet, and reports
     * whether the click was spent doing so.
     *
     * <p>The first click on a camera only opens the preview. Acting on it as well moved
     * the camera before its owner could see what they were moving: whichever property
     * the last session left active jumped a step the moment the map appeared.</p>
     *
     * <p>A player who cannot get a preview — offhand full — keeps editing on the first
     * click, since for them no click would ever be the second one.</p>
     */
    private boolean openedPreview(Player player, Camera camera) {
        if (plugin.preview().join(player, camera) != PreviewManager.JoinResult.JOINED)
            return false;

        plugin.preview().refresh(camera);
        plugin.messages().send(player, "preview.started",
                Placeholder.unparsed("camera", camera.name()));
        plugin.preview().showStatus(camera, player);
        return true;
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
            // Along the player's line of sight, not the camera's: they are looking at
            // the camera while they move it, so pushing it away and pulling it back is
            // the gesture that needs no thinking. Pitch rides along, which is what
            // retires the separate vertical property.
            case MOVE -> {
                var step = player.getEyeLocation().getDirection()
                        .multiply(direction * plugin.config().moveStep());
                manager.reposition(camera, clampToWorld(camera.anchor().add(step)));
            }
        }
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.preview().showStatus(camera, player);
    }

    /**
     * Keeps vertical movement inside the world; entities outside it behave oddly.
     */
    private static Location clampToWorld(Location location) {
        var world = location.getWorld();
        if (world != null)
            location.setY(Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, location.getY())));

        return location;
    }

    private String defaultName(Player player) {
        return "cam-" + (manager.ownedCount(player.getUniqueId()) + 1);
    }
}
