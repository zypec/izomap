package dev.zypec.izomap.render;

/**
 * The sun, the moon and the stars, as they fall on a photo's sky.
 *
 * <h2>Where a body lands in the frame</h2>
 *
 * <p>The projection is orthographic, so nothing infinitely far away has a position in
 * it at all: parallel rays either all hit a distant object or none of them do. The sky
 * is therefore treated as a dome the camera looks into, and a body's place in the frame
 * comes from its <b>direction</b> rather than from any point in the world:</p>
 *
 * <pre>
 * u = dot(towards the body, right)   v = dot(towards the body, up)
 * visible when dot(towards the body, view) &gt; 0
 * </pre>
 *
 * <p>Both are direction cosines, so scaling them by half the image height puts a body
 * 90° off the view axis exactly on the frame's edge and keeps the dome round on any
 * aspect ratio. Turning the camera therefore slides the sun across the frame the way it
 * would in the world, and two shots from the same camera put it in the same place.</p>
 *
 * <h2>Which sun</h2>
 *
 * <p>The disc is drawn in the direction the <b>shadows</b> come from
 * ({@code photo.shading.sun-yaw} / {@code sun-pitch}) rather than at the angle the game's
 * own clock would put it. The sky's colour comes from the clock and the shadows do not,
 * so the alternative was a photo that contradicts itself: shadows falling one way and
 * the sun sitting the other.</p>
 *
 * <h2>Stars are a function of direction, not of the screen</h2>
 *
 * <p>A star is a cell of a fixed grid laid over the sphere of directions, chosen by a
 * hash of that cell. Nothing about it depends on the camera, so panning across a night
 * sky slides the stars past rather than reshuffling them, and the same camera sees the
 * same sky twice. A cell is about a pixel wide at photo resolution; on the far smaller
 * preview several cells fall inside one pixel and only one of them is sampled, so a
 * preview shows a thinner sky than the photo it stands for.</p>
 */
public final class SkyBodies {

    /**
     * Nothing but the sky's own colour.
     */
    public static final SkyBodies NONE = new SkyBodies();

    /**
     * Times of day the sun is above the horizon. Outside them the moon has the sky:
     * a sun disc over a night-coloured sky is not a style, it is a mistake.
     */
    private static final int SUNRISE_TICKS = 23_000;
    private static final int SUNSET_TICKS = 13_000;

    /**
     * Cells per unit of direction. One cell is roughly one pixel across on a photo
     * rendered a thousand pixels tall, which is the size a star wants to be.
     */
    private static final double STAR_GRID = 512.0;

    /**
     * Sky brightness (0-1) at which stars stop being drawn. Above it the sky is day
     * enough that a star reads as a stuck pixel rather than as a star.
     */
    private static final double STAR_SKY_LIMIT = 0.35;

    /**
     * A body that is not in this photo. Read-only, so one instance answers for all of
     * them.
     */
    private static final double[] ABSENT = {0.0, 0.0, 0.0};

    private final boolean draws;

    /**
     * Screen centre and the half-height every direction cosine is scaled by.
     */
    private final double centerX;
    private final double centerY;
    private final double scale;

    private final double rightX, rightY, rightZ;
    private final double upX, upY, upZ;
    private final double viewX, viewY, viewZ;

    /**
     * Disc centres in pixels and their radii; a radius of {@code 0} means the body is
     * off, below the horizon or out of frame.
     */
    private final double sunX, sunY, sunRadius;
    private final double moonX, moonY, moonRadius;

    /**
     * How far the disc that eats the moon's dark limb sits from its centre. At two radii
     * it misses the moon entirely (full), at zero it covers it (new).
     */
    private final double phaseOffset;

    private final int sunArgb;
    private final int moonArgb;
    private final int starArgb;

    /**
     * Share of the star grid's cells that hold a star, already faded by how dark the sky
     * is; {@code 0} draws none.
     */
    private final double starChance;

