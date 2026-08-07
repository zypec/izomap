package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;

/**
 * Render işlemlerinin koordinasyonu.
 *
 * <p>İki aşama: (1) <b>ana thread</b>de kameranın geometrisi hesaplanır ve gerekli
 * bölge {@link WorldSnapshot} olarak yakalanır; (2) ağır ray-march
 * {@link IsometricRenderer} ile <b>asenkron</b> yürütülür. Bu sayede blok erişimi
 * daima güvenli iş parçacığında kalır, CPU-yoğun kısım ise ana thread'i bloklamaz.</p>
 */
public final class RenderService {

    private final Izomap plugin;
    private final IsometricRenderer renderer;

    public RenderService(Izomap plugin, BlockColorTable colorTable) {
        this.plugin = plugin;
        this.renderer = new IsometricRenderer(colorTable, new MapColorConverter(), plugin.config().stepSize());
    }

    /**
     * Kamerayı, en-boy oranı ön ayarının çözünürlüğünde çeker (önizleme/PNG için).
     * <b>Ana iş parçacığında</b> çağrılmalıdır.
     */
    public CompletableFuture<RenderResult> capture(Camera camera) {
        double ratio = camera.aspectRatio().value();
        int heightPx = Math.max(16, plugin.config().resolution());
        int widthPx = Math.max(16, (int) Math.round(heightPx * ratio));
        return capture(camera, widthPx, heightPx);
    }

    /**
     * Kamerayı, verilen tam piksel boyutunda çeker (grid'e göre çekim için).
     * En-boy oranı doğrudan piksel boyutlarından türetilir. <b>Ana thread'de</b>.
     */
    public CompletableFuture<RenderResult> capture(Camera camera, int widthPx, int heightPx) {
        Location anchor = camera.anchor();
        World world = anchor.getWorld();
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Kamera dünyası yüklü değil."));
        }

        double ratio = (double) widthPx / heightPx;
        double regionSize = plugin.config().regionSize();
        double spanHeight = regionSize / Math.max(0.1, camera.scale());
        double spanWidth = spanHeight * ratio;
        double maxDistance = regionSize * 3.0;

        Vector direction = directionFrom(camera.camYaw(), camera.camPitch());
        Vector[] basis = basisFrom(direction);
        Vector right = basis[0];
        Vector up = basis[1];

        Vector focus = anchor.toVector().add(direction.clone().multiply(regionSize));

        BoundingBox region = boxAround(focus, right, up, direction, spanWidth, spanHeight, maxDistance);
        WorldSnapshot snapshot = WorldSnapshot.capture(world, region);

        RenderGeometry geometry = new RenderGeometry(
                focus, right, up, direction, spanWidth, spanHeight, maxDistance, widthPx, heightPx);
        ColorFilter filter = camera.colorFilter();

        CompletableFuture<RenderResult> future = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                future.complete(renderer.render(snapshot, geometry, filter));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /** Bukkit yaw/pitch formülüyle birim yön vektörü. */
    private static Vector directionFrom(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vector(-cosPitch * Math.sin(yawRad), -Math.sin(pitchRad), cosPitch * Math.cos(yawRad));
    }

    /** Yön vektöründen sağ/yukarı eksenlerini türetir (dik bakışta dejenerasyonu ele alır). */
    private static Vector[] basisFrom(Vector direction) {
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = direction.clone().crossProduct(worldUp);
        if (right.lengthSquared() < 1.0e-6) {
            // Tam yukarı/aşağı bakış: referans olarak dünya +Z ekseni kullan.
            right = direction.clone().crossProduct(new Vector(0, 0, 1));
        }
        right.normalize();
        Vector up = right.clone().crossProduct(direction).normalize();
        return new Vector[] {right, up};
    }

    private static BoundingBox boxAround(Vector focus, Vector right, Vector up, Vector direction,
                                         double spanW, double spanH, double maxDist) {
        double hw = spanW / 2.0;
        double hh = spanH / 2.0;
        double hd = maxDist / 2.0;

        BoundingBox box = new BoundingBox(
                focus.getX(), focus.getY(), focus.getZ(),
                focus.getX(), focus.getY(), focus.getZ());
        for (int sw = -1; sw <= 1; sw += 2) {
            for (int sh = -1; sh <= 1; sh += 2) {
                for (int sd = -1; sd <= 1; sd += 2) {
                    Vector corner = focus.clone()
                            .add(right.clone().multiply(sw * hw))
                            .add(up.clone().multiply(sh * hh))
                            .add(direction.clone().multiply(sd * hd));
                    box.union(corner);
                }
            }
        }
        return box.expand(1.0);
    }
}
