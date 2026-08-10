package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.render.CaptureTooLargeException;
import dev.zypec.izomap.render.RenderResult;
import dev.zypec.izomap.render.RenderService;
import dev.zypec.izomap.util.Failures;
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
        storage.load().thenRun(() -> plugin.runOnMain(() -> {
            ingest(storage.readAll());
            loaded = true;
            restoreAll();
            cache.retainOnly(photos.keySet());
            photosChanged();
        }));
    }

    /**
     * Tells the cameras their photo counts moved. Cameras load before photos do, so
     * without this their holograms would sit on the count they saw at startup: zero.
     */
    private void photosChanged() {
        cameraManager.refreshHolograms();
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
        plugin.messages().info("log.photos-loaded",
                Placeholder.unparsed("count", String.valueOf(loaded.size())));
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
                    plugin.runOnMain(() -> applyToMaps(photo, ImageSlicer.slice(result, photo.grid())));
                } else {
                    plugin.runOnMain(() -> reRenderFromSpec(photo));
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
                plugin.messages().warn("log.photo-unrestorable",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("id", photo.shortId()));
                return;
            }
            spec = renderService.specFor(camera);
        }

        var grid = photo.grid();
        var used = spec;
        renderService.capture(used, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                plugin.messages().warn("log.photo-recapture-failed",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("reason", plugin.messages().reason(error)));
                return;
            }
            cache.write(photo.id(), grid, result);
            plugin.runOnMain(() -> {
                applyToMaps(photo, ImageSlicer.slice(result, grid));
                if (recovered) {
                    // Pin the parameters now, so the image stops following the camera
                    // from here on even if the cache is lost again.
                    photos.put(photo.id(), photo.withCapture(photo.cameraName(), used));
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
    private void reportCaptureError(Player player, String cameraName, Throwable error) {
        if (Failures.unwrap(error) instanceof CaptureTooLargeException tooLarge) {
            plugin.runOnMain(() -> plugin.messages().send(player, "photo.too-large",
                    Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                    Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
            return;
        }
        plugin.messages().warn("log.photo-render-failed",
                Placeholder.unparsed("camera", cameraName),
                Placeholder.unparsed("reason", plugin.messages().reason(error)));
        plugin.runOnMain(() -> plugin.messages().send(player, "photo.failed"));
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
                reportCaptureError(player, camera.name(), error);
                return;
            }
            plugin.runOnMain(() -> {
                var tiles = ImageSlicer.slice(result, grid);
                var photo = placer.place(player, camera, spec, name, grid, tiles);
                if (photo == null) {
                    plugin.messages().send(player, "map.place-blocked");
                    return;
                }
                photos.put(photo.id(), photo);
                storage.saveAll(photos.values());
                cache.write(photo.id(), grid, result);
                photosChanged();

                long ms = System.currentTimeMillis() - start;
                plugin.messages().send(player, "map.placed",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("count", String.valueOf(photo.mapIds().size())),
                        Placeholder.unparsed("ms", String.valueOf(ms)));
            });
        });
    }

    /**
     * Shoots a placed photo again and rewrites the maps it already hangs on.
     *
     * <p>The parameters come from the source camera's <b>current</b> settings, which is
     * the whole point of a retake. When that camera is gone the photo's own stored
     * parameters stand in, so the shot is repeated from the same spot and only the
     * world has moved on. Must be started on the main thread.</p>
     *
     * @param source camera to shoot from, or {@code null} for the photo's own
     */
    public void retake(Player player, PlacedPhoto photo, Camera source) {
        var camera = source != null
                ? source
                : cameraManager.byOwnerAndName(photo.owner(), photo.cameraName()).orElse(null);
        var spec = camera != null ? renderService.specFor(camera) : photo.spec();
        if (spec == null) {
            plugin.messages().send(player, "map.photo-no-source",
                    Placeholder.unparsed("camera", photo.cameraName()));
            return;
        }

        var cameraName = camera != null ? camera.name() : photo.cameraName();
        plugin.messages().send(player, "photo.capturing");
        var start = System.currentTimeMillis();
        var grid = photo.grid();

        renderService.capture(spec, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                // Nothing was written yet, so the wall still shows the previous shot.
                reportCaptureError(player, cameraName, error);
                return;
            }
            cache.write(photo.id(), grid, result);
            plugin.runOnMain(() -> {
                applyToMaps(photo, ImageSlicer.slice(result, grid));
                photos.put(photo.id(), photo.withCapture(cameraName, spec));
                storage.saveAll(photos.values());
                plugin.messages().send(player, "map.photo-retaken",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("camera", cameraName),
                        Placeholder.unparsed("ms", String.valueOf(System.currentTimeMillis() - start)));
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
                    "Photo has neither a cached image nor capture parameters: " + photo.shortId()));

        CompletableFuture<RenderResult> out = new CompletableFuture<>();
        plugin.runOnMain(() -> renderService.capture(spec, photo.grid().widthPx(), photo.grid().heightPx())
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
                        plugin.messages().warn("log.photo-export-failed",
                                Placeholder.unparsed("id", photo.shortId()),
                                Placeholder.unparsed("reason", plugin.messages().reason(error)));
                        plugin.runOnMain(() -> plugin.messages().send(player, "photo.export-failed"));
                        return;
                    }
                    var result = written.getKey();
                    var file = written.getValue();
                    plugin.runOnMain(() -> plugin.messages().send(player, "photo.saved",
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
     * How many placed photos were shot with a given camera.
     */
    public int countFor(UUID owner, String cameraName) {
        var count = 0;
        for (var photo : photos.values())
            if (photo.owner().equals(owner) && photo.cameraName().equalsIgnoreCase(cameraName))
                count++;
        return count;
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
        if (!owned.isEmpty()) {
            storage.saveAll(photos.values());
            photosChanged();
        }
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
        if (removed > 0) {
            storage.saveAll(photos.values());
            photosChanged();
        }
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
        photosChanged();
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
}
