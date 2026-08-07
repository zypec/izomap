package dev.zypec.izomap.map;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;

/**
 * Bir karonun ARGB piksellerini {@link MapCanvas}'a bir kez çizen renderer.
 *
 * <p>{@link MapCanvas#setPixelColor} palet eşlemesini içeride yaptığından ayrı bir
 * dönüşüm gerekmez. Render tek seferlik yapılır; sonraki tick'lerde tekrar
 * çizilmez (performans).</p>
 */
public final class TileMapRenderer extends MapRenderer {

    private final int[] argb;
    private boolean rendered;

    public TileMapRenderer(int[] argb) {
        super(false); // tüm oyuncular için aynı içerik (contextual değil)
        this.argb = argb;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (rendered) {
            return;
        }
        int size = GridOption.TILE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = argb[y * size + x];
                if ((color >>> 24) == 0) {
                    continue; // şeffaf piksel: boş bırak
                }
                canvas.setPixelColor(x, y, new Color(color, true));
            }
        }
        rendered = true;
    }
}
