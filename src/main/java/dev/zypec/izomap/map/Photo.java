package dev.zypec.izomap.map;

import dev.zypec.izomap.render.CaptureSpec;

import java.util.UUID;

/**
 * A photo taken with a camera. It exists whether or not it hangs anywhere: shooting
 * and putting up are two separate steps, so a player can look at what they got and
 * shoot again before committing to a wall.
 *
 * <p>The image itself lives in {@link PhotoCache}; the {@link CaptureSpec} here is the
 * backup that lets it be rendered again if the cache file is lost, and the source of
 * truth for a retake.</p>
 *
 * @param id         unique photo id
 * @param owner      player who took it
 * @param name       photo name, unique per owner
 * @param cameraName source camera name, kept for display, listing and retakes
 * @param spec       capture parameters, or {@code null} for photos placed before they
 *                   were recorded; those fall back to their camera's current settings
 * @param grid       grid the image is sliced onto
 * @param placement  where it hangs, or {@code null} while it is only in the list
 */
public record Photo(
        UUID id,
        UUID owner,
        String name,
        String cameraName,
        CaptureSpec spec,
        GridOption grid,
        Placement placement) {

    /**
     * Short display id: the first 8 characters.
     */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public boolean isPlaced() {
        return placement != null;
    }

    /**
     * Copy carrying the camera and parameters the image was last produced with. A
     * retake may shoot from a different camera than the original.
     */
    public Photo withCapture(String cameraName, CaptureSpec spec) {
        return new Photo(id, owner, name, cameraName, spec, grid, placement);
    }

    /**
     * Copy hanging somewhere else, or nowhere when {@code placement} is {@code null}.
     */
    public Photo withPlacement(Placement placement) {
        return new Photo(id, owner, name, cameraName, spec, grid, placement);
    }

    public Photo withName(String name) {
        return new Photo(id, owner, name, cameraName, spec, grid, placement);
    }
}
