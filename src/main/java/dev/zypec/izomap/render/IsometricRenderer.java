package dev.zypec.izomap.render;

import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Pure compute engine that walks orthographic rays over a {@link WorldSnapshot}.
 * It never touches Bukkit world state, so it can run asynchronously.
 *
 * <p>Rays advance block by block with the Amanatides-Woo DDA algorithm: each step
 * jumps exactly to the next block boundary, so thin blocks are never missed, no
 * sample is wasted, and the face the ray entered through is known for free.</p>
 *
 * <p>The walk decides only <i>what</i> a ray found and describes it as a
 * {@link RayHit}; turning that into a color is {@link ColorPipeline}'s job. The one
 * color question the walk answers itself is transparency, because a block with no map
 * color is see-through and the ray has to carry on past it.</p>
 */
public final class IsometricRenderer {

    /**
     * How much of a pixel see-through blocks must hold, with nothing behind them, before
     * they are drawn at all rather than left to the sky.
     *
     * <p>The palette has no translucency, so this is the same majority rule the
     * supersampler already applies to its samples: a lone tuft of grass on a ridge does
     * not paint the sky green, three layers of it do.</p>
     */
    private static final double MIN_VISIBLE_COVERAGE = 0.5;

    private final BlockColorTable colorTable;

    public IsometricRenderer(BlockColorTable colorTable) {
        this.colorTable = colorTable;
    }

    /**
     * Renders the image.
     *
     * @param sky           color for rays that reach nothing; {@link Sky#NONE} leaves
     *                      them transparent
     * @param shading       what may darken a surface beyond its own face
     * @param water         how deep water is allowed to look; {@link Water#FLAT} keeps the
     *                      column unmeasured
     * @param tints         what each biome paints its grass, leaves and water with;
     *                      {@link BiomeTints#NONE} leaves every block its own colour
     * @param progress      told as each row finishes, or {@code null} when nobody is
     *                      watching the capture
     * @param supersampling antialiasing rays per pixel (NxN); 1 disables it
     * @param withDepth     whether to record how far each pixel is, for {@link FocusPass}
     * @param executor      pool the row bands are dispatched to
     * @param threads       how many bands, and therefore threads, to use
     */
    public RenderResult render(WorldSnapshot snapshot, RenderGeometry geo, ColorPipeline pipeline,
                               Sky sky, Shading shading, Water water, BiomeTints tints,
                               int supersampling, boolean withDepth,
                               CaptureProgress progress, Executor executor, int threads) {
        final var w = geo.widthPx();
        final var h = geo.heightPx();
        final var argb = new int[w * h];
        // Only allocated when something is going to read it: at photo sizes this is as
        // large as the image itself.
        final var depth = withDepth ? new float[w * h] : null;
        final var samples = Math.max(1, supersampling);
        if (progress != null)
            progress.expect(h);

        var bands = Math.max(1, Math.min(threads, h));
        if (bands == 1) {
            renderBand(snapshot, geo, pipeline, sky, shading, water, tints, samples, progress, argb, depth, 0, h);
            return new RenderResult(w, h, argb, depth);
        }

        // The last band runs on the calling thread.
        var rowsPerBand = (h + bands - 1) / bands;
        var pending = new CompletableFuture<?>[bands - 1];
        for (int band = 0; band < bands - 1; band++) {
            final int from = band * rowsPerBand;
            final int to = Math.min(h, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> renderBand(snapshot, geo, pipeline, sky, shading, water, tints, samples, progress, argb, depth, from, to), executor);
        }
        renderBand(snapshot, geo, pipeline, sky, shading, water, tints, samples, progress, argb, depth, (bands - 1) * rowsPerBand, h);
        CompletableFuture.allOf(pending).join();

        return new RenderResult(w, h, argb, depth);
    }

