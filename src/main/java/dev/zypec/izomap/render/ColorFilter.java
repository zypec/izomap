package dev.zypec.izomap.render;

import java.util.List;

/**
 * A named chain of {@link ColorOp}s, applied to a colour before it snaps to the map
 * palette.
 *
 * <p>Was an enum of four effects; the chains now come from {@code filters.yml} so a
 * server owner can describe their own. Only the id is ever written to disk, so a filter
 * keeps its cameras and photos across a rename of its display text.</p>
 *
 * <p>Display names stay in {@code messages.yml} under {@code filter.<ID>}, which keeps
 * every translatable string in one file; a filter with no message there shows its id.</p>
 */
public final class ColorFilter {

    /**
     * The filter that changes nothing. A constant rather than a file entry: it needs no
     * configuration, it is what a camera starts on and what an unknown id falls back to,
     * so the code should not have to look it up to be sure of having one.
     */
    public static final ColorFilter ORIGINAL = new ColorFilter("ORIGINAL", List.of());

    private final String id;
    private final List<ColorOp> ops;

    public ColorFilter(String id, List<ColorOp> ops) {
        this.id = id;
        this.ops = List.copyOf(ops);
    }

    public String id() {
        return id;
    }

    /**
     * Whether this filter leaves colours alone, which lets the pipeline skip the snap
     * that would otherwise follow it.
     */
    public boolean isIdentity() {
        return ops.isEmpty();
    }

    /**
     * Runs the chain over a 0xRRGGBB colour.
     */
    public int apply(int rgb) {
        var out = rgb;
        for (var op : ops) {
            out = op.apply(out);
        }
        return out;
    }

    @Override
    public String toString() {
        return id;
    }
}
