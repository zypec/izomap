package dev.zypec.izomap.render;

/**
 * Thrown when the frame needs more chunks than {@code settings.max-capture-area}
 * allows. Rejecting the capture beats stalling the server on hundreds of megabytes
 * of chunk copies.
 */
public final class CaptureTooLargeException extends RuntimeException {

    private final int required;
    private final int budget;

    public CaptureTooLargeException(int required, int budget) {
        super("Capture needs " + required + " chunks, the limit is " + budget + ".");
        this.required = required;
        this.budget = budget;
    }

    /**
     * Number of chunks the frame requires.
     */
    public int required() {
        return required;
    }

    /**
     * Configured upper bound.
     */
    public int budget() {
        return budget;
    }
}
