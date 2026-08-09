package dev.zypec.izomap.camera;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.ImageSlicer;
import dev.zypec.izomap.map.MapService;
import dev.zypec.izomap.map.MapTile;
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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;

/**
 * {@code /izocam} Brigadier komut ağacı.
 *
 * <ul>
 *   <li>{@code create <ad>} – bakış yönünün önüne kamera kurar.</li>
 *   <li>{@code remove <ad> | all} – kamera(ları) siler.</li>
 *   <li>{@code list cameras | photos} – kameraları/fotoğrafları listeler.</li>
 *   <li>{@code item} – kamera yerleştirme eşyası verir.</li>
 *   <li>{@code ratio <ad> <oran>} – en-boy oranını ayarlar (ör. 4:3).</li>
 *   <li>{@code maps <ad> <grid>} – harita eşyalarını envantere verir.</li>
 *   <li>{@code open <ad>} – fotoğraf Dialog'unu açar.</li>
 *   <li>{@code unplace <id> | all} – yerleştirilmiş fotoğraf(lar)ı kaldırır.</li>
 *   <li>{@code cleanup} – kırılmış/kayıp çerçeveli fotoğraf kayıtlarını temizler.</li>
 *   <li>{@code reload} – yapılandırmayı yeniden yükler (admin).</li>
 * </ul>
 */
public final class CameraCommand {

    private static final String PERM = "izomap.camera";
    private static final String PERM_ADMIN = "izomap.admin";

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

