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
 * <h2>Geri çekme (backoff) neden var?</h2>
 *
 * <p>Ortografik projeksiyonda ışın başlangıcını <b>bakış yönü boyunca</b> kaydırmak
 * görüntüyü değiştirmez; yalnızca ışının nereden başladığını, dolayısıyla neyin
 * önünü kapatabileceğini değiştirir. Kadrajın kameranın altına düşen kısmı, kamera
 * yere yakınsa toprağın <i>içinde</i> başlar ve fotoğrafın altına düz bir toprak
 * kesiti basar. Bu yüzden yalnızca o ışınlar, tam olarak kameranın yatay düzlemine
 * çıkacak kadar geriye çekilir ({@code maxBackoff} ile sınırlı). Kameranın hizasında
 * ve üstünde kalan ışınlar hiç geri çekilmez, yani kameranın arkasından bakan bir
 * ışın oluşmaz.</p>
 *
 * <p>Işınların gördüğü hacim, kendi başlangıç noktalarından itibaren
 * {@code maxDistance} blok (geri çekilenler için ek olarak geri çekildikleri kadar)
 * ileriye uzanan bir prizmadır; böylece <b>kamera düzleminden itibaren</b> ileri
 * görüş mesafesi her ışın için aynı kalır.</p>
 *
 * @param planeCenter görüntü düzleminin dünya-uzayı merkezi
 * @param right       düzlemin +X (sağ) ekseni (birim, daima yatay)
 * @param up          düzlemin +Y (yukarı) ekseni (birim)
 * @param direction   ışın yönü (birim)
 * @param spanWidth   düzlemin dünya-uzayı genişliği (blok)
 * @param spanHeight  düzlemin dünya-uzayı yüksekliği (blok)
 * @param maxDistance ışınların ileri doğru kat ettiği mesafe (blok)
 * @param eyeY        kameranın dünya-uzayı yüksekliği; hiçbir ışın bunun altından
 *                    başlamaz
 * @param maxBackoff  bir ışının geriye çekilebileceği en fazla mesafe (blok);
 *                    0 ise geri çekme kapalıdır
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
        double eyeY,
        double maxBackoff,
        int widthPx,
        int heightPx) {
}
