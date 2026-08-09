package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.map.MapService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts a live 128x128 preview map of what the camera sees in the player's offhand
 * and refreshes it as the camera is edited.
 *
 * <p>The offhand must be empty to enter preview, the map cannot be dropped, moved in
 * the inventory or swapped to the main hand, and it is removed when the player
 * quits.</p>
 *
 * <p>A single {@link MapView} is reused per player, so re-rendering it updates the
 * held map in place.</p>
 *
 * <h2>The preview is captured at the camera's aspect ratio</h2>
 *
 * <p>The tile is square, so the render is fitted to the camera's ratio and
 * letterboxed into its center. Capturing 1:1 regardless of ratio used to misreport
 * both the real frame and the cost: the ray prism widens with the ratio, so a 16:9
 * photo needs nearly twice the chunks of a 1:1 preview and placement failed with
 * "frame too wide" after the preview had passed. Matching the ratio surfaces a
 * budget overrun before placement instead.</p>
 */
public final class PreviewManager implements Listener {

    private static final int TILE = 128;

    /** Dash length of a guide line, in pixels. */
    private static final int DASH = 3;
    /** Two tones so the guide stays visible on both light and dark terrain. */
    private static final int GUIDE_LIGHT = 0xFFFFFFFF;
    private static final int GUIDE_DARK = 0xFF303030;

    private final Izomap plugin;
    private final RenderService renderService;
    private final MapService mapService;
    private final NamespacedKey previewKey;

    private final Map<UUID, MapView> views = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PreviewManager(Izomap plugin, RenderService renderService, MapService mapService) {
        this.plugin = plugin;
        this.renderService = renderService;
        this.mapService = mapService;
        this.previewKey = new NamespacedKey(plugin, "preview_map");
    }

    /**
     * Re-renders the preview: starts one when the offhand is empty, updates an
     * existing one, and does nothing when the offhand holds something else. Must be
     * called on the main thread.
     */
    public void refresh(Player player, Camera camera) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        boolean hasPreview = isPreview(offhand);
        boolean offhandEmpty = offhand == null || offhand.getType().isAir();
        if (!hasPreview && !offhandEmpty) {
            return; // offhand is occupied
        }

        UUID id = player.getUniqueId();
        if (!inFlight.add(id)) {
            return; // one render at a time; swallow click spam
        }
        MapView view = views.computeIfAbsent(id, key -> mapService.createMapView(player.getWorld(), blank()));

        int width = previewWidth(camera.aspectRatio());
        int height = previewHeight(camera.aspectRatio());

        CompletableFuture<RenderResult> capture;
        try {
            capture = renderService.capture(camera, width, height);
        } catch (RuntimeException ex) {
            // Without releasing the lock here a synchronous failure freezes the preview.
            inFlight.remove(id);
            throw ex;
        }

        capture.whenComplete((result, error) ->
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    inFlight.remove(id);
                    if (error == null && result != null) {
                        mapService.applyTile(view, tileFrom(result, camera.thirdsGuide()));
                        placeIfEmpty(player, view);
                        return;
                    }
                    // Report a budget overrun on the action bar instead of stalling silently.
                    Throwable cause = error instanceof java.util.concurrent.CompletionException
                            && error.getCause() != null ? error.getCause() : error;
                    if (cause instanceof CaptureTooLargeException tooLarge) {
                        player.sendActionBar(plugin.messages().get("photo.too-large",
                                Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                                Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
                    }
                }));
    }

    // --- tile composition ---

    /** Render width once the camera's ratio is fitted into the 128x128 tile. */
    private static int previewWidth(AspectRatio ratio) {
        double value = ratio.value();
        return value >= 1.0 ? TILE : Math.max(1, (int) Math.round(TILE * value));
    }

    /** Render height once the camera's ratio is fitted into the 128x128 tile. */
    private static int previewHeight(AspectRatio ratio) {
        double value = ratio.value();
        return value >= 1.0 ? Math.max(1, (int) Math.round(TILE / value)) : TILE;
    }

    /**
     * Centers the render on the tile; the remaining area stays transparent, so the
     * map's own parchment shows through as the letterbox bars.
     */
    private static int[] tileFrom(RenderResult result, boolean thirdsGuide) {
        int[] tile = new int[TILE * TILE];
        int w = Math.min(TILE, result.width());
        int h = Math.min(TILE, result.height());
        int offsetX = (TILE - w) / 2;
        int offsetY = (TILE - h) / 2;
        for (int y = 0; y < h; y++) {
            System.arraycopy(result.argb(), y * result.width(), tile, (y + offsetY) * TILE + offsetX, w);
        }
        if (thirdsGuide) {
            drawThirdsGuide(tile, offsetX, offsetY, w, h);
        }
        return tile;
    }

    /**
     * Draws the lines splitting the frame into thirds. Preview only; a capture never
     * goes through this code.
     *
     * <p>A single color would vanish on snow or in a shaded forest, so the two tones
     * alternate in {@link #DASH}-pixel dashes and stay visible on any terrain.</p>
     */
    private static void drawThirdsGuide(int[] tile, int offsetX, int offsetY, int w, int h) {
        for (int third = 1; third <= 2; third++) {
            int lineX = offsetX + Math.min(w - 1, (int) Math.round(w * third / 3.0));
            for (int y = 0; y < h; y++) {
                tile[(offsetY + y) * TILE + lineX] = dashColor(y);
            }
            int lineY = offsetY + Math.min(h - 1, (int) Math.round(h * third / 3.0));
            for (int x = 0; x < w; x++) {
                tile[lineY * TILE + offsetX + x] = dashColor(x);
            }
        }
    }

    private static int dashColor(int along) {
        return (along / DASH) % 2 == 0 ? GUIDE_LIGHT : GUIDE_DARK;
    }

    /** Puts the preview map in an empty offhand; leaves an existing preview alone. */
    private void placeIfEmpty(Player player, MapView view) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItemInOffHand();
        if (isPreview(current)) {
            return; // the same MapView was updated, so the content is already fresh
        }
        if (current == null || current.getType().isAir()) {
            inventory.setItemInOffHand(previewItem(view));
        }
    }

    private ItemStack previewItem(MapView view) {
        ItemStack item = mapService.itemFor(view);
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(previewKey, PersistentDataType.BOOLEAN, true));
        return item;
    }

    /** Ends the preview and removes the map from the offhand. */
    public void endPreview(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isPreview(offhand)) {
            player.getInventory().setItemInOffHand(null);
        }
        views.remove(player.getUniqueId());
        inFlight.remove(player.getUniqueId());
    }

    private boolean isPreview(ItemStack item) {
        return item != null && item.hasItemMeta()
                && Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer()
                .get(previewKey, PersistentDataType.BOOLEAN));
    }

    // --- locking and cleanup ---

    // Dropping ends the preview cleanly instead of leaving the map on the ground.
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPreview(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            endPreview(event.getPlayer());
        }
    }

    // Hand swapping is blocked.
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isPreview(event.getOffHandItem()) || isPreview(event.getMainHandItem())) {
            event.setCancelled(true);
        }
    }

    // Moving or dragging it in the inventory is blocked.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (isPreview(event.getCurrentItem()) || isPreview(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endPreview(event.getPlayer());
    }

    // Clear a preview left in the offhand by a crash.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ItemStack offhand = event.getPlayer().getInventory().getItemInOffHand();
        if (isPreview(offhand)) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        }
    }

    private static int[] blank() {
        return new int[TILE * TILE];
    }
}
