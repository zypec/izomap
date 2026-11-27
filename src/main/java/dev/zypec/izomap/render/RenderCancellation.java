package dev.zypec.izomap.render;

import java.util.concurrent.CancellationException;

/**
 * Lets whoever started a render call it off part-way.
 *
 * <p>Asked between chunk copies on the main thread and between image rows in the
 * render pool, so a render nobody wants any more stops within a tick or a row rather
 * than running to the end. Set from the main thread, read from every render thread,
 * hence the volatile.</p>
 */
public final class RenderCancellation {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /**
     * Unwinds the render when it has been called off.
     */
    void check() {
        if (cancelled)
            throw new CancellationException("Render cancelled.");
    }
}
