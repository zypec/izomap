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
 * Builds {@link MapView}s and filled map items from tiles.
 *
 * <p>{@link Bukkit#createMap(World)} requires the main thread, so every method here
 * must be called from it.</p>
 */
public final class MapService {

    private final Izomap plugin;

    public MapService(Izomap plugin) {
        this.plugin = plugin;
    }

    /** Creates a {@link MapView} for a tile, with its renderer attached. */
    public MapView createMapView(World world, int[] argb) {
        MapView view = Bukkit.createMap(world);
        applyTile(view, argb);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        return view;
    }

    /** Looks up a map by id, or {@code null} when it no longer exists. */
    public MapView viewById(int id) {
        return id < 0 ? null : Bukkit.getMap(id);
    }

    /** Clears an existing {@link MapView}'s renderers and redraws the tile. */
    public void applyTile(MapView view, int[] argb) {
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.addRenderer(new TileMapRenderer(argb));
    }

    /** Builds a filled map item showing a {@link MapView}. */
    public ItemStack itemFor(MapView view) {
        ItemStack item = ItemStack.of(Material.FILLED_MAP);
        item.editMeta(MapMeta.class, meta -> meta.setMapView(view));
        return item;
    }

    /** Creates a filled map item for a single tile. */
    private ItemStack createMapItem(World world, MapTile tile) {
        return itemFor(createMapView(world, tile.argb()));
    }

    /** Converts a tile list to map items, preserving grid order. */
    public List<ItemStack> createMapItems(World world, List<MapTile> tiles) {
        List<ItemStack> items = new ArrayList<>(tiles.size());
        for (MapTile tile : tiles) {
            items.add(createMapItem(world, tile));
        }
        return items;
    }
}
