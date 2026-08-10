package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraStatus;
import dev.zypec.izomap.map.MapService;
import dev.zypec.izomap.util.Failures;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live 128x128 preview of what a camera sees, held in the offhand and refreshed as
 * the camera is edited.
 *
 * <h2>One session per camera, many watchers, one editor</h2>
 *
 * <p>A session belongs to a <b>camera</b>, not to a player: it owns a single
 * {@link MapView} that every watcher's map item points at, so one render feeds all of
 * them. Only the editor may adjust the camera by clicking it; everyone else watches.
 * The seat is claimed by interacting and released when the editor leaves, quits or
 * goes {@code camera.edit-lock-seconds} without touching the camera — otherwise a
 * player who clicked once and walked away would hold it forever.</p>
 *
 * <p>The offhand must be empty to join, the map cannot be dropped, moved in the
 * inventory, or swapped to the main hand, and it is taken back on every exit. Locking
 * watchers' maps too (rather than letting them carry one off, as first sketched)
 * keeps a live camera feed from leaking into inventories as a normal map item.</p>
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

    /**
     * Dash length of a guideline, in pixels.
     */
    private static final int DASH = 3;
    /**
     * Two tones so the guide stays visible on both light and dark terrain.
     */
    private static final int GUIDE_LIGHT = 0xFFFFFFFF;
    private static final int GUIDE_DARK = 0xFF303030;

    /**
     * The client fades the action bar out after ~3 s, so it is resent every second.
     */
    private static final long STATUS_PERIOD_TICKS = 20L;
    /**
     * How long a warning keeps the action bar to itself.
     */
    private static final long NOTICE_MS = 5_000L;

    /**
     * Outcome of trying to join a camera's preview.
     */
    public enum JoinResult {
        /**
         * The map is now in the player's offhand.
         */
        JOINED,
        /**
         * The player was already watching this camera.
         */
        ALREADY,
        /**
         * The offhand holds something else.
         */
        OFFHAND_FULL
    }

    private final Izomap plugin;
    private final RenderService renderService;
    private final MapService mapService;
    /**
     * Holds the camera id, so an item can be matched back to its session.
     */
    private final NamespacedKey previewKey;
    /**
     * Pre-rewrite marker; only read to clear maps left over from an older build.
     */
    private final NamespacedKey legacyPreviewKey;

    private final Map<UUID, PreviewSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> watchedCamera = new ConcurrentHashMap<>();
    /**
     * Runs only while somebody is watching; see {@link #startStatusTask()}.
     */
    private ScheduledTask statusTask;

    public PreviewManager(Izomap plugin, RenderService renderService, MapService mapService) {
        this.plugin = plugin;
        this.renderService = renderService;
        this.mapService = mapService;
        this.previewKey = new NamespacedKey(plugin, "preview_camera");
        this.legacyPreviewKey = new NamespacedKey(plugin, "preview_map");
    }

    /**
     * State shared by everyone watching one camera.
     */
    private static final class PreviewSession {
        private final UUID cameraId;
        /**
         * Created with the first watcher; a session with an editor alone has none.
         */
        private MapView view;
        private final Set<UUID> watchers = ConcurrentHashMap.newKeySet();
        private UUID editor;
        private long editorTouchedAt;
        /**
         * One render at a time per camera; swallows click spam from every watcher.
         */
        private boolean rendering;
        /**
         * Takes the action bar over from the status line until it expires.
         */
        private Component notice;
        private long noticeUntil;

        private PreviewSession(UUID cameraId) {
            this.cameraId = cameraId;
        }
    }

    // --- joining and leaving ---

    /**
     * Adds the player as a watcher when it can, then re-renders. Must be called on the
     * main thread.
     */
    public void refresh(Player player, Camera camera) {
        join(player, camera);
        refresh(camera);
    }

    /**
     * Re-renders the camera's preview; does nothing when nobody is watching.
     */
    public void refresh(Camera camera) {
        render(camera, sessions.get(camera.id()));
    }

    /**
     * Puts the camera's preview map in the player's offhand, switching them over from
     * another camera's preview if needed.
     */
    public JoinResult join(Player player, Camera camera) {
        var playerId = player.getUniqueId();
        if (camera.id().equals(watchedCamera.get(playerId)))
            return JoinResult.ALREADY;

        // Switching cameras: the old map has to go before the offhand can be checked.
        // This camera's editor seat is spared, since joining often follows claiming it.
        forget(player, camera.id());

        var offhand = player.getInventory().getItemInOffHand();
        if (!offhand.getType().isAir())
            return JoinResult.OFFHAND_FULL;

        var session = sessions.computeIfAbsent(camera.id(), PreviewSession::new);
        if (session.view == null)
            session.view = previewView(camera);

        session.watchers.add(playerId);
        watchedCamera.put(playerId, camera.id());
        // Handed over blank: the first render lands a moment later, and waiting for it
        // would leave the click with no feedback at all.
        player.getInventory().setItemInOffHand(previewItem(session.view, camera.id()));
        startStatusTask();
        return JoinResult.JOINED;
    }

    /**
     * Ends the player's preview and tells them why. Returns {@code false} when they
     * were not watching anything.
     */
    public boolean leave(Player player, String reasonKey) {
        if (!forget(player, null))
            return false;

        if (reasonKey != null)
            plugin.messages().send(player, reasonKey);

        return true;
    }

    /**
     * Ends the preview of a camera that no longer exists, for everyone watching it.
     *
     * <p>Without this the map stays in the offhand showing a frozen image of a camera
     * that is gone, and nothing can refresh or remove it.</p>
     */
    public void close(UUID cameraId, String reasonKey, String cameraName) {
        end(sessions.remove(cameraId),
                reasonKey == null ? null
                        : plugin.messages().get(reasonKey, Placeholder.unparsed("camera", cameraName)));
    }

    /**
     * Ends every session and takes the maps back, without telling anyone why.
     */
    public void closeAll() {
        for (var cameraId : new ArrayList<>(sessions.keySet())) {
            end(sessions.remove(cameraId), null);
        }
        stopStatusTask();
    }

    private void end(PreviewSession session, Component message) {
        if (session == null) {
            return;
        }
        if (session.editor != null) {
            watchedCamera.remove(session.editor);
        }
        for (var watcherId : session.watchers) {
            watchedCamera.remove(watcherId);
            var watcher = plugin.getServer().getPlayer(watcherId);
            if (watcher == null) {
                continue; // offline: the record is gone, PlayerJoinEvent clears the item
            }
            takeMap(watcher);
            if (message != null) {
                watcher.sendMessage(message);
            }
        }
    }

    /**
     * Claims the editor seat for the player, or reports who holds it and signs them up
     * as a watcher instead.
     */
    public boolean claimEditor(Player player, Camera camera) {
        var playerId = player.getUniqueId();
        var session = sessions.computeIfAbsent(camera.id(), PreviewSession::new);

        var editor = session.editor != null ? plugin.getServer().getPlayer(session.editor) : null;
        if (editor != null && !session.editor.equals(playerId) && !seatExpired(session)) {
            plugin.messages().send(player, "preview.busy", Placeholder.unparsed("editor", editor.getName()));
            join(player, camera);
            refresh(camera);
            return false;
        }
        session.editor = playerId;
        session.editorTouchedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * Whether the seat may be taken over because its holder went idle.
     */
    private boolean seatExpired(PreviewSession session) {
        long idleMs = plugin.config().editLockSeconds() * 1000L;
        return System.currentTimeMillis() - session.editorTouchedAt >= idleMs;
    }

    /**
     * Drops the player from whatever they were watching and takes their map back.
     * The seat on {@code keepSeatOn} is left alone; pass {@code null} to give up all
     * of them.
     */
    private boolean forget(Player player, UUID keepSeatOn) {
        var playerId = player.getUniqueId();
        takeMap(player);
        var cameraId = watchedCamera.remove(playerId);
        releaseEditorSeats(playerId, keepSeatOn);
        if (cameraId == null) {
            return false;
        }
        var session = sessions.get(cameraId);
        if (session != null) {
            session.watchers.remove(playerId);
            discardIfIdle(session);
        }
        return true;
    }

    /**
     * Frees every editor seat the player holds, whether or not they held a map.
     */
    private void releaseEditorSeats(UUID playerId, UUID keepSeatOn) {
        for (var session : sessions.values()) {
            if (playerId.equals(session.editor) && !session.cameraId.equals(keepSeatOn)) {
                session.editor = null;
                discardIfIdle(session);
            }
        }
    }

    private void discardIfIdle(PreviewSession session) {
        if (session.editor == null && session.watchers.isEmpty()) {
            sessions.remove(session.cameraId);
        }
    }

    private void takeMap(Player player) {
        if (isPreview(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    // --- rendering ---

    private void render(Camera camera, PreviewSession session) {
        if (session == null || session.view == null || session.watchers.isEmpty() || session.rendering) {
            return;
        }
        session.rendering = true;

        CompletableFuture<RenderResult> capture;
        try {
            capture = renderService.capture(camera,
                    previewWidth(camera.aspectRatio()), previewHeight(camera.aspectRatio()));
        } catch (RuntimeException ex) {
            // Without releasing the lock here a synchronous failure freezes the preview.
            session.rendering = false;
            throw ex;
        }

        capture.whenComplete((result, error) ->
                plugin.runOnMain(() -> {
                    session.rendering = false;
                    if (error == null && result != null) {
                        mapService.applyTile(session.view, tileFrom(result, camera.thirdsGuide()));
                        return;
                    }
                    // Report a budget overrun on the action bar instead of stalling silently.
                    if (Failures.unwrap(error) instanceof CaptureTooLargeException tooLarge) {
                        notice(session, plugin.messages().get("photo.too-large",
                                Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                                Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
                    }
                }));
    }

    private void actionBar(PreviewSession session, Component message) {
        for (var watcherId : session.watchers) {
            var watcher = plugin.getServer().getPlayer(watcherId);
            if (watcher != null) {
                watcher.sendActionBar(message);
            }
        }
    }

    // --- status line ---

    /**
     * Pushes the camera's status line to everyone watching it, plus the player who
     * caused the change — who may have no preview at all, when their offhand is full.
     */
    public void showStatus(Camera camera, Player actor) {
        var line = CameraStatus.line(plugin, camera);
        var session = sessions.get(camera.id());
        if (session != null) {
            actionBar(session, line);
        }
        if (session == null || !session.watchers.contains(actor.getUniqueId())) {
            actor.sendActionBar(line);
        }
    }

    /**
     * Repeats the status line for every watcher, so it survives the client's fade-out
     * without anyone having to click.
     *
     * <p>Started with the first watcher and stopped as soon as the last one leaves;
     * an always-running task would burn a tick a second for nothing.</p>
     */
    private void startStatusTask() {
        if (statusTask == null) {
            statusTask = plugin.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, task -> tickStatus(), STATUS_PERIOD_TICKS, STATUS_PERIOD_TICKS);
        }
    }

    private void stopStatusTask() {
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
    }

    private void tickStatus() {
        var anyWatcher = false;
        var now = System.currentTimeMillis();
        for (var session : sessions.values()) {
            if (session.watchers.isEmpty())
                continue;

            anyWatcher = true;
            if (session.notice != null && now < session.noticeUntil) {
                actionBar(session, session.notice);
                continue;
            }
            session.notice = null;
            var camera = plugin.cameras().byId(session.cameraId);
            if (camera != null) {
                actionBar(session, CameraStatus.line(plugin, camera));
            }
        }
        if (!anyWatcher) {
            stopStatusTask();
        }
    }

    /**
     * Holds the action bar against the status line, which would bury it in a second.
     */
    private void notice(PreviewSession session, Component message) {
        session.notice = message;
        session.noticeUntil = System.currentTimeMillis() + NOTICE_MS;
        actionBar(session, message);
    }

    // --- tile composition ---

    /**
     * Render width once the camera's ratio is fitted into the 128x128 tile.
     */
    private static int previewWidth(AspectRatio ratio) {
        double value = ratio.value();
        return value >= 1.0 ? TILE : Math.max(1, (int) Math.round(TILE * value));
    }

    /**
     * Render height once the camera's ratio is fitted into the 128x128 tile.
     */
    private static int previewHeight(AspectRatio ratio) {
        double value = ratio.value();
        return value >= 1.0 ? Math.max(1, (int) Math.round(TILE / value)) : TILE;
    }

    /**
     * Centers the render on the tile; the remaining area stays transparent, so the
     * map's own parchment shows through as the letterbox bars.
     */
    private static int[] tileFrom(RenderResult result, boolean thirdsGuide) {
        var tile = new int[TILE * TILE];
        var w = Math.min(TILE, result.width());
        var h = Math.min(TILE, result.height());
        var offsetX = (TILE - w) / 2;
        var offsetY = (TILE - h) / 2;
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
        for (var third = 1; third <= 2; third++) {
            var lineX = offsetX + Math.min(w - 1, (int) Math.round(w * third / 3.0));
            for (var y = 0; y < h; y++) {
                tile[(offsetY + y) * TILE + lineX] = dashColor(y);
            }
            var lineY = offsetY + Math.min(h - 1, (int) Math.round(h * third / 3.0));
            for (var x = 0; x < w; x++) {
                tile[lineY * TILE + offsetX + x] = dashColor(x);
            }
        }
    }

    private static int dashColor(int along) {
        return (along / DASH) % 2 == 0 ? GUIDE_LIGHT : GUIDE_DARK;
    }

    // --- the map item ---

    private ItemStack previewItem(MapView view, UUID cameraId) {
        var item = mapService.itemFor(view);
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(previewKey, PersistentDataType.STRING, cameraId.toString()));
        return item;
    }

    private boolean isPreview(ItemStack item) {
        return item != null && item.hasItemMeta()
               && item.getItemMeta().getPersistentDataContainer().has(previewKey, PersistentDataType.STRING);
    }

    /**
     * Also matches maps written by the pre-session build, which used a boolean flag.
     */
    private boolean isPreviewOrLegacy(ItemStack item) {
        return isPreview(item) || (item != null && item.hasItemMeta()
                                   && item.getItemMeta().getPersistentDataContainer().has(legacyPreviewKey, PersistentDataType.BOOLEAN));
    }

    // --- locking and cleanup ---

    // Dropping ends the preview cleanly instead of leaving the map on the ground.
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPreview(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            leave(event.getPlayer(), "preview.ended-dropped");
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

    // Death would scatter the map with the rest of the inventory, so it is pulled out
    // of the drops rather than dropped and lost.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        var player = event.getEntity();
        if (!watchedCamera.containsKey(player.getUniqueId())) {
            return;
        }
        event.getDrops().removeIf(this::isPreview);
        leave(player, "preview.ended-died");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer(), null);
    }

    // Clear a preview left in the offhand by a crash; its session did not survive.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (isPreviewOrLegacy(event.getPlayer().getInventory().getItemInOffHand())) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        }
    }

    /**
     * The camera's own preview map, created once and reused for its whole life.
     *
     * <p>A map id is spent for good the moment it is handed out — the server writes a
     * {@code map_<n>.dat} for it and nothing gives it back. Creating one per session
     * meant every open and close of a preview cost one, so the count grew with use
     * rather than with the number of cameras.</p>
     */
    private MapView previewView(Camera camera) {
        var existing = mapService.viewById(camera.previewMapId());
        if (existing != null) {
            mapService.applyTile(existing, blank()); // the last session's image is stale
            return existing;
        }
        var created = mapService.createMapView(worldOf(camera), blank());
        camera.previewMapId(created.getId());
        plugin.cameras().persist();
        return created;
    }

    private World worldOf(Camera camera) {
        var world = camera.anchor().getWorld();
        if (world != null) {
            return world;
        }
        var worlds = plugin.getServer().getWorlds();
        return worlds.getFirst();
    }

    private static int[] blank() {
        return new int[TILE * TILE];
    }
}
