package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.Collection;

/**
 * Thread-safe snapshot of a region of the world.
 *
 * <p>Chunk and block access is only safe on the main thread, so this holds
 * {@link ChunkSnapshot} copies taken there and lets the voxel walk run
 * asynchronously against them.</p>
 *
 * <p>Chunks without a copy count as air. Loading and copying them is
 * {@link RenderService}'s job.</p>
 */
public final class WorldSnapshot {

    /**
     * Brightest a cell can be, and what an uncaptured one is taken to be.
     */
    private static final int MAX_LIGHT = 15;

    /**
     * The copies laid out on the grid of chunk columns they span, row by row in x.
     *
     * <p>Looked up on every block step of every ray. A {@code HashMap<Long, ...>} boxed
     * the key and hashed it each time; here a step costs a subtraction, a bounds check
     * and an array read. The grid is the box around the copies, so it holds empty slots
     * along a diagonal view, which costs a reference each.</p>
     */
    private final ChunkSnapshot[] chunks;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final int count;
    private final int minY;
    private final int maxY;

    private WorldSnapshot(ChunkSnapshot[] chunks, int originX, int originZ, int width, int depth,
                          int count, int minY, int maxY) {
        this.chunks = chunks;
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.count = count;
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Builds a snapshot from ready chunk copies. Height limits are passed as values
     * rather than read off a {@code World}, so this is safe off the main thread.
     */
    public static WorldSnapshot of(Collection<ChunkSnapshot> snapshots, int minY, int maxY) {
        if (snapshots.isEmpty())
            return new WorldSnapshot(new ChunkSnapshot[0], 0, 0, 0, 0, 0, minY, maxY);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var snapshot : snapshots) {
            minX = Math.min(minX, snapshot.getX());
            maxX = Math.max(maxX, snapshot.getX());
            minZ = Math.min(minZ, snapshot.getZ());
            maxZ = Math.max(maxZ, snapshot.getZ());
        }
        var width = maxX - minX + 1;
        var depth = maxZ - minZ + 1;
        var grid = new ChunkSnapshot[width * depth];
        var count = 0;
        for (var snapshot : snapshots) {
            var index = (snapshot.getX() - minX) * depth + (snapshot.getZ() - minZ);
            if (grid[index] == null) count++;
            grid[index] = snapshot;
        }
        return new WorldSnapshot(grid, minX, minZ, width, depth, count, minY, maxY);
    }

    /**
     * The copy of the chunk column holding this block, or {@code null}.
     */
    private ChunkSnapshot chunkAt(int x, int z) {
        var cx = (x >> 4) - originX;
        var cz = (z >> 4) - originZ;
        if (cx < 0 || cz < 0 || cx >= width || cz >= depth)
            return null;
        return chunks[cx * depth + cz];
    }

    /**
     * Material at a world coordinate, or AIR when the chunk was not copied.
     */
    public Material materialAt(int x, int y, int z) {
        if (y < minY || y >= maxY)
            return Material.AIR;

        var snapshot = chunkAt(x, z);
        return snapshot == null ? Material.AIR : snapshot.getBlockType(x & 15, y, z & 15);

    }

    /**
     * Full block state at a world coordinate, or {@code null} when the chunk was not
     * copied.
     *
     * <p>Costs an object per call, unlike {@link #materialAt}, so the walk only asks
     * for the few materials whose color depends on their state.</p>
     */
    public BlockData blockDataAt(int x, int y, int z) {
        if (y < minY || y >= maxY)
            return null;

        var snapshot = chunkAt(x, z);
        return snapshot == null ? null : snapshot.getBlockData(x & 15, y, z & 15);
    }

    /**
     * Biome at a world coordinate, or {@code null} when the chunk was not copied.
     *
     * <p>Only meaningful when the chunks were copied with biomes; {@code RenderService}
     * asks for them exactly when something is tinted by biome, since carrying them costs
     * copy time like the light does.</p>
     */
    public Biome biomeAt(int x, int y, int z) {
        if (y < minY || y >= maxY)
            return null;

        var snapshot = chunkAt(x, z);
        return snapshot == null ? null : snapshot.getBiome(x & 15, y, z & 15);
    }

    /**
     * Brightest light reaching a cell, 0-15: the greater of the sky light that falls
     * into it and the light blocks around it emit.
     *
     * <p>Sky light is stored per block and does not follow the clock, so an outdoor
     * surface reads 15 at midnight here. A night sky is applied by the overload below,
     * from the photo's own sky rather than the world's clock.</p>
     *
     * <p>Cells outside the captured region read as fully lit, so a chunk that could
     * not be copied leaves a hole rather than a shadow.</p>
     *
     * <p>Only meaningful when the chunks were copied with light data; without it the
     * server hands back zeroes, which would darken the whole photo. {@code RenderService}
     * asks for light exactly when the shading needs it.</p>
     */
    public int lightAt(int x, int y, int z) {
        return lightAt(x, y, z, MAX_LIGHT);
    }

    /**
     * The same under a sky giving at most {@code skyLight}: the stored sky light loses
     * what the sky lacks, as the game darkens it at night, while emitted light is kept.
     */
    public int lightAt(int x, int y, int z, int skyLight) {
        if (y < minY || y >= maxY)
            return skyLight;

        var snapshot = chunkAt(x, z);
        if (snapshot == null)
            return skyLight;

        var local = x & 15;
        var localZ = z & 15;
        var sky = Math.max(0, snapshot.getBlockSkyLight(local, y, localZ) - (MAX_LIGHT - skyLight));
        return Math.max(sky, snapshot.getBlockEmittedLight(local, y, localZ));
    }

    /**
     * Lowest block height of the world (inclusive).
     */
    public int minY() {
        return minY;
    }

    /**
     * Highest block height of the world (exclusive).
     */
    public int maxY() {
        return maxY;
    }

    public int chunkCount() {
        return count;
    }

    /**
     * Packs chunk coordinates into a single {@code long} key.
     */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
