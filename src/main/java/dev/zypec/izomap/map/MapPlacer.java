package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Puts map tiles up in the world as a grid of {@link ItemFrame}s.
 *
 * <p>Where they go is decided by {@link PlacementArea}, which the ghost preview lines
 * up beforehand. Must be called on the main thread.</p>
 */
public final class MapPlacer {

    private final Izomap plugin;
    private final MapService mapService;
    private final PhotoKeys keys;

    public MapPlacer(Izomap plugin, MapService mapService, PhotoKeys keys) {
        this.plugin = plugin;
        this.mapService = mapService;
        this.keys = keys;
    }

    /**
     * Hangs the tiles in the area, or returns {@code null} without changing anything
     * when the area no longer has room.
     */
    public Placement place(PlacementArea area, UUID photoId, GridOption grid, List<MapTile> tiles) {
        var backing = plugin.config().buildBackingWall();
        if (!area.fits(grid, backing))
            return null;

        var invisible = plugin.config().invisibleFrames();
        var backingMaterial = resolveMaterial(plugin.config().backingMaterial());
        var world = area.world();
        var frameFacing = area.frameFacing();

        List<Integer> mapIds = new ArrayList<>(tiles.size());
        List<UUID> frameIds = new ArrayList<>(tiles.size());

        for (var index = 0; index < tiles.size(); index++) {
            var tile = tiles.get(index);
            var frameBlock = area.frameBlock(grid, tile);

            if (backing) {
                var backBlock = frameBlock.getRelative(area.forward());
                if (backBlock.isEmpty()) {
                    backBlock.setType(backingMaterial, false);
                }
            }

            var view = mapService.createMapView(world, tile.argb());
            var mapItem = mapService.itemFor(view);

            final var tileIndex = index;
            var frame = world.spawn(frameBlock.getLocation(), ItemFrame.class, f -> {
                f.setFacingDirection(frameFacing, true);
                f.setItem(mapItem, false);
                f.setVisible(!invisible);
                // Breakable on purpose; PhotoFrameListener handles breaking, rotating
                // and item removal, taking the whole photo down at once.
                f.setFixed(false);
                f.setPersistent(true);
                keys.tagFrame(f.getPersistentDataContainer(), photoId, tileIndex);
            });

            mapIds.add(view.getId());
            frameIds.add(frame.getUniqueId());
        }

        var base = area.base();
        return new Placement(world.getUID(), mapIds, frameIds, base.getX(), base.getY(), base.getZ());
    }

    private static Material resolveMaterial(String name) {
        var material = Material.matchMaterial(name);
        return (material != null && material.isBlock()) ? material : Material.STONE;
    }
}
