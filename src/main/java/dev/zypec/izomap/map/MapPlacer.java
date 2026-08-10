package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.render.CaptureSpec;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Places map tiles in the world as a grid of {@link ItemFrame}s.
 *
 * <p>The wall goes up in front of the player, facing them, and the image keeps its
 * orientation: tile (0,0) ends up top-left. Must be called on the main thread.</p>
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
     * Places the tiles or returns {@code null} without changing anything when there
     * is not enough free space.
     */
    public PlacedPhoto place(Player player, Camera camera, CaptureSpec spec, String name,
                             GridOption grid, List<MapTile> tiles) {
        var world = player.getWorld();
        var forward = horizontalFacing(player);
        var right = clockwise(forward);
        var frameFacing = forward.getOppositeFace(); // faces the player

        var distance = plugin.config().placementDistance();
        var base = player.getEyeLocation().getBlock().getRelative(forward, distance);
        var colOffset = (grid.cols() - 1) / 2;

        // Verify every target block is free first, so placement stays non-destructive.
        for (var tile : tiles)
            if (!frameBlock(base, right, forward, colOffset, grid, tile).isEmpty())
                return null;

        var invisible = plugin.config().invisibleFrames();
        var backing = plugin.config().buildBackingWall();
        var backingMaterial = resolveMaterial(plugin.config().backingMaterial());

        // Known before the frames exist so each one can carry the id in its PDC.
        var photoId = UUID.randomUUID();
        List<Integer> mapIds = new ArrayList<>(tiles.size());
        List<UUID> frameIds = new ArrayList<>(tiles.size());

        for (int index = 0; index < tiles.size(); index++) {
            var tile = tiles.get(index);
            var frameBlock = frameBlock(base, right, forward, colOffset, grid, tile);

            if (backing) {
                var backBlock = frameBlock.getRelative(forward);
                if (backBlock.isEmpty()) {
                    backBlock.setType(backingMaterial, false);
                }
            }

            var view = mapService.createMapView(world, tile.argb());
            var mapItem = mapService.itemFor(view);

            final int tileIndex = index;
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

        return new PlacedPhoto(photoId, player.getUniqueId(), name, camera.name(), spec,
                world.getUID(), grid, mapIds, frameIds,
                base.getX(), base.getY(), base.getZ());
    }

    private static Block frameBlock(Block base, BlockFace right, BlockFace forward,
                                    int colOffset, GridOption grid, MapTile tile) {
        return base
                .getRelative(right, tile.col() - colOffset)
                .getRelative(BlockFace.UP, (grid.rows() - 1) - tile.row());
    }

    private static BlockFace horizontalFacing(Player player) {
        var facing = player.getFacing();
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing;
            default -> BlockFace.NORTH;
        };
    }

    /**
     * 90° clockwise seen from above: the image's +X axis.
     */
    private static BlockFace clockwise(BlockFace forward) {
        return switch (forward) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private static Material resolveMaterial(String name) {
        var material = Material.matchMaterial(name);
        return (material != null && material.isBlock()) ? material : Material.STONE;
    }
}
