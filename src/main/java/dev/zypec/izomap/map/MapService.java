package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.List;

/**
 * Karolardan {@link MapView} ve doldurulmuş harita eşyaları üretir.
 *
 * <p>{@link Bukkit#createMap(World)} ana iş parçacığı gerektirdiğinden bu sınıfın
 * metotları <b>ana thread'de</b> çağrılmalıdır.</p>
 */
public final class MapService {

    private final Izomap plugin;

    public MapService(Izomap plugin) {
        this.plugin = plugin;
    }

    /** Bir karo için yeni bir {@link MapView} oluşturur (karo renderer'ı ekli). */
    public MapView createMapView(World world, int[] argb) {
        MapView view = Bukkit.createMap(world);
        applyTile(view, argb);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        return view;
    }

    /** Mevcut bir {@link MapView}'in renderer'larını temizleyip karoyu yeniden çizer. */
    public void applyTile(MapView view, int[] argb) {
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.addRenderer(new TileMapRenderer(argb));
    }

    /** Bir {@link MapView}'i gösteren doldurulmuş harita eşyası üretir. */
    public ItemStack itemFor(MapView view) {
        ItemStack item = ItemStack.of(Material.FILLED_MAP);
        item.editMeta(MapMeta.class, meta -> meta.setMapView(view));
        return item;
    }

    /** Tek bir karo için doldurulmuş harita eşyası oluşturur. */
    public ItemStack createMapItem(World world, MapTile tile) {
        return itemFor(createMapView(world, tile.argb()));
    }

    /** Karo listesini, ızgara sırasını koruyarak harita eşyalarına dönüştürür. */
    public List<ItemStack> createMapItems(World world, List<MapTile> tiles) {
        List<ItemStack> items = new ArrayList<>(tiles.size());
        for (MapTile tile : tiles) {
            items.add(createMapItem(world, tile));
        }
        return items;
    }
}
