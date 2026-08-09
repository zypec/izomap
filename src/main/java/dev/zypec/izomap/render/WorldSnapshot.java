package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Bir dünya bölgesinin iş parçacığı güvenli anlık görüntüsü.
 *
 * <p>Paper'da chunk/blok erişimi yalnızca ana (region) iş parçacığında güvenlidir.
 * Bu sınıf, gerekli chunk'ların <b>ana thread'de</b> alınmış {@link ChunkSnapshot}
 * kopyalarını tutar; ardından ağır voxel yürüyüşü bu kopyalar üzerinde
 * <b>asenkron</b> yürütülebilir.</p>
 *
 * <p>Kopyası bulunmayan chunk'lar hava sayılır (fotoğrafta şeffaf kalır).
 * Chunk'ların yüklenmesi ve kopyalanması {@link RenderService}'in işidir.</p>
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

    /** Hazır chunk kopyalarından anlık görüntü kurar. */
    public static WorldSnapshot of(World world, Collection<ChunkSnapshot> snapshots) {
        Map<Long, ChunkSnapshot> chunks = new HashMap<>(snapshots.size() * 2);
        for (ChunkSnapshot snapshot : snapshots) {
            chunks.put(key(snapshot.getX(), snapshot.getZ()), snapshot);
        }
        return new WorldSnapshot(chunks, world.getMinHeight(), world.getMaxHeight());
    }

    /** Dünya koordinatındaki bloğun materyali (kopyası yoksa AIR). */
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

    /** Dünyanın en alt blok yüksekliği (dahil). */
    public int minY() {
        return minY;
    }

    /** Dünyanın en üst blok yüksekliği (hariç). */
    public int maxY() {
        return maxY;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Chunk koordinatlarını tek bir {@code long} anahtara paketler. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
