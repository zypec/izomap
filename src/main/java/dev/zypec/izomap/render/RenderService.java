package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Render işlemlerinin koordinasyonu.
 *
 * <p>İki aşama: (1) <b>ana thread</b>de kameranın geometrisi hesaplanır ve gerekli
 * bölge {@link WorldSnapshot} olarak yakalanır; (2) ağır voxel yürüyüşü
 * {@link IsometricRenderer} ile <b>asenkron</b> yürütülür. Bu sayede blok erişimi
 * daima güvenli iş parçacığında kalır, CPU-yoğun kısım ise ana thread'i bloklamaz.</p>
 *
 * <p>Geometri üç bağımsız ayardan türetilir:</p>
 * <ul>
 *   <li>{@code photo.frame-height} — kadrajın dünya-uzayı yüksekliği, yani zoom.
 *       Ortografik projeksiyonda nesne boyutunu <b>yalnızca</b> bu belirler; kameranın
 *       hedefe uzaklığı boyutu değiştirmez.</li>
 *   <li>{@code photo.frame-shift} — kadrajın kameraya göre dikey kayması. {@code 0}
 *       iken kameranın baktığı nokta fotoğrafın ortasındadır.</li>
 *   <li>{@code settings.max-render-distance} — ışınların ileri gördüğü mesafe.</li>
 * </ul>
 *
 * <p>Kadrajın kameranın altına düşen kısmı için ışınlar bakış yönünde geriye
 * çekilir; gerekçesi ve sınırı {@link RenderGeometry} belgesindedir.</p>
 */
public final class RenderService {

    /** Bölge yakalanırken ışın prizmasının bölüneceği dilim sayısı. */
    private static final int BEAM_SLICES = 8;

    private final Izomap plugin;
    private final IsometricRenderer renderer;
    private final AtomicInteger threadCounter = new AtomicInteger();

    private ExecutorService workers;
    private int workerCount;

    public RenderService(Izomap plugin, BlockColorTable colorTable) {
        this.plugin = plugin;
        this.renderer = new IsometricRenderer(colorTable, new MapColorConverter());
    }

    /**
     * Kamerayı, verilen tam piksel boyutunda çeker. En-boy oranı doğrudan piksel
     * boyutlarından türetilir. <b>Ana iş parçacığında</b> çağrılmalıdır.
     */
    public CompletableFuture<RenderResult> capture(Camera camera, int widthPx, int heightPx) {
        Location anchor = camera.anchor();
        World world = anchor.getWorld();
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Kamera dünyası yüklü değil."));
        }

        double ratio = (double) widthPx / heightPx;
        double frameShift = plugin.config().frameShift();
        double spanHeight = plugin.config().frameHeight() / camera.zoom();
        double spanWidth = spanHeight * ratio;
        double maxDistance = plugin.config().maxRenderDistance();

        Vector direction = directionFrom(camera.camYaw(), camera.camPitch());
        Vector[] basis = basisFrom(direction);
        Vector right = basis[0];
        Vector up = basis[1];

        // Kadraj kameranın hizasındadır: frame-shift 0 iken kameranın baktığı nokta
        // fotoğrafın tam ortasındadır. Pozitif değer kadrajı kadraj yüksekliğinin bir
        // oranı kadar yukarı kaydırır (0.5 = kamera kadrajın alt kenarında).
        double eyeY = anchor.getY();
        Vector planeCenter = anchor.toVector()
                .add(up.clone().multiply(spanHeight * frameShift));

        // Kadrajın kameranın altına düşen kısmı, geriye çekilerek kameranın yatay
        // düzlemine kaldırılır (bkz. RenderGeometry). `right` daima yatay olduğundan
        // kadrajın en alçak noktası yalnızca `up` bileşeninden gelir.
        double dropBelowEye = Math.max(0.0, (0.5 - frameShift) * spanHeight * up.getY());
        double climb = -direction.getY();
        double maxBackoff = climb > 1.0e-6 ? Math.min(dropBelowEye / climb, maxDistance) : 0.0;

        List<BoundingBox> beam = beamSlices(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxBackoff, maxDistance);
        Set<Long> chunkKeys = chunkKeys(beam);

        int budget = plugin.config().maxChunksPerCapture();
        if (chunkKeys.size() > budget) {
            return CompletableFuture.failedFuture(new CaptureTooLargeException(chunkKeys.size(), budget));
        }

        RenderGeometry geometry = new RenderGeometry(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxDistance,
                eyeY, maxBackoff, widthPx, heightPx);
        ColorFilter filter = camera.colorFilter();
        int supersampling = plugin.config().supersampling();
        int threads = plugin.config().renderThreads();
        Executor executor = workers(threads);