    /** Komutu eklentinin yaşam döngüsüne kaydeder. */
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
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(PERM_ADMIN))
                        .executes(this::reload))
                .build();
    }

    private int create(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (manager.byOwnerAndName(player.getUniqueId(), name).isPresent()) {
            plugin.messages().send(player, "camera.name-taken", Placeholder.unparsed("name", name));
            return 0;
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        Location anchor = eye.add(direction.multiply(2.0));
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        Camera camera = manager.create(player, name, anchor);
        if (camera == null) {
            plugin.messages().send(player, "camera.limit-reached",
                    Placeholder.unparsed("limit", String.valueOf(plugin.config().maxCamerasPerPlayer())));
            return 0;
        }
        plugin.messages().send(player, "camera.created", Placeholder.unparsed("name", name));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            plugin.messages().send(player, "camera.not-found");
            return 0;
        }
        manager.remove(camera);
        plugin.messages().send(player, "camera.removed", Placeholder.unparsed("name", name));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int move(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            plugin.messages().send(player, "camera.not-found");
            return 0;
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        Location anchor = eye.add(direction.multiply(2.0));
        anchor.setYaw(player.getLocation().getYaw());
        anchor.setPitch(0.0f);

        manager.move(camera, anchor);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "camera.moved", Placeholder.unparsed("name", name));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int removeAllCameras(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        int count = manager.removeAllOwned(player.getUniqueId());
        plugin.messages().send(player, "camera.removed-all", Placeholder.unparsed("count", String.valueOf(count)));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int removeAllPhotos(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        int count = photoManager.removeAllOwned(player.getUniqueId());
        plugin.messages().send(player, "map.photos-removed-all", Placeholder.unparsed("count", String.valueOf(count)));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int listCameras(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        List<Camera> cameras = manager.ownedBy(player.getUniqueId());
        if (cameras.isEmpty()) {
            plugin.messages().send(player, "camera.list-empty");
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        player.sendMessage(plugin.messages().get("camera.list-header",
                Placeholder.unparsed("count", String.valueOf(cameras.size()))));
        for (Camera c : cameras) {
            player.sendMessage(plugin.messages().get("camera.list-entry",
                    Placeholder.unparsed("name", c.name()),
                    Placeholder.unparsed("ratio", c.aspectRatio().label()),
                    Placeholder.unparsed("scale", String.format(Locale.ROOT, "%.2f", c.zoom()))));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int listPhotos(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        List<PlacedPhoto> owned = photoManager.ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            plugin.messages().send(player, "map.photos-empty");
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        player.sendMessage(plugin.messages().get("map.photos-header",
                Placeholder.unparsed("count", String.valueOf(owned.size()))));
        for (PlacedPhoto photo : owned) {
            player.sendMessage(plugin.messages().get("map.photos-entry",
                    Placeholder.unparsed("name", photo.name()),
                    Placeholder.unparsed("id", photo.shortId()),
                    Placeholder.unparsed("grid", photo.grid().label())));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int giveItem(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        player.getInventory().addItem(manager.createCameraItem());
        plugin.messages().send(player, "camera.given-item");
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int setRatio(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            plugin.messages().send(player, "camera.not-found");
            return 0;
        }
        AspectRatio ratio = AspectRatio.fromLabel(StringArgumentType.getString(ctx, "ratio").trim());
        if (ratio == null) {
            plugin.messages().send(player, "photo.invalid-ratio");
            return 0;
        }
        camera.aspectRatio(ratio);
        manager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "photo.ratio-set", Placeholder.unparsed("ratio", ratio.label()));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int makeMaps(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            plugin.messages().send(player, "camera.not-found");
            return 0;
        }
        GridOption grid = GridOption.parse(StringArgumentType.getString(ctx, "grid"));
        if (grid == null || !GridLayouts.isValid(camera.aspectRatio(), grid)) {
            plugin.messages().send(player, "map.invalid-grid", Placeholder.unparsed("ad", name));
            return 0;
        }

        plugin.messages().send(player, "photo.capturing");
        long start = System.currentTimeMillis();
        World world = camera.anchor().getWorld();

        renderService.capture(camera, grid.widthPx(), grid.heightPx()).whenComplete((result, error) -> {
            if (error != null || result == null || world == null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
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
                List<MapTile> tiles = ImageSlicer.slice(result, grid);
                List<ItemStack> maps = mapService.createMapItems(world, tiles);
                boolean overflow = false;
                for (ItemStack map : maps) {
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
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int openDialog(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Camera camera = manager.byOwnerAndName(player.getUniqueId(), name).orElse(null);
        if (camera == null) {
            plugin.messages().send(player, "camera.not-found");
            return 0;
        }
        dialogs.openCaptureDialog(player, camera);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int unplace(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        PlacedPhoto photo = photoManager.findByShortId(player.getUniqueId(), id).orElse(null);
        if (photo == null) {
            plugin.messages().send(player, "map.photo-not-found");
            return 0;
        }
        photoManager.remove(photo);
        plugin.messages().send(player, "map.photo-removed", Placeholder.unparsed("name", photo.name()));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int cleanup(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return 0;
        }
        int removed = photoManager.cleanupOwned(player.getUniqueId());
        plugin.messages().send(player, "map.cleaned", Placeholder.unparsed("count", String.valueOf(removed)));

        // Kaydı silinmiş ama dünyada kalmış kamera entity'leri (yetimler). Yalnızca
        // yüklü chunk'lardakiler görülebilir, yani oyuncunun çevresi temizlenir.
        int orphans = manager.removeOrphanEntities(player.getWorld());
        if (orphans > 0) {
            plugin.messages().send(player, "camera.orphans-cleaned",
                    Placeholder.unparsed("count", String.valueOf(orphans)));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        plugin.messages().send(ctx.getSource().getSender(), "general.reloaded");
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void runOnMain(Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    // --- öneriler ---

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOwnedNames(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
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

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRatios(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining();
        for (AspectRatio ratio : AspectRatio.values()) {
            if (ratio.label().startsWith(prefix)) {
                builder.suggest(ratio.label());
            }
        }
        return builder.buildFuture();
    }

    // Önceki "name" argümanından kameranın oranına göre geçerli grid'leri önerir.
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestGrids(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
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

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPhotoIds(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CommandSender sender = ctx.getSource().getSender();
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

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "general.player-only");
        return null;
    }
}
