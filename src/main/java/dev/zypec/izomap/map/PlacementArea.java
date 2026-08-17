package dev.zypec.izomap.map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Where a photo would hang: the anchor block plus the axes its grid grows along.
 *
 * <p>The wall goes up in front of the player and faces them, and the image keeps its
 * orientation: tile (0,0) ends up top-left.</p>
 *
 * <p>The ghost preview and the real placement both work from this, so what a player
 * lines up is exactly what they get. Areas compare by value, which is how the preview
 * notices it has to move at all.</p>
 */
public record PlacementArea(World world, Block base, BlockFace right, BlockFace forward) {

    /**
     * How far below eye level the bottom row sits, in blocks.
     *
     * <p>Anchored at the eyes the grid grew straight up from them, so a photo hung
     * standing on the floor came out a row too high — the whole thing had to be aimed
     * from a crouch to sit at eye height.</p>
     */
    private static final int ANCHOR_DROP = 1;

    /**
     * Area a player would place into right now, {@code distance} blocks ahead of them.
     */
    public static PlacementArea inFrontOf(Player player, int distance) {
        var forward = horizontalFacing(player);
        return new PlacementArea(
                player.getWorld(),
                player.getEyeLocation().getBlock()
                        .getRelative(forward, distance)
                        .getRelative(BlockFace.DOWN, ANCHOR_DROP),
                clockwise(forward),
                forward);
    }

    /**
     * Direction the frames look, which is back at the player.
     */
    public BlockFace frameFacing() {
        return forward.getOppositeFace();
    }

    /**
     * Block a tile hangs on.
     */
    public Block frameBlock(GridOption grid, int col, int row) {
        return base
                .getRelative(right, col - (grid.cols() - 1) / 2)
                .getRelative(BlockFace.UP, (grid.rows() - 1) - row);
    }

    public Block frameBlock(GridOption grid, MapTile tile) {
        return frameBlock(grid, tile.col(), tile.row());
    }

    /**
     * Whether the grid has room here.
     *
     * <p>Every frame block must be free either way. What sits <i>behind</i> them only
     * matters when no wall is built: an item frame needs something to hang on, and
     * without this check the photo would go up and quietly fall off. With the wall
     * turned on the gaps are filled in, and an existing block is left in place and
     * used as the backing.</p>
     */
    public boolean fits(GridOption grid, boolean buildBackingWall) {
        for (var row = 0; row < grid.rows(); row++) {
            for (var col = 0; col < grid.cols(); col++) {
                var block = frameBlock(grid, col, row);
                if (!block.isEmpty())
                    return false;
                if (!buildBackingWall && !block.getRelative(forward).getType().isSolid())
                    return false;
            }
        }
        return true;
    }

    /**
     * Center of the grid, for anything that addresses the photo as a whole.
     */
    public Location center(GridOption grid) {
        var first = frameBlock(grid, 0, grid.rows() - 1).getLocation();
        var last = frameBlock(grid, grid.cols() - 1, 0).getLocation();
        return new Location(world,
                (first.getX() + last.getX()) / 2.0 + 0.5,
                (first.getY() + last.getY()) / 2.0 + 0.5,
                (first.getZ() + last.getZ()) / 2.0 + 0.5);
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
}