    /**
     * Renders the row range {@code [yFrom, yTo)}.
     */
    private void renderBand(WorldSnapshot snapshot, RenderGeometry geo, ColorPipeline pipeline,
                            Sky sky, Shading shading, Water water, BiomeTints tints,
                            int samples, CaptureProgress progress,
                            int[] argb, float[] depth, int yFrom, int yTo) {
        final var w = geo.widthPx();
        final var h = geo.heightPx();

        // Unpack the vectors into primitives for the hot loop.
        final double cx = geo.planeCenter().getX(), cy = geo.planeCenter().getY(), cz = geo.planeCenter().getZ();
        final double rx = geo.right().getX(), ry = geo.right().getY(), rz = geo.right().getZ();
        final double ux = geo.up().getX(), uy = geo.up().getY(), uz = geo.up().getZ();
        final double dx = geo.direction().getX(), dy = geo.direction().getY(), dz = geo.direction().getZ();

        final var spanW = geo.spanWidth();
        final var spanH = geo.spanHeight();
        final var maxDist = geo.maxDistance();
        final var eyeY = geo.eyeY();
        final var maxBackoff = geo.maxBackoff();
        // How much pulling a ray back raises it; 0 when not looking down, so no backoff.
        final var climbPerBlock = -dy;
        final var total = samples * samples;
        final var hit = new RayHit();
        final var run = new WaterRun();

        for (var py = yFrom; py < yTo; py++) {
            for (var px = 0; px < w; px++) {
                int hits = 0, sumR = 0, sumG = 0, sumB = 0;
                var sumDistance = 0.0;
                var firstId = 0;
                var uniform = true;

                for (var sy = 0; sy < samples; sy++) {
                    var v = (0.5 - (py + (sy + 0.5) / samples) / h) * spanH;
                    for (var sx = 0; sx < samples; sx++) {
                        var u = ((px + (sx + 0.5) / samples) / w - 0.5) * spanW;

                        var ox = cx + rx * u + ux * v;
                        var oy = cy + ry * u + uy * v;
                        var oz = cz + rz * u + uz * v;

                        // Rays below the camera would start inside the ground and print
                        // a dirt slab, so they are pulled back to the camera's plane.
                        var backoff = 0.0;
                        if (maxBackoff > 0.0 && oy < eyeY) {
                            var needed = (eyeY - oy) / climbPerBlock;
                            if (needed <= maxBackoff) {
                                backoff = needed;
                                // Assign eyeY directly; rounding could leave the ray a
                                // hair below it and print dirt along the bottom row.
                                oy = eyeY;
                            } else {
                                backoff = maxBackoff;
                                oy -= dy * backoff;
                            }
                            ox -= dx * backoff;
                            oz -= dz * backoff;
                        }

                        // View distance is measured from the camera plane, so a
                        // pulled-back ray walks the extra distance too.
                        marchRay(snapshot, shading, water, tints, run,
                                ox, oy, oz, dx, dy, dz, maxDist + backoff, hit);
                        if (!hit.hit)
                            continue;

                        int rgb;
                        if (hit.layers == 0) {
                            var id = pipeline.packedIdOf(hit);
                            // The tint travels in the key: two samples of the same block
                            // in two biomes are two colours, however equal their bytes.
                            var key = id | ((hit.tint + 1) << 8);
                            if (hits == 0) {
                                firstId = key;
                            } else if (key != firstId) {
                                uniform = false;
                            }
                            rgb = pipeline.rgbOf(id, hit.tint);
                        } else {
                            // A composited colour is off the palette by definition, so the
                            // pixel has to take the long way out through blend().
                            uniform = false;
                            rgb = pipeline.compositeRgb(hit);
                        }
                        hits++;
                        // Measured from the camera plane, not from where this ray began:
                        // a pulled-back ray walked its backoff before reaching it.
                        sumDistance += hit.distance - backoff;
                        sumR += (rgb >> 16) & 0xFF;
                        sumG += (rgb >> 8) & 0xFF;
                        sumB += rgb & 0xFF;
                    }
                }

                // The map palette has no translucency, so the majority decides: a pixel
                // is either terrain or whatever lies beyond it.
                if (hits * 2 < total) {
                    argb[py * w + px] = sky.argbAt(px, py);
                    if (depth != null) {
                        depth[py * w + px] = RenderResult.SKY_DEPTH;
                    }
                    continue;
                }
                // Samples that all agreed left the color on the palette, so the finished
                // value was precomputed; only an averaged pixel runs the stages for real.
                argb[py * w + px] = uniform
                        ? pipeline.argbOf(firstId & 0xFF, (firstId >> 8) - 1)
                        : pipeline.blend(sumR / hits, sumG / hits, sumB / hits);
                if (depth != null) {
                    depth[py * w + px] = (float) (sumDistance / hits);
                }
            }
            if (progress != null)
                progress.advance();
        }
    }

