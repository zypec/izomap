package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
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
 * Coordinates renders.
 *
 * <p>Two stages: the camera geometry is computed and the region captured as a
 * {@link WorldSnapshot} on the main thread, then the voxel walk runs asynchronously
 * in {@link IsometricRenderer}. Block access therefore always stays on the safe
 * thread while the CPU-heavy part never blocks it.</p>
 *
 * <p>Two settings define the frame: {@code photo.frame-height} is its world-space
 * height (the zoom, and under orthographic projection the only thing that sets
 * object size), and {@code photo.frame-shift} is its vertical offset from the
 * camera.</p>
 *
 * <h2>Why ray distance is not a setting</h2>
 *
 * <p>The required distance follows from the frame and the pitch: the ray at the top
 * edge must travel {@code vertical_drop / sin(pitch)} blocks to reach the target
 * floor. As a manual setting it had to be kept in sync with zoom, and forgetting
 * left the top of the frame silently empty, so it is computed instead. The only
 * cost setting is {@code settings.max-capture-area}, which bounds both that distance
 * and the number of copied chunks. {@code settings.render-depth} only sets how far
 * below the reference ground the target floor sits; see {@link #floorReference} for
 * why that reference cannot be the camera's own column.</p>
 *
 * <p>Rays covering the part of the frame below the camera are pulled back along the
 * view direction; see {@link RenderGeometry}.</p>
 */
public final class RenderService {

    /** Number of slices the ray prism is cut into when capturing the region. */
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
     * Builds the spec describing what this camera would capture right now, freezing
     * the config values the image depends on.
     */
    public CaptureSpec specFor(Camera camera) {
        Location anchor = camera.anchor();
        World world = anchor.getWorld();
        return new CaptureSpec(
                world != null ? world.getUID() : null,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                camera.camYaw(), camera.camPitch(), camera.zoom(), camera.colorFilter(),
                plugin.config().frameHeight(), plugin.config().frameShift(),
                plugin.config().supersampling(), plugin.config().maxCaptureArea(),
                plugin.config().renderDepth());
    }

    /**
     * Captures the camera at its current settings; see
     * {@link #capture(CaptureSpec, int, int)}.
     */
    public CompletableFuture<RenderResult> capture(Camera camera, int widthPx, int heightPx) {
        return capture(specFor(camera), widthPx, heightPx);
    }

    /**
     * Captures the spec at the exact pixel size given; the aspect ratio comes from
     * those dimensions. Must be called on the main thread.
     */
    public CompletableFuture<RenderResult> capture(CaptureSpec spec, int widthPx, int heightPx) {
        World world = spec.worldId() != null ? plugin.getServer().getWorld(spec.worldId()) : null;
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Kamera dünyası yüklü değil."));
        }
        Location anchor = new Location(world, spec.x(), spec.y(), spec.z());

        double ratio = (double) widthPx / heightPx;
        double frameShift = spec.frameShift();
        double spanHeight = spec.frameHeight() / spec.zoom();
        double spanWidth = spanHeight * ratio;
        int captureArea = spec.maxCaptureArea();

        Vector direction = directionFrom(spec.yaw(), spec.pitch());
        Vector[] basis = basisFrom(direction);
        Vector right = basis[0];
        Vector up = basis[1];

        // At frame-shift 0 the camera's target is the center of the photo; positive
        // values move the frame up by a ratio of its height.
        double eyeY = anchor.getY();
        Vector planeCenter = anchor.toVector()
                .add(up.clone().multiply(spanHeight * frameShift));

        // Ray distance is derived, not configured: it is what the top-edge ray needs
        // to reach the target floor. `right` is always horizontal, so the frame's
        // vertical extent comes from `up` alone.
        double climb = -direction.getY();
        double topAboveEye = Math.max(0.0, (0.5 + frameShift) * spanHeight * up.getY());
        double floorY = floorReference(world, anchor, eyeY) - spec.renderDepth();
        double maxDistance = climb > 1.0e-6
                ? Math.min((eyeY + topAboveEye - floorY) / climb, captureArea)
                : captureArea;

        // The part of the frame below the camera is lifted to its horizontal plane by
        // backoff, see RenderGeometry.
        double dropBelowEye = Math.max(0.0, (0.5 - frameShift) * spanHeight * up.getY());
        double maxBackoff = climb > 1.0e-6 ? Math.min(dropBelowEye / climb, maxDistance) : 0.0;

        List<BoundingBox> beam = beamSlices(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxBackoff, maxDistance);
        Set<Long> chunkKeys = chunkKeys(beam);

        int budget = spec.chunkBudget();
        if (chunkKeys.size() > budget) {
            return CompletableFuture.failedFuture(new CaptureTooLargeException(chunkKeys.size(), budget));
        }

        RenderGeometry geometry = new RenderGeometry(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxDistance,
                eyeY, maxBackoff, widthPx, heightPx);
        ColorFilter filter = spec.colorFilter();
        int supersampling = spec.supersampling();
        int threads = plugin.config().renderThreads();
        Executor executor = workers(threads);

        // Read the height limits here and pass them by value, so the snapshot never
        // touches the world off the main thread.
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        return snapshotChunks(world, chunkKeys, minY, maxY).thenCompose(snapshot -> {
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
     * Collects copies of the required chunks. Started on the main thread.
     *
     * <p>Loaded chunks are copied straight away; the rest go through Paper's async
     * chunk API so the server never reads or generates chunks on the main thread.</p>
     *
     * <p>The copy is taken <b>inside the load callback</b>. Paper defines the
     * {@code getChunkAtAsync} future as "always executed synchronously on the main
     * Server Thread", so {@code thenApply} runs there at a moment the chunk is
     * certainly loaded. Deferring the copy by a tick does not work: async loading
     * holds no ticket, the chunk can unload again in between, and the photo ends up
     * with chunk-sized holes.</p>
     */
    private CompletableFuture<WorldSnapshot> snapshotChunks(World world, Set<Long> keys, int minY, int maxY) {
        boolean load = plugin.config().loadMissingChunks();
        boolean generate = plugin.config().generateMissingChunks();

        List<ChunkSnapshot> ready = new ArrayList<>(keys.size());
        List<CompletableFuture<ChunkSnapshot>> loading = new ArrayList<>();
        for (long key : keys) {
            int cx = WorldSnapshot.chunkX(key);
            int cz = WorldSnapshot.chunkZ(key);
            if (world.isChunkLoaded(cx, cz)) {
                ready.add(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            } else if (load) {
                // With generate=false an ungenerated chunk completes the future as null.
                loading.add(world.getChunkAtAsync(cx, cz, generate).thenApply(
                        chunk -> chunk == null ? null : chunk.getChunkSnapshot(false, false, false)));
            }
        }

        int requested = keys.size();
        if (loading.isEmpty()) {
            warnIfIncomplete(world, requested, ready.size());
            return CompletableFuture.completedFuture(WorldSnapshot.of(ready, minY, maxY));
        }

        return CompletableFuture.allOf(loading.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            for (CompletableFuture<ChunkSnapshot> future : loading) {
                ChunkSnapshot snapshot = future.getNow(null);
                if (snapshot != null) {
                    ready.add(snapshot);
                }
            }
            warnIfIncomplete(world, requested, ready.size());
            return WorldSnapshot.of(ready, minY, maxY);
        });
    }

    /**
     * Reports chunks that could not be copied. Each one leaves a silent transparent
     * hole in the photo, which otherwise looks like a bug in the ray walk.
     */
    private void warnIfIncomplete(World world, int requested, int captured) {
        if (captured >= requested) {
            return;
        }
        plugin.getLogger().warning("Kadraja giren " + requested + " chunk'ın "
                + (requested - captured) + " tanesinin kopyası alınamadı (" + world.getName()
                + "); o bölgeler fotoğrafta şeffaf kalacak. Muhtemel sebep: chunk hiç üretilmemiş"
                + " (settings.generate-missing-chunks kapalı) ya da settings.load-missing-chunks kapalı.");
    }

    /**
     * Floor height ray distance is measured against, before {@code render-depth} is
     * subtracted.
     *
     * <p>The highest block in the camera's own column cannot be the reference on its
     * own: on top of a tower or a tall tree that column reads hundreds of blocks
     * high and drags the floor up with it, putting distant ocean or plains out of
     * range and leaving a flat horizontal empty band across the top of the photo
     * (the top rows need the longest travel, so they run out first).</p>
     *
     * <p>The reference is therefore the lower of sea level and the ground in the
     * camera's column, or the camera itself when it is below both. Any excess
     * distance is already bounded by {@code settings.max-capture-area}.</p>
     */
    private static double floorReference(World world, Location anchor, double eyeY) {
        return Math.min(eyeY, Math.min(world.getSeaLevel(), world.getHighestBlockYAt(anchor)));
    }

    /** Chunk columns the ray prism touches; geometry only, no block access. */
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

    /** Releases the render pool when the plugin shuts down. */
    public synchronized void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
            workerCount = 0;
        }
    }

    /**
     * Returns the render pool at the configured size, rebuilding it when the count
     * changed via {@code /izomap reload}.
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

    /** Unit direction vector from Bukkit's yaw/pitch formula. */
    private static Vector directionFrom(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vector(-cosPitch * Math.sin(yawRad), -Math.sin(pitchRad), cosPitch * Math.cos(yawRad));
    }

    /** Derives the right/up axes from the direction, handling the vertical case. */
    private static Vector[] basisFrom(Vector direction) {
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = direction.clone().crossProduct(worldUp);
        if (right.lengthSquared() < 1.0e-6) {
            // Straight up or down: use world +Z as the reference instead.
            right = direction.clone().crossProduct(new Vector(0, 0, 1));
        }
        right.normalize();
        Vector up = right.clone().crossProduct(direction).normalize();
        return new Vector[] {right, up};
    }

    /**
     * Slices the ray prism along its depth and returns a box per slice.
     *
     * <p>A single box would be far wider than the prism at diagonal yaws and would
     * multiply the captured chunk count; slicing keeps the captured region close to
     * the prism's real shape.</p>
     *
     * <p>The prism starts {@code backoff} blocks <b>behind</b> the plane so it covers
     * the pulled-back rays too; without that region those rays would cross
     * uncaptured space and the bottom of the photo would be wrong.</p>
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
