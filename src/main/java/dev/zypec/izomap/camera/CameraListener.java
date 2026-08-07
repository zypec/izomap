package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.ui.CameraDialogs;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Kamera etkileşimlerini yönetir:
 * <ul>
 *   <li>Interaction entity'ye <b>sağ tık</b>: aktif özelliği artır.</li>
 *   <li>Interaction entity'ye <b>sol tık</b> (attack): aktif özelliği azalt.</li>
 *   <li><b>Shift + sağ tık</b>: ayarlanan özelliği (Yaw/Pitch/Scale) değiştir.</li>
 *   <li>Kamera eşyasıyla bloğa sağ tık: o konuma yeni kamera yerleştir.</li>
 * </ul>
 */
public final class CameraListener implements Listener {

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

    // Sağ tık: artır veya (sneak) özelliği değiştir.
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

        if (player.isSneaking()) {
            camera.editProperty(camera.editProperty().next());
            player.sendActionBar(plugin.messages().get("camera.edit-switched",
                    Placeholder.unparsed("property", camera.editProperty().name())));
            return;
        }
        adjust(camera, +1, player);
    }

    // Sol tık (attack): azalt.
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

        // Shift + sol tık: fotoğraf Dialog'unu aç. Aksi halde: aktif özelliği azalt.
        if (player.isSneaking()) {
            dialogs.openCaptureDialog(player, camera);
            return;
        }
        adjust(camera, -1, player);
    }

    // Kamera eşyasıyla bloğa sağ tık: yeni kamera.
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
            case PITCH -> camera.camPitch((float) (camera.camPitch() + direction * plugin.config().angleStep()));
            case SCALE -> camera.scale((float) (camera.scale() + direction * plugin.config().scaleStep()));
        }
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        player.sendActionBar(plugin.messages().get("camera.edit-property",
                Placeholder.unparsed("property", camera.editProperty().name()),
                Placeholder.unparsed("value", currentValue(camera))));
    }

    private String currentValue(Camera camera) {
        return switch (camera.editProperty()) {
            case YAW -> String.format(Locale.ROOT, "%.0f°", camera.camYaw());
            case PITCH -> String.format(Locale.ROOT, "%.0f°", camera.camPitch());
            case SCALE -> String.format(Locale.ROOT, "%.2fx", camera.scale());
        };
    }

    private String defaultName(Player player) {
        return "cam-" + (manager.ownedCount(player.getUniqueId()) + 1);
    }
}
