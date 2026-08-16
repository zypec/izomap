package dev.zypec.izomap.storage;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Base class for YML data files such as {@code cameras.yml} and {@code photos.yml}.
 *
 * <p>Disk I/O never runs on the main thread; loading and saving go through Paper's
 * async scheduler.</p>
 */
public abstract class YamlStorage {

    protected final Izomap plugin;
    private final Path path;
    private final Executor asyncExecutor;

    /**
     * Only read and written inside synchronized blocks.
     */
    private FileConfiguration data;

    protected YamlStorage(Izomap plugin, String fileName) {
        this.plugin = plugin;
        this.path = plugin.getDataFolder().toPath().resolve(fileName);
        this.asyncExecutor = plugin.asyncExecutor();
    }

    /**
     * Loads the file asynchronously.
     */
    public CompletableFuture<Void> load() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (Files.notExists(path)) {
                    Files.createDirectories(path.getParent());
                    Files.createFile(path);
                }
            } catch (IOException e) {
                plugin.messages().error("log.data-file-create-failed",
                        Placeholder.unparsed("file", path.getFileName().toString()),
                        Placeholder.unparsed("reason", plugin.messages().reason(e)));
            }
            var loaded = YamlConfiguration.loadConfiguration(path.toFile());
            synchronized (this) {
                this.data = loaded;
            }
            onLoaded(loaded);
        }, asyncExecutor);
    }

    /**
     * Writes the in-memory data to disk asynchronously.
     */
    public CompletableFuture<Void> save() {
        final String dump;
        synchronized (this) {
            dump = (data == null) ? "" : data.saveToString();
        }
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, dump, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.messages().error("log.data-file-save-failed",
                        Placeholder.unparsed("file", path.getFileName().toString()),
                        Placeholder.unparsed("reason", plugin.messages().reason(e)));
            }
        }, asyncExecutor);
    }

    /**
     * Called on the async thread once loading finishes. Subclasses override it to
     * turn the raw data into their own model.
     */
    protected void onLoaded(FileConfiguration loaded) {
    }

    /**
     * Synchronized access to the in-memory configuration. May be {@code null} before
     * {@link #load()} completes.
     */
    protected synchronized FileConfiguration data() {
        return data;
    }

    /**
     * Replaces the in-memory configuration wholesale, for bulk serialization.
     */
    protected synchronized void setData(FileConfiguration replacement) {
        this.data = replacement;
    }

    /**
     * Writes to disk synchronously. Only for shutdown, where the async scheduler no
     * longer runs.
     */
    protected void saveNow() {
        final String dump;
        synchronized (this) {
            dump = (data == null) ? "" : data.saveToString();
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, dump, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.messages().error("log.data-file-save-failed-sync",
                    Placeholder.unparsed("file", path.getFileName().toString()),
                    Placeholder.unparsed("reason", plugin.messages().reason(e)));
        }
    }
}
