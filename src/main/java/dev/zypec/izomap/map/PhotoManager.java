package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.render.CaptureTooLargeException;
import dev.zypec.izomap.render.RenderService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yerleştirilmiş fotoğrafların çalışma zamanı yönetimi: çekim + dilimleme +
 * yerleştirme + kalıcılık koordinasyonu ve yeniden başlatmada yeniden render.
 */
public final class PhotoManager {

    private final Izomap plugin;
    private final CameraManager cameraManager;
    private final RenderService renderService;
    private final MapService mapService;
    private final MapPlacer placer;
    private final PhotoStorage storage;

    private final Map<UUID, PlacedPhoto> photos = new ConcurrentHashMap<>();

    public PhotoManager(Izomap plugin, CameraManager cameraManager,
                        RenderService renderService, MapService mapService) {
        this.plugin = plugin;
        this.cameraManager = cameraManager;
        this.renderService = renderService;
        this.mapService = mapService;
        this.placer = new MapPlacer(plugin, mapService);
        this.storage = new PhotoStorage(plugin);
    }

    // --- yaşam döngüsü ---

    public void load() {
        storage.load().thenRun(() -> runOnMain(() -> {
            ingest(storage.readAll());
            reRenderAll();
        }));
    }

    private void ingest(List<PlacedPhoto> loaded) {
        for (PlacedPhoto photo : loaded) {
            photos.put(photo.id(), photo);
        }
        plugin.getLogger().info(loaded.size() + " yerleştirilmiş fotoğraf yüklendi.");
    }

    /** Kaynağı hâlâ mevcut olan fotoğrafların haritalarını yeniden çizer. */
    private void reRenderAll() {
        for (PlacedPhoto photo : photos.values()) {
            Camera camera = cameraManager.byOwnerAndName(photo.owner(), photo.cameraName()).orElse(null);
            if (camera == null || Bukkit.getWorld(photo.worldId()) == null) {
                continue;
            }
            GridOption grid = photo.grid();
            renderService.capture(camera, grid.widthPx(), grid.heightPx()).thenAccept(result ->
                    runOnMain(() -> applyToMaps(photo, ImageSlicer.slice(result, grid))));
        }
    }

    @SuppressWarnings("deprecation")
    private void applyToMaps(PlacedPhoto photo, List<MapTile> tiles) {
        List<Integer> mapIds = photo.mapIds();
        int count = Math.min(mapIds.size(), tiles.size());
        for (int i = 0; i < count; i++) {
            MapView view = Bukkit.getMap(mapIds.get(i));
            if (view != null) {
                mapService.applyTile(view, tiles.get(i).argb());
            }
        }
    }