    private SkyBodies() {
        this.draws = false;
        this.centerX = 0;
        this.centerY = 0;
        this.scale = 0;
        this.rightX = 0;
        this.rightY = 0;
        this.rightZ = 0;
        this.upX = 0;
        this.upY = 0;
        this.upZ = 0;
        this.viewX = 0;
        this.viewY = 0;
        this.viewZ = 0;
        this.sunX = 0;
        this.sunY = 0;
        this.sunRadius = 0;
        this.moonX = 0;
        this.moonY = 0;
        this.moonRadius = 0;
        this.phaseOffset = 0;
        this.sunArgb = 0;
        this.moonArgb = 0;
        this.starArgb = 0;
        this.starChance = 0;
    }

    private SkyBodies(RenderGeometry geo, double[] sun, double[] moon, double phaseOffset,
                      int sunArgb, int moonArgb, int starArgb, double starChance) {
        this.centerX = geo.widthPx() / 2.0;
        this.centerY = geo.heightPx() / 2.0;
        this.scale = geo.heightPx() / 2.0;
        this.rightX = geo.right().getX();
        this.rightY = geo.right().getY();
        this.rightZ = geo.right().getZ();
        this.upX = geo.up().getX();
        this.upY = geo.up().getY();
        this.upZ = geo.up().getZ();
        this.viewX = geo.direction().getX();
        this.viewY = geo.direction().getY();
        this.viewZ = geo.direction().getZ();
        this.sunX = sun[0];
        this.sunY = sun[1];
        this.sunRadius = sun[2];
        this.moonX = moon[0];
        this.moonY = moon[1];
        this.moonRadius = moon[2];
        this.phaseOffset = phaseOffset;
        this.sunArgb = sunArgb;
        this.moonArgb = moonArgb;
        this.starArgb = starArgb;
        this.starChance = starChance;
        this.draws = sunRadius > 0.0 || moonRadius > 0.0 || starChance > 0.0;
    }

    /**
     * Works out where the bodies fall in this frame, once per render.
     *
     * @param sky      what the capture froze: the colour, the time and the settings
     * @param geo      the camera's frame; the projection uses its three axes
     * @param toSunX   unit vector pointing from the world <b>towards</b> the sun, as the
     * @param toSunY   shading uses it — the moon takes the opposite direction
     * @param toSunZ   —
     */
    public static SkyBodies of(SkySpec sky, RenderGeometry geo,
                               double toSunX, double toSunY, double toSunZ,
                               MapColorConverter converter) {
        var spec = sky.bodies();
        if (!spec.enabled() || !sky.draws())
            return NONE;

        var ticks = Math.floorMod(sky.ticks(), 24_000);
        var day = ticks < SUNSET_TICKS || ticks >= SUNRISE_TICKS;

        var height = geo.heightPx();
        var sun = spec.sun() && day
                ? project(geo, toSunX, toSunY, toSunZ, spec.sunSize() * height / 2.0)
                : ABSENT;
        var moon = spec.moon() && !day
                ? project(geo, -toSunX, -toSunY, -toSunZ, spec.moonSize() * height / 2.0)
                : ABSENT;

        // 0 is full and 4 new, so the mask slides in from a full radius away and back out
        // the other side; the side it comes from tells waxing from waning.
        var phase = Math.floorMod(sky.moonPhase(), 8);
        var lit = 1.0 - Math.min(phase, 8 - phase) / 4.0;
        var phaseOffset = 2.0 * moon[2] * lit * (phase <= 4 ? 1.0 : -1.0);

        var skyRgb = sky.argb() & 0xFFFFFF;
        // Stars fade out as the sky brightens rather than switching off at a threshold,
        // so a dusk photo keeps the few the eye would still find.
        var fade = 1.0 - Math.min(1.0, luma(skyRgb) / STAR_SKY_LIMIT);
        var starChance = spec.stars() ? spec.starDensity() * fade : 0.0;

        return new SkyBodies(geo, sun, moon, phaseOffset,
                argb(spec.sunRgb(), converter),
                argb(spec.moonRgb(), converter),
                argb(mix(skyRgb, spec.starRgb(), fade), converter),
                starChance);
    }

