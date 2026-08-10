package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.render.CaptureTooLargeException;
import dev.zypec.izomap.render.RenderResult;
import dev.zypec.izomap.render.RenderService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime management of placed photos: capture, slicing, placement, persistence and
 * restoring the images after a restart.
 */
public final class PhotoManager {

    private final Izomap plugin;
    private final CameraManager cameraManager;
    private final RenderService renderService;
    private final MapService mapService;
    private final MapPlacer placer;
    private final PhotoStorage storage;
    private final PhotoCache cache;
    private final PhotoExporter exporter;

    private final Map<UUID, PlacedPhoto> photos = new ConcurrentHashMap<>();

    /**
     * Whether {@code maps.yml} has been read. Until then an unknown frame is not an
     * orphan, only one whose record has not arrived yet.
     */
    private volatile boolean loaded;

    public PhotoManager(Izomap plugin, CameraManager cameraManager,
                        RenderService renderService, MapService mapService, PhotoKeys keys) {
        this.plugin = plugin;
        this.cameraManager = cameraManager;
        this.renderService = renderService;
        this.mapService = mapService;
        this.placer = new MapPlacer(plugin, mapService, keys);
        this.storage = new PhotoStorage(plugin);
        this.cache = new PhotoCache(plugin);
        this.exporter = new PhotoExporter(plugin);
    }

    // --- lifecycle ---

    public void load() {
        storage.load().thenRun(() -> runOnMain(() -> {
            ingest(storage.readAll());
            loaded = true;
            restoreAll();
            cache.retainOnly(photos.keySet());
        }));
    }

    /**
     * Whether the records have been read from disk.
     */
    public boolean isLoaded() {
        return loaded;
    }

    private void ingest(List<PlacedPhoto> loaded) {
        for (var photo : loaded) {
            photos.put(photo.id(), photo);
        }
        plugin.getLogger().info(loaded.size() + " yerleştirilmiş fotoğraf yüklendi.");
    }

    /**
     * Restores every photo's image from the cache, falling back to a re-render only
     * where the cache cannot serve it.
     *
     * <p>Reading is asynchronous and per photo, so startup does not wait on it; each
     * tile is applied on the main thread as it becomes ready.</p>
     */
    private void restoreAll() {
        for (var photo : photos.values()) {
            if (Bukkit.getWorld(photo.worldId()) == null) continue;

            cache.read(photo.id(), photo.grid()).whenComplete((result, error) -> {
                if (error == null && result != null) {
                    runOnMain(() -> applyToMaps(photo, ImageSlicer.slice(result, photo.grid())));
                } else {
                    runOnMain(() -> reRenderFromSpec(photo));
                }
            });
        }
    }

