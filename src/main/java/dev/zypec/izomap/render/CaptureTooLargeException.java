package dev.zypec.izomap.render;

/**
 * Kadraj, {@code settings.max-chunks-per-capture} sınırından daha fazla chunk
 * gerektirdiğinde fırlatılır.
 *
 * <p>Çekimi reddetmek, sunucuyu yüzlerce megabaytlık chunk kopyasıyla dondurmaya
 * yeğdir; oyuncuya yakınlaşması (zoom artırması) söylenir.</p>
 */
public final class CaptureTooLargeException extends RuntimeException {

    private final int required;
    private final int budget;

    public CaptureTooLargeException(int required, int budget) {
        super("Çekim " + required + " chunk gerektiriyor, sınır " + budget + ".");
        this.required = required;
        this.budget = budget;
    }

    /** Kadrajın gerektirdiği chunk sayısı. */
    public int required() {
        return required;
    }

    /** Yapılandırmadaki üst sınır. */
    public int budget() {
        return budget;
    }
}
