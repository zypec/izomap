package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.RenderResult;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Writes a rendered photo to {@code plugins/Izomap/exports/} as a PNG.
 *
 * <p>Encoding and disk I/O both run off the main thread; a full-size grid is several
 * megapixels and would stall a tick.</p>
 */
public final class PhotoExporter {

    /** Everything outside this set is replaced, so a name can never leave the folder. */
    private static final String ALLOWED = "[^A-Za-z0-9._-]";
    private static final int MAX_NAME = 64;
    private static final String EXTENSION = ".png";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Izomap plugin;
    private final Path folder;

    public PhotoExporter(Izomap plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("exports");
    }

    /**
     * Writes the image and completes with the file it landed in.
     *
     * @param requested the name the player asked for, or {@code null} for the default
     */
    public CompletableFuture<Path> write(RenderResult result, String photoName, String requested) {
        CompletableFuture<Path> out = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                Files.createDirectories(folder);
                Path file = resolve(photoName, requested);
                ImageIO.write(result.toImage(), "png", file.toFile());
                out.complete(file);
            } catch (IOException | RuntimeException ex) {
                out.completeExceptionally(ex);
            }
        });
        return out;
    }

    /**
     * Turns the requested name into a path inside the export folder.
     *
     * <p>The name is sanitized rather than rejected, and the result is checked against
     * the folder afterwards: a name is player input, and {@code ../../server.properties}
     * must not be able to name a file to overwrite.</p>
     */
    private Path resolve(String photoName, String requested) {
        String base = (requested == null || requested.isBlank())
                ? photoName + "-" + LocalDateTime.now().format(STAMP)
                : stripExtension(requested);
        String safe = sanitize(base);

        Path file = folder.resolve(safe + EXTENSION).normalize();
        if (!file.startsWith(folder.normalize())) {
            throw new IllegalArgumentException("Dosya adı export klasörünün dışına çıkıyor: " + requested);
        }
        return file;
    }

    /** Keeps a name to characters that mean the same thing on every filesystem. */
    static String sanitize(String raw) {
        String cleaned = raw.replaceAll(ALLOWED, "_");
        // A leading dot would hide the file, and a name of only dots names the folder.
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > MAX_NAME) {
            cleaned = cleaned.substring(0, MAX_NAME);
        }
        return cleaned.isBlank() ? "photo" : cleaned;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? name.substring(0, name.length() - EXTENSION.length())
                : name;
    }
}
