package dev.zypec.izomap.render;

/**
 * Depth of field: which distance stays sharp, and how everything else softens.
 *
 * <h2>There is no lens here</h2>
 *
 * <p>The projection is orthographic, so nothing about it produces a circle of
 * confusion on its own — parallel rays have no aperture to be wide or narrow. What
 * this describes is therefore not an optical effect being simulated but a chosen one:
 * a focus plane at {@link #distance} blocks from the camera plane, and a blur that
 * grows with how far a pixel's depth sits from it. On a tilted camera the result is
 * the tilt-shift look; on a level one it separates a subject from what is behind it.</p>
 *
 * <h2>Why the two sizes are ratios</h2>
 *
 * <p>A photo is rendered at anything from a 128px preview tile to 2048px across, and
 * the same shot must read the same at both. {@link #rangeRatio} is therefore measured
 * against the frame's world height (so zoom carries it) and {@link #maxRadius} against
 * the image's pixel height (so resolution carries it). Neither is a pixel or a block
 * count that would mean something different on the next grid.</p>
 *
 * <p>Frozen into {@link CaptureSpec} like the other settings the image depends on, so a
 * retake produces the photo that was hung on the wall rather than the one the server's
 * current config would draw.</p>
 *
 * @param enabled    whether the pass runs at all; the player's own switch
 * @param distance   distance from the camera plane that stays sharp, in blocks
 * @param rangeRatio depth offset at which blur reaches its maximum, as a ratio of the
 *                   frame's world height
 * @param maxRadius  blur radius at full defocus, as a ratio of the image's pixel height
 * @param samples    taps taken around the blur disc; the pass's cost setting
 * @param dither     how far a blurred pixel may stray from its true colour when it is
 *                   snapped back to the palette, in channel steps; 0 disables dithering
 */
public record FocusSpec(
        boolean enabled,
        double distance,
        double rangeRatio,
        double maxRadius,
        int samples,
        double dither) {

    /**
     * Everything sharp, as the renderer has always drawn it.
     */
    public static final FocusSpec NONE = new FocusSpec(false, 0.0, 0.5, 0.0, 0, 0.0);

    /**
     * Whether the render has to carry a depth buffer and run the pass.
     */
    public boolean draws() {
        return enabled && maxRadius > 0.0 && samples > 0;
    }
}
