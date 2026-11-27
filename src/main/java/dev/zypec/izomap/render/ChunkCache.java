package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chunk copies kept for a few seconds, so a preview being adjusted click after click
 * copies only the chunks that came into its frame.
 *
 * <p>The preview draws a 128 pixel map, yet every click used to copy every chunk the
 * frame covers again: turning a camera by 15 degrees changed a handful of them and
 * copied hundreds. A copy here is used again while it is younger than
 * {@link #MAX_AGE_NANOS}, which makes the preview up to that much behind a change in
 * the world and never more. Photos never read from it.</p>
 *
 * <p>A copy is only reused for a capture that wants the same extras (light, biomes),
 * since one taken without them cannot answer for them. Main thread only, like the
 * copier that fills it.</p>
 */
public final class ChunkCache {

    private static final long MAX_AGE_NANOS = 5_000_000_000L;

    private record Entry(ChunkSnapshot snapshot, boolean light, boolean biomes, long copiedAt) {
    }

    private final Map<Long, Entry> entries = new HashMap<>();
    private UUID worldId;

    /**
     * A fresh enough copy taken with the same extras, or {@code null}.
     */
    ChunkSnapshot get(UUID world, long key, boolean light, boolean biomes) {
        if (!world.equals(worldId))
            return null;

        var entry = entries.get(key);
        if (entry == null || entry.light() != light || entry.biomes() != biomes)
            return null;
        return System.nanoTime() - entry.copiedAt() < MAX_AGE_NANOS ? entry.snapshot() : null;
    }

    void put(UUID world, long key, ChunkSnapshot snapshot, boolean light, boolean biomes) {
        if (!world.equals(worldId)) {
            entries.clear();
            worldId = world;
        }
        entries.put(key, new Entry(snapshot, light, biomes, System.nanoTime()));
    }

    /**
     * Lets go of every copy too old to be used again, so the cache holds at most what
     * the last few seconds of renders covered.
     */
    void prune() {
        var now = System.nanoTime();
        entries.values().removeIf(entry -> now - entry.copiedAt() >= MAX_AGE_NANOS);
    }

    public void clear() {
        entries.clear();
        worldId = null;
    }
}
