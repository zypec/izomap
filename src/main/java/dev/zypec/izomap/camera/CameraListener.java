package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.place.Protection;
import dev.zypec.izomap.render.PreviewManager;
import dev.zypec.izomap.ui.CameraDialogs;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
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

    /**
     * Right-click: raise the property, or switch it while sneaking.
     *
     * <p>A click on an armor stand reaches the server as an "interact at" for each hand,
     * then possibly a plain "interact" too; only the main hand's "at" acts, and every
     * other one is swallowed so one click never counts twice.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractAtEntityEvent event) {
        var camera = cameraOf(event.getRightClicked());
        if (camera == null) return;

        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;

        var player = event.getPlayer();
        if (!Permissions.edit(player, camera)) {
            watch(player, camera);
            return;
        }
        if (!plugin.preview().claimEditor(player, camera)) return;
        if (openedPreview(player, camera)) return;

        if (player.isSneaking()) {
            camera.editProperty(camera.editProperty().next());
            plugin.preview().showStatus(camera, player);
            return;
        }
        adjust(camera, +1, player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlainInteract(PlayerInteractEntityEvent event) {
        if (cameraOf(event.getRightClicked()) != null)
            event.setCancelled(true);
    }

    // An empty-handed click would otherwise take or give the stand's equipment.
    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (cameraOf(event.getRightClicked()) != null)
            event.setCancelled(true);
    }

    /**
     * Middle click opens the capture screen, the way shift + left click does. Only an
     * entity with a pick result reports one, which is why the click box is an armor
     * stand.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPick(PlayerPickEntityEvent event) {
        var camera = cameraOf(event.getEntity());
        if (camera == null) return;

        event.setCancelled(true);
        var player = event.getPlayer();
        if (!Permissions.edit(player, camera)) {
            watch(player, camera);
            return;
        }
        if (!plugin.preview().claimEditor(player, camera)) return;
        dialogs.openCaptureDialog(player, camera);
    }

    // Nothing hurts a click box: explosions, fire, lava, or a creative player's swing.
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent) && cameraOf(event.getEntity()) != null)
            event.setCancelled(true);
    }

    // Left-click: lower.
    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        var camera = cameraOf(event.getEntity());
        if (camera == null) return;

        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) return;

        if (!Permissions.edit(player, camera)) {
            watch(player, camera);
            return;
        }
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
            } else if (id.equals(camera.interactionEntityId())) {
                if (entity instanceof ArmorStand box) {
                    manager.applyClickBoxSize(box);
                } else {
                    // A camera from before the click box was an armor stand.
                    manager.replaceClickBox(camera, entity);
                }
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

        var target = clicked.getRelative(face);
        if (!Protection.mayBuild(player, target, held)) {
            plugin.messages().send(player, "camera.protected");
            return;
        }
        var anchor = target.getLocation().add(0.5, 0.0, 0.5);
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

    /**
     * What a click on somebody else's camera does: it opens the live view, and once that
     * is open, says whose camera it is.
     */
    private void watch(Player player, Camera camera) {
        switch (plugin.preview().join(player, camera)) {
            case JOINED -> {
                plugin.preview().refresh(camera);
                plugin.messages().send(player, "preview.started",
                        Placeholder.unparsed("camera", camera.name()));
                plugin.preview().showStatus(camera, player);
            }
            case OFFHAND_FULL -> plugin.messages().send(player, "preview.offhand-full");
            case ALREADY -> {
                var owner = plugin.getServer().getOfflinePlayer(camera.owner()).getName();
                plugin.messages().send(player, "camera.view-only",
                        Placeholder.unparsed("owner", owner != null ? owner : "?"),
                        Placeholder.unparsed("name", camera.name()));
            }
        }
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
                    // A level camera is where the isometric frame falls apart and where
                    // depth is most worth using deliberately, so the way out is offered
                    // next to the warning — but only to somebody who has it and is not
                    // already using it.
                    if (!camera.focusEnabled() && Permissions.focus(player)) {
                        plugin.messages().send(player, "camera.shallow-pitch-focus");
                    }
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
                var to = clampToWorld(camera.anchor().add(step));
                // Asked only on entering a new block: within one the answer cannot change.
                if (!to.getBlock().equals(camera.anchor().getBlock())
                    && !Protection.mayBuild(player, to.getBlock(), manager.createCameraItem())) {
                    plugin.messages().send(player, "camera.protected");
                    return;
                }
                camera.aimY(Double.NaN);
                manager.reposition(camera, to);
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

    /**
     * The camera this entity is the click box of, or {@code null}.
     */
    private Camera cameraOf(Entity entity) {
        return entity instanceof ArmorStand || entity instanceof Interaction
                ? manager.byInteractionEntity(entity.getUniqueId())
                : null;
    }

    private String defaultName(Player player) {
        return "cam-" + (manager.ownedCount(player.getUniqueId()) + 1);
    }
}
