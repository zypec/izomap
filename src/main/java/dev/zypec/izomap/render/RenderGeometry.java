package dev.zypec.izomap.render;

import org.bukkit.util.Vector;

/**
 * Ortografik (izometrik) render için kamera geometrisi.
 *
 * <p>Görüntü düzlemi {@code focus} noktasında, {@code right}/{@code up} eksenleri
 * ile tanımlanır; tüm ışınlar {@code direction} yönünde paraleldir (ortografik
 * projeksiyon). Bu, perspektif bozulması olmayan izometrik görünümü verir.</p>
 *
 * @param focus       görüntü düzleminin dünya-uzayı merkezi
 * @param right       düzlemin +X (sağ) ekseni (birim)
 * @param up          düzlemin +Y (yukarı) ekseni (birim)
 * @param direction   ışın yönü (birim)
 * @param spanWidth   düzlemin dünya-uzayı genişliği (blok)
 * @param spanHeight  düzlemin dünya-uzayı yüksekliği (blok)
 * @param maxDistance ışınların kat ettiği toplam derinlik (blok)
 * @param widthPx     çıktı genişliği (piksel)
 * @param heightPx    çıktı yüksekliği (piksel)
 */
public record RenderGeometry(
        Vector focus,
        Vector right,
        Vector up,
        Vector direction,
        double spanWidth,
        double spanHeight,
        double maxDistance,
        int widthPx,
        int heightPx) {
}
