package dev.zypec.izomap.render;

/**
 * Decides how many brightness steps a surface loses to its surroundings.
 *
 * <p>Built once per render from a {@link ShadingSpec} and read from every render thread;
 * it holds the sun as a vector so the walk never repeats the trigonometry.</p>
 *
 * <h2>What each technique costs</h2>
 *
 * <p><b>The sun shadow</b> is a second ray per <i>hit</i> — not per step of the first
 * ray — walked with the same DDA until it meets a block or runs out of
 * {@code shadow-distance}. It is the expensive one, and the one that does most for the
 * picture: without it a wall and the ground in front of it are the same brightness, so
 * nothing in the frame says where the light comes from.</p>
 *
 * <p><b>Ambient occlusion</b> is four snapshot reads per hit, no rays at all. It darkens
 * a face only when the space in front of it is boxed in, which is what makes an inside
 * corner read as a corner rather than as a fold in a flat colour.</p>
 *
 * <p>Both are off by default. They change what a photo looks like, so they belong to the
 * server's taste rather than to a default.</p>
 */
public final class Shading {

    /**
     * Nothing beyond the face's own brightness.
     */
    public static final Shading NONE = new Shading(ShadingSpec.NONE, 0.0, 0.0, 0.0);

    /**
     * How many of the four neighbouring cells must be solid before a face counts as
     * boxed in. Three of four is a corner; two is a wall the face runs along, which
     * would darken half of every building.
     */
    private static final int OCCLUDED_NEIGHBOURS = 3;

    private final ShadingSpec spec;
    /**
     * Unit vector pointing from a surface towards the sun.
     */
    private final double sunX;
    private final double sunY;
    private final double sunZ;

    private Shading(ShadingSpec spec, double sunX, double sunY, double sunZ) {
        this.spec = spec;
        this.sunX = sunX;
        this.sunY = sunY;
        this.sunZ = sunZ;
    }

    /**
     * Builds the shading a spec asks for; {@link #NONE} when it asks for nothing.
     *
     * @param sunX unit vector towards the sun, as {@code RenderService} works it out
     */
    public static Shading of(ShadingSpec spec, double sunX, double sunY, double sunZ) {
        return spec.enabled() ? new Shading(spec, sunX, sunY, sunZ) : NONE;
    }

    /**
     * Steps to take off the surface at this block, entered through this face.
     */
    int stepsAt(WorldSnapshot snapshot, BlockColorTable colors, int x, int y, int z, RayHit.Face face) {
        if (this == NONE) return 0;

        var steps = 0;
        if (spec.sunShadow() && inShadow(snapshot, colors, x, y, z))
            steps++;

        if (spec.ambientOcclusion() && isBoxedIn(snapshot, colors, x, y, z, face))
            steps++;

        return steps;
    }

    /**
     * Whether anything stands between this block and the sun.
     *
     * <p>Starts at the block's own centre and skips it, so a block never shadows itself;
     * blocks that do not draw on a map do not cast a shadow either, on the grounds that
     * the photo cannot show them casting it.</p>
     */
    private boolean inShadow(WorldSnapshot snapshot, BlockColorTable colors, int x, int y, int z) {
        var ox = x + 0.5;
        var oy = y + 0.5;
        var oz = z + 0.5;

        var stepX = Double.compare(sunX, 0.0);
        var stepY = Double.compare(sunY, 0.0);
        var stepZ = Double.compare(sunZ, 0.0);
        if (stepX == 0 && stepY == 0 && stepZ == 0) return false;

        var invX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / sunX);
        var invY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / sunY);
        var invZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / sunZ);

        var tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1 - ox) : (ox - x)) * invX;
        var tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1 - oy) : (oy - y)) * invY;
        var tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * invZ;

        var cx = x;
        var cy = y;
        var cz = z;
        var travelled = 0.0;
        var limit = spec.shadowDistance();

        while (travelled <= limit) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                travelled = tMaxX;
                tMaxX += invX;
                cx += stepX;
            } else if (tMaxY <= tMaxZ) {
                travelled = tMaxY;
                tMaxY += invY;
                cy += stepY;
            } else {
                travelled = tMaxZ;
                tMaxZ += invZ;
                cz += stepZ;
            }
            if (cy >= snapshot.maxY()) return false; // out under open sky

            if (cy >= snapshot.minY() && draws(snapshot, colors, cx, cy, cz)) return true;
        }
        return false;
    }

    /**
     * Whether the space in front of this face is hemmed in on most sides.
     *
     * <p>Looks at the cell the face opens onto and the four around it in the face's own
     * plane. Nothing here can tell where inside the face a pixel sits — the walk works
     * in whole blocks — so the answer is one step for the whole face or none.</p>
     */
    private boolean isBoxedIn(WorldSnapshot snapshot, BlockColorTable colors,
                              int x, int y, int z, RayHit.Face face) {
        var front = switch (face) {
            case TOP -> new int[]{0, 1, 0};
            case BOTTOM -> new int[]{0, -1, 0};
            case SIDE_X -> new int[]{1, 0, 0};
            case SIDE_Z -> new int[]{0, 0, 1};
        };
        // The two axes the face spans, which is where its neighbours are.
        var tangents = switch (face) {
            case TOP, BOTTOM -> new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            case SIDE_X -> new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            case SIDE_Z -> new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        };

        var fx = x + front[0];
        var fy = y + front[1];
        var fz = z + front[2];

        var solid = 0;
        for (var t : tangents) {
            if (draws(snapshot, colors, fx + t[0], fy + t[1], fz + t[2]))
                solid++;
        }
        return solid >= OCCLUDED_NEIGHBOURS;
    }

    /**
     * Whether a block would show on a map, which is the only sense in which one is
     * solid here: glass and torches neither hide anything nor occlude anything.
     */
    private static boolean draws(WorldSnapshot snapshot, BlockColorTable colors, int x, int y, int z) {
        return colors.baseColorOf(snapshot.materialAt(x, y, z)) != MapBaseColor.NONE;
    }
}
