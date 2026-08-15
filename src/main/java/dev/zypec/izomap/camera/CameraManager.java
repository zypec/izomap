package dev.zypec.izomap.camera;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.AspectRatio;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of cameras: entity lifecycle, transforms, and persistence through
 * {@link CameraStorage}.
 *
 * <p>The in-memory model is the source of truth; every change writes the whole
 * collection to disk asynchronously.</p>
 */
public final class CameraManager {

    /**
     * Smallest click box, so a shrunk camera can still be hit.
     */
    private static final double MIN_INTERACTION = 0.25;
    /**
     * Largest click box, so a grown one does not swallow its surroundings.
     */
    private static final double MAX_INTERACTION = 3.0;

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
    public CompletableFuture<Void> load() {
        return storage.load().thenCompose(v -> {
            CompletableFuture<Void> done = new CompletableFuture<>();
            plugin.runOnMain(() -> {
                ingest(storage.readAll());
                done.complete(null);
            });
            return done;
        });
    }

    private void ingest(List<Camera> cameras) {
        var changed = false;
        for (var c : cameras) {
            byId.put(c.id(), c);
            if (c.interactionEntityId() != null) {
                byInteraction.put(c.interactionEntityId(), c);
            }
            changed |= applyTransform(c);
        }
        if (changed)
            persistAsync();

        plugin.messages().info("log.cameras-loaded",
                Placeholder.unparsed("count", String.valueOf(cameras.size())));
    }

    public void saveSync() {
        storage.saveAllSync(byId.values());
    }

    private void persistAsync() {
        storage.saveAll(new ArrayList<>(byId.values()));
    }

    /**
     * Writes the collection out after a change made outside this class.
     */
    public void persist() {
        persistAsync();
    }

    // --- queries ---

    public Camera byInteractionEntity(UUID interactionId) {
        return byInteraction.get(interactionId);
    }

    /**
     * Camera by id, or {@code null}.
     */
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

        var world = anchor.getWorld();
        var display = spawnDisplay(world, anchor);
        var interaction = spawnInteraction(world, anchor);

        var camera = new Camera(UUID.randomUUID(), owner.getUniqueId(), name, anchor);
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

    /**
     * Moves the camera and both of its entities to a new location.
     */
    public void move(Camera camera, Location newAnchor) {
        reposition(camera, newAnchor);
        persistAsync();
    }

    /**
     * Moves the camera without writing to disk, for callers that persist once at the
     * end of a larger change; a save serializes the whole collection, so doing it twice
     * per click is pure waste.
     */
    public void reposition(Camera camera, Location newAnchor) {
        var display = plugin.getServer().getEntity(camera.displayEntityId());
        if (display != null) {
            display.teleport(newAnchor);
        }
        var interaction = plugin.getServer().getEntity(camera.interactionEntityId());
        if (interaction != null) {
            interaction.teleport(newAnchor);
        }
        camera.anchor(newAnchor);
        applyTransform(camera);
    }

    /**
     * Removes every camera owned by a player and returns how many were removed.
     */
    public int removeAllOwned(UUID owner) {
        var owned = ownedBy(owner);
        for (var camera : owned)
            forget(camera);

        if (!owned.isEmpty())
            persistAsync();

        return owned.size();
    }

    /**
     * Removes the camera's entities and drops its record.
     *
     * <p>Entities only resolve while their chunk is loaded, so the anchor's chunk is
     * loaded first. Otherwise, the record would go but the entities would stay behind
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
        removeEntity(camera.hologramEntityId());
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
        for (var entity : world.getEntities()) {
            if (!(entity instanceof Display) && !(entity instanceof Interaction))
                continue;

            var cameraId = keys.readCameraId(entity.getPersistentDataContainer());
            if (cameraId == null || byId.containsKey(cameraId))
                continue;

            entity.remove();
            removed++;
        }
        return removed;
    }

    /**
     * Loads the anchor's chunk, without which entities cannot be resolved.
     */
    private void loadAnchorChunk(Camera camera) {
        var anchor = camera.anchor();
        var world = anchor.getWorld();
        if (world != null) {
            world.getChunkAt(anchor);
        }
    }

    // --- transform ---

