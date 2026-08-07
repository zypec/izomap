package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Harita karolarını dünyaya {@link ItemFrame} ızgarası olarak yerleştirir.
 *
 * <p>Duvar, oyuncunun baktığı yönde, oyuncuya bakacak şekilde kurulur. Görselin
 * yönü korunur: sol üst karo (col=0,row=0) ızgaranın sol üstünde belirir.
 * <b>Ana iş parçacığında</b> çağrılmalıdır (blok/entity işlemleri).</p>
 */
public final class MapPlacer {

    private final Izomap plugin;
    private final MapService mapService;

    public MapPlacer(Izomap plugin, MapService mapService) {
        this.plugin = plugin;
        this.mapService = mapService;
    }

    /**
     * Karoları yerleştirir. Yeterli boş alan yoksa {@code null} döner (hiçbir şey
     * değiştirilmez).
     */
    public PlacedPhoto place(Player player, Camera camera, String name, GridOption grid, List<MapTile> tiles) {
        World world = player.getWorld();
        BlockFace forward = horizontalFacing(player);
        BlockFace right = clockwise(forward);
        BlockFace frameFacing = forward.getOppositeFace(); // oyuncuya bakar

        int distance = plugin.config().placementDistance();
        Block base = player.getEyeLocation().getBlock().getRelative(forward, distance);
        int colOffset = (grid.cols() - 1) / 2;

        // Önce tüm hedef blokların boş olduğunu doğrula (non-destructive).
        for (MapTile tile : tiles) {
            if (!frameBlock(base, right, forward, colOffset, grid, tile).isEmpty()) {
                return null;
            }
        }

        boolean invisible = plugin.config().invisibleFrames();
        boolean backing = plugin.config().buildBackingWall();
        Material backingMaterial = resolveMaterial(plugin.config().backingMaterial());

        List<Integer> mapIds = new ArrayList<>(tiles.size());
        List<UUID> frameIds = new ArrayList<>(tiles.size());

        for (MapTile tile : tiles) {
            Block frameBlock = frameBlock(base, right, forward, colOffset, grid, tile);

            if (backing) {
                Block backBlock = frameBlock.getRelative(forward);
                if (backBlock.isEmpty()) {
                    backBlock.setType(backingMaterial, false);
                }
            }

            MapView view = mapService.createMapView(world, tile.argb());
            ItemStack mapItem = mapService.itemFor(view);

            ItemFrame frame = world.spawn(frameBlock.getLocation(), ItemFrame.class, f -> {
                f.setFacingDirection(frameFacing, true);
                f.setItem(mapItem, false);
                f.setVisible(!invisible);
                // fixed=false: kırılabilir olsun; kırılma/döndürme/eşya düşürme
                // PhotoFrameListener tarafından yönetilir (biri kırılınca hepsi kalkar).
                f.setFixed(false);
                f.setPersistent(true);
            });

            mapIds.add(view.getId());
            frameIds.add(frame.getUniqueId());
        }

        return new PlacedPhoto(UUID.randomUUID(), player.getUniqueId(), name, camera.name(),
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
        BlockFace facing = player.getFacing();
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing;
            default -> BlockFace.NORTH;
        };
    }

    /** Yukarıdan bakışta saat yönünde 90° (görüntünün +X/sağ ekseni). */
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
        Material material = Material.matchMaterial(name);
        return (material != null && material.isBlock()) ? material : Material.STONE;
    }
}
