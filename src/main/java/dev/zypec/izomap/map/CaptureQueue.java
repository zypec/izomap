package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Lets at most {@code settings.max-concurrent-captures} photos render at once, and each
 * camera shoot one at a time.
 *
 * <p>Every capture holds its own copy of the chunks it covers until its walk is done:
 * a thousand chunks by default, thousands when {@code max-capture-area} is raised.
 * Nothing used to bound how many ran together, so a busy evening, or a startup with a
 * dozen photos to re-render, could hold several of those in memory at once and split
 * the render pool between them. The rest wait here, in order.</p>
 *
 * <p>The live preview does not pass through: it is one small map per camera, and a
 * preview stuck behind a 16x9 photo would look like a dead camera.</p>
 *
 * <p>Main thread only.</p>
 */
public final class CaptureQueue {

    private record Job(UUID key, Supplier<CompletableFuture<?>> start, Consumer<Boolean> started) {
    }

    private final Izomap plugin;
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    /**
     * Keys of every job queued or running: a camera, or a photo shot from its own spec.
     */
    private final Set<UUID> busy = new HashSet<>();
    private int running;

    public CaptureQueue(Izomap plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether a job under this key is queued or running.
     */
    public boolean busy(UUID key) {
        return key != null && busy.contains(key);
    }

    /**
     * Runs {@code start} as soon as a slot is free.
     *
     * @param key     what may only shoot once at a time, usually the camera; {@code null}
     *                for a job that does not care
     * @param queued  told how many jobs are ahead when this one has to wait; not called
     *                when it starts at once
     * @param started told when the job starts, with whether it had to wait first
     * @return whether the job was accepted; {@code false} when its key is busy already
     */
    public boolean submit(UUID key, Supplier<CompletableFuture<?>> start, IntConsumer queued,
                          Consumer<Boolean> started) {
        if (busy(key))
            return false;

        if (key != null)
            busy.add(key);

        var job = new Job(key, start, started);
        if (running < plugin.config().maxConcurrentCaptures()) {
            run(job, false);
        } else {
            waiting.add(job);
            if (queued != null)
                queued.accept(waiting.size());
        }
        return true;
    }

    private void run(Job job, boolean waited) {
        running++;
        if (job.started() != null)
            job.started().accept(waited);

        CompletableFuture<?> future;
        try {
            future = job.start().get();
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        future.whenComplete((ignored, error) -> plugin.runOnMain(() -> finish(job)));
    }

    private void finish(Job job) {
        running--;
        if (job.key() != null)
            busy.remove(job.key());

        while (running < plugin.config().maxConcurrentCaptures() && !waiting.isEmpty())
            run(waiting.poll(), true);
    }
}
