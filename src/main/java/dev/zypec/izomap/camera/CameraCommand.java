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
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.ImageSlicer;
import dev.zypec.izomap.map.MapService;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.map.PlacedPhoto;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.CaptureTooLargeException;
import dev.zypec.izomap.render.RenderService;
import dev.zypec.izomap.ui.CameraDialogs;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Brigadier command tree for {@code /izocam}.
 *
 * <ul>
 *   <li>{@code create <name>} – places a camera in front of the player.</li>
 *   <li>{@code move <name>} – moves a camera to where the player is looking.</li>
 *   <li>{@code remove <name> | all} – removes cameras or photos.</li>
 *   <li>{@code list cameras | photos} – lists cameras or photos.</li>
 *   <li>{@code item} – gives the camera placement item.</li>
 *   <li>{@code ratio <name> <ratio>} – sets the aspect ratio.</li>
 *   <li>{@code maps <name> <grid>} – puts the map items in the inventory.</li>
 *   <li>{@code preview <name> | stop} – watches a camera's live view, or stops.</li>
 *   <li>{@code open <name>} – opens the capture dialog.</li>
 *   <li>{@code unplace <id>} – removes a placed photo.</li>
 *   <li>{@code cleanup} – clears records whose frames are gone.</li>
 *   <li>{@code reload} – reloads the configuration (admin).</li>
 * </ul>
 */
public final class CameraCommand {

    private static final String PERM = Izomap.PLUGIN_ID + ".camera";
    private static final String PERM_ADMIN = Izomap.PLUGIN_ID + ".admin";

    private final Izomap plugin;
    private final CameraManager manager;
    private final RenderService renderService;
    private final MapService mapService;
    private final PhotoManager photoManager;
    private final CameraDialogs dialogs;

    private CameraCommand(Izomap plugin, CameraManager manager, RenderService renderService,
                          MapService mapService, PhotoManager photoManager, CameraDialogs dialogs) {
        this.plugin = plugin;
        this.manager = manager;
        this.renderService = renderService;
        this.mapService = mapService;
        this.photoManager = photoManager;
        this.dialogs = dialogs;
    }

