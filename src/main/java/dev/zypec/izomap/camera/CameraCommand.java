package dev.zypec.izomap.camera;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.place.Protection;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.ui.CameraDialogs;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Brigadier command tree for {@code /izocam}.
 *
 * <ul>
 *   <li>{@code create <name>} – places a camera in front of the player.</li>
 *   <li>{@code move <name>} – moves a camera to where the player is looking.</li>
 *   <li>{@code remove <name> | all} – removes cameras or photos.</li>
 *   <li>{@code list cameras | photos} – lists cameras or photos.</li>
 *   <li>{@code item} – gives the camera placement item.</li>
 *   <li>{@code pickup <name>} – takes a camera back, item and all.</li>
 *   <li>{@code ratio <name> <ratio>} – sets the aspect ratio.</li>
 *   <li>{@code preview <name> | stop} – watches a camera's live view, or stops.</li>
 *   <li>{@code open <name>} – opens the capture dialog.</li>
 *   <li>{@code unplace <camera/photo>} – takes a photo off the wall, keeping it in the list.</li>
 *   <li>{@code retake [with <camera>] <camera/photo>} – shoots a photo again in place.</li>
 *   <li>{@code cancel} – leaves placement mode.</li>
 *   <li>{@code cleanup} – clears records whose frames are gone.</li>
 *   <li>{@code reload} – reloads the configuration (admin).</li>
 * </ul>
 */
public final class CameraCommand {

    private static final String PERM = Izomap.PLUGIN_ID + ".camera";
    private static final String PERM_ADMIN = Izomap.PLUGIN_ID + ".admin";

    private final Izomap plugin;
    private final CameraManager manager;
    private final PhotoManager photoManager;
    private final CameraDialogs dialogs;

    private CameraCommand(Izomap plugin, CameraManager manager, PhotoManager photoManager,
                          CameraDialogs dialogs) {
        this.plugin = plugin;
        this.manager = manager;
        this.photoManager = photoManager;
        this.dialogs = dialogs;
    }

