package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.map.MapService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Oyuncunun offhand'ine, kameranın gördüğünü gösteren 1x1 (128x128) canlı önizleme
 * haritası koyar ve kamera düzenlendikçe günceller.
 *
 * <p>Kurallar:</p>
 * <ul>
 *   <li>Önizlemeye girmek için offhand <b>boş</b> olmalıdır.</li>
 *   <li>Önizleme haritası yere atılamaz, envanterde taşınamaz, mainhand'e geçirilemez.</li>
 *   <li>Oyuncu çıkınca harita silinir; tekrar girdiğinde yeniden başlar.</li>
 * </ul>
 *
 * <p>Her oyuncu için tek bir {@link MapView} yeniden kullanılır; aynı MapView yeniden
 * render edilince eldeki harita otomatik güncellenir.</p>
 */
public final class PreviewManager implements Listener {

    private static final int TILE = 128;

    private final Izomap plugin;
    private final RenderService renderService;
    private final MapService mapService;
    private final NamespacedKey previewKey;

    private final Map<UUID, MapView> views = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PreviewManager(Izomap plugin, RenderService renderService, MapService mapService) {
        this.plugin = plugin;
        this.renderService = renderService;
        this.mapService = mapService;
        this.previewKey = new NamespacedKey(plugin, "preview_map");
    }

    /**
     * Önizlemeyi yeniden render eder. Offhand boşsa önizleme başlatılır; önizleme zaten
     * varsa güncellenir; offhand başka bir eşyayla doluysa hiçbir şey yapılmaz.
     * <b>Ana iş parçacığında</b> çağrılmalıdır.
     */
    public void refresh(Player player, Camera camera) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        boolean hasPreview = isPreview(offhand);
        boolean offhandEmpty = offhand == null || offhand.getType().isAir();
        if (!hasPreview && !offhandEmpty) {
            return; // offhand dolu: önizlemeye girilemez
        }

        UUID id = player.getUniqueId();
        if (!inFlight.add(id)) {
            return; // aynı anda tek render; tıklama spam'ini yut
        }
        MapView view = views.computeIfAbsent(id, key -> mapService.createMapView(player.getWorld(), blank()));

        renderService.capture(camera, TILE, TILE).whenComplete((result, error) ->
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    inFlight.remove(id);
                    if (error == null && result != null) {
                        mapService.applyTile(view, result.argb());
                        placeIfEmpty(player, view);
                        return;
                    }
                    // Önizleme sessizce donmasın: bütçe aşımının nedeni action bar'da yazar.
                    Throwable cause = error instanceof java.util.concurrent.CompletionException
                            && error.getCause() != null ? error.getCause() : error;
                    if (cause instanceof CaptureTooLargeException tooLarge) {
                        player.sendActionBar(plugin.messages().get("photo.too-large",
                                Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                                Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
                    }
                }));
    }

    /** Offhand boşsa önizleme haritasını koyar (zaten önizlemeyse dokunmaz). */
    private void placeIfEmpty(Player player, MapView view) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItemInOffHand();
        if (isPreview(current)) {
            return; // aynı MapView güncellendiği için içerik zaten tazelendi
        }
        if (current == null || current.getType().isAir()) {
            inventory.setItemInOffHand(previewItem(view));
        }
    }

    private ItemStack previewItem(MapView view) {
        ItemStack item = mapService.itemFor(view);
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(previewKey, PersistentDataType.BOOLEAN, true));
        return item;
    }

    /** Önizlemeyi sonlandırır: offhand'deki haritayı kaldırır. */
    public void endPreview(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isPreview(offhand)) {
            player.getInventory().setItemInOffHand(null);
        }
        views.remove(player.getUniqueId());
        inFlight.remove(player.getUniqueId());
    }

    private boolean isPreview(ItemStack item) {
        return item != null && item.hasItemMeta()
                && Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer()
                .get(previewKey, PersistentDataType.BOOLEAN));
    }

    // --- kilitleme ve temizlik ---

    // Q ile atma: yere düşürmek yerine önizlemeyi temiz şekilde sonlandır.
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPreview(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            endPreview(event.getPlayer());
        }
    }

    // F ile el değiştirme engellenir.
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isPreview(event.getOffHandItem()) || isPreview(event.getMainHandItem())) {
            event.setCancelled(true);
        }
    }

    // Envanterde taşıma/sürükleme engellenir.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (isPreview(event.getCurrentItem()) || isPreview(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endPreview(event.getPlayer());
    }

    // Sunucu çökmesi gibi durumlarda offhand'de kalmış olabilecek önizlemeyi temizle.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ItemStack offhand = event.getPlayer().getInventory().getItemInOffHand();
        if (isPreview(offhand)) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        }
    }

    private static int[] blank() {
        return new int[TILE * TILE];
    }
}
