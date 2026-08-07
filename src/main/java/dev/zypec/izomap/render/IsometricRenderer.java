package dev.zypec.izomap.render;

import org.bukkit.Material;

/**
 * Ortografik ray-march ile bir {@link WorldSnapshot}'tan izometrik görüntü üreten
 * saf hesap motoru. Bukkit dünya durumuna dokunmaz; tamamen anlık görüntü üzerinde
 * çalışır ve bu nedenle <b>asenkron</b> yürütülebilir.
 *
 * <p>Renkler harita paletinin kendi sisteminden gelir: blok
 * {@link MapBaseColor temel rengini} verir, ışının çarptığı yüz ise parlaklık
 * ({@link MapBaseColor.Shade}) varyantını seçer. Böylece filtre uygulanmadığında
 * her piksel doğrudan geçerli bir harita rengidir.</p>
 */
public final class IsometricRenderer {

    private final BlockColorTable colorTable;
    private final MapColorConverter converter;
    private final double step;

    public IsometricRenderer(BlockColorTable colorTable, MapColorConverter converter, double step) {
        this.colorTable = colorTable;
        this.converter = converter;
        this.step = Math.max(0.05, step);
    }

    public RenderResult render(WorldSnapshot snapshot, RenderGeometry geo, ColorFilter filter) {
        final int w = geo.widthPx();
        final int h = geo.heightPx();
        final int[] argb = new int[w * h];

        // Vektörleri sıcak döngü için ilkel değerlere aç.
        final double fx = geo.focus().getX(), fy = geo.focus().getY(), fz = geo.focus().getZ();
        final double rx = geo.right().getX(), ry = geo.right().getY(), rz = geo.right().getZ();
        final double ux = geo.up().getX(), uy = geo.up().getY(), uz = geo.up().getZ();
        final double dx = geo.direction().getX(), dy = geo.direction().getY(), dz = geo.direction().getZ();

        final double spanW = geo.spanWidth();
        final double spanH = geo.spanHeight();
        final double maxDist = geo.maxDistance();
        final double startBack = maxDist / 2.0;

        for (int py = 0; py < h; py++) {
            double v = (0.5 - (py + 0.5) / h) * spanH;
            for (int px = 0; px < w; px++) {
                double u = ((px + 0.5) / w - 0.5) * spanW;

                // Işın başlangıcı: düzlem noktası, yarı derinlik kadar geriden.
                double ox = fx + rx * u + ux * v - dx * startBack;
                double oy = fy + ry * u + uy * v - dy * startBack;
                double oz = fz + rz * u + uz * v - dz * startBack;

                int idx = py * w + px;
                argb[idx] = marchRay(snapshot, ox, oy, oz, dx, dy, dz, maxDist, filter);
            }
        }
        return new RenderResult(w, h, argb);
    }

    /** Işını yürütür; ilk isabet eden bloğun harita rengini döndürür (isabet yoksa 0). */
    private int marchRay(WorldSnapshot snapshot,
                         double ox, double oy, double oz,
                         double dx, double dy, double dz,
                         double maxDist, ColorFilter filter) {
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;

        for (double t = 0.0; t <= maxDist; t += step) {
            int bx = fastFloor(ox + dx * t);
            int by = fastFloor(oy + dy * t);
            int bz = fastFloor(oz + dz * t);
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;

            Material material = snapshot.materialAt(bx, by, bz);
            if (material.isAir()) {
                continue;
            }
            MapBaseColor base = colorTable.baseColorOf(material);
            if (base == MapBaseColor.NONE) {
                // Haritada renksiz blok (cam, meşale, fidan...): vanilla gibi ışın devam eder.
                continue;
            }

            int rgb = base.rgb(faceShade(ox, oy, oz, dx, dy, dz, bx, by, bz));
            if (filter != ColorFilter.ORIGINAL) {
                // Efekt palet dışına çıkarabilir; en yakın gerçek harita rengine geri snap'le.
                rgb = converter.snap(filter.apply(rgb));
            }
            return rgb | 0xFF000000;
        }
        // İsabet yok: şeffaf piksel.
        return 0;
    }

    /**
     * Işının bloğa hangi yüzden girdiğini bulur ve o yüzün parlaklığını seçer.
     *
     * <p>Vanilla haritada parlaklık yükseklik farkından gelir; izometrik görünümde
     * karşılığı yüz yönelimidir: üst yüz en parlak (255), iki yan yüz farklı
     * tonlarda (220 / 180), alt yüz en koyu (135). Çarpanlar harita paletiyle
     * birebir aynı olduğundan sonuç yine geçerli bir harita rengidir.</p>
     */
    private static MapBaseColor.Shade faceShade(double ox, double oy, double oz,
                                                double dx, double dy, double dz,
                                                int bx, int by, int bz) {
        double tx = axisEntry(ox, dx, bx);
        double ty = axisEntry(oy, dy, by);
        double tz = axisEntry(oz, dz, bz);

        if (ty >= tx && ty >= tz) {
            return dy < 0 ? MapBaseColor.Shade.HIGH : MapBaseColor.Shade.LOWEST;
        }
        return tx >= tz ? MapBaseColor.Shade.NORMAL : MapBaseColor.Shade.LOW;
    }

    /** Işının, blok hücresinin ilgili eksendeki giriş düzlemine ulaşma parametresi. */
    private static double axisEntry(double origin, double dir, int block) {
        if (dir > 0.0) {
            return (block - origin) / dir;
        }
        if (dir < 0.0) {
            return (block + 1 - origin) / dir;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