    /**
     * Walks the ray block by block and describes what it found in {@code out}, leaving
     * {@link RayHit#hit} false when the ray reached nothing.
     *
     * <p>Amanatides-Woo: keep the parametric distance to the next boundary on each
     * axis ({@code tMax}), step along the smallest one. That axis is also the face
     * the ray entered through.</p>
     *
     * <h2>Three kinds of block, not two</h2>
     *
     * <p>A block used to be either see-through (no map colour, ray continues) or a full
     * cube (ray stops). Between them sit the blocks that fill only part of their cell: the
     * ray does not stop, it records the colour with the share of the pixel the block holds
     * and carries on to whatever is behind it. {@link RayHit#MAX_LAYERS} of those and the
     * next one counts as solid, so a curtain of vines cannot walk a ray across the map.</p>
     *
     * <p>Water is the same idea with the share measured rather than tabled: the column is
     * followed to its floor, and the number of cells decides either how far the surface
     * darkens ({@code DEPTH}) or how much of the floor shows through it
     * ({@code TRANSLUCENT}).</p>
     */
    private void marchRay(WorldSnapshot snapshot, Shading shading, Water water, BiomeTints tints,
                          WaterRun run,
                          double ox, double oy, double oz,
                          double dx, double dy, double dz,
                          double maxDist, RayHit out) {
        out.reset();
        run.reset();
        var layered = colorTable.anyPartial() || water.measures();
        var tinted = tints.enabled();

        var x = fastFloor(ox);
        var y = fastFloor(oy);
        var z = fastFloor(oz);

        var stepX = Double.compare(dx, 0.0);
        var stepY = Double.compare(dy, 0.0);
        var stepZ = Double.compare(dz, 0.0);
        if (stepX == 0 && stepY == 0 && stepZ == 0)
            return;

        double invX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double invY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double invZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1 - ox) : (ox - x)) * invX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1 - oy) : (oy - y)) * invY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * invZ;

        // A ray climbing into a block enters through its bottom, and vice versa; the
        // direction never changes, so the horizontal face is decided once.
        final var yFace = stepY > 0 ? RayHit.Face.BOTTOM : RayHit.Face.TOP;

        // The first cell has no entry face since the ray starts inside it; assume the
        // face most perpendicular to the view so a camera inside a block still shades.
        var face = initialFace(dx, dy, dz);
        var t = 0.0;

        while (true) {
            if (y >= snapshot.minY() && y < snapshot.maxY()) {
                var material = snapshot.materialAt(x, y, z);
                // Air is not checked separately: the table answers NONE for it too, and
                // Material#isAir goes through the block registry, which is a poor thing
                // to ask once per block of every ray.
                var base = colorTable.baseColorOf(material);
                if (!layered) {
                    // Colorless on maps (glass, torches): continue like vanilla.
                    if (base != MapBaseColor.NONE) {
                        surfaceAt(snapshot, shading, material,
                                resolveBase(snapshot, material, base, x, y, z),
                                tintAt(snapshot, tints, tinted, material, x, y, z),
                                x, y, z, face, stepX, stepZ, t, out);
                        return;
                    }
                } else if (water.measures() && base != MapBaseColor.NONE && water.isWater(material)) {
                    // Not a hit yet: the column is followed to its floor and the cells
                    // counted, because that count is the colour.
                    run.enter(base, face, tintAt(snapshot, tints, tinted, material, x, y, z), t, x, y, z);
                } else {
                    // Anything that is not water ends a column, air and glass included: a
                    // ray can leave a waterfall sideways as easily as through its bed.
                    if (run.cells > 0 && !closeColumn(snapshot, shading, water, run, stepX, stepZ, out))
                        return;

                    if (base != MapBaseColor.NONE) {
                        var coverage = colorTable.coverageOf(material);
                        var tint = tintAt(snapshot, tints, tinted, material, x, y, z);
                        base = resolveBase(snapshot, material, base, x, y, z);
                        if (coverage >= 1.0f || !out.addLayer(base, face, tint, coverage)) {
                            surfaceAt(snapshot, shading, material, base, tint,
                                    x, y, z, face, stepX, stepZ, t, out);
                            return;
                        }
                        if (out.layers == 1)
                            out.distance = t; // stands in until a surface is reached
                    }
                }
            } else if ((y >= snapshot.maxY() && stepY >= 0) || (y < snapshot.minY() && stepY <= 0)) {
                // Left the world and will not come back.
                finishLayers(snapshot, shading, water, run, stepX, stepZ, out);
                return;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                tMaxX += invX;
                x += stepX;
                face = RayHit.Face.SIDE_X;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                tMaxY += invY;
                y += stepY;
                face = yFace;
            } else {
                t = tMaxZ;
                tMaxZ += invZ;
                z += stepZ;
                face = RayHit.Face.SIDE_Z;
            }
            if (t > maxDist) {
                finishLayers(snapshot, shading, water, run, stepX, stepZ, out);
                return;
            }
        }
    }

    /**
     * Records the surface the ray stopped on. Whatever layers it gathered on the way stay
     * where they are, in front of it.
     */
    private void surfaceAt(WorldSnapshot snapshot, Shading shading, Material material,
                           MapBaseColor base, int tint, int x, int y, int z, RayHit.Face face,
                           int stepX, int stepZ, double t, RayHit out) {
        out.hit = true;
        out.opaque = true;
        out.material = material;
        out.base = base;
        out.tint = tint;
        out.face = face;
        out.distance = t;
        out.darken = shading.stepsAt(snapshot, colorTable, x, y, z, face, stepX, stepZ);
    }

    /**
     * Which biome tint this block takes here, or {@link RayHit#NO_TINT} for the blocks —
     * nearly all of them — that look the same wherever they stand.
     *
     * <p>The biome is only read for a block that is tinted at all, so a stone wall costs
     * one array read and no chunk lookup.</p>
     */
    private int tintAt(WorldSnapshot snapshot, BiomeTints tints, boolean tinted,
                       Material material, int x, int y, int z) {
        if (!tinted)
            return RayHit.NO_TINT;

        var channel = colorTable.tintChannelOf(material);
        return channel == null ? RayHit.NO_TINT : tints.indexOf(snapshot.biomeAt(x, y, z), channel);
    }

    /**
     * The colour of this particular block: crops and the like wear a different one as they
     * grow, and only they are worth reading a whole block state for.
     */
    private MapBaseColor resolveBase(WorldSnapshot snapshot, Material material,
                                     MapBaseColor base, int x, int y, int z) {
        if (!colorTable.variesByState(material))
            return base;

        var data = snapshot.blockDataAt(x, y, z);
        return data != null ? colorTable.baseColorOf(material, data) : base;
    }

    /**
     * Resolves the water column the ray has just left and reports whether the walk should
     * carry on past it.
     *
     * <p>Only translucent water lets it: there the column becomes a layer, weighted by how
     * deep it was, and the floor underneath is still to be found. In {@code DEPTH} the
     * surface <i>is</i> the answer, and so is a translucent column that found no room for
     * a layer — three vines' worth of water is not water you can see into.</p>
     */
    private boolean closeColumn(WorldSnapshot snapshot, Shading shading, Water water, WaterRun run,
                                int stepX, int stepZ, RayHit out) {
        if (water.translucent()
            && out.addLayer(run.base, run.face, run.tint, water.opacity(run.cells))) {
            if (out.layers == 1)
                out.distance = run.t;

            run.reset();
            return true;
        }
        surfaceAt(snapshot, shading, Material.WATER, run.base, run.tint,
                run.x, run.y, run.z, run.face, stepX, stepZ, run.t, out);
        out.darken += water.depthSteps(run.cells);
        return false;
    }

    /**
     * Settles a ray that ran out of world or of distance with something still pending: a
     * water column it never found the floor of, or layers with nothing behind them.
     *
     * <p>A ray that only grazed a tuft of grass leaves it: the palette has no
     * translucency, so the same majority rule the supersampler uses decides whether the
     * layers are the pixel or the sky is.</p>
     */
    private void finishLayers(WorldSnapshot snapshot, Shading shading, Water water, WaterRun run,
                              int stepX, int stepZ, RayHit out) {
        if (run.cells > 0 && !closeColumn(snapshot, shading, water, run, stepX, stepZ, out))
            return;

        out.hit = out.layers > 0 && out.covered() >= MIN_VISIBLE_COVERAGE;
    }

    /**
     * Water column the ray is in the middle of, kept per render band so measuring one
     * costs no allocation.
     */
    private static final class WaterRun {
        private int cells;
        private MapBaseColor base;
        private RayHit.Face face;
        private int tint;
        private double t;
        private int x;
        private int y;
        private int z;

        /**
         * Counts a cell, remembering where the column was entered the first time. The
         * biome is the surface's, so a river running out of a swamp changes colour where
         * the bank does rather than where its bed does.
         */
        private void enter(MapBaseColor base, RayHit.Face face, int tint, double t, int x, int y, int z) {
            if (cells++ == 0) {
                this.base = base;
                this.face = face;
                this.tint = tint;
                this.t = t;
                this.x = x;
                this.y = y;
                this.z = z;
            }
        }

        private void reset() {
            cells = 0;
        }
    }

    /**
     * Face assumed for a ray that starts inside a block: the one most perpendicular to
     * the view direction.
     */
    private static RayHit.Face initialFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az)
            return dy < 0.0 ? RayHit.Face.TOP : RayHit.Face.BOTTOM;

        return ax >= az ? RayHit.Face.SIDE_X : RayHit.Face.SIDE_Z;
    }

    private static int fastFloor(double value) {
        var i = (int) value;
        return value < i ? i - 1 : i;
    }
}
