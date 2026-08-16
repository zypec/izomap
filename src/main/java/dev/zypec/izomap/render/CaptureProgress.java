package dev.zypec.izomap.render;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How far along a capture is, for the surfaces that say so while it runs.
 *
 * <p>Counted in image rows, because that is the unit the walk finishes one at a time
 * and the only one it can report without slowing down: a row is thousands of rays, so
 * incrementing per row costs nothing measurable.</p>
 *
 * <p>It covers the ray walk alone. Copying the chunks comes first and is not counted,
 * so a capture that has to read cold chunks from disk sits at zero for a moment before
 * it starts climbing. Weighing the two phases against each other would need to know how
 * long a chunk read takes, which varies by more than the estimate would be worth.</p>
 *
 * <p>Written by every render thread and read by the main one, hence the atomics.</p>
 */
public final class CaptureProgress {

    /**
     * Cells in the bar {@link #bar()} draws.
     */
    private static final int BAR_CELLS = 10;
    private static final char BAR_FULL = '▰';
    private static final char BAR_EMPTY = '▱';

    private final AtomicInteger done = new AtomicInteger();
    private volatile int total;

    /**
     * Declares how many rows the walk is about to cover.
     */
    public void expect(int rows) {
        total = Math.max(0, rows);
        done.set(0);
    }

    /**
     * Reports one more finished row. Called from the render threads.
     */
    public void advance() {
        done.incrementAndGet();
    }

    /**
     * Completion as a whole percentage, 0 before the walk begins.
     */
    public int percent() {
        var rows = total;
        if (rows <= 0) return 0;

        return Math.min(100, (int) (done.get() * 100L / rows));
    }

    /**
     * The same as a bar, for a message that wants one without doing the arithmetic.
     */
    public String bar() {
        var filled = percent() * BAR_CELLS / 100;
        var out = new StringBuilder(BAR_CELLS);
        for (var cell = 0; cell < BAR_CELLS; cell++) {
            out.append(cell < filled ? BAR_FULL : BAR_EMPTY);
        }
        return out.toString();
    }
}
