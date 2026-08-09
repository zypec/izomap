package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

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
        for (ChunkSnapshot snapshot : snapshots) {
            chunks.put(key(snapshot.getX(), snapshot.getZ()), snapshot);
        }
        return new WorldSnapshot(chunks, minY, maxY);
    }

    /** Material at a world coordinate, or AIR when the chunk was not copied. */
    public Material materialAt(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return Material.AIR;
        }
        ChunkSnapshot snapshot = chunks.get(key(x >> 4, z >> 4));
        if (snapshot == null) {
            return Material.AIR;
        }
        return snapshot.getBlockType(x & 15, y, z & 15);
    }

    /** Lowest block height of the world (inclusive). */
    public int minY() {
        return minY;
    }

    /** Highest block height of the world (exclusive). */
    public int maxY() {
        return maxY;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Packs chunk coordinates into a single {@code long} key. */
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
