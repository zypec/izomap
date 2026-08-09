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
 * <p>Colors come from the map palette itself: the block gives the
 * {@link MapBaseColor} and the entered face selects the {@link MapBaseColor.Shade}.
 * Without a filter or supersampling every pixel is already a valid map color.</p>
 */
public final class IsometricRenderer {

    /** The ray hit nothing (transparent pixel). */
    private static final int MISS = 0;

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private final BlockColorTable colorTable;
    private final MapColorConverter converter;

    public IsometricRenderer(BlockColorTable colorTable, MapColorConverter converter) {
        this.colorTable = colorTable;
        this.converter = converter;
    }

    /**
     * Renders the image.
     *
     * @param supersampling antialiasing rays per pixel (NxN); 1 disables it
     * @param executor      pool the row bands are dispatched to
     * @param threads       how many bands, and therefore threads, to use
     */
    public RenderResult render(WorldSnapshot snapshot, RenderGeometry geo, ColorFilter filter,
                               int supersampling, Executor executor, int threads) {
        final int w = geo.widthPx();
        final int h = geo.heightPx();
        final int[] argb = new int[w * h];
        final int samples = Math.max(1, supersampling);

        int bands = Math.max(1, Math.min(threads, h));
        if (bands == 1) {
            renderBand(snapshot, geo, filter, samples, argb, 0, h);
            return new RenderResult(w, h, argb);
        }

        // The last band runs on the calling thread.
        int rowsPerBand = (h + bands - 1) / bands;
        CompletableFuture<?>[] pending = new CompletableFuture<?>[bands - 1];
        for (int band = 0; band < bands - 1; band++) {
            final int from = band * rowsPerBand;
            final int to = Math.min(h, from + rowsPerBand);
            pending[band] = CompletableFuture.runAsync(
                    () -> renderBand(snapshot, geo, filter, samples, argb, from, to), executor);
        }
        renderBand(snapshot, geo, filter, samples, argb, (bands - 1) * rowsPerBand, h);
        CompletableFuture.allOf(pending).join();

        return new RenderResult(w, h, argb);
    }

