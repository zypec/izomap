package dev.zypec.izomap.place;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PlacementArea;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost preview for hanging a photo: a grid of {@link BlockDisplay}s that follows the
 * player's gaze and glows green or red depending on whether the photo would fit.
 *
 * <p>Placement used to happen the instant the dialog was confirmed, wherever the player
 * happened to be looking. Now they line the photo up first and commit with a click, so
 * a misjudged spot costs nothing.</p>
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

    private final Izomap plugin;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledTask task;
    private int ticks;

    public PlacementManager(Izomap plugin) {
        this.plugin = plugin;
    }

    /**
     * One player lining up one photo.
     */
    private static final class Session {
        private final UUID photoId;
        private final GridOption grid;
        private final List<BlockDisplay> ghosts = new ArrayList<>();
        private final long expiresAt;
        private PlacementArea area;
        private boolean fits;

        private Session(UUID photoId, GridOption grid, long expiresAt) {
            this.photoId = photoId;
            this.grid = grid;
            this.expiresAt = expiresAt;
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
        var session = new Session(photo.id(), photo.grid(), System.currentTimeMillis() + timeout);
        session.area = PlacementArea.inFrontOf(player, plugin.config().placementDistance());
        session.fits = session.area.fits(session.grid, plugin.config().buildBackingWall());

        spawnGhosts(player, session);
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
                discard(sessions.remove(entry.getKey()));
            }
        }
    }

    /**
     * Ends every session and clears the ghosts, so shutdown leaves nothing floating.
     */
    public void cancelAll() {
        for (var playerId : List.copyOf(sessions.keySet())) {
            discard(sessions.remove(playerId));
        }
        stopTask();
    }

    private void end(Player player, String reasonKey) {
        discard(sessions.remove(player.getUniqueId()));
        if (reasonKey != null)
            plugin.messages().send(player, reasonKey);

        if (sessions.isEmpty())
            stopTask();
    }

    private void discard(Session session) {
        if (session == null) return;

        for (var ghost : session.ghosts)
            ghost.remove();

        session.ghosts.clear();
    }

    // --- ghosts ---

    private void spawnGhosts(Player player, Session session) {
        var material = ghostMaterial();
        var blockData = material.createBlockData();
        var color = session.fits ? VALID : BLOCKED;

        for (var row = 0; row < session.grid.rows(); row++) {
            for (var col = 0; col < session.grid.cols(); col++) {
                var block = session.area.frameBlock(session.grid, col, row);
                var ghost = block.getWorld().spawn(block.getLocation(), BlockDisplay.class, e -> {
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
                player.showEntity(plugin, ghost);
                session.ghosts.add(ghost);
            }
        }
    }

    /**
     * Moves the ghosts onto the area and recolors them, both only when something
     * actually changed.
     */
    private void updateGhosts(Session session, PlacementArea area, boolean fits) {
        if (!area.equals(session.area)) {
            var index = 0;
            for (var row = 0; row < session.grid.rows(); row++) {
                for (var col = 0; col < session.grid.cols(); col++) {
                    session.ghosts.get(index++).teleport(area.frameBlock(session.grid, col, row).getLocation());
                }
            }
            session.area = area;
        }
        if (fits != session.fits) {
            var color = fits ? VALID : BLOCKED;
            for (var ghost : session.ghosts)
                ghost.setGlowColorOverride(color);

            session.fits = fits;
        }
    }

    /**
     * Falls back to a bright block when {@code placement.backing-material} is not one:
     * the ghost is the only thing the player is judging, so it has to be visible.
     */
    private Material ghostMaterial() {
        var material = Material.matchMaterial(plugin.config().backingMaterial());
        return (material != null && material.isBlock()) ? material : Material.WHITE_CONCRETE;
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
                discard(sessions.remove(entry.getKey()));
                continue;
            }
            if (now >= session.expiresAt) {
                end(player, "placement.ended-timeout");
                continue;
            }
            var area = PlacementArea.inFrontOf(player, plugin.config().placementDistance());
            updateGhosts(session, area, area.fits(session.grid, plugin.config().buildBackingWall()));
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
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;
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
        if (event.getHand() != EquipmentSlot.HAND)
            return;
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
        discard(sessions.remove(event.getPlayer().getUniqueId()));
        if (sessions.isEmpty())
            stopTask();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        cancel(event.getEntity(), "placement.ended-died");
    }

    // The ghosts belong to the world they were spawned in and cannot follow.
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cancel(event.getPlayer(), "placement.ended-world-change");
    }
}
