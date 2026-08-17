package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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

    private final Map<Long, ChunkSnapshot> chunks;
    private final int minY;
    private final int maxY;

    private WorldSnapshot(Map<Long, ChunkSnapshot> chunks, int minY, int maxY) {
        this.chunks = chunks;
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Builds a snapshot from ready chunk copies. Height limits are passed as values
     * rather than read off a {@code World}, so this is safe off the main thread.
     */
    public static WorldSnapshot of(Collection<ChunkSnapshot> snapshots, int minY, int maxY) {
        Map<Long, ChunkSnapshot> chunks = new HashMap<>(snapshots.size() * 2);
        for (var snapshot : snapshots) {
            chunks.put(key(snapshot.getX(), snapshot.getZ()), snapshot);
        }
        return new WorldSnapshot(chunks, minY, maxY);
    }

    /**
     * Material at a world coordinate, or AIR when the chunk was not copied.
     */
    public Material materialAt(int x, int y, int z) {
        if (y < minY || y >= maxY)
            return Material.AIR;

        var snapshot = chunks.get(key(x >> 4, z >> 4));
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

        var snapshot = chunks.get(key(x >> 4, z >> 4));
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

        var snapshot = chunks.get(key(x >> 4, z >> 4));
        return snapshot == null ? null : snapshot.getBiome(x & 15, y, z & 15);
    }

    /**
     * Brightest light reaching a cell, 0-15: the greater of the sky light that falls
     * into it and the light blocks around it emit.
     *
     * <p>Sky light is stored per block and does not follow the clock, so an outdoor
     * surface reads 15 at midnight too. That is on purpose — the sun this renderer
     * shades with is a fixed angle rather than the world's own (see {@link Shading}),
     * and a photo of a lit landscape should not turn black because it was taken at
     * night.</p>
     *
     * <p>Cells outside the captured region read as fully lit, so a chunk that could
     * not be copied leaves a hole rather than a shadow.</p>
     *
     * <p>Only meaningful when the chunks were copied with light data; without it the
     * server hands back zeroes, which would darken the whole photo. {@code RenderService}
     * asks for light exactly when the shading needs it.</p>
     */
    public int lightAt(int x, int y, int z) {
        if (y < minY || y >= maxY)
            return MAX_LIGHT;

        var snapshot = chunks.get(key(x >> 4, z >> 4));
        if (snapshot == null)
            return MAX_LIGHT;

        var local = x & 15;
        var localZ = z & 15;
        return Math.max(snapshot.getBlockSkyLight(local, y, localZ),
                snapshot.getBlockEmittedLight(local, y, localZ));
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
        return chunks.size();
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
