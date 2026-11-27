package dev.zypec.izomap.storage;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Base class for YML data files such as {@code cameras.yml} and {@code photos.yml}.
 *
 * <p>Disk I/O never runs on the main thread: loading goes through Paper's async
 * scheduler, saving through one writer thread per file (see {@link #requestSave}).</p>
 */
public abstract class YamlStorage {

    protected final Izomap plugin;
    /**
     * How long saves gather before one write, in ticks.
     */
    private static final long DEBOUNCE_TICKS = 20L;

    /**
     * How long shutdown waits for a write already under way.
     */
    private static final long WRITER_WAIT_SECONDS = 10L;

    private final Path path;
    private final Executor asyncExecutor;
    private final ExecutorService writer;
    private final AtomicReference<FileConfiguration> pendingDump = new AtomicReference<>();
    /**
     * Main thread only, like {@link #scheduled}.
     */
    private Supplier<FileConfiguration> pendingSerializer;
    private boolean scheduled;

    /**
     * Only read and written inside synchronized blocks.
     */
    private FileConfiguration data;

    protected YamlStorage(Izomap plugin, String fileName) {
        this.plugin = plugin;
        this.path = plugin.getDataFolder().toPath().resolve(fileName);
        this.asyncExecutor = plugin.asyncExecutor();
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "izomap-save-" + fileName);
            thread.setDaemon(true);
            return thread;
        });
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
     * Asks for the data to be written soon. Main thread only.
     *
     * <p>Saves are <b>debounced</b>: a camera turned click after click used to rewrite
     * {@code cameras.yml} from scratch on every click. The first request schedules one
     * write {@link #DEBOUNCE_TICKS} later and every request until then folds into it;
     * {@code serializer} runs at that moment, so the write carries the state as it is
     * then, not as it was at the first click.</p>
     *
     * <p>Writes go through one thread per file, in order, and the newest dump wins: two
     * saves used to run on a shared pool and could finish the wrong way round, leaving
     * the older data on disk. Each write goes to a temporary file that is then moved over
     * the real one, so a crash mid-write leaves the previous file whole instead of an
     * empty one.</p>
     */
    protected void requestSave(Supplier<FileConfiguration> serializer) {
        pendingSerializer = serializer;
        if (scheduled) return;

        scheduled = true;
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> flush(), DEBOUNCE_TICKS);
    }

    private void flush() {
        scheduled = false;
        var serializer = pendingSerializer;
        pendingSerializer = null;
        if (serializer == null) return;

        var replacement = serializer.get();
        setData(replacement);
        // The configuration is a fresh object nobody touches again, so it can be dumped
        // off the main thread.
        if (pendingDump.getAndSet(replacement) == null)
            writer.execute(this::writePending);
    }

    private void writePending() {
        var latest = pendingDump.getAndSet(null);
        if (latest == null) return;

        try {
            write(latest.saveToString());
        } catch (IOException e) {
            plugin.messages().error("log.data-file-save-failed",
                    Placeholder.unparsed("file", path.getFileName().toString()),
                    Placeholder.unparsed("reason", plugin.messages().reason(e)));
        }
    }

    private void write(String dump) throws IOException {
        Files.createDirectories(path.getParent());
        var temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, dump, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
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
     * Writes {@code replacement} to disk now, after anything already queued. Only for
     * shutdown, where the scheduler no longer runs a debounced save.
     */
    protected void saveNow(FileConfiguration replacement) {
        pendingSerializer = null;
        pendingDump.set(null);
        setData(replacement);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(WRITER_WAIT_SECONDS, TimeUnit.SECONDS))
                writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            write(replacement.saveToString());
        } catch (IOException e) {
            plugin.messages().error("log.data-file-save-failed-sync",
                    Placeholder.unparsed("file", path.getFileName().toString()),
                    Placeholder.unparsed("reason", plugin.messages().reason(e)));
        }
    }
}
