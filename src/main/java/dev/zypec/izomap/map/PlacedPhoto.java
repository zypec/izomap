package dev.zypec.izomap.map;

import java.util.List;
import java.util.UUID;

/**
 * A photo placed in the world as a grid of item frames.
 *
 * <p>The source camera and the map ids are stored so the maps can be redrawn after a
 * restart; the frame UUIDs are kept for management.</p>
 *
 * @param id         unique photo id
 * @param owner      player who placed it
 * @param name       photo name
 * @param cameraName source camera name, used to re-render
 * @param worldId    world UUID
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
        UUID worldId,
        GridOption grid,
        List<Integer> mapIds,
        List<UUID> frameIds,
        int baseX,
        int baseY,
        int baseZ) {

    /** Short display id: the first 8 characters. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
