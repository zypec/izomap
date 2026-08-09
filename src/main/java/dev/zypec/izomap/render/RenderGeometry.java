package dev.zypec.izomap.render;

import org.bukkit.util.Vector;

/**
 * Camera geometry for an orthographic (isometric) render.
 *
 * <p>The image plane sits at {@code planeCenter} and is spanned by {@code right} and
 * {@code up}; every ray starts on that plane and travels parallel along
 * {@code direction}.</p>
 *
 * <h2>Why backoff exists</h2>
 *
 * <p>Under orthographic projection, moving a ray's origin <b>along the view
 * direction</b> does not change the image, only where the ray starts and therefore
 * what can occlude it. The part of the frame below the camera starts <i>inside</i>
 * the ground when the camera is near it, printing a flat dirt slab at the bottom of
 * the photo. Only those rays are pulled back, just far enough to reach the camera's
 * horizontal plane and never more than {@code maxBackoff}, so no ray ever ends up
 * looking from behind the camera.</p>
 *
 * <p>Each ray sees a prism extending {@code maxDistance} blocks forward from its own
 * origin, plus whatever it was pulled back by, keeping forward view distance
 * measured <b>from the camera plane</b> identical for every ray.</p>
 *
 * @param planeCenter world-space center of the image plane
 * @param right       plane's +X axis (unit, always horizontal)
 * @param up          plane's +Y axis (unit)
 * @param direction   ray direction (unit)
 * @param spanWidth   world-space width of the plane (blocks)
 * @param spanHeight  world-space height of the plane (blocks)
 * @param maxDistance distance the rays travel forward (blocks)
 * @param eyeY        world-space height of the camera; no ray starts below it
 * @param maxBackoff  furthest a ray may be pulled back (blocks); 0 disables backoff
 * @param widthPx     output width (pixels)
 * @param heightPx    output height (pixels)
 */
public record RenderGeometry(
        Vector planeCenter,
        Vector right,
        Vector up,
        Vector direction,
        double spanWidth,
        double spanHeight,
        double maxDistance,
        double eyeY,
        double maxBackoff,
        int widthPx,
        int heightPx) {
}
