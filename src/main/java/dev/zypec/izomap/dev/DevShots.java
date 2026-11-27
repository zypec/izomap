package dev.zypec.izomap.dev;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.map.PhotoExporter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Shoots the views listed in {@code plugins/Izomap/dev-shots.yml} straight to PNG, with no
 * camera and no player: {@code /izodev shot <name>} or {@code /izodev shots}.
 *
 * <p>Registered only on a server started with {@code -Dizomap.dev=true}, which is what
 * {@code ./gradlew runServer} passes. It exists so the sample photos in the docs can be
 * produced and reproduced from the console; a release server never has the command.</p>
 *
 * <p>The file is read again on every call, so a view can be tuned and reshot without a
 * reload.</p>
 */
public final class DevShots {

    public static final String PROPERTY = "izomap.dev";

    private final Izomap plugin;
    private final PhotoExporter exporter;

    private DevShots(Izomap plugin) {
        this.plugin = plugin;
        this.exporter = new PhotoExporter(plugin);
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void register(Izomap plugin) {
        var shots = new DevShots(plugin);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(Commands.literal("izodev")
                        .requires(source -> source.getSender().isOp())
                        .then(Commands.literal("shots").executes(shots::shootAll))
                        .then(Commands.literal("shot")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(shots::shootOne)))
                        .build(), "Development tools", List.of()));
        plugin.getLogger().warning("Development mode: /izodev is registered");
    }

    private int shootAll(CommandContext<CommandSourceStack> ctx) {
        var file = load();
        var section = file.getConfigurationSection("shots");
        if (section == null) {
            plugin.getLogger().warning("dev-shots.yml has no shots");
            return 0;
        }
        List<String> names = new ArrayList<>(section.getKeys(false));
        chain(file, names, 0);
        return Command.SINGLE_SUCCESS;
    }

    private int shootOne(CommandContext<CommandSourceStack> ctx) {
        chain(load(), List.of(StringArgumentType.getString(ctx, "name")), 0);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * One shot at a time: each copies its chunks on the main thread, and several at once
     * would hold it for all of them together.
     */
    private void chain(YamlConfiguration file, List<String> names, int index) {
        if (index >= names.size()) {
            plugin.getLogger().info("dev: " + names.size() + " shot(s) done");
            return;
        }
        var name = names.get(index);
        shoot(name, file.getConfigurationSection("shots." + name)).whenComplete((ignored, failure) -> {
            if (failure != null)
                plugin.getLogger().warning("dev: " + name + " failed: " + failure.getMessage());
            plugin.runOnMain(() -> chain(file, names, index + 1));
        });
    }

    private CompletableFuture<?> shoot(String name, ConfigurationSection shot) {
        if (shot == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("no such shot"));

        var world = plugin.getServer().getWorld(shot.getString("world", "world"));
        if (world == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("world is not loaded"));

        var anchor = new Location(world, shot.getDouble("x"), shot.getDouble("y"), shot.getDouble("z"));
        var render = plugin.render();
        var spec = render.specFor(anchor,
                (float) shot.getDouble("yaw", 45.0), (float) shot.getDouble("pitch", 30.0),
                (float) shot.getDouble("zoom", 1.0),
                plugin.filters().byId(shot.getString("filter", "ORIGINAL"), plugin.filters().all().getFirst()),
                PhotoStyle.valueOf(shot.getString("style", "SHARP").toUpperCase(Locale.ROOT)),
                SkyOption.valueOf(shot.getString("sky", "DAY").toUpperCase(Locale.ROOT)),
                shot.getBoolean("bodies", true),
                shot.contains("focus"), shot.getDouble("focus", 0.0),
                (float) shot.getDouble("sun-yaw", plugin.config().shading().sunYaw()),
                (float) shot.getDouble("sun-pitch", plugin.config().shading().sunPitch()));

        var grid = shot.getString("grid", "4x2").split("x");
        var width = Integer.parseInt(grid[0]) * 128;
        var height = Integer.parseInt(grid[1]) * 128;

        var started = System.nanoTime();
        return render.capture(spec, width, height)
                .thenCompose(result -> exporter.write(result, name, name))
                .thenAccept(path -> plugin.getLogger().info("dev: " + name + " -> " + path.getFileName()
                        + " (" + width + "x" + height + ", " + (System.nanoTime() - started) / 1_000_000 + " ms)"));
    }

    private YamlConfiguration load() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "dev-shots.yml"));
    }
}