    /**
     * Registers the command on the plugin lifecycle.
     */
    public static void register(Izomap plugin, CameraManager manager, RenderService renderService,
                                MapService mapService, PhotoManager photoManager, CameraDialogs dialogs) {
        CameraCommand command = new CameraCommand(plugin, manager, renderService, mapService, photoManager, dialogs);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        command.build(),
                        "Izomap kamera komutları",
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
                .then(Commands.literal("ratio")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .then(Commands.argument("ratio", StringArgumentType.greedyString())
                                        .suggests(this::suggestRatios)
                                        .executes(this::setRatio))))
                .then(Commands.literal("maps")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestOwnedNames)
                                .then(Commands.argument("grid", StringArgumentType.word())
                                        .suggests(this::suggestGrids)
                                        .executes(this::makeMaps))))
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
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(this::suggestPhotoIds)
                                .executes(this::unplace)))
                .then(Commands.literal("cleanup")
                        .executes(this::cleanup))
                .then(Commands.literal("export")
                        .requires(source -> source.getSender().hasPermission(PERM_ADMIN))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(this::suggestAllPhotoIds)
                                .executes(ctx -> export(ctx, null))
                                .then(Commands.argument("file", StringArgumentType.word())
                                        .executes(ctx -> export(ctx,
                                                StringArgumentType.getString(ctx, "file"))))))
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

        var eye = player.getEyeLocation();
        var direction = eye.getDirection();
        var anchor = eye.add(direction.multiply(2.0));
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        var camera = manager.create(player, name, anchor);
        if (camera == null) {
            plugin.messages().send(player, "camera.limit-reached",
                    Placeholder.unparsed("limit", String.valueOf(plugin.config().maxCamerasPerPlayer())));
            return 0;
        }
        plugin.messages().send(player, "camera.created", Placeholder.unparsed("name", name));
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

        var eye = player.getEyeLocation();
        var direction = eye.getDirection();
        var anchor = eye.add(direction.multiply(2.0));
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        manager.move(camera, anchor);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "camera.moved", Placeholder.unparsed("name", camera.name()));
        return Command.SINGLE_SUCCESS;
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
            player.sendMessage(plugin.messages().get("map.photos-entry",
                    Placeholder.unparsed("name", photo.name()),
                    Placeholder.unparsed("id", photo.shortId()),
                    Placeholder.unparsed("grid", photo.grid().label())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int giveItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        player.getInventory().addItem(manager.createCameraItem());
        plugin.messages().send(player, "camera.given-item");
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

        camera.aspectRatio(ratio);
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "photo.ratio-set", Placeholder.unparsed("ratio", ratio.label()));
        return Command.SINGLE_SUCCESS;
    }

    private int makeMaps(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);
        var camera = camera(ctx, player);

        var grid = GridOption.parse(StringArgumentType.getString(ctx, "grid"));
        if (grid == null || !GridLayouts.isValid(camera.aspectRatio(), grid)) {
            var options = GridLayouts.optionsFor(camera.aspectRatio()).stream()
                    .map(GridOption::label)
                    .collect(Collectors.joining(", "));
            plugin.messages().send(player, "map.invalid-grid",
                    Placeholder.unparsed("ratio", camera.aspectRatio().label()),
                    Placeholder.unparsed("grids", options));
            return 0;
        }

        plugin.messages().send(player, "photo.capturing");
        var start = System.currentTimeMillis();
        var world = camera.anchor().getWorld();

        renderService.capture(camera, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null || world == null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof CaptureTooLargeException tooLarge) {
                    runOnMain(() -> plugin.messages().send(player, "photo.too-large",
                            Placeholder.unparsed("required", String.valueOf(tooLarge.required())),
                            Placeholder.unparsed("budget", String.valueOf(tooLarge.budget()))));
                    return;
                }
                plugin.getLogger().warning("Harita render'ı başarısız (" + camera.name() + "): "
                                           + (cause != null ? cause.getMessage() : "boş sonuç/dünya"));
                runOnMain(() -> plugin.messages().send(player, "photo.failed"));
                return;
            }
            runOnMain(() -> {
                var tiles = ImageSlicer.slice(result, grid);
                var maps = mapService.createMapItems(world, tiles);
                boolean overflow = false;
                for (var map : maps) {
                    if (!player.getInventory().addItem(map).isEmpty()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), map);
                        overflow = true;
                    }
                }
                long ms = System.currentTimeMillis() - start;
                plugin.messages().send(player, "map.created",
                        Placeholder.unparsed("count", String.valueOf(maps.size())),
                        Placeholder.unparsed("grid", grid.label()),
                        Placeholder.unparsed("ms", String.valueOf(ms)));
                if (overflow) {
                    plugin.messages().send(player, "map.inventory-full");
                }
            });
        });
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
        var camera = camera(ctx, player);

        switch (plugin.preview().join(player, camera)) {
            case JOINED -> {
                plugin.preview().refresh(camera);
                plugin.messages().send(player, "preview.started", Placeholder.unparsed("camera", camera.name()));
            }
            case ALREADY -> plugin.messages().send(player, "preview.already");
            case OFFHAND_FULL -> plugin.messages().send(player, "preview.offhand-full");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int previewStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        if (!plugin.preview().leave(player, "preview.ended-self")) {
            plugin.messages().send(player, "preview.not-active");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int unplace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var id = StringArgumentType.getString(ctx, "id");
        var photo = photoManager.findByShortId(player.getUniqueId(), id).orElse(null);
        if (photo == null) {
            plugin.messages().send(player, "map.photo-not-found");
            return 0;
        }
        photoManager.remove(photo);
        plugin.messages().send(player, "map.photo-removed", Placeholder.unparsed("name", photo.name()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Admin only, so it resolves any owner's photo rather than just the caller's.
     */
    private int export(CommandContext<CommandSourceStack> ctx, String fileName) throws CommandSyntaxException {
        var player = requirePlayer(ctx);

        var photo = photoManager.findByShortId(StringArgumentType.getString(ctx, "id")).orElse(null);
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
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        plugin.messages().send(ctx.getSource().getSender(), "general.reloaded");
        return Command.SINGLE_SUCCESS;
    }

    private void runOnMain(Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    // --- suggestions ---

    private CompletableFuture<Suggestions> suggestOwnedNames(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            String prefix = builder.getRemainingLowerCase();
            for (Camera c : manager.ownedBy(player.getUniqueId())) {
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

    // Suggests grids valid for the aspect ratio of the camera named in the previous argument.
    private CompletableFuture<Suggestions> suggestGrids(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        var sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            String name = StringArgumentType.getString(ctx, "name");
            Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
            if (camera != null) {
                String prefix = builder.getRemaining();
                for (GridOption option : GridLayouts.optionsFor(camera.aspectRatio())) {
                    if (option.label().startsWith(prefix)) {
                        builder.suggest(option.label());
                    }
                }
            }
        }
        return builder.buildFuture();
    }

    // Every photo, not just the caller's: the command that uses it is admin only.
    private CompletableFuture<Suggestions> suggestAllPhotoIds(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String prefix = builder.getRemaining();
        for (PlacedPhoto photo : photoManager.all()) {
            if (photo.shortId().startsWith(prefix)) {
                builder.suggest(photo.shortId());
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPhotoIds(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        var sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            String prefix = builder.getRemaining();
            for (PlacedPhoto photo : photoManager.ownedBy(player.getUniqueId())) {
                if (photo.shortId().startsWith(prefix)) {
                    builder.suggest(photo.shortId());
                }
            }
        }
        return builder.buildFuture();
    }

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var sender = ctx.getSource().getSender();
        if (sender instanceof Player player) return player;

        var message = plugin.messages().asVanilla("general.player-only");
        throw new CommandSyntaxException(
                new SimpleCommandExceptionType(message),
                message);
    }

    private Camera camera(CommandContext<CommandSourceStack> ctx, Player player) throws CommandSyntaxException {
        var name = StringArgumentType.getString(ctx, "name");
        var camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            var message = plugin.messages().asVanilla("camera.not-found");
            throw new CommandSyntaxException(
                    new SimpleCommandExceptionType(message),
                    message);
        }
        return camera;
    }
}
