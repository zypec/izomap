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
 * Runtime registry of cameras: entity lifecycle, transforms and persistence through
 * {@link CameraStorage}.
 *
 * <p>The in-memory model is the source of truth; every change writes the whole
 * collection to disk asynchronously.</p>
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

    // --- lifecycle ---

    /**
     * Loads {@code cameras.yml} asynchronously, then ingests it on the main thread.
     * The returned future completes once ingestion is done.
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

    // --- queries ---

    public Camera byInteractionEntity(UUID interactionId) {
        return byInteraction.get(interactionId);
    }

    /** Camera by id, or {@code null}. */
    public Camera byId(UUID cameraId) {
        return cameraId != null ? byId.get(cameraId) : null;
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

    // --- creation and removal ---

    /**
     * Creates a camera with its display and interaction entities at the given
     * location, or returns {@code null} when the owner is at their limit.
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
        // Face the player's yaw at the configured downward pitch. Copying the player's
        // pitch would let a camera placed while looking up shoot from below.
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
        forget(camera);
        persistAsync();
    }

    /** Moves the camera and both of its entities to a new location. */
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

    /** Removes every camera owned by a player and returns how many were removed. */
    public int removeAllOwned(UUID owner) {
        List<Camera> owned = ownedBy(owner);
        for (Camera camera : owned) {
            forget(camera);
        }
        if (!owned.isEmpty()) {
            persistAsync();
        }
        return owned.size();
    }

    /**
     * Removes the camera's entities and drops its record.
     *
     * <p>Entities only resolve while their chunk is loaded, so the anchor's chunk is
     * loaded first. Otherwise the record would go but the entities would stay behind
     * as orphans: models belonging to no camera and removable by nothing.</p>
     *
     * <p>Anyone previewing it is released first; their map has nothing left to render
     * and would sit in the offhand frozen on the last image.</p>
     */
    private void forget(Camera camera) {
        if (plugin.preview() != null) {
            plugin.preview().close(camera.id(), "preview.ended-camera-removed", camera.name());
        }
        loadAnchorChunk(camera);
        removeEntity(camera.displayEntityId());
        removeEntity(camera.interactionEntityId());
        byId.remove(camera.id());
        if (camera.interactionEntityId() != null) {
            byInteraction.remove(camera.interactionEntityId());
        }
    }

    /**
     * Removes Izomap entities left in the world that belong to no camera record and
     * returns how many were removed.
     *
     * <p>{@link World#getEntities()} only sees loaded chunks, so this cleans up the
     * area around the player. Cameras that still have a record are untouched.</p>
     */
    public int removeOrphanEntities(World world) {
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Display) && !(entity instanceof Interaction)) {
                continue;
            }
            UUID cameraId = keys.readCameraId(entity.getPersistentDataContainer());
            if (cameraId == null || byId.containsKey(cameraId)) {
                continue;
            }
            entity.remove();
            removed++;
        }
        return removed;
    }

    /** Loads the anchor's chunk, without which entities cannot be resolved. */
    private void loadAnchorChunk(Camera camera) {
        Location anchor = camera.anchor();
        World world = anchor.getWorld();
        if (world != null) {
            world.getChunkAt(anchor);
        }
    }

    // --- transform ---

    /**
     * Applies the camera's yaw/pitch to its display entity.
     *
     * <p>Skipped when the entity is not loaded; {@link CameraListener} reapplies it
     * once the chunk loads, otherwise the entity would stay frozen with the
     * transform it was created with.</p>
     *
     * <p>Model size comes from {@code camera.model-scale}; zoom does not affect it.</p>
     */
    public void applyTransform(Camera camera) {
        if (camera.displayEntityId() == null) {
            return;
        }
        if (plugin.getServer().getEntity(camera.displayEntityId()) instanceof Display display) {
            applyTransform(camera, display);
        }
    }

    /** Applies the transform to a display entity already at hand. */
    public void applyTransform(Camera camera, Display display) {
        // The configured offsets (X=pitch, Y=yaw, Z=roll) correct visual alignment for
        // custom models; the Y -> X -> Z order makes Z a roll around the view axis.
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
     * Reapplies the transform of every loaded camera, so a changed model rotation
     * offset takes effect right after {@code /izomap reload}.
     */
    public void refreshTransforms() {
        for (Camera camera : byId.values()) {
            applyTransform(camera);
        }
    }

    /** Applies the transform after a change and triggers persistence. */
    public void applyAndPersist(Camera camera) {
        applyTransform(camera);
        persistAsync();
    }

    // --- camera item ---

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

    // --- internals ---

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
