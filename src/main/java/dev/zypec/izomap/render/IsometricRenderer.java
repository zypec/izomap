package dev.zypec.izomap.render;

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

    private final BlockColorTable colorTable;

    public IsometricRenderer(BlockColorTable colorTable) {
        this.colorTable = colorTable;
    }

    /**
     * Renders the image.
     *
     * @param supersampling antialiasing rays per pixel (NxN); 1 disables it
     * @param executor      pool the row bands are dispatched to
     * @param threads       how many bands, and therefore threads, to use
     */
    public RenderResult render(WorldSnapshot snapshot, RenderGeometry geo, ColorPipeline pipeline,
                               int supersampling, Executor executor, int threads) {
        final var w = geo.widthPx();
        final var h = geo.heightPx();
        final var argb = new int[w * h];
        final var samples = Math.max(1, supersampling);

        var bands = Math.max(1, Math.min(threads, h));
        if (bands == 1) {
            renderBand(snapshot, geo, pipeline, samples, argb, 0, h);
            return new RenderResult(w, h, argb);
        }

        // The last band runs on the calling thread.
        var rowsPerBand = (h + bands - 1) / bands;
        var pending = new CompletableFuture<?>[bands - 1];
        for (int band = 0; band < bands - 1; band++) {
            final int from = band * rowsPerBand;
            final int to = Math.min(h, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> renderBand(snapshot, geo, pipeline, samples, argb, from, to), executor);
        }
        renderBand(snapshot, geo, pipeline, samples, argb, (bands - 1) * rowsPerBand, h);
        CompletableFuture.allOf(pending).join();

        return new RenderResult(w, h, argb);
    }

    /**
     * Renders the row range {@code [yFrom, yTo)}.
     */
    private void renderBand(WorldSnapshot snapshot, RenderGeometry geo, ColorPipeline pipeline,
                            int samples, int[] argb, int yFrom, int yTo) {
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

        for (var py = yFrom; py < yTo; py++) {
            for (var px = 0; px < w; px++) {
                int hits = 0, sumR = 0, sumG = 0, sumB = 0;
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
                        marchRay(snapshot, ox, oy, oz, dx, dy, dz, maxDist + backoff, hit);
                        if (!hit.hit)
                            continue;

                        var id = pipeline.packedIdOf(hit);
                        if (hits == 0) {
                            firstId = id;
                        } else if (id != firstId) {
                            uniform = false;
                        }
                        hits++;
                        var rgb = pipeline.paletteRgbOf(id);
                        sumR += (rgb >> 16) & 0xFF;
                        sumG += (rgb >> 8) & 0xFF;
                        sumB += rgb & 0xFF;
                    }
                }

                // The map palette has no translucency, so the majority decides.
                if (hits * 2 < total) {
                    argb[py * w + px] = 0;
                    continue;
                }
                // Samples that all agreed left the color on the palette, so the finished
                // value was precomputed; only an averaged pixel runs the stages for real.
                argb[py * w + px] = uniform
                        ? pipeline.argbOf(firstId)
                        : pipeline.blend(sumR / hits, sumG / hits, sumB / hits);
            }
        }
    }

    /**
     * Walks the ray block by block and describes the first hit in {@code out}, leaving
     * {@link RayHit#hit} false when the ray reached nothing.
     *
     * <p>Amanatides-Woo: keep the parametric distance to the next boundary on each
     * axis ({@code tMax}), step along the smallest one. That axis is also the face
     * the ray entered through.</p>
     */
    private void marchRay(WorldSnapshot snapshot,
                          double ox, double oy, double oz,
                          double dx, double dy, double dz,
                          double maxDist, RayHit out) {
        out.hit = false;

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
                if (!material.isAir()) {
                    var base = colorTable.baseColorOf(material);
                    // Colorless on maps (glass, torches, saplings): continue like vanilla.
                    if (base != MapBaseColor.NONE) {
                        // Crops and the like wear a different color as they grow, and
                        // only they are worth reading a whole block state for.
                        if (colorTable.variesByState(material)) {
                            var data = snapshot.blockDataAt(x, y, z);
                            if (data != null)
                                base = colorTable.baseColorOf(material, data);
                        }
                        out.hit = true;
                        out.material = material;
                        out.base = base;
                        out.face = face;
                        return;
                    }
                }
            } else if ((y >= snapshot.maxY() && stepY >= 0) || (y < snapshot.minY() && stepY <= 0)) {
                // Left the world and will not come back.
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
                return;
            }
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
