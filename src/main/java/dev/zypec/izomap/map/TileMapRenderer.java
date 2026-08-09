package dev.zypec.izomap.map;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;

/**
 * Draws a tile's ARGB pixels onto a {@link MapCanvas} once.
 *
 * <p>{@link MapCanvas#setPixelColor} maps to the palette internally, so no separate
 * conversion is needed. Drawing happens a single time, not every tick.</p>
 */
public final class TileMapRenderer extends MapRenderer {

    private final int[] argb;
    private boolean rendered;

    public TileMapRenderer(int[] argb) {
        super(false); // same content for every player, not contextual
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
                    continue; // transparent pixel: leave it blank
                }
                canvas.setPixelColor(x, y, new Color(color, true));
            }
        }
        rendered = true;
    }
}