    /**
     * Where a direction lands in the frame, and how big it is drawn there.
     *
     * @return {@code {x, y, radius}} in pixels, or {@link #ABSENT} when the body is
     * behind the camera or too small to paint
     */
    private static double[] project(RenderGeometry geo, double x, double y, double z, double radius) {
        var view = geo.direction();
        if (x * view.getX() + y * view.getY() + z * view.getZ() <= 0.0 || radius < 0.5)
            return ABSENT;

        var right = geo.right();
        var up = geo.up();
        var u = x * right.getX() + y * right.getY() + z * right.getZ();
        var v = x * up.getX() + y * up.getY() + z * up.getZ();
        var scale = geo.heightPx() / 2.0;
        return new double[]{
                geo.widthPx() / 2.0 + u * scale,
                geo.heightPx() / 2.0 - v * scale,
                radius};
    }

    /**
     * Whether anything is drawn on top of the sky's own colour.
     */
    public boolean draws() {
        return draws;
    }

    /**
     * Colour for a sky pixel, or {@code 0} to leave it to the sky's gradient.
     */
    public int argbAt(int x, int y) {
        if (!draws) return 0;

        var px = x + 0.5;
        var py = y + 0.5;

        if (sunRadius > 0.0 && within(px - sunX, py - sunY, sunRadius))
            return sunArgb;

        if (moonRadius > 0.0 && within(px - moonX, py - moonY, moonRadius)) {
            // The dark limb is what the phase mask covers; it is left to the sky rather
            // than painted, so the unlit part of the moon is simply not there.
            return within(px - moonX - phaseOffset, py - moonY, moonRadius) ? 0 : moonArgb;
        }

        return starChance > 0.0 && isStar(px, py) ? starArgb : 0;
    }

    private static boolean within(double dx, double dy, double radius) {
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Whether the direction this pixel looks in falls in a grid cell that holds a star.
     *
     * <p>The direction is rebuilt from the pixel rather than stored, which is the inverse
     * of {@link #project}: the two cosines come from the offset to the centre and the
     * third component from what is left of the unit sphere.</p>
     */
    private boolean isStar(double px, double py) {
        var u = (px - centerX) / scale;
        var v = (centerY - py) / scale;
        var forward = 1.0 - u * u - v * v;
        // Past the dome's edge — a frame wider than it is tall reaches there — the
        // direction is flattened into the plane rather than left undefined.
        var f = forward > 0.0 ? Math.sqrt(forward) : 0.0;

        var dx = rightX * u + upX * v + viewX * f;
        var dy = rightY * u + upY * v + viewY * f;
        var dz = rightZ * u + upZ * v + viewZ * f;

        var hash = hash((int) Math.floor(dx * STAR_GRID),
                (int) Math.floor(dy * STAR_GRID),
                (int) Math.floor(dz * STAR_GRID));
        return (hash & 0xFFFF) < starChance * 0x10000;
    }

    /**
     * Deterministic per-cell noise; the same cell always answers the same way, whichever
     * camera asks and however many times.
     */
    private static int hash(int x, int y, int z) {
        var h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    /**
     * Perceived brightness, 0 to 1.
     */
    private static double luma(int rgb) {
        return (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF)) / 255.0;
    }

    private static int mix(int from, int to, double t) {
        var r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        var g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        var b = lerp(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, double t) {
        var value = (int) Math.round(from + (to - from) * t);
        return Math.clamp(value, 0, 255);
    }

    /**
     * A body's colour as the maps can store it: snapped once here, never per pixel.
     */
    private static int argb(int rgb, MapColorConverter converter) {
        return 0xFF000000 | converter.snap(rgb);
    }
}
