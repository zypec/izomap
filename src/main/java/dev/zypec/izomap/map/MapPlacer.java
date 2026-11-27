package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.map.PlacementArea.Fit;
import dev.zypec.izomap.place.Protection;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Puts map tiles up in the world as a grid of {@link ItemFrame}s.
 *
 * <p>Where they go is decided by {@link PlacementArea}, which the ghost preview lines
 * up beforehand. Must be called on the main thread.</p>
 *
 * <h2>Protection plugins have the last word</h2>
 *
 * <p>Every backing block and every frame is put up the way a player's own would be: the
 * block is set and a {@code BlockPlaceEvent} fired, the frame is spawned and a
 * {@code HangingPlaceEvent} fired. The first one a plugin cancels takes everything
 * already put up back down, so a photo is hung whole or not at all. Maps are only made
 * once every piece has been allowed, since a map id is spent for good.</p>
 */
public final class MapPlacer {

    /**
     * What came of trying to hang a photo: the placement, or why there is none.
     */
    public record Hung(Placement placement, Fit refusal) {
        static Hung refused(Fit refusal) {
            return new Hung(null, refusal);
        }
    }

    private final Izomap plugin;
    private final MapService mapService;
    private final PhotoKeys keys;

    public MapPlacer(Izomap plugin, MapService mapService, PhotoKeys keys) {
        this.plugin = plugin;
        this.mapService = mapService;
        this.keys = keys;
    }

    /**
     * Whether this player's photos get a backing block where nothing is there to hang
     * on: the server has to build them and the player has to be allowed to.
     */
    public boolean buildsBacking(Player player) {
        return plugin.config().buildBackingWall() && Permissions.backing(player);
    }

    /**
     * Hangs the tiles in the area, changing nothing when the area no longer has room or
     * a protection plugin refuses any part of it.
     */
    public Hung place(Player player, PlacementArea area, UUID photoId, GridOption grid, List<MapTile> tiles) {
        var backing = buildsBacking(player);
        var fit = area.fit(grid, backing);
        if (fit != Fit.FITS)
            return Hung.refused(fit);

        var invisible = plugin.config().invisibleFrames();
        var backingMaterial = resolveMaterial(plugin.config().backingMaterial());
        var world = area.world();
        var frameFacing = area.frameFacing();

        List<BlockState> built = new ArrayList<>();
        List<ItemFrame> frames = new ArrayList<>(tiles.size());
        for (var index = 0; index < tiles.size(); index++) {
            var frameBlock = area.frameBlock(grid, tiles.get(index));
            var backBlock = frameBlock.getRelative(area.forward());

            if (backing && backBlock.isEmpty()) {
                var replaced = backBlock.getState();
                backBlock.setType(backingMaterial, false);
                built.add(replaced);
                if (!Protection.mayPlace(player, backBlock, replaced, frameBlock)) {
                    takeDown(frames, built);
                    return Hung.refused(Fit.PROTECTED);
                }
            }

            final var tileIndex = index;
            var frame = world.spawn(frameBlock.getLocation(), ItemFrame.class, f -> {
                f.setFacingDirection(frameFacing, true);
                f.setVisible(!invisible);
                // Breakable on purpose; PhotoFrameListener handles breaking, rotating
                // and item removal, taking the whole photo down at once.
                f.setFixed(false);
                f.setPersistent(true);
                keys.tagFrame(f.getPersistentDataContainer(), photoId, tileIndex);
            });
            frames.add(frame);
            if (!Protection.mayHang(player, frame, backBlock, frameFacing)) {
                takeDown(frames, built);
                return Hung.refused(Fit.PROTECTED);
            }
        }

        List<Integer> mapIds = new ArrayList<>(tiles.size());
        List<UUID> frameIds = new ArrayList<>(tiles.size());
        for (var index = 0; index < tiles.size(); index++) {
            var view = mapService.createMapView(world, tiles.get(index).argb());
            var frame = frames.get(index);
            frame.setItem(mapService.itemFor(view), false);
            mapIds.add(view.getId());
            frameIds.add(frame.getUniqueId());
        }

        var base = area.base();
        return new Hung(new Placement(world.getUID(), mapIds, frameIds, base.getX(), base.getY(), base.getZ()),
                Fit.FITS);
    }

    /**
     * Undoes a half-hung photo, newest first, so every block gets back exactly what it
     * held.
     */
    private static void takeDown(List<ItemFrame> frames, List<BlockState> built) {
        for (var frame : frames)
            frame.remove();

        for (var index = built.size() - 1; index >= 0; index--)
            built.get(index).update(true, false);
    }

    private static Material resolveMaterial(String name) {
        var material = Material.matchMaterial(name);
        return (material != null && material.isBlock()) ? material : Material.STONE;
    }
}
