package dev.zypec.izomap.place;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PlacementArea;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Ghost preview for hanging a photo: a grid of {@link BlockDisplay}s that follows the
 * player's gaze and glows green or red depending on whether the photo would fit.
 *
 * <p>Placement used to happen the instant the dialog was confirmed, wherever the player
 * happened to be looking. Now they line the photo up first and commit with a click, so
 * a misjudged spot costs nothing.</p>
 *
 * <h2>Two layers, because two different things get built</h2>
 *
 * <p>Hanging a photo puts up at most two things per tile: the item frame itself, and —
 * only where nothing is there to hang it on — a backing block behind it. The preview
 * shows them separately.</p>
 *
 * <ul>
 *   <li><b>Frame layer.</b> One thin pane per tile, standing in the frame cell and
 *       pushed flush against the wall face, so it sits where the map will. Panes are
 *       given the connections along the wall's own axis, which makes each one span its
 *       full block and the grid read as one continuous sheet. Always shown: it is the
 *       photo, and it is what the player is actually aiming.</li>
 *   <li><b>Backing layer.</b> A full block behind a tile, shown <b>only for the cells
 *       that would really be filled in</b> — {@code build-backing-wall} on and that
 *       block empty. Hanging on a wall the player built themselves promises no blocks,
 *       so it shows none; previously this layer was the only thing drawn and it stood
 *       in the frame cell, which made every placement look like it would bury the
 *       wall.</li>
 * </ul>
 *
 * <h2>Only that player sees it</h2>
 *
 * <p>Paper has no real client-side entity API, so the ghosts are spawned
 * {@code visibleByDefault(false)} and shown to their one owner. They are also
 * <b>not persistent</b>: a crash mid-placement must not leave a wall of floating blocks
 * behind, and nothing about them is worth saving.</p>
 *
 * <h2>The gesture is global, not a hitbox</h2>
 *
 * <p>A {@code BlockDisplay} cannot be clicked, and the obvious fix — one
 * {@code Interaction} entity spanning the grid — sizes badly: its box is square in X
 * and Z, so a 16-wide photo would reach eight blocks out towards the player and
 * swallow everything around them. While a session is open the right-click itself is
 * the confirmation instead, wherever it lands, and sneaking turns it into a cancel.</p>
 *
 * <h2>Why the session holds the offhand</h2>
 *
 * <p>A right-click that hits nothing is only reported when a hand holds something: the
 * client walks both hands, skips the empty ones, and sends a use packet for the rest.
 * Empty-handed at the sky, the server hears nothing at all and the confirmation never
 * arrives — which is most of the time in creative. A marker item is therefore put in
 * the offhand for the length of the session, which restores the packet and, as a
 * bonus, shows what the player is carrying to the wall. Whatever was there is given
 * back on every exit.</p>
 */
public final class PlacementManager implements Listener {

    /**
     * How often the ghosts are re-aimed. The grid only ever sits on whole blocks, so
     * most ticks change nothing and cost one comparison.
     */
    private static final long PERIOD_TICKS = 2L;

    /**
     * Movement is smoothed over slightly more than the update period, so a step to the
     * next block glides instead of snapping.
     */
    private static final int TELEPORT_DURATION_TICKS = 3;

    /**
     * Action bar refreshes per {@link #PERIOD_TICKS}; the client fades it after ~3 s.
     */
    private static final int STATUS_EVERY = 10;

    private static final Color VALID = Color.fromRGB(0x55FF55);
    private static final Color BLOCKED = Color.fromRGB(0xFF5555);

    /**
     * Stand-in for the item frame. A pane is the thinnest full-height, full-width block
     * model there is, and a stained one still reads as a surface once the glow outlines
     * it; plain glass would be an outline around nothing.
     */
    private static final Material FRAME_MATERIAL = Material.WHITE_STAINED_GLASS_PANE;

    /**
     * How far the pane is pushed towards the wall, in blocks.
     *
     * <p>A pane's model straddles the block centre at 7/16..9/16, and a map frame hangs
     * in the outermost 1/16. Shifting by 6.5/16 lands the pane in 13.5/16..15.5/16:
     * where the map will be, with half a pixel of daylight left so its face and the
     * wall's do not fight over the same plane.</p>
     */
    private static final float FRAME_OFFSET = 6.5f / 16f;

    /**
     * How far down the ghosts are drawn from the cells they stand for, in blocks.
     *
     * <p>Only the drawing moves: every decision — which cells must be free, where a
     * backing is owed, where the frames finally go — keeps working from
     * {@link PlacementArea#frameBlock}, so the shift never reaches the world.</p>
     */
    private static final int GHOST_DROP = 1;

    private final Izomap plugin;
    /**
     * Marks the offhand item as ours, so it can be told apart from a real one.
     */
    private final NamespacedKey markerKey;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledTask task;
    private int ticks;

