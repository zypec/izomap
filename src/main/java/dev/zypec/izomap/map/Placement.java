package dev.zypec.izomap.map;

import java.util.List;
import java.util.UUID;

/**
 * Where a photo currently hangs. A photo without one has been shot but not put up.
 *
 * @param worldId  world the frames hang in
 * @param mapIds   map view ids in tile order (row-major)
 * @param frameIds item frame entity UUIDs in tile order
 * @param baseX    X of the anchor block, used to load the chunk during cleanup
 * @param baseY    Y of the anchor block
 * @param baseZ    Z of the anchor block
 */
public record Placement(
        UUID worldId,
        List<Integer> mapIds,
        List<UUID> frameIds,
        int baseX,
        int baseY,
        int baseZ) {
}
