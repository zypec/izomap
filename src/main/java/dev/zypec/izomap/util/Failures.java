package dev.zypec.izomap.util;

import java.util.concurrent.CompletionException;

/**
 * Helpers for the failures that come out of a {@link java.util.concurrent.CompletableFuture}
 * chain.
 */
public final class Failures {

    private Failures() {
    }

    /**
     * The failure a future chain actually hit.
     *
     * <p>A chain reports its failures wrapped in a {@link CompletionException} that
     * carries no message and no type of its own, so both the {@code instanceof} checks
     * that pick out a specific failure and the text shown in a log have to look at what
     * is inside it.</p>
     */
    public static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }
}
