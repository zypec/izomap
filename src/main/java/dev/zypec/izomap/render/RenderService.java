package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    /**
     * Shadow length the shader style casts at least, in blocks: its look is long
     * shadows, and the server's default stops a tower's shadow halfway down the street.
     */
    private static final int SHADER_SHADOW_DISTANCE = 48;

    private final Izomap plugin;
    /**
     * Replaced wholesale by {@link #reloadColors()}; a render in flight keeps the one
     * it started with, which is why the field is read into a local before the walk.
     */
    private volatile IsometricRenderer renderer;
    /**
     * Reloaded with the colour table, and read into a local before a render for the same
     * reason.
     */
    private volatile BiomeTints biomeTints = BiomeTints.NONE;
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
        this.biomeTints = BiomeTints.load(plugin);
    }

    /**
     * Reads the biome tints for the first time. Separate from the constructor because it
     * asks the server's registries, which are not ready while plugins are being
     * constructed.
     */
    public void loadBiomeTints() {
        this.biomeTints = BiomeTints.load(plugin);
    }

    /**
     * Builds the spec describing what this camera would capture right now, freezing
     * the config values the image depends on.
     */
    public CaptureSpec specFor(Camera camera) {
        return specFor(camera.anchor(), camera.camYaw(), camera.camPitch(), camera.zoom(),
                camera.colorFilter(), camera.style(), camera.sky(), camera.skyBodies(),
                camera.focusEnabled(), camera.focusDistance(), camera.sunYaw(), camera.sunPitch(),
                camera.fogEnabled(), camera.fogStart(), camera.fogEnd());
    }

    /**
     * The same for a view no camera stands at, such as one the development tools shoot.
     */
    public CaptureSpec specFor(Location anchor, float yaw, float pitch, float zoom,
                               ColorFilter colorFilter, PhotoStyle style, SkyOption sky,
                               boolean skyBodies, boolean focusEnabled, double focusDistance,
                               float sunYaw, float sunPitch,
                               boolean fogEnabled, double fogStart, double fogEnd) {
        var world = anchor.getWorld();
        var skySpec = skyFor(sky, skyBodies && sky.draws(), world);
        return new CaptureSpec(
                world != null ? world.getUID() : null,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                yaw, pitch, zoom, colorFilter, style,
                skySpec,
                style == PhotoStyle.SHADER
                        ? shadingFor(skySpec, sunYaw, sunPitch).withShadows(SHADER_SHADOW_DISTANCE)
                        : shadingFor(skySpec, sunYaw, sunPitch),
                plugin.config().water(),
                plugin.config().focus(focusEnabled, focusDistance),
                plugin.config().fog(fogEnabled, fogStart, fogEnd),
                style == PhotoStyle.SHADER ? plugin.config().shader() : ShaderSpec.NONE,
                plugin.config().frameHeight(), plugin.config().frameShift(),
                plugin.config().supersampling(), plugin.config().maxCaptureArea(),
                plugin.config().renderDepth());
    }

    /**
     * The server's shading under this camera's sun and its sky's hour.
     *
     * <p>A night sky over a landscape in full daylight read as a pasted backdrop. The sky
     * light follows the hour the sky is frozen at, the way the game's own sunlight does:
     * full from mid-morning to mid-afternoon, {@code photo.shading.night-light} from
     * late evening to before dawn, a ramp between. A transparent sky leaves the day.</p>
     */
    private ShadingSpec shadingFor(SkySpec sky, float sunYaw, float sunPitch) {
        var shading = plugin.config().shading().withSun(sunYaw, sunPitch);
        return sky.draws() ? shading.withSkyLight(skyLightAt(sky.ticks(), plugin.config().nightLight())) : shading;
    }

    /**
     * Sky light at an hour of the day: 1 at noon to -1 at midnight on a cosine, scaled so
     * the day holds full light for a while either side of noon.
     */
    static int skyLightAt(int ticks, int nightLight) {
        var sun = Math.cos(2.0 * Math.PI * (ticks - SkyOption.DAY.ticks()) / 24_000.0);
        var day = Math.max(0.0, Math.min(1.0, sun * 1.5 + 0.75));
        return (int) Math.round(nightLight + (ShadingSpec.DAYLIGHT - nightLight) * day);
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
     * What a camera's frame lands on: the point its centre is aimed at, and the four
     * corners of the frame on the ground there.
     *
     * <p>An orthographic camera's rays are parallel, so where the camera stands along its
     * view says nothing about the picture: this is what does. The ground is read only in
     * loaded chunks (sea level stands in elsewhere), so asking never loads one. A camera
     * that does not look down has no ground point; {@code corners} is then empty and the
     * target sits {@link #LEVEL_TARGET} blocks ahead. Main thread only.</p>
     *
     * @param distance how far the target is from the camera along its view
     */
    public record Frame(Vector target, List<Vector> corners, double distance) {
    }

    /** How far ahead a level camera's target is put, for lack of ground to aim at. */
    private static final double LEVEL_TARGET = 96.0;

    public Frame frame(Camera camera) {
        return frame(camera, null);
    }

    /** @param chunks copies of chunks the world has not loaded, read where it has none; may be null */
    public Frame frame(Camera camera, ChunkCache chunks) {
        return frame(camera.anchor(), camera.camYaw(), camera.camPitch(), camera.zoom(), camera.aspectRatio().value(), camera.aimY(), chunks);
    }

    public Frame frame(Location anchor, float yaw, float pitch, float zoom, double ratio) {
        return frame(anchor, yaw, pitch, zoom, ratio, Double.NaN, null);
    }

    /**
     * @param aimY the height of the plane the frame lies on, or NaN to read the ground
     */
    public Frame frame(Location anchor, float yaw, float pitch, float zoom, double ratio, double aimY, ChunkCache chunks) {
        var world = anchor.getWorld();
        var direction = directionFrom(yaw, pitch);
        var basis = basisFrom(direction);
        var spanHeight = plugin.config().frameHeight() / zoom;
        var centre = anchor.toVector().add(basis[1].clone().multiply(spanHeight * plugin.config().frameShift()));
        var climb = -direction.getY();
        if (world == null || climb < 0.02) {
            var ahead = centre.clone().add(direction.clone().multiply(LEVEL_TARGET));
            return new Frame(ahead, List.of(), LEVEL_TARGET);
        }
        // A plane the camera was aimed on stays put as the camera turns, tilts or zooms;
        // otherwise the middle is whatever its ray meets first, a wall as much as the ground.
        double t;
        if (!Double.isNaN(aimY) && aimY < centre.getY()) t = (centre.getY() - aimY) / climb;
        else {
            var hit = firstHit(world, chunks, centre, direction, MARCH_REACH);
            t = !Double.isNaN(hit) ? hit : (centre.getY() - groundAt(world, centre, direction, climb)) / climb;
        }
        var target = centre.clone().add(direction.clone().multiply(t));
        var ground = target.getY();
        // A corner lies where its ray meets the world, or on the plane of the middle when it
        // meets nothing; never far beyond the middle, or a nearly level camera's frame would
        // reach hundreds of blocks past what its picture shows.
        var furthest = t + 2.0 * spanHeight;
        List<Vector> corners = new ArrayList<>(4);
        for (var corner : new double[][]{{-1, 1}, {1, 1}, {1, -1}, {-1, -1}}) {
            var start = centre.clone()
                    .add(basis[0].clone().multiply(corner[0] * spanHeight * ratio / 2.0))
                    .add(basis[1].clone().multiply(corner[1] * spanHeight / 2.0));
            var hit = firstHit(world, chunks, start, direction, furthest);
            var reach = Double.isNaN(hit) ? Math.min(furthest, Math.max(0.0, (start.getY() - ground) / climb)) : hit;
            corners.add(start.add(direction.clone().multiply(reach)));
        }
        return new Frame(target, corners, t);
    }

    /** How far a ray is followed over the heightmap looking for what it meets. */
    private static final double MARCH_REACH = 640.0;
    /** How many rays across and down the frame are tried for {@link #clearDistance}. */
    private static final int CLEAR_SAMPLES = 6;
    /** How far above its aim the world is looked through for what the frame shows: the tallest build worth keeping in a photo. */
    private static final double CLEAR_RISE = 160.0;
    /** How far in front of a camera the nearest thing it shows is kept, and how high above its aim it stands at least. */
    private static final double CLEAR_MARGIN = 2.0;
    private static final double CLEAR_ABOVE = 4.0;

    /**
     * How far along a ray, in blocks, it meets a block that is not air, walked half a block
     * at a time. NaN when it meets nothing within {@code reach}. Reads the world where the
     * chunk is loaded and a copy in {@code chunks} where it is not, which is where an editor
     * camera's preview copied it; a chunk in neither counts as open air, and is never loaded.
     */
    private static double firstHit(World world, ChunkCache chunks, Vector from, Vector direction, double reach) {
        int bottom = world.getMinHeight(), top = world.getMaxHeight();
        var id = world.getUID();
        long lastKey = Long.MIN_VALUE;
        org.bukkit.ChunkSnapshot copy = null;
        var loaded = false;
        for (var t = 0.0; t <= reach; t += 0.5) {
            var y = (int) Math.floor(from.getY() + direction.getY() * t);
            if (y < bottom) return t;
            if (y >= top) continue;
            int x = (int) Math.floor(from.getX() + direction.getX() * t), z = (int) Math.floor(from.getZ() + direction.getZ() * t);
            var key = WorldSnapshot.key(x >> 4, z >> 4);
            if (key != lastKey) {
                lastKey = key;
                loaded = world.isChunkLoaded(x >> 4, z >> 4);
                copy = loaded || chunks == null ? null : chunks.any(id, key);
            }
            var solid = loaded ? !world.getBlockAt(x, y, z).getType().isAir()
                    : copy != null && !copy.getBlockType(x & 15, y, z & 15).isAir();
            if (solid) return t;
        }
        return Double.NaN;
    }

    /**
     * The least distance back along its view a camera aimed at {@code target} can stand:
     * just in front of the nearest thing any part of its frame shows. An orthographic
     * picture is the same from any distance along the view, so the camera is kept as near as
     * that allows rather than as far as it once was, and does not climb a little more with
     * every hill it is moved over. Rays are tried across the frame, each from where it is
     * {@link #CLEAR_RISE} above the aim. Main thread.
     */
    public double clearDistance(Location target, float yaw, float pitch, float zoom, double ratio, ChunkCache chunks) {
        var direction = directionFrom(yaw, pitch);
        var climb = -direction.getY();
        var least = CLEAR_ABOVE / Math.max(climb, 0.05);
        var world = target.getWorld();
        if (world == null || climb < 0.02) return Math.max(least, LEVEL_TARGET);
        var basis = basisFrom(direction);
        var spanHeight = plugin.config().frameHeight() / zoom;
        var spanWidth = spanHeight * ratio;
        var needed = least;
        for (var i = 0; i <= CLEAR_SAMPLES; i++)
            for (var j = 0; j <= CLEAR_SAMPLES; j++) {
                var through = target.toVector()
                        .add(basis[0].clone().multiply((i / (double) CLEAR_SAMPLES - 0.5) * spanWidth))
                        .add(basis[1].clone().multiply((j / (double) CLEAR_SAMPLES - 0.5) * spanHeight));
                var top = Math.min(world.getMaxHeight(), target.getY() + CLEAR_RISE);
                var back = Math.min(MARCH_REACH, Math.max(0.0, (top - through.getY()) / climb));
                var from = through.clone().subtract(direction.clone().multiply(back));
                var hit = firstHit(world, chunks, from, direction, back);
                if (!Double.isNaN(hit)) needed = Math.max(needed, back - hit + CLEAR_MARGIN);
            }
        return needed;
    }

    /**
     * The height of the ground the centre ray meets, found by walking the estimate in: a
     * guess at sea level, then the ground under where that guess lands, twice.
     */
    private static double groundAt(World world, Vector from, Vector direction, double climb) {
        double ground = world.getSeaLevel();
        for (var pass = 0; pass < 3; pass++) {
            var t = Math.max(0.0, (from.getY() - ground) / climb);
            var x = (int) Math.floor(from.getX() + direction.getX() * t);
            var z = (int) Math.floor(from.getZ() + direction.getZ() * t);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) break;
            ground = Math.min(from.getY() - 1, world.getHighestBlockYAt(x, z) + 1);
        }
        return ground;
    }

    /**
     * Where a camera aimed this way must stand to keep {@code target} in the middle of its
     * frame, {@code distance} blocks back along its view.
     */
    /**
     * The same, then drawn back along the view until it stands in open air above the
     * ground under it. Along its own view an orthographic camera's picture does not
     * change, but a camera inside a hill sees the hill's insides: its lowest rays start at
     * its own height, which is underground, and print a cross-section of the earth along
     * the bottom of the photo. A level camera cannot climb this way and is left where it is.
     * Reads only loaded chunks. Main thread.
     */
    public Location standClear(Location target, float yaw, float pitch, double distance) {
        var direction = directionFrom(yaw, pitch);
        var at = standFor(target, yaw, pitch, distance);
        var world = target.getWorld();
        if (world == null || -direction.getY() < 0.02) return at;
        for (var back = 0; back < 64; back++) {
            int bx = at.getBlockX(), bz = at.getBlockZ();
            if (!world.isChunkLoaded(bx >> 4, bz >> 4) || at.getY() > world.getHighestBlockYAt(bx, bz) + 2) break;
            if (at.getY() >= world.getMaxHeight() - 2) break;
            at = standFor(target, yaw, pitch, distance + 4.0 * (back + 1));
        }
        return at;
    }

    /** The camera's up in its own picture, at this facing and tilt. */
    public Vector upFrom(float yaw, float pitch) {
        return basisFrom(directionFrom(yaw, pitch))[1].clone();
    }

    public Location standFor(Location target, float yaw, float pitch, double distance) {
        var direction = directionFrom(yaw, pitch);
        var at = target.toVector().subtract(direction.multiply(distance));
        var out = new Location(target.getWorld(), at.getX(), at.getY(), at.getZ(), yaw, 0.0f);
        var world = target.getWorld();
        if (world != null)
            out.setY(Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, out.getY())));
        return out;
    }

    /**
     * Where a camera's focus may be put: {@code suggested} is the ground its frame is
     * centred on, {@code limit} the furthest its rays reach. Both in blocks from the
     * camera plane.
     */
    public record FocusRange(double suggested, double limit) {
    }

    /**
     * Resolves a camera's sky choice into what that sky is right now.
     *
     * <p>Done here, once, at capture: {@code WORLD} has to read a clock that keeps
     * moving, and the whole point of freezing it is that the photo keeps the evening it
     * was taken in. The time is kept alongside the colour because the bodies need it —
     * which of the two is up comes from the hour, the moon's phase from the day.</p>
     *
     * <p>A fixed choice such as {@code NIGHT} still takes its phase from the world:
     * the hour is the part the player picked, and the calendar is not.</p>
     */
    private SkySpec skyFor(SkyOption option, boolean withBodies, World world) {
        if (!option.draws())
            return SkySpec.NONE;

        var ticks = option.ticks();
        if (ticks < 0)
            ticks = world != null ? (int) (world.getTime() % 24_000L) : SkyOption.DAY.ticks();

        var argb = 0xFF000000 | Sky.colorAt(ticks,
                plugin.config().skyColor("dawn", 0xFF9E6B),
                plugin.config().skyColor("day", 0x78A7FF),
                plugin.config().skyColor("dusk", 0xFF7A4D),
                plugin.config().skyColor("night", 0x0B1026));
        var phase = world != null ? (int) ((world.getFullTime() / 24_000L) % 8L) : 0;
        var bodies = withBodies ? plugin.config().skyBodies() : SkyBodiesSpec.NONE;
        return new SkySpec(argb, ticks, phase, bodies);
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
        return capture(spec, widthPx, heightPx, progress, null, null, null);
    }

    /**
     * The same, stopping early once {@code cancellation} is called off: the future then
     * fails with a {@link CancellationException}. Chunk copies are taken from and kept in
     * {@code cache} when one is given, and how far the frame sees goes to {@code scene};
     * only the live preview asks for either.
     */
    public CompletableFuture<RenderResult> capture(CaptureSpec spec, int widthPx, int heightPx,
                                                   CaptureProgress progress,
                                                   RenderCancellation cancellation, ChunkCache cache,
                                                   Consumer<SceneDepth> scene) {
        var world = spec.worldId() != null ? plugin.getServer().getWorld(spec.worldId()) : null;
        if (world == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Camera world is not loaded."));

        // The ray distance reads the ground under the camera, and reading an unloaded
        // chunk loads it on the spot: 400 ms of main thread for a camera nobody stands
        // near. It is loaded off the thread first; the callback runs back on it.
        var cx = (int) Math.floor(spec.x()) >> 4;
        var cz = (int) Math.floor(spec.z()) >> 4;
        if (!world.isChunkLoaded(cx, cz))
            return world.getChunkAtAsync(cx, cz, false)
                    .thenCompose(chunk -> captureLoaded(world, spec, widthPx, heightPx, progress,
                            cancellation, cache, scene));

        return captureLoaded(world, spec, widthPx, heightPx, progress, cancellation, cache, scene);
    }

    /**
     * The capture once the camera's own chunk has been loaded, or found missing.
     */
    private CompletableFuture<RenderResult> captureLoaded(World world, CaptureSpec spec, int widthPx, int heightPx,
                                                          CaptureProgress progress,
                                                          RenderCancellation cancellation, ChunkCache cache,
                                                          Consumer<SceneDepth> scene) {
        if (cancellation != null && cancellation.cancelled())
            return CompletableFuture.failedFuture(new CancellationException("Render cancelled."));

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
        var water = Water.of(spec.water(), direction.getX(), direction.getY(), direction.getZ(),
                toSun.getX(), toSun.getY(), toSun.getZ(), plugin.config().skyBodies().sunRgb(),
                spec.shading().skyLight() / (double) ShadingSpec.DAYLIGHT);
        // Read once here: a reload swapping the table mid-render would leave half the
        // image on one set of tints, like the colour table it travels with.
        var tints = biomeTints;

        var geometry = new RenderGeometry(
                planeCenter, right, up, direction, spanWidth, spanHeight, maxDistance,
                eyeY, maxBackoff, renderWidth, renderHeight);
        // Built from the geometry, since where a body falls in the frame is a question
        // about the camera's axes and nothing else.
        var sky = !spec.sky().draws()
                ? Sky.NONE
                : Sky.of(spec.sky().argb() & 0xFFFFFF, plugin.config().skyGradient(),
                plugin.config().skyHorizonBlend(), plugin.config().skyDither(),
                renderHeight, converter,
                SkyBodies.of(spec.sky(), geometry, toSun.getX(), toSun.getY(), toSun.getZ(), converter));
        var pipeline = ColorPipeline.of(spec.colorFilter(), converter, tints);
        var focus = spec.focus();
        var fog = spec.fog();
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
        return snapshotChunks(world, chunkKeys, minY, maxY,
                spec.shading().readsLight(), tints.enabled(), cancellation, cache).thenCompose(snapshot -> {
            var capturedAt = System.nanoTime();
            if (cancellation != null) cancellation.walking(true);
            CompletableFuture<RenderResult> future = new CompletableFuture<>();
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    var result = walker.render(
                            snapshot, geometry, pipeline, sky, shading, water, tints, supersampling,
                            focus.draws() || fog.draws() || spec.shader().haze() > 0.0 || scene != null,
                            progress, cancellation,
                            executor, threads);
                    if (cancellation != null) cancellation.check();
                    if (scene != null)
                        scene.accept(SceneDepth.of(result.depth(), result.width(), result.height()));
                    // Before the blur, so a defocused far hill is soft and hazy at once
                    // rather than a sharp line of fog drawn over a blur.
                    result = FogPass.apply(result, fog, sky, converter, executor, threads);
                    if (cancellation != null) cancellation.check();
                    // Before the blur too: a shader grades and glows the scene, then the
                    // lens softens what is out of focus.
                    result = ShaderPass.apply(result, spec.shader(), sky, captureArea, converter, executor, threads);
                    if (cancellation != null) cancellation.check();
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
     * <p>Loaded chunks are copied a time budget per tick, the rest go through Paper's
     * async chunk API first; see {@link ChunkCopier}.</p>
     *
     * <p>{@code light} copies the sky and block light arrays along with the blocks.
     * Paper's three-argument overload takes them <b>by default</b>, so every render used
     * to pay for light it never read; it is now asked for only when the shading darkens
     * by it.</p>
     */
    private CompletableFuture<WorldSnapshot> snapshotChunks(World world, Set<Long> keys,
                                                            int minY, int maxY,
                                                            boolean light, boolean biomes,
                                                            RenderCancellation cancellation, ChunkCache cache) {
        var requested = keys.size();
        return ChunkCopier.copy(plugin, world, keys, light, biomes, cancellation, cache).thenApply(copied -> {
            // A preview draws before its far chunks arrive, so a gap there is expected.
            if (cache == null || cache.waits())
                warnIfIncomplete(world, requested, copied.size());
            return WorldSnapshot.of(copied, minY, maxY);
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
        var floor = Math.min(eyeY, world.getSeaLevel());
        // Never loads a chunk for this; a capture has loaded it already, and a camera
        // whose chunk could not be had falls back on sea level.
        if (!world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4))
            return floor;
        return Math.min(floor, world.getHighestBlockYAt(anchor));
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
