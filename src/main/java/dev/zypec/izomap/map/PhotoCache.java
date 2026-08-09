package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.MapColorConverter;
import dev.zypec.izomap.render.RenderResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Stores the captured image of every placed photo under
 * {@code plugins/Izomap/photos/<id>.izm}, so a restart loads the picture instead of
 * shooting it again.
 *
 * <h2>Format</h2>
 *
 * <p>One byte per pixel: the vanilla map palette index
 * ({@code baseColorId * 4 + shadeId}), where {@code 0} is transparent. Every rendered
 * pixel is already a palette entry, so writing loses nothing and reading is a table
 * lookup rather than a color match. The body is Deflate-compressed, which flat areas
 * such as sky, water and fields shrink dramatically.</p>
 *
 * <pre>
 * int  magic   "IZMP"
 * int  version
 * int  width, height
 * int  cols, rows
 * byte[] deflate(indices)   row-major, width*height bytes
 * </pre>
 *
 * <p>The cache is never authoritative in the sense of being irreplaceable: a missing,
 * truncated or outdated file simply makes the caller re-render from the photo's
 * {@link dev.zypec.izomap.render.CaptureSpec}, so the format may change with a version
 * bump and no migration.</p>
 *
 * <p>All three operations run off the main thread; only applying the result to a
 * {@code MapView} belongs on it.</p>
 */
public final class PhotoCache {

    /** "IZMP" */
    private static final int MAGIC = 0x495A4D50;
    private static final int VERSION = 1;

    private final Izomap plugin;
    private final Path folder;
    private final Executor asyncExecutor;
    private final MapColorConverter converter = new MapColorConverter();

    public PhotoCache(Izomap plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("photos");
        this.asyncExecutor = task ->
                plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    /**
     * Writes the render to the cache. The pixel buffer is read, not copied, so the
     * caller must not modify it afterwards; renders are immutable once complete.
     */
    public CompletableFuture<Void> write(UUID photoId, GridOption grid, RenderResult result) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(folder);
                writeFile(fileOf(photoId), grid, result);
            } catch (IOException e) {
                plugin.getLogger().warning("Fotoğraf ön belleği yazılamadı (" + photoId + "): " + e.getMessage());
            }
        }, asyncExecutor);
    }

    /**
     * Reads the cached image, or completes with {@code null} when there is nothing
     * usable: no file, a different format version, or a size that no longer matches
     * the photo's grid.
     */
    public CompletableFuture<RenderResult> read(UUID photoId, GridOption grid) {
        return CompletableFuture.supplyAsync(() -> {
            Path file = fileOf(photoId);
            if (Files.notExists(file)) {
                return null;
            }
            try {
                return readFile(file, grid);
            } catch (IOException e) {
                plugin.getLogger().warning("Fotoğraf ön belleği okunamadı (" + photoId
                        + "): " + e.getMessage() + " — parametrelerden yeniden çekilecek.");
                return null;
            }
        }, asyncExecutor);
    }

    /**
     * Deletes cache files that belong to no record: leftovers from a crash between the
     * write and the record save, or from a delete that raced its own write.
     *
     * <p>An empty record set is treated as "not known yet" rather than "nothing is
     * kept", because {@code maps.yml} failing to load would otherwise wipe every
     * cached image at once.</p>
     */
    public CompletableFuture<Void> retainOnly(Set<UUID> photoIds) {
        if (photoIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (Files.notExists(folder)) {
                return;
            }
            int removed = 0;
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    UUID id = idOf(file);
                    if (id != null && !photoIds.contains(id) && Files.deleteIfExists(file)) {
                        removed++;
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Fotoğraf ön belleği taranamadı: " + e.getMessage());
                return;
            }
            if (removed > 0) {
                plugin.getLogger().info("Sahipsiz " + removed + " fotoğraf ön bellek dosyası silindi.");
            }
        }, asyncExecutor);
    }

    /** Deletes the cached image of a photo that no longer exists. */
    public CompletableFuture<Void> delete(UUID photoId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(fileOf(photoId));
            } catch (IOException e) {
                plugin.getLogger().warning("Fotoğraf ön belleği silinemedi (" + photoId + "): " + e.getMessage());
            }
        }, asyncExecutor);
    }

    private void writeFile(Path file, GridOption grid, RenderResult result) throws IOException {
        int[] argb = result.argb();
        byte[] indices = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            indices[i] = converter.packedId(argb[i]);
        }

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            DataOutputStream header = new DataOutputStream(out);
            header.writeInt(MAGIC);
            header.writeInt(VERSION);
            header.writeInt(result.width());
            header.writeInt(result.height());
            header.writeInt(grid.cols());
            header.writeInt(grid.rows());
            header.flush();

            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream body = new DeflaterOutputStream(out, deflater)) {
                body.write(indices);
            } finally {
                deflater.end();
            }
        }
    }

    private RenderResult readFile(Path file, GridOption grid) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            DataInputStream header = new DataInputStream(in);
            if (header.readInt() != MAGIC || header.readInt() != VERSION) {
                return null;
            }
            int width = header.readInt();
            int height = header.readInt();
            int cols = header.readInt();
            int rows = header.readInt();
            if (cols != grid.cols() || rows != grid.rows()
                    || width != grid.widthPx() || height != grid.heightPx()) {
                return null;
            }

            byte[] indices = new byte[width * height];
            try (InflaterInputStream body = new InflaterInputStream(in)) {
                if (body.readNBytes(indices, 0, indices.length) != indices.length) {
                    return null; // truncated
                }
            }

            int[] argb = new int[indices.length];
            for (int i = 0; i < indices.length; i++) {
                argb[i] = MapColorConverter.argbOf(indices[i]);
            }
            return new RenderResult(width, height, argb);
        }
    }

    private Path fileOf(UUID photoId) {
        return folder.resolve(photoId + ".izm");
    }

    /** Photo id a cache file name encodes, or {@code null} for anything else. */
    private static UUID idOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".izm")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
