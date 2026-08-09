package dev.zypec.izomap.render;

import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Ortografik voxel yürüyüşü ile bir {@link WorldSnapshot}'tan izometrik görüntü üreten
 * saf hesap motoru. Bukkit dünya durumuna dokunmaz; tamamen anlık görüntü üzerinde
 * çalışır ve bu nedenle <b>asenkron</b> yürütülebilir.
 *
 * <p>Işınlar sabit adımla değil, Amanatides-Woo DDA algoritmasıyla <b>blok blok</b>
 * ilerler: her adım tam olarak bir sonraki blok sınırına atlar. Bu sayede ince blok
 * kaçırılmaz, gereksiz örnekleme yapılmaz ve ışının bloğa hangi yüzden girdiği
 * (gölgelendirme için) hesaplama gerektirmeden bilinir.</p>
 *
 * <p>Renkler harita paletinin kendi sisteminden gelir: blok
 * {@link MapBaseColor temel rengini} verir, ışının çarptığı yüz ise parlaklık
 * ({@link MapBaseColor.Shade}) varyantını seçer. Böylece filtre ve kenar yumuşatma
 * uygulanmadığında her piksel doğrudan geçerli bir harita rengidir.</p>
 */
public final class IsometricRenderer {

    /** Işının hiçbir bloğa isabet etmediğini belirtir (şeffaf piksel). */
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
     * Görüntüyü üretir.
     *
     * @param supersampling piksel başına kenar yumuşatma ışını (NxN); 1 = kapalı
     * @param executor      satır bantlarının dağıtılacağı havuz
     * @param threads       kaç bant (dolayısıyla kaç iş parçacığı) kullanılacağı
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

        // Görüntü yatay bantlara bölünür; son bant çağıran iş parçacığında koşar.
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

    /** Verilen satır aralığını ({@code [yFrom, yTo)}) render eder. */
    private void renderBand(WorldSnapshot snapshot, RenderGeometry geo, ColorFilter filter,
                            int samples, int[] argb, int yFrom, int yTo) {
        final int w = geo.widthPx();
        final int h = geo.heightPx();

        // Vektörleri sıcak döngü için ilkel değerlere aç.
        final double cx = geo.planeCenter().getX(), cy = geo.planeCenter().getY(), cz = geo.planeCenter().getZ();
        final double rx = geo.right().getX(), ry = geo.right().getY(), rz = geo.right().getZ();
        final double ux = geo.up().getX(), uy = geo.up().getY(), uz = geo.up().getZ();
        final double dx = geo.direction().getX(), dy = geo.direction().getY(), dz = geo.direction().getZ();

        final double spanW = geo.spanWidth();
        final double spanH = geo.spanHeight();
        final double maxDist = geo.maxDistance();
        final int total = samples * samples;
        final boolean needsSnap = samples > 1 || filter != ColorFilter.ORIGINAL;

        for (int py = yFrom; py < yTo; py++) {
            for (int px = 0; px < w; px++) {
                int hits = 0, sumR = 0, sumG = 0, sumB = 0;

                for (int sy = 0; sy < samples; sy++) {
                    double v = (0.5 - (py + (sy + 0.5) / samples) / h) * spanH;
                    for (int sx = 0; sx < samples; sx++) {
                        double u = ((px + (sx + 0.5) / samples) / w - 0.5) * spanW;

                        // Işın, kameranın kendi düzleminden başlar ve yalnızca ileri gider.
                        double ox = cx + rx * u + ux * v;
                        double oy = cy + ry * u + uy * v;
                        double oz = cz + rz * u + uz * v;

                        int color = marchRay(snapshot, ox, oy, oz, dx, dy, dz, maxDist);
                        if (color != MISS) {
                            hits++;
                            sumR += (color >> 16) & 0xFF;
                            sumG += (color >> 8) & 0xFF;
                            sumB += color & 0xFF;
                        }
                    }
                }

                // Harita paleti yarı saydamlığı desteklemez: çoğunluk kararı verir.
                if (hits * 2 < total) {
                    argb[py * w + px] = 0;
                    continue;
                }
                int rgb = ((sumR / hits) << 16) | ((sumG / hits) << 8) | (sumB / hits);
                if (filter != ColorFilter.ORIGINAL) {
                    rgb = filter.apply(rgb);
                }
                // Ortalama ve efekt palet dışına çıkarabilir; gerçek harita rengine snap'le.
                argb[py * w + px] = (needsSnap ? converter.snap(rgb) : rgb) | 0xFF000000;
            }
        }
    }

    /**
     * Işını blok blok yürütür; ilk isabet eden bloğun harita rengini döndürür
     * (isabet yoksa {@link #MISS}).
     *
     * <p>Amanatides-Woo: her eksen için bir sonraki blok sınırına olan parametrik
     * uzaklık ({@code tMax}) tutulur, en küçüğü seçilerek o eksende bir blok
     * ilerlenir. Seçilen eksen aynı zamanda ışının girdiği yüzdür.</p>
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

        // İlk hücrenin giriş yüzü yoktur (ışın onun içinde doğar): bakışa en dik
        // yüzü varsay, böylece kamera bir bloğun içindeyse de makul bir ton çıkar.
        int face = dominantAxis(dx, dy, dz);
        double t = 0.0;

        while (true) {
            if (y >= snapshot.minY() && y < snapshot.maxY()) {
                Material material = snapshot.materialAt(x, y, z);
                if (!material.isAir()) {
                    MapBaseColor base = colorTable.baseColorOf(material);
                    // Haritada renksiz blok (cam, meşale, fidan...): vanilla gibi ışın devam eder.
                    if (base != MapBaseColor.NONE) {
                        return base.rgb(shadeOf(face, dy)) | 0xFF000000;
                    }
                }
            } else if ((y >= snapshot.maxY() && stepY >= 0) || (y < snapshot.minY() && stepY <= 0)) {
                // Dünyanın dışına çıktı ve geri dönmeyecek.
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
     * Işının girdiği yüzün parlaklığını seçer.
     *
     * <p>Vanilla haritada parlaklık yükseklik farkından gelir; izometrik görünümde
     * karşılığı yüz yönelimidir: üst yüz en parlak (255), iki yan yüz farklı
     * tonlarda (220 / 180), alt yüz en koyu (135). Çarpanlar harita paletiyle
     * birebir aynı olduğundan sonuç yine geçerli bir harita rengidir.</p>
     */
    private static MapBaseColor.Shade shadeOf(int face, double dy) {
        if (face == AXIS_Y) {
            return dy < 0.0 ? MapBaseColor.Shade.HIGH : MapBaseColor.Shade.LOWEST;
        }
        return face == AXIS_X ? MapBaseColor.Shade.NORMAL : MapBaseColor.Shade.LOW;
    }

    /** Yön vektörünün en büyük bileşenine karşılık gelen eksen. */
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