        return snapshotChunks(world, chunkKeys).thenCompose(snapshot -> {
            CompletableFuture<RenderResult> future = new CompletableFuture<>();
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    future.complete(renderer.render(snapshot, geometry, filter, supersampling, executor, threads));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        });
    }

    /**
     * Gereken chunk'ların kopyalarını toplar. <b>Ana iş parçacığında</b> başlatılır.
     *
     * <p>Yüklü olanlar hemen kopyalanır. Yüklü olmayanlar Paper'ın asenkron chunk
     * API'siyle <b>ana thread dışında</b> yüklenir; ancak yükleme bittikten sonra
     * ana thread'e dönülüp kopya alınır. Böylece uzak manzara çekilebilirken
     * sunucu, ana iş parçacığında chunk üretmek/okumak zorunda kalmaz.</p>
     */
    private CompletableFuture<WorldSnapshot> snapshotChunks(World world, Set<Long> keys) {
        boolean load = plugin.config().loadMissingChunks();
        boolean generate = plugin.config().generateMissingChunks();

        List<ChunkSnapshot> ready = new ArrayList<>(keys.size());
        List<CompletableFuture<Chunk>> loading = new ArrayList<>();
        for (long key : keys) {
            int cx = WorldSnapshot.chunkX(key);
            int cz = WorldSnapshot.chunkZ(key);
            if (world.isChunkLoaded(cx, cz)) {
                ready.add(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            } else if (load) {
                loading.add(world.getChunkAtAsync(cx, cz, generate));
            }
        }

        if (loading.isEmpty()) {
            return CompletableFuture.completedFuture(WorldSnapshot.of(world, ready));
        }

        return CompletableFuture.allOf(loading.toArray(new CompletableFuture<?>[0])).thenCompose(ignored -> {
            CompletableFuture<WorldSnapshot> done = new CompletableFuture<>();
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                for (CompletableFuture<Chunk> future : loading) {
                    // Üretilmemiş chunk için null döner; yükleme sonrası tekrar
                    // boşaltılmış olabileceğinden isLoaded ile doğrulanır.
                    Chunk chunk = future.getNow(null);
                    if (chunk != null && chunk.isLoaded()) {
                        ready.add(chunk.getChunkSnapshot(false, false, false));
                    }
                }
                done.complete(WorldSnapshot.of(world, ready));
            });
            return done;
        });
    }

    /** Işın prizmasının dokunduğu chunk sütunları (yalnızca geometri; blok erişimi yok). */
    private static Set<Long> chunkKeys(List<BoundingBox> beam) {
        Set<Long> keys = new HashSet<>();
        for (BoundingBox box : beam) {
            int minChunkX = (int) Math.floor(box.getMinX()) >> 4;
            int maxChunkX = (int) Math.floor(box.getMaxX()) >> 4;
            int minChunkZ = (int) Math.floor(box.getMinZ()) >> 4;
            int maxChunkZ = (int) Math.floor(box.getMaxZ()) >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    keys.add(WorldSnapshot.key(cx, cz));
                }
            }
        }
        return keys;
    }

    /** Eklenti kapanırken render havuzunu serbest bırakır. */
    public synchronized void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
            workerCount = 0;
        }
    }

    /**
     * Yapılandırılan boyutta render havuzunu döndürür.
     * Sayı {@code /izomap reload} ile değiştiyse havuz yeniden kurulur.
     */
    private synchronized Executor workers(int threads) {
        if (threads <= 1) {
            shutdown();
            return Runnable::run;
        }
        if (workers == null || workerCount != threads) {
            shutdown();
            workers = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "izomap-render-" + threadCounter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            workerCount = threads;
        }
        return workers;
    }

    /** Bukkit yaw/pitch formülüyle birim yön vektörü. */
    private static Vector directionFrom(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vector(-cosPitch * Math.sin(yawRad), -Math.sin(pitchRad), cosPitch * Math.cos(yawRad));
    }

    /** Yön vektöründen sağ/yukarı eksenlerini türetir (dik bakışta dejenerasyonu ele alır). */
    private static Vector[] basisFrom(Vector direction) {
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = direction.clone().crossProduct(worldUp);
        if (right.lengthSquared() < 1.0e-6) {
            // Tam yukarı/aşağı bakış: referans olarak dünya +Z ekseni kullan.
            right = direction.clone().crossProduct(new Vector(0, 0, 1));
        }
        right.normalize();
        Vector up = right.clone().crossProduct(direction).normalize();
        return new Vector[] {right, up};
    }

    /**
     * Işın prizmasını derinlik boyunca dilimleyip her dilimin kutusunu döndürür.
     *
     * <p>Tek bir kutu kullanılsaydı, çapraz bakışta prizmanın eğik olması yüzünden
     * kutu gereğinden çok daha geniş olur ve yakalanacak chunk sayısı katlanırdı.
     * Dilimleme, yakalanan bölgeyi prizmanın gerçek şekline yaklaştırır.</p>
     *
     * <p>Prizma, geri çekilen ışınları da kapsaması için düzlemin
     * {@code backoff} blok <b>gerisinden</b> başlar; o bölge yakalanmazsa geri
     * çekilen ışınlar boş (hava) bir alandan geçer ve fotoğrafın alt kısmı
     * yanlış çıkardı.</p>
     */
    private static List<BoundingBox> beamSlices(Vector center, Vector right, Vector up, Vector direction,
                                                double spanW, double spanH, double backoff, double maxDist) {
        double hw = spanW / 2.0;
        double hh = spanH / 2.0;
        double sliceDepth = (backoff + maxDist) / BEAM_SLICES;

        List<BoundingBox> slices = new ArrayList<>(BEAM_SLICES);
        for (int i = 0; i < BEAM_SLICES; i++) {
            double near = -backoff + i * sliceDepth;
            BoundingBox box = null;
            for (int sw = -1; sw <= 1; sw += 2) {
                for (int sh = -1; sh <= 1; sh += 2) {
                    for (int sd = 0; sd <= 1; sd++) {
                        Vector corner = center.clone()
                                .add(right.clone().multiply(sw * hw))
                                .add(up.clone().multiply(sh * hh))
                                .add(direction.clone().multiply(near + sd * sliceDepth));
                        if (box == null) {
                            box = new BoundingBox(
                                    corner.getX(), corner.getY(), corner.getZ(),
                                    corner.getX(), corner.getY(), corner.getZ());
                        } else {
                            box.union(corner);
                        }
                    }
                }
            }
            slices.add(box.expand(1.0));
        }
        return slices;
    }
}