    /**
     * Registers the command on the plugin lifecycle.
     */
    public static void register(Izomap plugin, CameraManager manager, PhotoManager photoManager,
                                CameraDialogs dialogs) {
        CameraCommand command = new CameraCommand(plugin, manager, photoManager, dialogs);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        command.build(),
                        plugin.messages().plain("general.command-description"),
                        List.of("izocamera")));
    }

    private LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("izocam")
                .requires(source -> source.getSender().hasPermission(PERM))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(this::create)))
                .then(Commands.literal("move")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .executes(this::move)))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all")
                                .then(Commands.literal("cameras").executes(this::removeAllCameras))
                                .then(Commands.literal("photos").executes(this::removeAllPhotos)))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .executes(this::remove)))
                .then(Commands.literal("list")
                        .then(Commands.literal("cameras").executes(this::listCameras))
                        .then(Commands.literal("photos").executes(this::listPhotos)))
                .then(Commands.literal("item")
                        .executes(this::giveItem))
                .then(Commands.literal("pickup")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .executes(this::pickup)))
                .then(Commands.literal("ratio")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .then(Commands.argument("ratio", StringArgumentType.greedyString())
                                        .suggests(this::suggestRatios)
                                        .executes(this::setRatio))))
                .then(Commands.literal("preview")
                        .then(Commands.literal("stop").executes(this::previewStop))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .executes(this::previewStart)))
                .then(Commands.literal("open")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .executes(this::openDialog)))
                .then(Commands.literal("unplace")
                        .then(Commands.argument("photo", StringArgumentType.greedyString())
                                .suggests(this::suggestPhotoReferences)
                                .executes(this::unplace)))
                .then(Commands.literal("retake")
                        // The camera goes in front because the reference is greedy: it
                        // may hold spaces and a slash, so nothing can follow it.
                        .then(Commands.literal("with")
                                .then(Commands.argument("camera", StringArgumentType.word())
                                        .suggests(this::suggestOwnedNames)
                                        .then(Commands.argument("photo", StringArgumentType.greedyString())
                                                .suggests(this::suggestPhotoReferences)
                                                .executes(ctx -> retake(ctx,
                                                        StringArgumentType.getString(ctx, "camera"))))))
                        .then(Commands.argument("photo", StringArgumentType.greedyString())
                                .suggests(this::suggestPhotoReferences)
                                .executes(ctx -> retake(ctx, null))))
                .then(Commands.literal("cancel")
                        .executes(this::cancelPlacement))
                .then(Commands.literal("cleanup")
                        .executes(this::cleanup))
                .then(Commands.literal("export")
                        .requires(source -> source.getSender().hasPermission(Permissions.EXPORT))
                        .then(Commands.literal("as")
                                .then(Commands.argument("file", StringArgumentType.word())
                                        .then(Commands.argument("photo", StringArgumentType.greedyString())
                                                .suggests(this::suggestAllPhotoReferences)
                                                .executes(ctx -> export(ctx,
                                                        StringArgumentType.getString(ctx, "file"))))))
                        .then(Commands.argument("photo", StringArgumentType.greedyString())
                                .suggests(this::suggestAllPhotoReferences)
                                .executes(ctx -> export(ctx, null))))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(PERM_ADMIN))
                        .executes(this::reload))
                .build();
    }

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var name = StringArgumentType.getString(ctx, "name");
        if (manager.byOwnerAndName(player.getUniqueId(), name).isPresent()) {
            plugin.messages().send(player, "camera.name-taken", Placeholder.unparsed("name", name));
            return 0;
        }

        var anchor = inFrontOf(player);
        if (!Protection.mayBuild(player, anchor.getBlock(), manager.createCameraItem())) {
            plugin.messages().send(player, "camera.protected");
            return 0;
        }

        var camera = manager.create(player, name, anchor);
        if (camera == null) {
            plugin.messages().send(player, "camera.limit-reached",
                    Placeholder.unparsed("limit", String.valueOf(manager.cameraLimitFor(player))));
            return 0;
        }
        plugin.messages().send(player, "camera.created", Placeholder.unparsed("name", name));
        // Straight into the preview: a camera that was just placed is about to be aimed,
        // and asking for one more command before anything is visible is a step for
        // nothing. A full offhand is not an error here, only a preview that waits.
        startPreview(player, camera, false);
        return Command.SINGLE_SUCCESS;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        manager.remove(camera);
        plugin.messages().send(player, "camera.removed", Placeholder.unparsed("name", camera.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int move(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        var anchor = inFrontOf(player);
        if (!Protection.mayBuild(player, anchor.getBlock(), manager.createCameraItem())) {
            plugin.messages().send(player, "camera.protected");
            return 0;
        }

        manager.move(camera, anchor);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "camera.moved", Placeholder.unparsed("name", camera.name()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Where a camera made or moved by command goes: two blocks along the player's view.
     */
    private static Location inFrontOf(Player player) {
        var eye = player.getEyeLocation();
        var anchor = eye.add(eye.getDirection().multiply(2.0));
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);
        return anchor;
    }

    private int removeAllCameras(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var count = manager.removeAllOwned(player.getUniqueId());
        plugin.messages().send(player, "camera.removed-all", Placeholder.unparsed("count", String.valueOf(count)));
        return Command.SINGLE_SUCCESS;
    }

    private int removeAllPhotos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var count = photoManager.removeAllOwned(player.getUniqueId());
        plugin.messages().send(player, "map.photos-removed-all", Placeholder.unparsed("count", String.valueOf(count)));
        return Command.SINGLE_SUCCESS;
    }

    private int listCameras(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var cameras = manager.ownedBy(player.getUniqueId());
        if (cameras.isEmpty()) {
            plugin.messages().send(player, "camera.list-empty");
            return Command.SINGLE_SUCCESS;
        }

        player.sendMessage(plugin.messages().get("camera.list-header",
                Placeholder.unparsed("count", String.valueOf(cameras.size()))));
        for (var c : cameras) {
            player.sendMessage(plugin.messages().get("camera.list-entry",
                    Placeholder.unparsed("name", c.name()),
                    Placeholder.unparsed("ratio", c.aspectRatio().label()),
                    Placeholder.unparsed("scale", String.format(Locale.ROOT, "%.2f", c.zoom()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listPhotos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var owned = photoManager.ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            plugin.messages().send(player, "map.photos-empty");
            return Command.SINGLE_SUCCESS;
        }

        player.sendMessage(plugin.messages().get("map.photos-header",
                Placeholder.unparsed("count", String.valueOf(owned.size()))));
        for (var photo : owned) {
            player.sendMessage(plugin.messages().get(
                    photo.isPlaced() ? "map.photos-entry-placed" : "map.photos-entry",
                    Placeholder.unparsed("name", photo.name()),
                    Placeholder.unparsed("id", photo.shortId()),
                    Placeholder.unparsed("camera", photo.cameraName()),
                    Placeholder.unparsed("grid", photo.grid().label())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int giveItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        if (manager.giveOrDrop(player, manager.createCameraItem())) {
            plugin.messages().send(player, "camera.item-dropped");
        }
        plugin.messages().send(player, "camera.given-item");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Takes a camera back, asking first when photos would go with it.
     */
    private int pickup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        if (photoManager.countFor(camera.owner(), camera.name()) > 0) {
            dialogs.openPickupDialog(player, camera);
        } else {
            dialogs.pickUp(player, camera);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setRatio(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        var ratio = AspectRatio.fromLabel(StringArgumentType.getString(ctx, "ratio").trim());
        if (ratio == null) {
            plugin.messages().send(player, "photo.invalid-ratio");
            return 0;
        }
        if (!Permissions.ratio(player, ratio)) {
            plugin.messages().send(player, "photo.setting-not-allowed",
                    Placeholder.component("setting", plugin.messages().getOr("setting.ratio", "ratio")),
                    Placeholder.unparsed("value", ratio.label()));
            return 0;
        }

        camera.aspectRatio(ratio);
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "photo.ratio-set", Placeholder.unparsed("ratio", ratio.label()));
        return Command.SINGLE_SUCCESS;
    }

    private int openDialog(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        dialogs.openCaptureDialog(player, camera);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Joins the camera's preview as a watcher; adjusting still needs a click on it.
     */
    private int previewStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        startPreview(player, camera(ctx, player), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Puts the player in front of the camera's live view.
     *
     * @param explicit whether the player asked for the preview itself; when they only
     *                 placed a camera, a full offhand is not worth a complaint
     */
    private void startPreview(Player player, Camera camera, boolean explicit) {
        switch (plugin.preview().join(player, camera)) {
            case JOINED -> {
                plugin.preview().refresh(camera);
                plugin.messages().send(player, "preview.started", Placeholder.unparsed("camera", camera.name()));
            }
            case ALREADY -> {
                if (explicit) plugin.messages().send(player, "preview.already");
            }
            case OFFHAND_FULL -> {
                if (explicit) plugin.messages().send(player, "preview.offhand-full");
            }
        }
    }

    private int previewStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        if (!plugin.preview().leave(player, "preview.ended-self")) {
            plugin.messages().send(player, "preview.not-active");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Takes a photo off the wall. The photo itself stays in its camera's list, so it
     * can go back up somewhere else; deleting it for good is the dialog's job.
     */
    private int unplace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var photo = photo(ctx, player);
        if (photo == null) {
            plugin.messages().send(player, "map.photo-not-found");
            return 0;
        }
        if (!photo.isPlaced()) {
            plugin.messages().send(player, "map.photo-not-placed",
                    Placeholder.unparsed("name", photo.name()));
            return 0;
        }
        photoManager.unplace(photo);
        plugin.messages().send(player, "map.photo-taken-down", Placeholder.unparsed("name", photo.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int cancelPlacement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        if (!plugin.placement().cancel(player, "placement.ended-self")) {
            plugin.messages().send(player, "placement.not-active");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Shoots a hanging photo again without taking it down. Without a camera argument
     * the photo's own source camera is used, or its stored parameters when that camera
     * is gone.
     */
    private int retake(CommandContext<CommandSourceStack> ctx, String cameraName) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var photo = photo(ctx, player);
        if (photo == null) {
            plugin.messages().send(player, "map.photo-not-found");
            return 0;
        }

        Camera source = null;
        if (cameraName != null) {
            source = manager.byOwnerAndName(player.getUniqueId(), cameraName).orElse(null);
            if (source == null) {
                plugin.messages().send(player, "camera.not-found");
                return 0;
            }
        }
        photoManager.retake(player, photo, source);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Admin only, so it resolves any owner's photo rather than just the caller's.
     */
    private int export(CommandContext<CommandSourceStack> ctx, String fileName) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var photo = photoManager.findByReference(StringArgumentType.getString(ctx, "photo")).orElse(null);
        if (photo == null) {
            plugin.messages().send(player, "map.photo-not-found");
            return 0;
        }
        plugin.messages().send(player, "photo.exporting", Placeholder.unparsed("name", photo.name()));
        photoManager.export(player, photo, fileName);
        return Command.SINGLE_SUCCESS;
    }

    private int cleanup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        int removed = photoManager.cleanupOwned(player.getUniqueId());
        plugin.messages().send(player, "map.cleaned", Placeholder.unparsed("count", String.valueOf(removed)));

        // Orphaned camera entities; only loaded chunks are visible, so this cleans up
        // the area around the player.
        int orphans = manager.removeOrphanEntities(player.getWorld());
        if (orphans > 0) {
            plugin.messages().send(player, "camera.orphans-cleaned",
                    Placeholder.unparsed("count", String.valueOf(orphans)));
        }

        int frames = photoManager.removeOrphanFrames(player.getWorld());
        if (frames > 0) {
            plugin.messages().send(player, "map.orphan-frames-cleaned",
                    Placeholder.unparsed("count", String.valueOf(frames)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        plugin.messages().send(ctx.getSource().getSender(), "general.reloaded");
        return Command.SINGLE_SUCCESS;
    }

    // --- suggestions ---

    private CompletableFuture<Suggestions> suggestOwnedNames(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            String prefix = builder.getRemainingLowerCase();
            // An admin may act on anybody's camera, so they are offered every one.
            var cameras = player.hasPermission(PERM_ADMIN) ? manager.all() : manager.ownedBy(player.getUniqueId());
            for (Camera c : cameras) {
                if (c.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(c.name());
                }
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestRatios(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String prefix = builder.getRemaining();
        for (AspectRatio ratio : AspectRatio.values()) {
            if (ratio.label().startsWith(prefix)) {
                builder.suggest(ratio.label());
            }
        }
        return builder.buildFuture();
    }

    // Every photo, not just the caller's: the command that uses it is admin only.
    /**
     * Every photo on the server, for the admin commands.
     */
    private CompletableFuture<Suggestions> suggestAllPhotoReferences(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestReferences(builder, photoManager.all());
    }

    private CompletableFuture<Suggestions> suggestPhotoReferences(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            return suggestReferences(builder, photoManager.ownedBy(player.getUniqueId()));
        }
        return builder.buildFuture();
    }

    /**
     * Offers {@code camera/photo} for each, matched case-insensitively: the reference is
     * typed, not copied, so the case a player uses is their own.
     */
    private CompletableFuture<Suggestions> suggestReferences(
            SuggestionsBuilder builder, Collection<Photo> photos) {
        var prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (var photo : photos) {
            var reference = photo.reference();
            if (reference.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                builder.suggest(reference);
            }
        }
        return builder.buildFuture();
    }

    /**
     * The photo the player named, or {@code null} when nothing answers to it.
     */
    private Photo photo(CommandContext<CommandSourceStack> ctx, Player player) {
        return photoManager.findByReference(
                player.getUniqueId(), StringArgumentType.getString(ctx, "photo")).orElse(null);
    }

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var sender = ctx.getSource().getSender();
        if (sender instanceof Player player) return player;

        var message = plugin.messages().asVanilla("general.player-only");
        throw new CommandSyntaxException(
                new SimpleCommandExceptionType(message),
                message);
    }

    /**
     * The camera the player named: their own first. An admin may name anybody's, as long
     * as only one camera answers to it. Anybody else naming a camera that exists but is
     * not theirs is told whose it is, since "not found" for a camera in plain sight reads
     * as the plugin having lost it.
     */
    private Camera camera(CommandContext<CommandSourceStack> ctx, Player player) throws CommandSyntaxException {
        var name = StringArgumentType.getString(ctx, "name");
        var own = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (own != null)
            return own;

        var others = manager.named(name);
        if (others.size() == 1 && player.hasPermission(PERM_ADMIN))
            return others.getFirst();

        var message = others.isEmpty()
                ? plugin.messages().asVanilla("camera.not-found")
                : others.size() > 1 && player.hasPermission(PERM_ADMIN)
                ? plugin.messages().asVanilla("camera.ambiguous",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("count", String.valueOf(others.size())))
                : plugin.messages().asVanilla("camera.not-yours",
                        Placeholder.unparsed("name", others.getFirst().name()),
                        Placeholder.unparsed("owner", ownerName(others.getFirst())));
        throw new CommandSyntaxException(new SimpleCommandExceptionType(message), message);
    }

    private String ownerName(Camera camera) {
        var name = plugin.getServer().getOfflinePlayer(camera.owner()).getName();
        return name != null ? name : camera.owner().toString();
    }
}
