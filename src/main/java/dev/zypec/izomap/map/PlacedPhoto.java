package dev.zypec.izomap.map;

import java.util.List;
import java.util.UUID;

/**
 * Dünyaya yerleştirilmiş bir fotoğraf (ItemFrame ızgarası).
 *
 * <p>Yeniden başlatma sonrası haritaların yeniden çizilebilmesi için kaynağı
 * ({@code cameraName}) ve harita kimlikleri saklanır; yönetim için çerçeve
 * UUID'leri tutulur.</p>
 *
 * @param id         benzersiz fotoğraf kimliği
 * @param owner      yerleştiren oyuncu
 * @param name       fotoğraf adı (Dialog'da girilir)
 * @param cameraName kaynak kamera adı (yeniden render için)
 * @param worldId    dünya UUID'si
 * @param grid       kullanılan ızgara
 * @param mapIds     harita view kimlikleri (karo sırasıyla: satır-öncelikli)
 * @param frameIds   ItemFrame entity UUID'leri (karo sırasıyla)
 * @param baseX      yerleşim çıpa bloğunun X'i (cleanup'ta chunk yüklemek için)
 * @param baseY      yerleşim çıpa bloğunun Y'si
 * @param baseZ      yerleşim çıpa bloğunun Z'si
 */
public record PlacedPhoto(
        UUID id,
        UUID owner,
        String name,
        String cameraName,
        UUID worldId,
        GridOption grid,
        List<Integer> mapIds,
        List<UUID> frameIds,
        int baseX,
        int baseY,
        int baseZ) {

    /** Kısa görüntüleme kimliği (ilk 8 karakter). */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
