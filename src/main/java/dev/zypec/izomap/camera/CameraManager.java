package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.AspectRatio;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kameraların çalışma zamanı kaydı: entity yaşam döngüsü, transform uygulama
 * ve kalıcılık ({@link CameraStorage}) koordinasyonu.
 *
 * <p>Bellek modeli tek doğruluk kaynağıdır; her değişiklikte tüm koleksiyon
 * asenkron olarak diske yazılır.</p>
 */
public final class CameraManager {

    private final Izomap plugin;
    private final CameraKeys keys;
    private final CameraStorage storage;

    private final Map<UUID, Camera> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Camera> byInteraction = new ConcurrentHashMap<>();

    public CameraManager(Izomap plugin, CameraKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
        this.storage = new CameraStorage(plugin);
    }

    // --- yaşam döngüsü ---

    /**
     * cameras.yml'i asenkron yükler, ardından ana thread'de belleğe alır.
     * Dönen future belleğe alma tamamlandığında (ana thread'de) tamamlanır.
     */
    public java.util.concurrent.CompletableFuture<Void> load() {
        return storage.load().thenCompose(v -> {
            java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                ingest(storage.readAll());
                done.complete(null);
            });
            return done;
        });
    }

    private void ingest(List<Camera> cameras) {
        for (Camera c : cameras) {
            byId.put(c.id(), c);
            if (c.interactionEntityId() != null) {
                byInteraction.put(c.interactionEntityId(), c);
            }
            applyTransform(c);
        }
        plugin.getLogger().info(cameras.size() + " kamera yüklendi.");
    }

    public void saveSync() {
        storage.saveAllSync(byId.values());
    }

    private void persistAsync() {
        storage.saveAll(new ArrayList<>(byId.values()));
    }

    // --- sorgular ---

    public Camera byInteractionEntity(UUID interactionId) {
        return byInteraction.get(interactionId);
    }

    public Optional<Camera> byOwnerAndName(UUID owner, String name) {
        return byId.values().stream()
                .filter(c -> c.owner().equals(owner) && c.name().equalsIgnoreCase(name))
                .findFirst();
    }

    public List<Camera> ownedBy(UUID owner) {
        List<Camera> out = new ArrayList<>();
        for (Camera c : byId.values()) {
            if (c.owner().equals(owner)) {
                out.add(c);
            }
        }
        return out;
    }

    public int ownedCount(UUID owner) {
        return (int) byId.values().stream().filter(c -> c.owner().equals(owner)).count();
    }

    public Collection<Camera> all() {
        return byId.values();
    }

    // --- oluşturma / silme ---

    /**
     * Verilen konumda yeni bir kamera oluşturur (display + interaction entity).
     * Limit aşılırsa {@code null} döner.
     */
    public Camera create(Player owner, String name, Location anchor) {
        if (ownedCount(owner.getUniqueId()) >= plugin.config().maxCamerasPerPlayer()) {
            return null;
        }

        World world = anchor.getWorld();
        Display display = spawnDisplay(world, anchor);
        Interaction interaction = spawnInteraction(world, anchor);

        Camera camera = new Camera(UUID.randomUUID(), owner.getUniqueId(), name, anchor);
        camera.displayEntityId(display.getUniqueId());
        camera.interactionEntityId(interaction.getUniqueId());
        // Varsayılan olarak oyuncunun yatay yönüne bakan, aşağı eğimli (izometrik) açı.
        // Oyuncunun anlık pitch'i kopyalanmaz; aksi halde yukarı bakarken kurulan kamera
        // "ters açıdan" (aşağıdan yukarı) çekebilir. Pitch etkileşimle ayarlanabilir.
        camera.camYaw(owner.getLocation().getYaw());
        camera.camPitch((float) plugin.config().defaultPitch());
        camera.aspectRatio(AspectRatio.fromString(plugin.config().defaultAspectRatio(), AspectRatio.RATIO_1_1));

        keys.tagCamera(display.getPersistentDataContainer(), camera.id());
        keys.tagCamera(interaction.getPersistentDataContainer(), camera.id());

        byId.put(camera.id(), camera);
        byInteraction.put(interaction.getUniqueId(), camera);

        applyTransform(camera);
        persistAsync();
        return camera;
    }

    public void remove(Camera camera) {
        removeEntity(camera.displayEntityId());
        removeEntity(camera.interactionEntityId());
        byId.remove(camera.id());
        if (camera.interactionEntityId() != null) {
            byInteraction.remove(camera.interactionEntityId());
        }
        persistAsync();
    }

    /** Kamerayı (display + interaction entity) yeni konuma taşır. */
    public void move(Camera camera, Location newAnchor) {
        Entity display = plugin.getServer().getEntity(camera.displayEntityId());
        if (display != null) {
            display.teleport(newAnchor);
        }
        Entity interaction = plugin.getServer().getEntity(camera.interactionEntityId());
        if (interaction != null) {
            interaction.teleport(newAnchor);
        }
        camera.anchor(newAnchor);
        applyTransform(camera);
        persistAsync();
    }

    /** Bir oyuncunun tüm kameralarını siler; silinen sayısını döndürür. */
    public int removeAllOwned(UUID owner) {
        List<Camera> owned = ownedBy(owner);
        for (Camera camera : owned) {
            removeEntity(camera.displayEntityId());
            removeEntity(camera.interactionEntityId());
            byId.remove(camera.id());
            if (camera.interactionEntityId() != null) {
                byInteraction.remove(camera.interactionEntityId());
            }
        }
        if (!owned.isEmpty()) {
            persistAsync();
        }
        return owned.size();
    }

    // --- transform ---

    /**
     * Kameranın yaw/pitch değerlerini görsel display entity'ye uygular.
     * Entity yüklü değilse sessizce atlanır (sonraki yüklemede uygulanır).
     *
     * <p>Modelin boyutu {@code camera.model-scale} config değeridir; kameranın
     * yakınlaştırması (zoom) modelin boyutunu <b>etkilemez</b>.</p>
     */
    public void applyTransform(Camera camera) {
        if (camera.displayEntityId() == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(camera.displayEntityId());
        if (!(entity instanceof Display display)) {
            return;
        }
        // Model, camYaw/camPitch yönüne bakacak şekilde döndürülür. Config'teki üç eksenli
        // rotasyon offseti (X=pitch, Y=yaw, Z=roll) farklı bir model kullanıldığında görsel
        // hizalamayı elle düzeltmeye yarar; sıra Y -> X -> Z olduğundan Z, modelin kendi
        // bakış ekseni etrafında yatırma (roll) sağlar.
        double yaw = camera.camYaw() + plugin.config().modelRotationY();
        double pitch = camera.camPitch() + plugin.config().modelRotationX();
        double roll = plugin.config().modelRotationZ();
        Quaternionf rotation = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch),
                (float) Math.toRadians(roll));
        Transformation transformation = new Transformation(
                new Vector3f(),
                rotation,
                new Vector3f((float) plugin.config().modelScale()),
                new Quaternionf());

        display.setInterpolationDuration(3);
        display.setInterpolationDelay(0);
        display.setTransformation(transformation);
    }

    /**
     * Yüklü tüm kameraların transformunu yeniden uygular.
     *
     * <p>Config'teki model rotasyon offseti değiştirilip {@code /izomap reload}
     * çalıştırıldığında sonucun anında görünmesi için kullanılır.</p>
     */
    public void refreshTransforms() {
        for (Camera camera : byId.values()) {
            applyTransform(camera);
        }
    }

    /** Değişiklik sonrası transform'u uygular ve kalıcılığı tetikler. */
    public void applyAndPersist(Camera camera) {
        applyTransform(camera);
        persistAsync();
    }

    // --- kamera eşyası ---

    public ItemStack createCameraItem() {
        Material material = resolveMaterial(plugin.config().modelMaterial(), Material.SPYGLASS);
        ItemStack item = ItemStack.of(material.isItem() ? material : Material.SPYGLASS);
        item.editMeta(meta -> {
            meta.displayName(plugin.messages().get("camera.item-name")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(plugin.messages().list("camera.item-lore").stream()
                    .map(c -> c.decoration(TextDecoration.ITALIC, false))
                    .toList());
            keys.markItem(meta.getPersistentDataContainer());
        });
        return item;
    }

    // --- iç yardımcılar ---

    private Display spawnDisplay(World world, Location anchor) {
        String type = plugin.config().displayType();
        Material material = resolveMaterial(plugin.config().modelMaterial(), Material.SPYGLASS);

        if ("BLOCK_DISPLAY".equalsIgnoreCase(type) && material.isBlock()) {
            return world.spawn(anchor, BlockDisplay.class, e -> {
                e.setBlock(material.createBlockData());
                configureDisplay(e);
            });
        }
        return world.spawn(anchor, ItemDisplay.class, e -> {
            e.setItemStack(ItemStack.of(material.isItem() ? material : Material.SPYGLASS));
            configureDisplay(e);
        });
    }

    private void configureDisplay(Display display) {
        display.setBillboard(Display.Billboard.FIXED);
        display.setViewRange(1.0f);
        display.setPersistent(true);
    }

    private Interaction spawnInteraction(World world, Location anchor) {
        return world.spawn(anchor, Interaction.class, e -> {
            e.setInteractionWidth(0.6f);
            e.setInteractionHeight(0.6f);
            e.setResponsive(true);
            e.setPersistent(true);
        });
    }

    private void removeEntity(UUID id) {
        if (id == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(id);
        if (entity != null) {
            entity.remove();
        }
    }

    private static Material resolveMaterial(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material matched = Material.matchMaterial(name);
        return matched != null ? matched : fallback;
    }
}
