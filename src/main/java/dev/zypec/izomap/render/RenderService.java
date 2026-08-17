package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * Number of slices the ray prism is cut into when capturing the region.
     */
    private static final int BEAM_SLICES = 8;

    private final Izomap plugin;
    /**
     * Replaced wholesale by {@link #reloadColors()}; a render in flight keeps the one
     * it started with, which is why the field is read into a local before the walk.
     */
    private volatile IsometricRenderer renderer;
    private final MapColorConverter converter = new MapColorConverter();
    private final AtomicInteger threadCounter = new AtomicInteger();

    private ExecutorService workers;
    private int workerCount;

    public RenderService(Izomap plugin, BlockColorTable colorTable) {
        this.plugin = plugin;
        this.renderer = new IsometricRenderer(colorTable);
    }

    /**
     * Reads {@code block-colors.yml} and the server's block colours again, so an edited
     * override takes effect on {@code /izocam reload} rather than on the next restart.
     */
    public void reloadColors() {
        this.renderer = new IsometricRenderer(BlockColorTable.load(plugin));
    }

    /**
     * Builds the spec describing what this camera would capture right now, freezing
     * the config values the image depends on.
     */
    public CaptureSpec specFor(Camera camera) {
        var anchor = camera.anchor();
        var world = anchor.getWorld();
        return new CaptureSpec(
                world != null ? world.getUID() : null,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                camera.camYaw(), camera.camPitch(), camera.zoom(), camera.colorFilter(), camera.style(),
                skyArgbFor(camera.sky(), world),
                plugin.config().shading(),
                plugin.config().water(),
                plugin.config().focus(camera.focusEnabled(), camera.focusDistance()),
                plugin.config().frameHeight(), plugin.config().frameShift(),
                plugin.config().supersampling(), plugin.config().maxCaptureArea(),
                plugin.config().renderDepth());
    }

    /**
     * The distances the focus slider may pick from for this camera, and where it should
     * sit before the player has moved it.
     *
     * <p>Both come from the same geometry the capture uses, so the slider cannot offer a
     * distance the rays never travel. Reads the world, so main thread only.</p>
     */
    public FocusRange focusRange(Camera camera) {
        var area = plugin.config().maxCaptureArea();
        var anchor = camera.anchor();
        var world = anchor.getWorld();
        if (world == null)
            return new FocusRange(area / 2.0, area);

        var direction = directionFrom(camera.camYaw(), camera.camPitch());
        var up = basisFrom(direction)[1];
        var spanHeight = plugin.config().frameHeight() / camera.zoom();
        var frameShift = plugin.config().frameShift();
        var eyeY = anchor.getY();
        var floorY = floorReference(world, anchor, eyeY) - plugin.config().renderDepth();
        var climb = -direction.getY();
        var limit = rayDistance(eyeY, floorY, climb, up.getY(), spanHeight, frameShift, area);

        // What the centre of the frame is aimed at: where its ray meets the reference
        // ground. A level camera has no such point, so the middle of the range stands in.
        var suggested = climb > 1.0e-6
                ? Math.min((eyeY - floorY) / climb, limit)
                : limit / 2.0;
        return new FocusRange(Math.max(1.0, suggested), Math.max(1.0, limit));
    }

    /**
     * Where a camera's focus may be put: {@code suggested} is the ground its frame is
     * centred on, {@code limit} the furthest its rays reach. Both in blocks from the
     * camera plane.
     */
    public record FocusRange(double suggested, double limit) {
    }

    /**
     * Resolves a player's sky choice into the colour it means right now.
     *
     * <p>Done here, once, at capture: {@code WORLD} has to read a clock that keeps
     * moving, and the whole point of freezing it is that the photo keeps the evening it
     * was taken in.</p>
     */
    private int skyArgbFor(SkyOption option, World world) {
        if (!option.draws())
            return 0;

        var ticks = option.ticks();
        if (ticks < 0)
            ticks = world != null ? (int) (world.getTime() % 24_000L) : SkyOption.DAY.ticks();

        return 0xFF000000 | Sky.colorAt(ticks,
                plugin.config().skyColor("dawn", 0xFF9E6B),
                plugin.config().skyColor("day", 0x78A7FF),
                plugin.config().skyColor("dusk", 0xFF7A4D),
                plugin.config().skyColor("night", 0x0B1026));
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
        return capture(spec, widthPx, heightPx, null);
    }

    /**
     * The same, reporting how far the ray walk has come as it goes.
     */
    public CompletableFuture<RenderResult> capture(CaptureSpec spec, int widthPx, int heightPx,
                                                   CaptureProgress progress) {
        var world = spec.worldId() != null ? plugin.getServer().getWorld(spec.worldId()) : null;
        if (world == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Camera world is not loaded."));

        var anchor = new Location(world, spec.x(), spec.y(), spec.z());

        var ratio = (double) widthPx / heightPx;
        var frameShift = spec.frameShift();
        var spanHeight = spec.frameHeight() / spec.zoom();
        var spanWidth = spanHeight * ratio;
        var captureArea = spec.maxCaptureArea();

        var direction = directionFrom(spec.yaw(), spec.pitch());
        var basis = basisFrom(direction);
        var right = basis[0];
        var up = basis[1];

        // At frame-shift 0 the camera's target is the center of the photo; positive
        // values move the frame up by a ratio of its height.
        var eyeY = anchor.getY();
        var planeCenter = anchor.toVector()
                .add(up.clone().multiply(spanHeight * frameShift));

        // Ray distance is derived, not configured: it is what the top-edge ray needs
        // to reach the target floor. `right` is always horizontal, so the frame's
        // vertical extent comes from `up` alone.
        var climb = -direction.getY();
        var floorY = floorReference(world, anchor, eyeY) - spec.renderDepth();
        var maxDistance = rayDistance(eyeY, floorY, climb, up.getY(), spanHeight, frameShift, captureArea);

        // The part of the frame below the camera is lifted to its horizontal plane by
        // backoff, see RenderGeometry.
        var dropBelowEye = Math.max(0.0, (0.5 - frameShift) * spanHeight * up.getY());
        var maxBackoff = climb > 1.0e-6 ? Math.min(dropBelowEye / climb, maxDistance) : 0.0;

        var beam = beamSlices(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxBackoff, maxDistance);
        var chunkKeys = chunkKeys(beam);

        var budget = spec.chunkBudget();
        if (chunkKeys.size() > budget)
            return CompletableFuture.failedFuture(new CaptureTooLargeException(chunkKeys.size(), budget));

        var style = spec.style();
        // FAST casts its rays over a smaller image and lets the scale-up fill the rest,
        // so the ray count falls with the square of the scale.
        var scale = style.scalesDown() ? plugin.config().styleFastScale() : 1.0;
        var renderWidth = Math.max(1, (int) Math.round(widthPx * scale));
        var renderHeight = Math.max(1, (int) Math.round(heightPx * scale));
        // The sun vector is the view direction of something looking down from the sun,
        // reversed: a surface looks up towards it.
        var toSun = directionFrom(spec.shading().sunYaw(), spec.shading().sunPitch()).multiply(-1.0);
        var shading = Shading.of(spec.shading(), toSun.getX(), toSun.getY(), toSun.getZ());
        var water = Water.of(spec.water());
        var sky = spec.skyArgb() == 0
                ? Sky.NONE
                : Sky.of(spec.skyArgb() & 0xFFFFFF, plugin.config().skyGradient(),
                plugin.config().skyHorizonBlend(), plugin.config().skyDither(),
                renderHeight, converter);

        var geometry = new RenderGeometry(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxDistance,
                eyeY, maxBackoff, renderWidth, renderHeight);
        var pipeline = ColorPipeline.of(spec.colorFilter(), converter);
        var focus = spec.focus();
        var supersampling = spec.supersampling();
        var threads = plugin.config().renderThreads();
        var executor = workers(threads);
        var timing = plugin.config().renderTiming();

        // Read the height limits here and pass them by value, so the snapshot never
        // touches the world off the main thread.
        var minY = world.getMinHeight();
        var maxY = world.getMaxHeight();

        // Taken now, so a reload part-way through cannot leave half the image on one
        // colour table and half on another.
        var walker = renderer;
        var requestedAt = System.nanoTime();
        return snapshotChunks(world, chunkKeys, minY, maxY, spec.shading().blockLight()).thenCompose(snapshot -> {
            var capturedAt = System.nanoTime();
            CompletableFuture<RenderResult> future = new CompletableFuture<>();
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    var result = walker.render(
                            snapshot, geometry, pipeline, sky, shading, water, supersampling, focus.draws(),
                            progress, executor, threads);
                    // Before the scale-up, where the depth buffer still lines up with the
                    // pixels. The radius is a ratio of image height, so a FAST render
                    // blurs a smaller image by a smaller radius and the scale-up puts
                    // both back; applying it afterwards would mean stretching the depth.
                    result = FocusPass.apply(result, focus, spanHeight, converter, executor, threads);
                    result = StylePass.upscale(result, widthPx, heightPx, converter);
                    if (timing) {
                        logTiming(geometry, snapshot, supersampling, requestedAt, capturedAt, System.nanoTime());
                    }
                    future.complete(result);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        });
    }

    /**
     * Reports what a capture cost, split into copying the chunks and walking the rays.
     *
     * <p>This is the reference point new render stages are measured against, so the two
     * halves stay apart: copying is main-thread work bounded by disk, the walk is pure
     * CPU spread over the render pool. Off by default, because a live preview renders
     * over and over and would flood the console.</p>
     */
    private void logTiming(RenderGeometry geometry, WorldSnapshot snapshot, int supersampling,
                           long requestedAt, long capturedAt, long finishedAt) {
        plugin.messages().info("log.render-timing",
                Placeholder.unparsed("width", String.valueOf(geometry.widthPx())),
                Placeholder.unparsed("height", String.valueOf(geometry.heightPx())),
                Placeholder.unparsed("samples", String.valueOf(supersampling)),
                Placeholder.unparsed("chunks", String.valueOf(snapshot.chunkCount())),
                Placeholder.unparsed("copy", millis(capturedAt - requestedAt)),
                Placeholder.unparsed("render", millis(finishedAt - capturedAt)));
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /**
     * Collects copies of the required chunks. Started on the main thread.
     *
     * <p>Loaded chunks are copied straight away; the rest go through Paper's async
     * chunk API so the server never reads or generates chunks on the main thread.</p>
     *
     * <p>{@code light} copies the sky and block light arrays along with the blocks.
     * Paper's three-argument overload takes them <b>by default</b>, so every render used
     * to pay for light it never read; it is now asked for only when the shading darkens
     * by it.</p>
     *
     * <p>The copy is taken <b>inside the load callback</b>. Paper defines the
     * {@code getChunkAtAsync} future as "always executed synchronously on the main
     * Server Thread", so {@code thenApply} runs there at a moment the chunk is
     * certainly loaded. Deferring the copy by a tick does not work: async loading
     * holds no ticket, the chunk can unload again in between, and the photo ends up
     * with chunk-sized holes.</p>
     */
    private CompletableFuture<WorldSnapshot> snapshotChunks(World world, Set<Long> keys,
                                                            int minY, int maxY, boolean light) {
        var load = plugin.config().loadMissingChunks();
        var generate = plugin.config().generateMissingChunks();

        List<ChunkSnapshot> ready = new ArrayList<>(keys.size());
        List<CompletableFuture<ChunkSnapshot>> loading = new ArrayList<>();
        for (var key : keys) {
            var cx = WorldSnapshot.chunkX(key);
            var cz = WorldSnapshot.chunkZ(key);
            if (world.isChunkLoaded(cx, cz)) {
                ready.add(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false, light));
            } else if (load) {
                // With generate=false, an ungenerated chunk completes the future as null.
                loading.add(world.getChunkAtAsync(cx, cz, generate).thenApply(
                        chunk -> chunk == null ? null : chunk.getChunkSnapshot(false, false, false, light)));
            }
        }

        var requested = keys.size();
        if (loading.isEmpty()) {
            warnIfIncomplete(world, requested, ready.size());
            return CompletableFuture.completedFuture(WorldSnapshot.of(ready, minY, maxY));
        }

        return CompletableFuture.allOf(loading.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            for (var future : loading) {
                var snapshot = future.getNow(null);
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
        if (captured >= requested) return;

        plugin.messages().warn("log.chunks-incomplete",
                Placeholder.unparsed("requested", String.valueOf(requested)),
                Placeholder.unparsed("missing", String.valueOf(requested - captured)),
                Placeholder.unparsed("world", world.getName()));
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

    /**
     * How far the rays travel: what the ray at the top edge of the frame needs to reach
     * the target floor, bounded by {@code settings.max-capture-area}. A camera that does
     * not look down has no such ray and takes the bound.
     */
    private static double rayDistance(double eyeY, double floorY, double climb, double upY,
                                      double spanHeight, double frameShift, double captureArea) {
        if (climb <= 1.0e-6)
            return captureArea;

        var topAboveEye = Math.max(0.0, (0.5 + frameShift) * spanHeight * upY);
        return Math.min((eyeY + topAboveEye - floorY) / climb, captureArea);
    }

    /**
     * Chunk columns the ray prism touches; geometry only, no block access.
     */
    private static Set<Long> chunkKeys(List<BoundingBox> beam) {
        Set<Long> keys = new HashSet<>();
        for (var box : beam) {
            var minChunkX = (int) Math.floor(box.getMinX()) >> 4;
            var maxChunkX = (int) Math.floor(box.getMaxX()) >> 4;
            var minChunkZ = (int) Math.floor(box.getMinZ()) >> 4;
            var maxChunkZ = (int) Math.floor(box.getMaxZ()) >> 4;
            for (var cx = minChunkX; cx <= maxChunkX; cx++) {
                for (var cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    keys.add(WorldSnapshot.key(cx, cz));
                }
            }
        }
        return keys;
    }

    /**
     * Releases the render pool when the plugin shuts down.
     */
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

    /**
     * Unit direction vector from Bukkit's yaw/pitch formula.
     */
    private static Vector directionFrom(float yaw, float pitch) {
        var yawRad = Math.toRadians(yaw);
        var pitchRad = Math.toRadians(pitch);
        var cosPitch = Math.cos(pitchRad);
        return new Vector(-cosPitch * Math.sin(yawRad), -Math.sin(pitchRad), cosPitch * Math.cos(yawRad));
    }

    /**
     * Derives the right/up axes from the direction, handling the vertical case.
     */
    private static Vector[] basisFrom(Vector direction) {
        var worldUp = new Vector(0, 1, 0);
        var right = direction.clone().crossProduct(worldUp);
        if (right.lengthSquared() < 1.0e-6) {
            // Straight up or down: use world +Z as the reference instead.
            right = direction.clone().crossProduct(new Vector(0, 0, 1));
        }
        right.normalize();
        var up = right.clone().crossProduct(direction).normalize();
        return new Vector[]{right, up};
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
        var hw = spanW / 2.0;
        var hh = spanH / 2.0;
        var sliceDepth = (backoff + maxDist) / BEAM_SLICES;

        List<BoundingBox> slices = new ArrayList<>(BEAM_SLICES);
        for (int i = 0; i < BEAM_SLICES; i++) {
            double near = -backoff + i * sliceDepth;
            BoundingBox box = null;
            for (int sw = -1; sw <= 1; sw += 2) {
                for (int sh = -1; sh <= 1; sh += 2) {
                    for (int sd = 0; sd <= 1; sd++) {
                        var corner = center.clone()
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
