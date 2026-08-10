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
 * Runtime management of photos: capture, slicing, hanging, persistence and restoring
 * the images after a restart.
 *
 * <p>Shooting and hanging are separate. A capture only produces a photo; putting it on
 * a wall is a second, undoable step that goes through the ghost preview in
 * {@code dev.zypec.izomap.place}. Taking a photo down leaves the photo itself alone —
 * only {@link #delete} throws it away.</p>
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

    private final Map<UUID, Photo> photos = new ConcurrentHashMap<>();

    /**
     * Whether {@code photos.yml} has been read. Until then an unknown frame is not an
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
     * Whether the records have been read from disk.
     */
    public boolean isLoaded() {
        return loaded;
    }

    private void ingest(List<Photo> loaded) {
        for (var photo : loaded) {
            photos.put(photo.id(), photo);
        }
        plugin.messages().info("log.photos-loaded",
                Placeholder.unparsed("count", String.valueOf(loaded.size())));
    }

    /**
     * Tells the cameras their photo counts moved. Cameras load before photos do, so
     * without this their holograms would sit on the count they saw at startup: zero.
     */
    private void photosChanged() {
        cameraManager.refreshHolograms();
    }

    /**
     * Restores the image of every photo that hangs somewhere, from the cache, falling
     * back to a re-render only where the cache cannot serve it.
     *
     * <p>Photos that were shot but never put up are left alone: nothing on a wall
     * depends on them, and rendering every one of them at startup is exactly the cost
     * the cache exists to avoid. Their image is produced the moment it is asked for.</p>
     *
     * <p>Reading is asynchronous and per photo, so startup does not wait on it; each
     * tile is applied on the main thread as it becomes ready.</p>
     */
    private void restoreAll() {
        for (var photo : photos.values()) {
            if (!photo.isPlaced() || Bukkit.getWorld(photo.placement().worldId()) == null) continue;

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
    private void reRenderFromSpec(Photo photo) {
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
                    replace(photo.withCapture(photo.cameraName(), used));
                }
            });
        });
    }

    private void applyToMaps(Photo photo, List<MapTile> tiles) {
        if (!photo.isPlaced()) return;

        var mapIds = photo.placement().mapIds();
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

    // --- capture ---

    /**
     * Shoots the camera and files the result under the player's photos. Nothing is put
     * up in the world; that is {@link #place}'s job. Must be started on the main thread.
     */
    public void capture(Player player, Camera camera, String name, GridOption grid) {
        plugin.messages().send(player, "photo.capturing");
        var start = System.currentTimeMillis();

        // Frozen once, so the cache, the record and the image all describe the same shot.
        var spec = renderService.specFor(camera);
        var photo = new Photo(UUID.randomUUID(), player.getUniqueId(),
                uniqueName(player.getUniqueId(), name), camera.name(), spec, grid, null);

        renderService.capture(spec, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null) {
                reportCaptureError(player, camera.name(), error);
                return;
            }
            cache.write(photo.id(), grid, result);
            plugin.runOnMain(() -> {
                photos.put(photo.id(), photo);
                storage.saveAll(photos.values());
                photosChanged();
                plugin.messages().send(player, "photo.captured",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("id", photo.shortId()),
                        Placeholder.unparsed("ms", String.valueOf(System.currentTimeMillis() - start)));
            });
        });
    }

    /**
     * Shoots a photo again, keeping everything else about it. A photo that hangs
     * somewhere is rewritten in place, on the same maps.
     *
     * <p>The parameters come from the source camera's <b>current</b> settings, which is
     * the whole point of a retake. When that camera is gone the photo's own stored
     * parameters stand in, so the shot is repeated from the same spot and only the
     * world has moved on. Must be started on the main thread.</p>
     *
     * @param source camera to shoot from, or {@code null} for the photo's own
     */
    public void retake(Player player, Photo photo, Camera source) {
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
                var current = photos.get(photo.id());
                if (current == null) return; // deleted while the render was running

                applyToMaps(current, ImageSlicer.slice(result, grid));
                replace(current.withCapture(cameraName, spec));
                plugin.messages().send(player, "map.photo-retaken",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("camera", cameraName),
                        Placeholder.unparsed("ms", String.valueOf(System.currentTimeMillis() - start)));
            });
        });
    }

    // --- hanging and taking down ---

    /**
     * Hangs the photo in the area the player lined up with the ghost preview. Must be
     * started on the main thread.
     */
    public void place(Player player, Photo photo, PlacementArea area) {
        image(photo).whenComplete((result, error) -> {
            if (error != null || result == null) {
                plugin.messages().warn("log.photo-unplaceable",
                        Placeholder.unparsed("name", photo.name()),
                        Placeholder.unparsed("reason", plugin.messages().reason(error)));
                plugin.runOnMain(() -> plugin.messages().send(player, "photo.failed"));
                return;
            }
            plugin.runOnMain(() -> {
                var current = photos.get(photo.id());
                if (current == null) return; // deleted while the image was being fetched

                // Placing an already hanging photo moves it; the old frames have to go
                // first or they stay behind with nothing pointing at them.
                if (current.isPlaced())
                    removeFrames(current);

                var placement = placer.place(area, current.id(), current.grid(),
                        ImageSlicer.slice(result, current.grid()));
                if (placement == null) {
                    plugin.messages().send(player, "map.place-blocked");
                    return;
                }
                replace(current.withPlacement(placement));
                plugin.messages().send(player, "map.placed",
                        Placeholder.unparsed("name", current.name()),
                        Placeholder.unparsed("count", String.valueOf(placement.mapIds().size())));
            });
        });
    }

    /**
     * Takes the photo off the wall but keeps it: the image and its parameters are still
     * there, so it can go back up somewhere else.
     */
    public void unplace(Photo photo) {
        if (!photo.isPlaced()) return;

        removeFrames(photo);
        replace(photo.withPlacement(null));
    }

    /**
     * Throws the photo away for good, frames, record and cached image alike.
     */
    public void delete(Photo photo) {
        plugin.placement().cancelFor(photo.id(), "placement.ended-photo-removed");
        if (photo.isPlaced())
            removeFrames(photo);

        photos.remove(photo.id());
        cache.delete(photo.id());
        storage.saveAll(photos.values());
        photosChanged();
    }

    /**
     * Renames the photo, or reports that the owner already has one by that name.
     */
    public boolean rename(Photo photo, String name) {
        if (nameTaken(photo.owner(), name, photo.id()))
            return false;

        replace(photo.withName(name));
        return true;
    }

    /**
     * Removes every photo of a player and returns how many were removed.
     */
    public int removeAllOwned(UUID owner) {
        var owned = ownedBy(owner);
        for (var photo : owned) {
            plugin.placement().cancelFor(photo.id(), "placement.ended-photo-removed");
            if (photo.isPlaced())
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
     * Takes down the record of every photo of the owner whose frames are gone from the
     * world, and returns how many. The photos themselves survive as unplaced ones: the
     * wall is what went missing, not the picture. Main thread only.
     */
    public int cleanupOwned(UUID owner) {
        var removed = 0;
        for (var photo : ownedBy(owner)) {
            if (!photo.isPlaced() || Bukkit.getWorld(photo.placement().worldId()) == null)
                continue;

            loadChunks(photo);
            var anyAlive = photo.placement().frameIds().stream()
                    .anyMatch(id -> plugin.getServer().getEntity(id) != null);
            if (!anyAlive) {
                photos.put(photo.id(), photo.withPlacement(null));
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
     * Removes the frame entities and their maps without dropping items. The chunks
     * are loaded first because {@code getEntity} returns null for unloaded ones,
     * which would leave the frames behind in the world.
     */
    private void removeFrames(Photo photo) {
        loadChunks(photo);
        for (var frameId : photo.placement().frameIds()) {
            var entity = plugin.getServer().getEntity(frameId);
            if (entity != null)
                entity.remove();
        }
    }

    /**
     * Loads the chunks the photo spans, so its frames can be resolved.
     */
    private void loadChunks(Photo photo) {
        var placement = photo.placement();
        var world = Bukkit.getWorld(placement.worldId());
        if (world == null) return;

        var span = Math.max(photo.grid().cols(), photo.grid().rows()) + 2;
        var minChunkX = (placement.baseX() - span) >> 4;
        var maxChunkX = (placement.baseX() + span) >> 4;
        var minChunkZ = (placement.baseZ() - span) >> 4;
        var maxChunkZ = (placement.baseZ() + span) >> 4;
        for (var cx = minChunkX; cx <= maxChunkX; cx++)
            for (var cz = minChunkZ; cz <= maxChunkZ; cz++)
                world.getChunkAt(cx, cz);
    }

    /**
     * Stores an updated copy of a photo and writes the collection out.
     */
    private void replace(Photo photo) {
        photos.put(photo.id(), photo);
        storage.saveAll(photos.values());
    }

    /**
     * The name with a number appended until it is free.
     *
     * <p>The capture dialog offers the camera's name by default, so shooting twice in a
     * row would otherwise be refused for a name clash. Now that shooting is cheap and
     * meant to be repeated, numbering the shot beats turning it away.</p>
     */
    private String uniqueName(UUID owner, String name) {
        if (!nameTaken(owner, name, null))
            return name;

        for (var suffix = 2; ; suffix++) {
            var candidate = name + "-" + suffix;
            if (!nameTaken(owner, candidate, null))
                return candidate;
        }
    }

    private boolean nameTaken(UUID owner, String name, UUID exceptId) {
        return photos.values().stream().anyMatch(p -> p.owner().equals(owner)
                                                     && !p.id().equals(exceptId)
                                                     && p.name().equalsIgnoreCase(name));
    }

    // --- image access ---

    /**
     * The photo's image: the cache first, a re-render from its capture parameters when
     * the cache cannot serve it.
     *
     * <p>Fails only when neither is available, which is a photo placed before specs
     * were recorded whose cache file is also gone.</p>
     */
    public CompletableFuture<RenderResult> image(Photo photo) {
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
    private CompletableFuture<RenderResult> renderFromSpec(Photo photo) {
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
    public void export(Player player, Photo photo, String fileName) {
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

    // --- queries ---

    public List<Photo> ownedBy(UUID owner) {
        List<Photo> out = new ArrayList<>();
        for (var photo : photos.values())
            if (photo.owner().equals(owner))
                out.add(photo);
        return out;
    }

    /**
     * The owner's photos taken with a given camera, oldest name order, for the list UI.
     */
    public List<Photo> takenWith(UUID owner, String cameraName) {
        return photos.values().stream()
                .filter(p -> p.owner().equals(owner) && p.cameraName().equalsIgnoreCase(cameraName))
                .sorted(java.util.Comparator.comparing(Photo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * How many photos were shot with a given camera.
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
    public Optional<Photo> byId(UUID id) {
        return Optional.ofNullable(photos.get(id));
    }

    /**
     * Finds an owner's photo by its short id.
     */
    public Optional<Photo> findByShortId(UUID owner, String shortId) {
        return photos.values().stream()
                .filter(p -> p.owner().equals(owner) && p.shortId().equalsIgnoreCase(shortId))
                .findFirst();
    }

    /**
     * Finds any photo by its short id, whoever owns it; for admin commands.
     */
    public Optional<Photo> findByShortId(String shortId) {
        return photos.values().stream()
                .filter(p -> p.shortId().equalsIgnoreCase(shortId))
                .findFirst();
    }

    /**
     * Finds the photo an item frame belongs to.
     */
    public Optional<Photo> findByFrame(UUID frameId) {
        return photos.values().stream()
                .filter(p -> p.isPlaced() && p.placement().frameIds().contains(frameId))
                .findFirst();
    }

    public Collection<Photo> all() {
        return photos.values();
    }
}
