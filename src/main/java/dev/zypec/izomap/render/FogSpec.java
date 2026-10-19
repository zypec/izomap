package dev.zypec.izomap.render;

/**
 * Distance fog: surfaces far from the camera plane fade towards the sky behind them.
 *
 * <h2>Why a parallel projection wants it</h2>
 *
 * <p>An orthographic image has no perspective to say what is near and what is far: a
 * hill a hundred blocks back is drawn at the same size as the one in front. Fading with
 * depth gives the eye that cue back, the way haze does over a real landscape.</p>
 *
 * <p>Distances are in blocks from the camera plane, the same depth {@link FocusPass}
 * reads, so the fog sits on the same place in the world whatever the zoom or grid.
 * Frozen into {@link CaptureSpec} like the other settings the image depends on.</p>
 *
 * @param enabled  whether the pass runs at all
 * @param start    depth at which the fog begins, in blocks
 * @param end      depth at which it reaches {@code strength}, in blocks
 * @param strength share of the fog color at {@code end} and beyond, 0 to 1
 * @param rgb      fog color as 0xRRGGBB, or {@link #SKY} to take the sky's own color
 *                 at each row
 * @param dither   how far a fogged pixel may stray from its true colour when it is
 *                 snapped back to the palette, in channel steps; 0 disables dithering
 */
public record FogSpec(
        boolean enabled,
        double start,
        double end,
        double strength,
        int rgb,
        double dither) {

    /**
     * The fog takes the color of the sky at the same row of the frame.
     */
    public static final int SKY = -1;

    /**
     * No fog, as the renderer has always drawn.
     */
    public static final FogSpec NONE = new FogSpec(false, 0.0, 0.0, 0.0, SKY, 0.0);

    /**
     * Whether the render has to carry a depth buffer and run the pass.
     */
    public boolean draws() {
        return enabled && strength > 0.0;
    }
}
