package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Bir dünya bölgesinin iş parçacığı güvenli anlık görüntüsü.
 *
 * <p>Paper'da chunk/blok erişimi yalnızca ana (region) iş parçacığında güvenlidir.
 * Bu sınıf, gerekli chunk'ları <b>ana thread'de</b> {@link ChunkSnapshot} olarak
 * kopyalar; ardından ağır ray-march işlemi bu kopya üzerinde <b>asenkron</b>
 * yürütülebilir.</p>
 */
public final class WorldSnapshot {

    private final Map<Long, ChunkSnapshot> chunks;
    private final int minY;
    private final int maxY;

    private WorldSnapshot(Map<Long, ChunkSnapshot> chunks, int minY, int maxY) {
        this.chunks = chunks;
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Verilen bölgeyi kapsayan chunk'ları yakalar.
     * <b>Ana iş parçacığında</b> çağrılmalıdır.
     */
    public static WorldSnapshot capture(World world, BoundingBox region) {
        int minChunkX = (int) Math.floor(region.getMinX()) >> 4;
        int maxChunkX = (int) Math.floor(region.getMaxX()) >> 4;
        int minChunkZ = (int) Math.floor(region.getMinZ()) >> 4;
        int maxChunkZ = (int) Math.floor(region.getMaxZ()) >> 4;

        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkSnapshot snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
                chunks.put(key(cx, cz), snapshot);
            }
        }
        return new WorldSnapshot(chunks, world.getMinHeight(), world.getMaxHeight());
    }

    /** Dünya koordinatındaki bloğun materyali (bölge dışıysa AIR). */
    public Material materialAt(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return Material.AIR;
        }
        ChunkSnapshot snapshot = chunks.get(key(x >> 4, z >> 4));
        if (snapshot == null) {
            return Material.AIR;
        }
        return snapshot.getBlockType(x & 15, y, z & 15);
    }

    public int chunkCount() {
        return chunks.size();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
