package dev.zypec.izomap.render;

import java.util.UUID;

/**
 * Everything that decides what a capture looks like, detached from the camera that
 * produced it.
 *
 * <p>A photo keeps its own spec so a re-render yields the same image the player hung
 * on the wall: the camera may since have been turned, zoomed, filtered or deleted,
 * and the server's config may have changed too. The spec therefore carries the
 * config-derived values by copy rather than reading them again.</p>
 *
 * <p>Only image-defining values belong here. Cost and scheduling settings that do not
 * change the result ({@code settings.render-threads}, chunk loading) stay in the
 * config and are read at render time.</p>
 *
 * @param worldId        world the camera stands in
 * @param yaw            view yaw, degrees
 * @param pitch          view pitch, degrees (positive looks down)
 * @param zoom           zoom multiplier; the frame covers {@code frameHeight / zoom} blocks
 * @param colorFilter    color effect applied before the palette snap
 * @param style          how the pixels are drawn, as opposed to colored
 * @param skyArgb        sky color frozen at capture, or {@code 0} for none. The colour
 *                       is stored rather than the time, so a re-render neither drifts
 *                       with the clock nor with a retuned palette
 * @param shading        what darkens a surface beyond its own face, at capture time
 * @param water          how water is coloured, at capture time; {@link WaterSpec#FLAT}
 *                       leaves it the single tone it used to be
 * @param focus          which distance stays sharp and how the rest softens, at capture
 *                       time; {@link FocusSpec#NONE} leaves the whole frame sharp
 * @param frameHeight    {@code photo.frame-height} at capture time
 * @param frameShift     {@code photo.frame-shift} at capture time
 * @param supersampling  {@code photo.supersampling} at capture time
 * @param maxCaptureArea {@code settings.max-capture-area} at capture time
 * @param renderDepth    {@code settings.render-depth} at capture time
 */
public record CaptureSpec(
        UUID worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float zoom,
        ColorFilter colorFilter,
        PhotoStyle style,
        int skyArgb,
        ShadingSpec shading,
        WaterSpec water,
        FocusSpec focus,
        double frameHeight,
        double frameShift,
        int supersampling,
        int maxCaptureArea,
        int renderDepth) {

    /**
     * Chunk budget matching {@link #maxCaptureArea()}, since capture works in chunks.
     */
    public int chunkBudget() {
        int side = (maxCaptureArea + 15) / 16;
        return side * side;
    }
}
