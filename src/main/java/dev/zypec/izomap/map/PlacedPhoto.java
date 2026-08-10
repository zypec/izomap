package dev.zypec.izomap.map;

import dev.zypec.izomap.render.CaptureSpec;

import java.util.List;
import java.util.UUID;

/**
 * A photo placed in the world as a grid of item frames.
 *
 * <p>The image itself lives in {@link PhotoCache}; the {@link CaptureSpec} here is the
 * backup that lets it be rendered again if the cache file is lost, and the source of
 * truth for a retake. The frame UUIDs are kept for management.</p>
 *
 * @param id         unique photo id
 * @param owner      player who placed it
 * @param name       photo name
 * @param cameraName source camera name, kept for display and for retakes
 * @param spec       capture parameters, or {@code null} for photos placed before they
 *                   were recorded; those fall back to their camera's current settings
 * @param worldId    world the frames hang in
 * @param grid       grid used
 * @param mapIds     map view ids in tile order (row-major)
 * @param frameIds   item frame entity UUIDs in tile order
 * @param baseX      X of the anchor block, used to load the chunk during cleanup
 * @param baseY      Y of the anchor block
 * @param baseZ      Z of the anchor block
 */
public record PlacedPhoto(
        UUID id,
        UUID owner,
        String name,
        String cameraName,
        CaptureSpec spec,
        UUID worldId,
        GridOption grid,
        List<Integer> mapIds,
        List<UUID> frameIds,
        int baseX,
        int baseY,
        int baseZ) {

    /**
     * Short display id: the first 8 characters.
     */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    /**
     * Copy carrying the camera and parameters the image was last produced with. A
     * retake may shoot from a different camera than the original.
     */
    public PlacedPhoto withCapture(String cameraName, CaptureSpec spec) {
        return new PlacedPhoto(id, owner, name, cameraName, spec, worldId, grid,
                mapIds, frameIds, baseX, baseY, baseZ);
    }
}
