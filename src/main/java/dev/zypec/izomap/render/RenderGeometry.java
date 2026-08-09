package dev.zypec.izomap.render;

import org.bukkit.util.Vector;

/**
 * Ortografik (izometrik) render için kamera geometrisi.
 *
 * <p>Görüntü düzlemi {@code planeCenter} noktasında, {@code right}/{@code up}
 * eksenleri ile tanımlanır; tüm ışınlar bu düzlemden başlayıp {@code direction}
 * yönünde paralel ilerler (ortografik projeksiyon). Bu, perspektif bozulması
 * olmayan izometrik görünümü verir.</p>
 *
 * <p>Düzlem kameranın <b>tam üzerindedir</b>: hiçbir ışın kameranın arkasından
 * başlamaz, dolayısıyla kameranın gerisindeki veya içinde durduğu araziye ait
 * bloklar fotoğrafa giremez. Işınların gördüğü hacim, düzlemden itibaren
 * {@code maxDistance} blok ileriye uzanan bir prizmadır.</p>
 *
 * @param planeCenter görüntü düzleminin dünya-uzayı merkezi (kameranın hizasında)
 * @param right       düzlemin +X (sağ) ekseni (birim)
 * @param up          düzlemin +Y (yukarı) ekseni (birim)
 * @param direction   ışın yönü (birim)
 * @param spanWidth   düzlemin dünya-uzayı genişliği (blok)
 * @param spanHeight  düzlemin dünya-uzayı yüksekliği (blok)
 * @param maxDistance ışınların ileri doğru kat ettiği mesafe (blok)
 * @param widthPx     çıktı genişliği (piksel)
 * @param heightPx    çıktı yüksekliği (piksel)
 */
public record RenderGeometry(
        Vector planeCenter,
        Vector right,
        Vector up,
        Vector direction,
        double spanWidth,
        double spanHeight,
        double maxDistance,
        int widthPx,
        int heightPx) {
}