    public PlacementManager(Izomap plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "placement_marker");
    }

    /**
     * One player lining up one photo.
     */
    private static final class Session {
        private final UUID photoId;
        private final GridOption grid;
        /**
         * One pane per tile, in row-major order, standing where the frame will.
         */
        private final List<BlockDisplay> frames = new ArrayList<>();
        /**
         * One block per tile behind its frame, in the same order — empty when
         * {@code build-backing-wall} is off and nothing would ever be built.
         */
        private final List<BlockDisplay> backings = new ArrayList<>();
        /**
         * Which backing ghosts the player is currently being shown, so a hide or show
         * packet only goes out when the answer changes.
         */
        private final boolean[] backingShown;
        private final long expiresAt;
        /**
         * What the offhand held before the marker took its place.
         */
        private final ItemStack offhand;
        private PlacementArea area;
        private boolean fits;

        private Session(UUID photoId, GridOption grid, long expiresAt, ItemStack offhand) {
            this.photoId = photoId;
            this.grid = grid;
            this.backingShown = new boolean[grid.tileCount()];
            this.expiresAt = expiresAt;
            this.offhand = offhand;
        }
    }

    // --- lifecycle ---

    /**
     * Puts the player into placement mode for a photo. Main thread only.
     *
     * <p>Their camera preview is closed first: the offhand map and the action bar both
     * belong to the placement from here on, and a live preview fighting the status line
     * for the same row would leave neither readable.</p>
     */
    public void start(Player player, Photo photo) {
        cancel(player, null);
        plugin.preview().leave(player, null);

        var timeout = plugin.config().placementTimeoutSeconds() * 1000L;
        var session = new Session(photo.id(), photo.grid(), System.currentTimeMillis() + timeout,
                player.getInventory().getItemInOffHand().clone());
        session.area = PlacementArea.inFrontOf(player, plugin.config().placementDistance());
        session.fits = session.area.fits(session.grid, plugin.config().buildBackingWall());

        spawnGhosts(player, session);
        player.getInventory().setItemInOffHand(marker(photo));
        sessions.put(player.getUniqueId(), session);
        startTask();

        plugin.messages().send(player, "placement.started", Placeholder.unparsed("name", photo.name()));
        showStatus(player, session);
    }

    /**
     * Ends the player's session and hangs the photo, or explains why it cannot.
     */
    public void confirm(Player player) {
        var session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (!session.fits) {
            player.sendActionBar(plugin.messages().get("placement.blocked"));
            return;
        }
        var photo = plugin.photos().byId(session.photoId).orElse(null);
        var area = session.area;
        end(player, null);
        if (photo != null) {
            plugin.photos().place(player, photo, area);
        }
    }

    /**
     * Ends the player's session, if any, and tells them why. Returns whether there was
     * one to end.
     */
    public boolean cancel(Player player, String reasonKey) {
        if (!sessions.containsKey(player.getUniqueId()))
            return false;

        end(player, reasonKey);
        return true;
    }

    /**
     * Ends whoever is lining up a photo that no longer exists.
     */
    public void cancelFor(UUID photoId, String reasonKey) {
        for (var entry : Map.copyOf(sessions).entrySet()) {
            if (!entry.getValue().photoId.equals(photoId)) continue;

            var player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                end(player, reasonKey);
            } else {
                discard(entry.getKey(), sessions.remove(entry.getKey()));
            }
        }
    }

    /**
     * Ends every session and clears the ghosts, so shutdown leaves nothing floating.
     */
    public void cancelAll() {
        for (var playerId : List.copyOf(sessions.keySet())) {
            discard(playerId, sessions.remove(playerId));
        }
        stopTask();
    }

    private void end(Player player, String reasonKey) {
        discard(player.getUniqueId(), sessions.remove(player.getUniqueId()));
        if (reasonKey != null)
            plugin.messages().send(player, reasonKey);

        if (sessions.isEmpty())
            stopTask();
    }

    /**
     * Clears the ghosts and gives the offhand back. Safe for an offline player: only
     * the item needs them online, and a marker left in a saved inventory is cleared
     * when they next join.
     */
    private void discard(UUID playerId, Session session) {
        if (session == null) return;

        for (var ghost : ghosts(session))
            ghost.remove();

        session.frames.clear();
        session.backings.clear();

        var player = plugin.getServer().getPlayer(playerId);
        if (player != null)
            restoreOffhand(player, session);
    }

    /**
     * Puts back whatever the marker displaced, unless the offhand has moved on to
     * something else in the meantime.
     */
    private void restoreOffhand(Player player, Session session) {
        if (isMarker(player.getInventory().getItemInOffHand()))
            player.getInventory().setItemInOffHand(session.offhand);
    }

    // --- ghosts ---

    private void spawnGhosts(Player player, Session session) {
        var forward = session.area.forward();
        var paneData = paneFacing(forward);
        var flush = flushWithWall(forward);
        var backingData = backingMaterial().createBlockData();
        var buildsBacking = plugin.config().buildBackingWall();
        var color = session.fits ? VALID : BLOCKED;

        for (var row = 0; row < session.grid.rows(); row++) {
            for (var col = 0; col < session.grid.cols(); col++) {
                var block = session.area.frameBlock(session.grid, col, row);
                var frame = spawnGhost(ghostLocation(block), paneData, color);
                frame.setTransformation(flush);
                player.showEntity(plugin, frame);
                session.frames.add(frame);

                // Shown by refreshBackings below, and only where one would be built.
                if (buildsBacking)
                    session.backings.add(spawnGhost(
                            ghostLocation(block.getRelative(forward)), backingData, color));
            }
        }
        refreshBackings(player, session);
    }

    private BlockDisplay spawnGhost(Location location, BlockData blockData, Color color) {
        return location.getWorld().spawn(location, BlockDisplay.class, e -> {
            e.setBlock(blockData);
            e.setVisibleByDefault(false);
            // Nothing here is worth keeping; a crash must not leave a wall behind.
            e.setPersistent(false);
            e.setGlowing(true);
            e.setGlowColorOverride(color);
            // Full brightness, so the outline reads the same at night as at noon.
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTeleportDuration(TELEPORT_DURATION_TICKS);
        });
    }

    /**
     * Moves the ghosts onto the area and recolors them, both only when something
     * actually changed, then re-decides which backing blocks are still promised.
     */
    private void updateGhosts(Player player, Session session, PlacementArea area, boolean fits) {
        if (!area.equals(session.area)) {
            // Turning to another wall turns the panes with it; walking keeps them as is.
            var turned = area.forward() != session.area.forward();
            var paneData = turned ? paneFacing(area.forward()) : null;
            var flush = turned ? flushWithWall(area.forward()) : null;

            var index = 0;
            for (var row = 0; row < session.grid.rows(); row++) {
                for (var col = 0; col < session.grid.cols(); col++) {
                    var block = area.frameBlock(session.grid, col, row);
                    var frame = session.frames.get(index);
                    frame.teleport(ghostLocation(block));
                    if (turned) {
                        frame.setBlock(paneData);
                        frame.setTransformation(flush);
                    }
                    if (!session.backings.isEmpty())
                        session.backings.get(index)
                                .teleport(ghostLocation(block.getRelative(area.forward())));

                    index++;
                }
            }
            session.area = area;
        }
        if (fits != session.fits) {
            var color = fits ? VALID : BLOCKED;
            for (var ghost : ghosts(session))
                ghost.setGlowColorOverride(color);

            session.fits = fits;
        }
        refreshBackings(player, session);
    }

    /**
     * Shows a backing ghost exactly where one would be built and hides it everywhere
     * else, so the preview never promises a block it will not place.
     *
     * <p>Re-read every update rather than only after the player moves: the wall behind
     * the grid is someone else's to change, and a cell that fills in while they aim
     * should stop advertising a block.</p>
     */
    private void refreshBackings(Player player, Session session) {
        if (session.backings.isEmpty()) return;

        var index = 0;
        for (var row = 0; row < session.grid.rows(); row++) {
            for (var col = 0; col < session.grid.cols(); col++) {
                var needed = session.area.frameBlock(session.grid, col, row)
                        .getRelative(session.area.forward()).isEmpty();
                if (needed != session.backingShown[index]) {
                    session.backingShown[index] = needed;
                    if (needed) {
                        player.showEntity(plugin, session.backings.get(index));
                    } else {
                        player.hideEntity(plugin, session.backings.get(index));
                    }
                }
                index++;
            }
        }
    }

    /**
     * Where the ghost for a cell is drawn: {@link #GHOST_DROP} blocks below the cell
     * itself.
     */
    private static Location ghostLocation(Block block) {
        return block.getLocation().subtract(0, GHOST_DROP, 0);
    }

    private static Iterable<BlockDisplay> ghosts(Session session) {
        return () -> Stream.concat(session.frames.stream(), session.backings.stream()).iterator();
    }

    /**
     * Falls back to a bright block when {@code placement.backing-material} is not one:
     * a ghost that cannot be seen tells the player nothing.
     */
    private Material backingMaterial() {
        var material = Material.matchMaterial(plugin.config().backingMaterial());
        return (material != null && material.isBlock()) ? material : Material.WHITE_CONCRETE;
    }

    /**
     * A pane connected along the wall's own axis, which is what makes it span its whole
     * block instead of standing up as the stub an unconnected pane renders as.
     */
    private static BlockData paneFacing(BlockFace forward) {
        var pane = (MultipleFacing) FRAME_MATERIAL.createBlockData();
        var alongWall = (forward == BlockFace.NORTH || forward == BlockFace.SOUTH)
                ? new BlockFace[]{BlockFace.EAST, BlockFace.WEST}
                : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH};
        for (var face : alongWall)
            pane.setFace(face, true);

        return pane;
    }

    /**
     * Pushes the pane from the middle of its block onto the wall face the frame will
     * hang on. Displays are not rotated, so the shift is along a world axis.
     */
    private static Transformation flushWithWall(BlockFace forward) {
        var offset = new Vector3f(forward.getModX(), forward.getModY(), forward.getModZ())
                .mul(FRAME_OFFSET);
        return new Transformation(offset, new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f());
    }

    // --- the offhand marker ---

    /**
     * The item the session holds the offhand with; it names the photo being hung.
     */
    private ItemStack marker(Photo photo) {
        var item = ItemStack.of(Material.ITEM_FRAME);
        item.editMeta(meta -> {
            meta.displayName(plugin.messages().get("placement.item-name",
                    Placeholder.unparsed("name", photo.name())).decoration(TextDecoration.ITALIC, false));
            meta.lore(plugin.messages().list("placement.item-lore").stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isMarker(ItemStack item) {
        return item != null && item.hasItemMeta()
               && item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    // --- the update loop ---

    private void startTask() {
        if (task == null) {
            task = plugin.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, scheduled -> tick(), PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        ticks++;
        var now = System.currentTimeMillis();
        for (var entry : Map.copyOf(sessions).entrySet()) {
            var player = plugin.getServer().getPlayer(entry.getKey());
            var session = entry.getValue();
            if (player == null) {
                discard(entry.getKey(), sessions.remove(entry.getKey()));
                continue;
            }
            if (now >= session.expiresAt) {
                end(player, "placement.ended-timeout");
                continue;
            }
            var area = PlacementArea.inFrontOf(player, plugin.config().placementDistance());
            updateGhosts(player, session, area, area.fits(session.grid, plugin.config().buildBackingWall()));
            if (ticks % STATUS_EVERY == 0) {
                showStatus(player, session);
            }
        }
        if (sessions.isEmpty())
            stopTask();
    }

    private void showStatus(Player player, Session session) {
        player.sendActionBar(plugin.messages().get(
                session.fits ? "placement.actionbar" : "placement.actionbar-blocked"));
    }

    // --- gestures ---

    /**
     * While a session is open the right-click belongs to it, wherever it lands.
     *
     * <p>Both hands count. The marker rides in the offhand precisely so that a click at
     * the sky is reported at all, and that click arrives tagged with the hand that held
     * something. Holding an item in the main hand as well makes the client send one
     * packet per hand; the second finds no session left and falls through.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        if (!sessions.containsKey(event.getPlayer().getUniqueId()))
            return;

        event.setCancelled(true);
        resolve(event.getPlayer());
    }

    /**
     * Same for a right-click that lands on an entity, so a camera standing in the way
     * cannot steal the confirmation.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId()))
            return;

        event.setCancelled(true);
        resolve(event.getPlayer());
    }

    private void resolve(Player player) {
        if (player.isSneaking()) {
            cancel(player, "placement.ended-self");
        } else {
            confirm(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        discard(playerId, sessions.remove(playerId));
        if (sessions.isEmpty())
            stopTask();
    }

    /**
     * Keeps the marker out of the grave and puts what it displaced in its place, so
     * dying mid-placement costs the player exactly what dying normally costs.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        var session = sessions.get(event.getEntity().getUniqueId());
        if (session != null) {
            event.getDrops().removeIf(this::isMarker);
            if (!event.getKeepInventory() && !session.offhand.getType().isAir())
                event.getDrops().add(session.offhand);
        }
        cancel(event.getEntity(), "placement.ended-died");
    }

    // Dropping the marker ends the session instead of leaving it on the ground.
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isMarker(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            cancel(event.getPlayer(), "placement.ended-self");
        }
    }

    // The marker stays in the offhand: swapping it away would take the gesture with it.
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isMarker(event.getOffHandItem()) || isMarker(event.getMainHandItem()))
            event.setCancelled(true);
    }

    // Moving or dragging it in the inventory is blocked for the same reason.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isMarker(event.getCurrentItem()) || isMarker(event.getCursor()))
            event.setCancelled(true);
    }

    // Clear a marker left in the offhand by a crash; its session did not survive.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (isMarker(event.getPlayer().getInventory().getItemInOffHand()))
            event.getPlayer().getInventory().setItemInOffHand(null);
    }

    // The ghosts belong to the world they were spawned in and cannot follow.
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cancel(event.getPlayer(), "placement.ended-world-change");
    }
}