    /**
     * Applies the camera's yaw/pitch to its display entity, resizes its click box and
     * brings its hologram up to date.
     *
     * <p>Skipped when the entities are not loaded; {@link CameraListener} reapplies
     * this once the chunk loads, otherwise they would stay frozen as they were
     * created.</p>
     *
     * <p>Model size comes from {@code camera.model-scale}; zoom does not affect it.</p>
     *
     * @return whether the record changed and needs saving, see {@link #syncHologram}
     */
    public boolean applyTransform(Camera camera) {
        if (camera.displayEntityId() != null
            && plugin.getServer().getEntity(camera.displayEntityId()) instanceof Display display) {
            applyTransform(camera, display);
        }
        if (camera.interactionEntityId() != null
            && plugin.getServer().getEntity(camera.interactionEntityId()) instanceof Interaction interaction) {
            applyInteractionSize(interaction);
        }
        return syncHologram(camera);
    }

    /**
     * Applies the transform to a display entity already at hand.
     */
    public void applyTransform(Camera camera, Display display) {
        // The configured offsets (X=pitch, Y=yaw, Z=roll) correct visual alignment for
        // custom models; the Y -> X -> Z order makes Z a roll around the view axis.
        double yaw = camera.camYaw() + plugin.config().modelRotationY();
        double pitch = camera.camPitch() + plugin.config().modelRotationX();
        double roll = plugin.config().modelRotationZ();
        var rotation = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch),
                (float) Math.toRadians(roll));
        var transformation = new Transformation(
                new Vector3f(),
                rotation,
                new Vector3f((float) plugin.config().modelScale()),
                new Quaternionf());

        display.setInterpolationDuration(3);
        display.setInterpolationDelay(0);
        display.setTransformation(transformation);

        if (display instanceof ItemDisplay item) {
            item.setItemDisplayTransform(resolveDisplayTransform());
        }
    }

    /**
     * Sizes the click box with the model.
     *
     * <p>A fixed box stops matching the model as soon as {@code model-scale} moves:
     * clicks land next to a scaled-up camera with no response, and a scaled-down one
     * keeps an invisible area clickable. The bounds keep a tiny camera reachable and
     * a huge one from swallowing everything around it.</p>
     */
    public void applyInteractionSize(Interaction interaction) {
        var size = (float) Math.clamp(
                plugin.config().interactionSize() * plugin.config().modelScale(), MIN_INTERACTION, MAX_INTERACTION);
        interaction.setInteractionWidth(size);
        interaction.setInteractionHeight(size);
    }

    // --- hologram ---

    /**
     * Brings the camera's hologram in line with the camera: created when it is missing,
     * removed when the feature is switched off, otherwise moved and rewritten.
     *
     * <p>A missing hologram is only recreated once the camera's <b>display</b> entity
     * resolves. {@code getEntity} also returns null for an unloaded chunk, so without
     * that check every start would hang a second hologram on every camera whose chunk
     * happens to be unloaded.</p>
     *
     * <p>For the same reason a hologram that cannot be resolved is left alone when the
     * feature is off: dropping the id while the entity is still out there would leave
     * something in the world that nothing can find again.</p>
     *
     * @return whether the stored entity id changed and the record needs saving
     */
    public boolean syncHologram(Camera camera) {
        var existing = entityById(camera.hologramEntityId());

        if (!plugin.config().hologramEnabled()) {
            if (existing == null) return false;

            existing.remove();
            camera.hologramEntityId(null);
            return true;
        }
        if (existing instanceof TextDisplay hologram) {
            // Every adjustment click lands here, and most of them do not move the camera.
            var target = hologramLocation(camera);
            if (!hologram.getLocation().equals(target)) {
                hologram.teleport(target);
            }
            styleHologram(camera, hologram);
            return false;
        }
        if (!(entityById(camera.displayEntityId()) instanceof Display)) return false;

        var world = camera.anchor().getWorld();
        if (world == null) return false;

        var hologram = world.spawn(hologramLocation(camera), TextDisplay.class, e -> {
            e.setPersistent(true);
            keys.tagCamera(e.getPersistentDataContainer(), camera.id());
            styleHologram(camera, e);
        });
        camera.hologramEntityId(hologram.getUniqueId());
        return true;
    }

    /**
     * Rewrites every hologram, for facts a camera cannot notice on its own such as how
     * many photos have been taken with it.
     */
    public void refreshHolograms() {
        var changed = false;
        for (var camera : byId.values())
            changed |= syncHologram(camera);

        if (changed)
            persistAsync();
    }

    private void styleHologram(Camera camera, TextDisplay hologram) {
        hologram.text(CameraHologram.text(plugin, camera, photoCount(camera)));
        hologram.setBillboard(resolveBillboard());
        hologram.setViewRange((float) plugin.config().hologramViewRange());

        var background = resolveBackground();
        hologram.setDefaultBackground(background == null);
        if (background != null)
            hologram.setBackgroundColor(background);
    }

    /**
     * Where the hologram floats. The offset scales with the model, so it neither sinks
     * into a scaled-up camera nor drifts off a scaled-down one.
     */
    private Location hologramLocation(Camera camera) {
        return camera.anchor()
                .add(0.0, plugin.config().hologramOffsetY() * plugin.config().modelScale(), 0.0);
    }

    private int photoCount(Camera camera) {
        var photos = plugin.photos();
        return photos != null ? photos.countFor(camera.owner(), camera.name()) : 0;
    }

    private Display.Billboard resolveBillboard() {
        var name = plugin.config().hologramBillboard();
        try {
            return Display.Billboard.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.messages().warn("log.invalid-hologram-billboard",
                    Placeholder.unparsed("value", name),
                    Placeholder.unparsed("options", Arrays.toString(Display.Billboard.values())));
            return Display.Billboard.CENTER;
        }
    }

    /**
     * Configured background color, or {@code null} to keep the vanilla one.
     */
    private Color resolveBackground() {
        var raw = plugin.config().hologramBackground().trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase("default"))
            return null;
        if (raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("transparent"))
            return Color.fromARGB(0, 0, 0, 0);

        var hex = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            var value = Integer.parseUnsignedInt(hex, 16);
            return Color.fromARGB(hex.length() > 6 ? value : value | 0xFF000000);
        } catch (NumberFormatException ex) {
            plugin.messages().warn("log.invalid-hologram-background",
                    Placeholder.unparsed("value", raw));
            return null;
        }
    }

    /**
     * Falls back to the default pose rather than dropping the model's look entirely.
     */
    private ItemDisplayTransform resolveDisplayTransform() {
        var name = plugin.config().itemDisplayTransform();
        try {
            return ItemDisplayTransform.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.messages().warn("log.invalid-item-display-transform",
                    Placeholder.unparsed("value", name),
                    Placeholder.unparsed("options", Arrays.toString(ItemDisplayTransform.values())));
            return ItemDisplayTransform.NONE;
        }
    }

    /**
     * Reapplies the transform of every loaded camera, so a changed model rotation
     * offset takes effect right after {@code /izomap reload}.
     */
    public void refreshTransforms() {
        var changed = false;
        for (var camera : byId.values())
            changed |= applyTransform(camera);

        if (changed)
            persistAsync();
    }

    /**
     * Applies the transform after a change and triggers persistence.
     */
    public void applyAndPersist(Camera camera) {
        applyTransform(camera);
        persistAsync();
    }

    // --- camera item ---

    public ItemStack createCameraItem() {
        var material = resolveMaterial(plugin.config().modelMaterial(), Material.SPYGLASS);
        var item = ItemStack.of(material.isItem() ? material : Material.SPYGLASS);
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
        var type = plugin.config().displayType();
        var material = resolveMaterial(plugin.config().modelMaterial(), Material.SPYGLASS);

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
            applyInteractionSize(e);
            e.setResponsive(true);
            e.setPersistent(true);
        });
    }

    private void removeEntity(UUID id) {
        var entity = entityById(id);
        if (entity != null)
            entity.remove();
    }

    private Entity entityById(UUID id) {
        return id != null ? plugin.getServer().getEntity(id) : null;
    }

    private static Material resolveMaterial(String name, Material fallback) {
        if (name == null) return fallback;

        var matched = Material.matchMaterial(name);
        return matched != null ? matched : fallback;
    }
}
