package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The live preview's chunk copies, and the loads it does not wait for.
 *
 * <p><b>Copies are kept</b>, so a click copies only what came into the frame. A copy of a
 * chunk that was loaded is used again while it is younger than {@link #NEAR_AGE_NANOS},
 * so the preview lags a change in the world by at most that; one this cache loaded
 * itself lies where no player is, and is kept for {@link #FAR_AGE_NANOS}.</p>
 *
 * <p><b>Unloaded chunks are fetched in the background.</b> A wide frame reaches thousands
 * of chunks nobody is near, and waiting for the disk made every click on such a camera
 * take seconds before anything showed: measured on the development server, 3,126 chunks,
 * 3.7 to 4 s of copying against 0.1 to 0.2 s of ray walk. The preview now draws what is
 * there at once and asks for the rest here, {@link #MAX_LOADING} at a time; as they land
 * the preview is drawn again, at most once per {@link #NOTIFY_NANOS}, and once more when
 * the last one is in. Photos never read from it and always wait for every chunk.</p>
 *
 * <p>Main thread only; Paper completes chunk loads there.</p>
 */
public final class ChunkCache {

    private static final long NEAR_AGE_NANOS = 5_000_000_000L;
    private static final long FAR_AGE_NANOS = 60_000_000_000L;
    private static final long NOTIFY_NANOS = 1_000_000_000L;
    private static final int MAX_LOADING = 32;

    private record Entry(ChunkSnapshot snapshot, boolean light, boolean biomes, long copiedAt, boolean far) {
    }

    /**
     * A live preview's cache: short-lived copies, far chunks fetched in the background.
     */
    public ChunkCache() {
        this(false, NEAR_AGE_NANOS, FAR_AGE_NANOS);
    }

    private ChunkCache(boolean waits, long nearAge, long farAge) {
        this.waits = waits;
        this.nearAge = nearAge;
        this.farAge = farAge;
    }

    /**
     * A cache for a run of photos of one place, such as the development tools' matrix:
     * copies never expire and every capture waits for all of its chunks, so the first
     * photo copies the world and the rest read it.
     */
    public static ChunkCache keeping() {
        return new ChunkCache(true, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * The web editor's cache for one camera: the preview waits for every chunk, as the
     * editor shows nothing until it has the whole picture, and the copies outlast a few
     * minutes of settings being tried, where the game's preview keeps them for seconds.
     */
    public static ChunkCache forEditor() {
        return new ChunkCache(true, 120_000_000_000L, 240_000_000_000L);
    }

    boolean waits() {
        return waits;
    }

    private long maxAge(Entry entry) {
        return entry.far() ? farAge : nearAge;
    }

    /**
     * Whether a capture reading this cache waits for every chunk, as a photo does, rather
     * than drawing without the ones still loading, as the preview does.
     */
    private final boolean waits;
    private final long nearAge;
    private final long farAge;

    private final Map<Long, Entry> entries = new HashMap<>();
    private final ArrayDeque<Long> wanted = new ArrayDeque<>();
    private final Set<Long> loading = new HashSet<>();
    private UUID worldId;
    private World world;
    private boolean light;
    private boolean biomes;
    private boolean generate;
    private Runnable listener;
    private long notifiedAt;
    private boolean landedSinceNotify;

    /**
     * What to run when fetched chunks have landed, so the preview can be drawn again.
     */
    public void onFetched(Runnable listener) {
        this.listener = listener;
    }

    /**
     * A fresh enough copy taken with the same extras, or {@code null}.
     */
    ChunkSnapshot get(UUID world, long key, boolean light, boolean biomes) {
        if (!world.equals(worldId))
            return null;

        var entry = entries.get(key);
        if (entry == null || entry.light() != light || entry.biomes() != biomes)
            return null;
        return System.nanoTime() - entry.copiedAt() < maxAge(entry) ? entry.snapshot() : null;
    }

    /** Any copy of the chunk at all, however old and with whatever extras: enough to say where its blocks are. */
    ChunkSnapshot any(UUID world, long key) {
        if (!world.equals(worldId)) return null;
        var entry = entries.get(key);
        return entry == null ? null : entry.snapshot();
    }

    void put(UUID world, long key, ChunkSnapshot snapshot, boolean light, boolean biomes) {
        put(world, key, snapshot, light, biomes, false);
    }

    private void put(UUID world, long key, ChunkSnapshot snapshot, boolean light, boolean biomes, boolean far) {
        if (!world.equals(worldId)) {
            entries.clear();
            wanted.clear();
            worldId = world;
        }
        entries.put(key, new Entry(snapshot, light, biomes, System.nanoTime(), far));
    }

    /**
     * Sets what the frame needs that is neither loaded nor kept, replacing what an older
     * frame wanted and has not been asked for yet. Loads already under way carry on.
     */
    void want(World world, Collection<Long> keys, boolean light, boolean biomes, boolean generate) {
        if (!world.getUID().equals(worldId)) {
            entries.clear();
            worldId = world.getUID();
        }
        this.world = world;
        this.light = light;
        this.biomes = biomes;
        this.generate = generate;
        wanted.clear();
        for (var key : keys) {
            if (!loading.contains(key))
                wanted.add(key);
        }
        requestMore();
    }

    /**
     * Whether chunks of the last frame are still on their way.
     */
    boolean fetching() {
        return !wanted.isEmpty() || !loading.isEmpty();
    }

    private void requestMore() {
        while (loading.size() < MAX_LOADING && !wanted.isEmpty()) {
            var key = wanted.poll();
            if (!loading.add(key))
                continue;

            var target = world;
            var withLight = light;
            var withBiomes = biomes;
            target.getChunkAtAsync(WorldSnapshot.chunkX(key), WorldSnapshot.chunkZ(key), generate)
                    .whenComplete((chunk, error) -> {
                        loading.remove(key);
                        if (chunk != null && error == null && target.getUID().equals(worldId)) {
                            put(worldId, key, chunk.getChunkSnapshot(false, withBiomes, false, withLight),
                                    withLight, withBiomes, true);
                            landedSinceNotify = true;
                        }
                        requestMore();
                        notifyIfDue();
                    });
        }
    }

    private void notifyIfDue() {
        if (listener == null || !landedSinceNotify)
            return;

        var now = System.nanoTime();
        if (fetching() && now - notifiedAt < NOTIFY_NANOS)
            return;

        notifiedAt = now;
        landedSinceNotify = false;
        listener.run();
    }

    /**
     * Lets go of every copy too old to be used again.
     */
    void prune() {
        var now = System.nanoTime();
        entries.values().removeIf(entry -> now - entry.copiedAt() >= maxAge(entry));
    }

    public void clear() {
        entries.clear();
        wanted.clear();
        worldId = null;
        world = null;
        listener = null;
    }
}