    /** Renders the row range {@code [yFrom, yTo)}. */
    private void renderBand(WorldSnapshot snapshot, RenderGeometry geo, ColorFilter filter,
                            int samples, int[] argb, int yFrom, int yTo) {
        final int w = geo.widthPx();
        final int h = geo.heightPx();

        // Unpack the vectors into primitives for the hot loop.
        final double cx = geo.planeCenter().getX(), cy = geo.planeCenter().getY(), cz = geo.planeCenter().getZ();
        final double rx = geo.right().getX(), ry = geo.right().getY(), rz = geo.right().getZ();
        final double ux = geo.up().getX(), uy = geo.up().getY(), uz = geo.up().getZ();
        final double dx = geo.direction().getX(), dy = geo.direction().getY(), dz = geo.direction().getZ();

        final double spanW = geo.spanWidth();
        final double spanH = geo.spanHeight();
        final double maxDist = geo.maxDistance();
        final double eyeY = geo.eyeY();
        final double maxBackoff = geo.maxBackoff();
        // How much pulling a ray back raises it; 0 when not looking down, so no backoff.
        final double climbPerBlock = -dy;
        final int total = samples * samples;
        final boolean needsSnap = samples > 1 || filter != ColorFilter.ORIGINAL;

        for (int py = yFrom; py < yTo; py++) {
            for (int px = 0; px < w; px++) {
                int hits = 0, sumR = 0, sumG = 0, sumB = 0;

                for (int sy = 0; sy < samples; sy++) {
                    double v = (0.5 - (py + (sy + 0.5) / samples) / h) * spanH;
                    for (int sx = 0; sx < samples; sx++) {
                        double u = ((px + (sx + 0.5) / samples) / w - 0.5) * spanW;

                        double ox = cx + rx * u + ux * v;
                        double oy = cy + ry * u + uy * v;
                        double oz = cz + rz * u + uz * v;

                        // Rays below the camera would start inside the ground and print
                        // a dirt slab, so they are pulled back to the camera's plane.
                        double backoff = 0.0;
                        if (maxBackoff > 0.0 && oy < eyeY) {
                            double needed = (eyeY - oy) / climbPerBlock;
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
                        int color = marchRay(snapshot, ox, oy, oz, dx, dy, dz, maxDist + backoff);
                        if (color != MISS) {
                            hits++;
                            sumR += (color >> 16) & 0xFF;
                            sumG += (color >> 8) & 0xFF;
                            sumB += color & 0xFF;
                        }
                    }
                }

                // The map palette has no translucency, so the majority decides.
                if (hits * 2 < total) {
                    argb[py * w + px] = 0;
                    continue;
                }
                int rgb = ((sumR / hits) << 16) | ((sumG / hits) << 8) | (sumB / hits);
                if (filter != ColorFilter.ORIGINAL) {
                    rgb = filter.apply(rgb);
                }
                // Averaging and filtering can leave the palette; snap back onto it.
                argb[py * w + px] = (needsSnap ? converter.snap(rgb) : rgb) | 0xFF000000;
            }
        }
    }

    /**
     * Walks the ray block by block and returns the map color of the first hit, or
     * {@link #MISS}.
     *
     * <p>Amanatides-Woo: keep the parametric distance to the next boundary on each
     * axis ({@code tMax}), step along the smallest one. That axis is also the face
     * the ray entered through.</p>
     */
    private int marchRay(WorldSnapshot snapshot,
                         double ox, double oy, double oz,
                         double dx, double dy, double dz,
                         double maxDist) {
        int x = fastFloor(ox);
        int y = fastFloor(oy);
        int z = fastFloor(oz);

        int stepX = dx > 0.0 ? 1 : (dx < 0.0 ? -1 : 0);
        int stepY = dy > 0.0 ? 1 : (dy < 0.0 ? -1 : 0);
        int stepZ = dz > 0.0 ? 1 : (dz < 0.0 ? -1 : 0);
        if (stepX == 0 && stepY == 0 && stepZ == 0) {
            return MISS;
        }

        double invX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double invY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double invZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1 - ox) : (ox - x)) * invX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1 - oy) : (oy - y)) * invY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * invZ;

        // The first cell has no entry face since the ray starts inside it; assume the
        // face most perpendicular to the view so a camera inside a block still shades.
        int face = dominantAxis(dx, dy, dz);
        double t = 0.0;

        while (true) {
            if (y >= snapshot.minY() && y < snapshot.maxY()) {
                Material material = snapshot.materialAt(x, y, z);
                if (!material.isAir()) {
                    MapBaseColor base = colorTable.baseColorOf(material);
                    // Colorless on maps (glass, torches, saplings): continue like vanilla.
                    if (base != MapBaseColor.NONE) {
                        return base.rgb(shadeOf(face, dy)) | 0xFF000000;
                    }
                }
            } else if ((y >= snapshot.maxY() && stepY >= 0) || (y < snapshot.minY() && stepY <= 0)) {
                // Left the world and will not come back.
                return MISS;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                tMaxX += invX;
                x += stepX;
                face = AXIS_X;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                tMaxY += invY;
                y += stepY;
                face = AXIS_Y;
            } else {
                t = tMaxZ;
                tMaxZ += invZ;
                z += stepZ;
                face = AXIS_Z;
            }
            if (t > maxDist) {
                return MISS;
            }
        }
    }

    /**
     * Picks the brightness for the face the ray entered through.
     *
     * <p>Vanilla derives brightness from height differences; the isometric equivalent
     * is face orientation: top brightest (255), the two sides at 220 and 180, bottom
     * darkest (135).</p>
     */
    private static MapBaseColor.Shade shadeOf(int face, double dy) {
        if (face == AXIS_Y) {
            return dy < 0.0 ? MapBaseColor.Shade.HIGH : MapBaseColor.Shade.LOWEST;
        }
        return face == AXIS_X ? MapBaseColor.Shade.NORMAL : MapBaseColor.Shade.LOW;
    }

    /** Axis of the direction vector's largest component. */
    private static int dominantAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return AXIS_Y;
        }
        return ax >= az ? AXIS_X : AXIS_Z;
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
