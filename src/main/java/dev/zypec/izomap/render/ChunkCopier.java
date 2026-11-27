package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Copies the chunks a capture needs on the main thread, a few milliseconds per tick.
 *
 * <p>Copying every loaded chunk at once put the whole copy in one tick: 300 to 700 chunks
 * for a top-down shot, thousands for a horizon, and the server stalled for as long. The
 * copy now runs until {@code settings.chunk-copy-budget-ms} is spent and carries on in
 * the next tick; at least one chunk is copied per tick so a capture always moves.</p>
 *
 * <p>Chunks that are not loaded are all requested asynchronously at once and queued for
 * the copy as they arrive. Asking for them a few dozen at a time was tried and made a
 * wide frame take minutes to load without making the longest tick any shorter.</p>
 *
 * <p>A chunk may unload between ticks. One that was loaded when the capture started
 * holds no ticket of ours, so if it is gone by its turn it is loaded again
 * asynchronously instead of being skipped. One this copier loaded itself is held with a
 * plugin chunk ticket from the moment it arrives until it is copied: async loading holds
 * none, and the chunk would otherwise be free to unload while it waits in the queue.</p>
 *
 * <p>Main thread only, queue included; Paper runs {@code getChunkAtAsync} callbacks
 * there too.</p>
 */
final class ChunkCopier {

    private record Pending(int x, int z, boolean ticketed) {
    }

    private final Izomap plugin;
    private final World world;
    private final int requested;
    private final boolean light;
    private final boolean biomes;
    private final boolean load;
    private final boolean generate;
    private final long budgetNanos;
    private final RenderCancellation cancellation;
    private final ChunkCache cache;

    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    /**
     * Chunks to request, as packed keys: everything unloaded at the start, and anything
     * that unloaded before its turn.
     */
    private final ArrayDeque<Long> toLoad = new ArrayDeque<>();
    private final List<ChunkSnapshot> copied = new ArrayList<>();
    private final CompletableFuture<List<ChunkSnapshot>> result = new CompletableFuture<>();
    private int loading;
    private ScheduledTask task;

    private ChunkCopier(Izomap plugin, World world, int requested, boolean light, boolean biomes,
                        RenderCancellation cancellation, ChunkCache cache) {
        this.plugin = plugin;
        this.world = world;
        this.requested = requested;
        this.light = light;
        this.biomes = biomes;
        this.load = plugin.config().loadMissingChunks();
        this.generate = plugin.config().generateMissingChunks();
        this.budgetNanos = plugin.config().chunkCopyBudgetMs() * 1_000_000L;
        this.cancellation = cancellation;
        this.cache = cache;
    }

    /**
     * Starts copying and spends the first slice of the budget straight away, so a
     * small capture such as the preview finishes without waiting for a tick.
     */
    static CompletableFuture<List<ChunkSnapshot>> copy(Izomap plugin, World world, Set<Long> keys,
                                                       boolean light, boolean biomes,
                                                       RenderCancellation cancellation, ChunkCache cache) {
        var copier = new ChunkCopier(plugin, world, keys.size(), light, biomes, cancellation, cache);
        if (cache != null)
            cache.prune();
        for (var key : keys) {
            var cached = cache != null ? cache.get(world.getUID(), key, light, biomes) : null;
            if (cached != null) {
                copier.copied.add(cached);
                continue;
            }
            var x = WorldSnapshot.chunkX(key);
            var z = WorldSnapshot.chunkZ(key);
            if (world.isChunkLoaded(x, z)) {
                copier.queue.add(new Pending(x, z, false));
            } else if (copier.load) {
                copier.toLoad.add(key);
            }
        }
        copier.run();
        return copier.result;
    }

    int requested() {
        return requested;
    }

    /**
     * Requests every chunk waiting to be loaded.
     */
    private void requestMore() {
        while (!toLoad.isEmpty()) {
            var key = toLoad.poll();
            request(WorldSnapshot.chunkX(key), WorldSnapshot.chunkZ(key));
        }
    }

    /**
     * Loads a chunk off the main thread and queues it once it is in. With
     * {@code generate} off an ungenerated chunk arrives as {@code null} and is left out.
     */
    private void request(int x, int z) {
        loading++;
        world.getChunkAtAsync(x, z, generate).whenComplete((chunk, error) -> {
            loading--;
            if (result.isDone()) {
                // Called off, but the load was paid for: the next render of the same
                // preview, which is why this one was called off, gets the copy.
                if (cache != null && chunk != null && error == null)
                    cache.put(world.getUID(), WorldSnapshot.key(x, z),
                            chunk.getChunkSnapshot(false, biomes, false, light), light, biomes);
                return;
            }

            if (chunk != null && error == null) {
                world.addPluginChunkTicket(x, z, plugin);
                queue.add(new Pending(x, z, true));
            }
            ensureScheduled();
        });
    }

    private void ensureScheduled() {
        if (task == null && !result.isDone())
            task = plugin.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, scheduled -> run(), 1L, 1L);
    }

    /**
     * Copies until this tick's budget is spent.
     */
    private void run() {
        if (result.isDone()) {
            stop();
            return;
        }
        if (cancellation != null && cancellation.cancelled()) {
            abandon();
            return;
        }
        requestMore();
        var start = System.nanoTime();
        do {
            var next = queue.poll();
            if (next == null) break;
            copyOne(next);
        } while (System.nanoTime() - start < budgetNanos);

        if (!finishIfDone())
            ensureScheduled();
    }

    private void copyOne(Pending chunk) {
        if (world.isChunkLoaded(chunk.x(), chunk.z())) {
            var snapshot = world.getChunkAt(chunk.x(), chunk.z()).getChunkSnapshot(false, biomes, false, light);
            copied.add(snapshot);
            if (cache != null)
                cache.put(world.getUID(), WorldSnapshot.key(chunk.x(), chunk.z()), snapshot, light, biomes);
        } else if (!chunk.ticketed()) {
            // It was loaded when the capture began, so it exists; only its turn came late.
            toLoad.add(WorldSnapshot.key(chunk.x(), chunk.z()));
        }
        if (chunk.ticketed())
            world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
    }

    private boolean finishIfDone() {
        if (!queue.isEmpty() || loading > 0 || !toLoad.isEmpty())
            return false;

        stop();
        result.complete(copied);
        return true;
    }

    private void abandon() {
        for (var chunk : queue) {
            if (chunk.ticketed())
                world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
        queue.clear();
        toLoad.clear();
        stop();
        result.completeExceptionally(new CancellationException("Render cancelled."));
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