    /**
     * Renders a photo again from its stored parameters and rewrites the cache. Used
     * when the cache file is missing or unreadable; the maps are left as they are when
     * there is nothing left to render from. Main thread only.
     */
    private void reRenderFromSpec(PlacedPhoto photo) {
        var spec = photo.spec();
        var recovered = spec == null;
        if (recovered) {
            // Placed before specs were recorded: the source camera is all we have, and
            // its current settings may no longer match what is on the wall.
            var camera = cameraManager.byOwnerAndName(photo.owner(), photo.cameraName()).orElse(null);
            if (camera == null) {
                plugin.getLogger().warning("Fotoğraf '" + photo.name() + "' (" + photo.shortId()
                                           + ") ne ön bellekte var ne de çekim parametreleri kayıtlı; haritalar son"
                                           + " hâlinde bırakıldı.");
                return;
            }
            spec = renderService.specFor(camera);
        }

        var grid = photo.grid();
        var used = spec;
        renderService.capture(used, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                plugin.getLogger().warning("Fotoğraf '" + photo.name() + "' yeniden çekilemedi: "
                                           + (error != null ? error.getMessage() : "boş sonuç"));
                return;
            }
            cache.write(photo.id(), grid, result);
            runOnMain(() -> {
                applyToMaps(photo, ImageSlicer.slice(result, grid));
                if (recovered) {
                    // Pin the parameters now, so the image stops following the camera
                    // from here on even if the cache is lost again.
                    photos.put(photo.id(), photo.withSpec(used));
                    storage.saveAll(photos.values());
                }
            });
        });
    }

    private void applyToMaps(PlacedPhoto photo, List<MapTile> tiles) {
        var mapIds = photo.mapIds();
        var count = Math.min(mapIds.size(), tiles.size());
        for (var i = 0; i < count; i++) {
            var view = Bukkit.getMap(mapIds.get(i));
            if (view != null)
                mapService.applyTile(view, tiles.get(i).argb());
        }
    }

    /**
     * Reports a capture failure to the player, with a dedicated budget message.
     */
    private void reportCaptureError(Player player, Camera camera, Throwable error) {
        var cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
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

    // --- capture and placement ---

    /**
     * Captures the camera, slices it into tiles and places it in the world. Must be
     * started on the main thread.
     */
    public void captureAndPlace(Player player, Camera camera, String name, GridOption grid) {
        // Names are unique per owner.
        var nameTaken = photos.values().stream()
                .anyMatch(p -> p.owner().equals(player.getUniqueId()) && p.name().equalsIgnoreCase(name));
        if (nameTaken) {
            plugin.messages().send(player, "map.name-taken", Placeholder.unparsed("name", name));
            return;
        }

        plugin.messages().send(player, "photo.capturing");
        var start = System.currentTimeMillis();

        // Frozen once, so the cache, the record and the image all describe the same shot.
        var spec = renderService.specFor(camera);

        renderService.capture(spec, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                reportCaptureError(player, camera, error);
                return;
            }
            runOnMain(() -> {
                var tiles = ImageSlicer.slice(result, grid);
                var photo = placer.place(player, camera, spec, name, grid, tiles);
                if (photo == null) {
                    plugin.messages().send(player, "map.place-blocked");
                    return;
                }
                photos.put(photo.id(), photo);
                storage.saveAll(photos.values());
                cache.write(photo.id(), grid, result);

                long ms = System.currentTimeMillis() - start;
                plugin.messages().send(player, "map.placed",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("count", String.valueOf(photo.mapIds().size())),
                        Placeholder.unparsed("ms", String.valueOf(ms)));
            });
        });
    }

    // --- image access ---

    /**
     * The photo's image: the cache first, a re-render from its capture parameters when
     * the cache cannot serve it.
     *
     * <p>Fails only when neither is available, which is a photo placed before specs
     * were recorded whose cache file is also gone.</p>
     */
    public CompletableFuture<RenderResult> image(PlacedPhoto photo) {
        return cache.read(photo.id(), photo.grid())
                // A cache miss is expected, not exceptional; it decides the next step.
                .handle((result, error) -> result)
                .thenCompose(cached -> cached != null
                        ? CompletableFuture.completedFuture(cached)
                        : renderFromSpec(photo));
    }

    /**
     * Re-renders from the stored spec. The capture itself has to start on the main thread.
     */
    private CompletableFuture<RenderResult> renderFromSpec(PlacedPhoto photo) {
        var spec = photo.spec();
        if (spec == null)
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Fotoğrafın ne ön belleği ne çekim parametreleri var: " + photo.shortId()));

        CompletableFuture<RenderResult> out = new CompletableFuture<>();
        runOnMain(() -> renderService.capture(spec, photo.grid().widthPx(), photo.grid().heightPx())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        out.completeExceptionally(error);
                    } else {
                        out.complete(result);
                    }
                }));
        return out;
    }

    /**
     * Writes the photo to a PNG under {@code exports/} and reports the result.
     */
    public void export(Player player, PlacedPhoto photo, String fileName) {
        var start = System.currentTimeMillis();
        image(photo)
                .thenCompose(result -> exporter.write(result, photo.name(), fileName)
                        .thenApply(file -> Map.entry(result, file)))
                .whenComplete((written, error) -> {
                    if (error != null || written == null) {
                        var cause = error instanceof java.util.concurrent.CompletionException
                                    && error.getCause() != null ? error.getCause() : error;
                        plugin.getLogger().warning("Fotoğraf dışa aktarılamadı ("
                                                   + photo.shortId() + "): " + (cause != null ? cause.getMessage() : "boş sonuç"));
                        runOnMain(() -> plugin.messages().send(player, "photo.export-failed"));
                        return;
                    }
                    var result = written.getKey();
                    var file = written.getValue();
                    runOnMain(() -> plugin.messages().send(player, "photo.saved",
                            Placeholder.unparsed("file", relativePath(file)),
                            Placeholder.unparsed("width", String.valueOf(result.width())),
                            Placeholder.unparsed("height", String.valueOf(result.height())),
                            Placeholder.unparsed("size", String.valueOf(kilobytes(file))),
                            Placeholder.unparsed("ms", String.valueOf(System.currentTimeMillis() - start))));
                });
    }

    /**
     * Shown to the player relative to the data folder; the absolute path is noise.
     */
    private String relativePath(Path file) {
        var base = plugin.getDataFolder().toPath();
        return file.startsWith(base) ? base.relativize(file).toString() : file.toString();
    }

    private static long kilobytes(Path file) {
        try {
            return Math.max(1L, Files.size(file) / 1024L);
        } catch (java.io.IOException ex) {
            return 0L;
        }
    }

    // --- management ---

    public List<PlacedPhoto> ownedBy(UUID owner) {
        List<PlacedPhoto> out = new ArrayList<>();
        for (var photo : photos.values())
            if (photo.owner().equals(owner))
                out.add(photo);
        return out;
    }

    /**
     * Finds a photo by its full id, however it was reached.
     */
    public Optional<PlacedPhoto> byId(UUID id) {
        return Optional.ofNullable(photos.get(id));
    }

    /**
     * Finds an owner's photo by its short id.
     */
    public Optional<PlacedPhoto> findByShortId(UUID owner, String shortId) {
        return photos.values().stream()
                .filter(p -> p.owner().equals(owner) && p.shortId().equalsIgnoreCase(shortId))
                .findFirst();
    }

    /**
     * Finds any photo by its short id, whoever owns it; for admin commands.
     */
    public Optional<PlacedPhoto> findByShortId(String shortId) {
        return photos.values().stream()
                .filter(p -> p.shortId().equalsIgnoreCase(shortId))
                .findFirst();
    }

    /**
     * Finds the photo an item frame belongs to.
     */
    public Optional<PlacedPhoto> findByFrame(UUID frameId) {
        return photos.values().stream()
                .filter(p -> p.frameIds().contains(frameId))
                .findFirst();
    }

    /**
     * Removes every photo placed by a player and returns how many were removed.
     */
    public int removeAllOwned(UUID owner) {
        var owned = ownedBy(owner);
        for (var photo : owned) {
            removeFrames(photo);
            photos.remove(photo.id());
            cache.delete(photo.id());
        }
        if (!owned.isEmpty())
            storage.saveAll(photos.values());
        return owned.size();
    }

    /**
     * Clears records of the owner's photos whose frames are gone from the world. The
     * relevant chunks are loaded first so the check is accurate. Main thread only.
     */
    public int cleanupOwned(UUID owner) {
        var removed = 0;
        for (var photo : ownedBy(owner)) {
            if (Bukkit.getWorld(photo.worldId()) == null)
                continue;
            loadChunks(photo);
            var anyAlive = photo.frameIds().stream()
                    .anyMatch(id -> plugin.getServer().getEntity(id) != null);
            if (!anyAlive) {
                photos.remove(photo.id());
                cache.delete(photo.id());
                removed++;
            }
        }
        if (removed > 0)
            storage.saveAll(photos.values());
        return removed;
    }

    /**
     * Removes the photo's frames without dropping items and deletes its record.
     */
    public void remove(PlacedPhoto photo) {
        removeFrames(photo);
        photos.remove(photo.id());
        cache.delete(photo.id());
        storage.saveAll(photos.values());
    }

    /**
     * Removes the frame entities and their maps without dropping items. The chunks
     * are loaded first because {@code getEntity} returns null for unloaded ones,
     * which would leave the frames behind in the world.
     */
    private void removeFrames(PlacedPhoto photo) {
        loadChunks(photo);
        for (var frameId : photo.frameIds()) {
            var entity = plugin.getServer().getEntity(frameId);
            if (entity != null)
                entity.remove();
        }
    }

    /**
     * Loads the chunks the photo spans, so its frames can be resolved.
     */
    private void loadChunks(PlacedPhoto photo) {
        var world = Bukkit.getWorld(photo.worldId());
        if (world == null) return;

        var span = Math.max(photo.grid().cols(), photo.grid().rows()) + 2;
        var minChunkX = (photo.baseX() - span) >> 4;
        var maxChunkX = (photo.baseX() + span) >> 4;
        var minChunkZ = (photo.baseZ() - span) >> 4;
        var maxChunkZ = (photo.baseZ() + span) >> 4;
        for (var cx = minChunkX; cx <= maxChunkX; cx++)
            for (var cz = minChunkZ; cz <= maxChunkZ; cz++)
                world.getChunkAt(cx, cz);
    }

    public Collection<PlacedPhoto> all() {
        return photos.values();
    }

    private void runOnMain(Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }
}