    /** Çekim hatasını oyuncuya bildirir; bütçe aşımı için özel mesaj gönderir. */
    private void reportCaptureError(Player player, Camera camera, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof CaptureTooLargeException tooLarge) {
            runOnMain(() -> plugin.messages().send(player, "photo.too-large",
                    Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                    Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
            return;
        }
        plugin.getLogger().warning("Fotoğraf render'ı başarısız (" + camera.name() + "): "
                + (cause != null ? cause.getMessage() : "boş sonuç"));
        runOnMain(() -> plugin.messages().send(player, "photo.failed"));
    }

    public void saveSync() {
        storage.saveAllSync(photos.values());
    }

    // --- çekim + yerleştirme ---

    /**
     * Kamerayı çeker, karolara böler ve dünyaya yerleştirir. Dialog onayından
     * çağrılır. <b>Ana iş parçacığında</b> başlatılmalıdır.
     */
    public void captureAndPlace(Player player, Camera camera, String name, GridOption grid) {
        // Aynı isimde ikinci bir fotoğrafa izin verme.
        boolean nameTaken = photos.values().stream()
                .anyMatch(p -> p.owner().equals(player.getUniqueId()) && p.name().equalsIgnoreCase(name));
        if (nameTaken) {
            plugin.messages().send(player, "map.name-taken", Placeholder.unparsed("name", name));
            return;
        }

        plugin.messages().send(player, "photo.capturing");
        long start = System.currentTimeMillis();

        renderService.capture(camera, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                reportCaptureError(player, camera, error);
                return;
            }
            runOnMain(() -> {
                List<MapTile> tiles = ImageSlicer.slice(result, grid);
                PlacedPhoto photo = placer.place(player, camera, name, grid, tiles);
                if (photo == null) {
                    plugin.messages().send(player, "map.place-blocked");
                    return;
                }
                photos.put(photo.id(), photo);
                storage.saveAll(photos.values());

                long ms = System.currentTimeMillis() - start;
                plugin.messages().send(player, "map.placed",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("count", String.valueOf(photo.mapIds().size())),
                        Placeholder.unparsed("ms", String.valueOf(ms)));
            });
        });
    }

    // --- yönetim ---

    public List<PlacedPhoto> ownedBy(UUID owner) {
        List<PlacedPhoto> out = new ArrayList<>();
        for (PlacedPhoto photo : photos.values()) {
            if (photo.owner().equals(owner)) {
                out.add(photo);
            }
        }
        return out;
    }

    /** Kısa kimliğe (ilk 8 karakter) göre, sahibe ait fotoğrafı bulur. */
    public Optional<PlacedPhoto> findByShortId(UUID owner, String shortId) {
        return photos.values().stream()
                .filter(p -> p.owner().equals(owner) && p.shortId().equalsIgnoreCase(shortId))
                .findFirst();
    }

    /** Bir ItemFrame UUID'sinin ait olduğu fotoğrafı bulur (kırılma yönetimi için). */
    public Optional<PlacedPhoto> findByFrame(UUID frameId) {
        return photos.values().stream()
                .filter(p -> p.frameIds().contains(frameId))
                .findFirst();
    }

    /** Bir oyuncunun tüm yerleştirilmiş fotoğraflarını kaldırır; sayısını döndürür. */
    public int removeAllOwned(UUID owner) {
        List<PlacedPhoto> owned = ownedBy(owner);
        for (PlacedPhoto photo : owned) {
            removeFrames(photo);
            photos.remove(photo.id());
        }
        if (!owned.isEmpty()) {
            storage.saveAll(photos.values());
        }
        return owned.size();
    }

    /**
     * Sahibe ait, çerçeveleri artık dünyada bulunmayan (kırılmış/kaybolmuş) fotoğraf
     * kayıtlarını temizler. Doğru tespit için ilgili chunk yüklenir.
     * <b>Ana iş parçacığında</b> çağrılmalıdır.
     */
    public int cleanupOwned(UUID owner) {
        int removed = 0;
        for (PlacedPhoto photo : ownedBy(owner)) {
            if (Bukkit.getWorld(photo.worldId()) == null) {
                continue;
            }
            // Çerçevelerin çözümlenebilmesi için ilgili chunk'ları yükle.
            loadChunks(photo);
            boolean anyAlive = photo.frameIds().stream()
                    .anyMatch(id -> plugin.getServer().getEntity(id) != null);
            if (!anyAlive) {
                photos.remove(photo.id());
                removed++;
            }
        }
        if (removed > 0) {
            storage.saveAll(photos.values());
        }
        return removed;
    }

    /** Fotoğrafın çerçevelerini (eşya düşürmeden) kaldırır ve kaydı siler. */
    public void remove(PlacedPhoto photo) {
        removeFrames(photo);
        photos.remove(photo.id());
        storage.saveAll(photos.values());
    }

    /**
     * Çerçeve entity'lerini (ve içindeki haritayı) eşya düşürmeden kaldırır.
     * Çerçeveler chunk yüklü değilse {@code getEntity} null döneceğinden, önce ilgili
     * chunk'lar yüklenir; aksi halde çerçeveler dünyada kalırdı (bilinen silme bug'ı).
     */
    private void removeFrames(PlacedPhoto photo) {
        loadChunks(photo);
        for (UUID frameId : photo.frameIds()) {
            Entity entity = plugin.getServer().getEntity(frameId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /** Fotoğrafın kapladığı bölgedeki chunk'ları yükler (çerçeve çözümlemesi için). */
    private void loadChunks(PlacedPhoto photo) {
        World world = Bukkit.getWorld(photo.worldId());
        if (world == null) {
            return;
        }
        int span = Math.max(photo.grid().cols(), photo.grid().rows()) + 2;
        int minChunkX = (photo.baseX() - span) >> 4;
        int maxChunkX = (photo.baseX() + span) >> 4;
        int minChunkZ = (photo.baseZ() - span) >> 4;
        int maxChunkZ = (photo.baseZ() + span) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }

    public Collection<PlacedPhoto> all() {
        return photos.values();
    }

    private void runOnMain(Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }
}
