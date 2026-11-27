package dev.zypec.izomap.dev;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.map.PhotoExporter;
import dev.zypec.izomap.render.CaptureSpec;
import dev.zypec.izomap.render.ChunkCache;
import dev.zypec.izomap.render.ColorPipeline;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.RenderCancellation;
import dev.zypec.izomap.render.SkyOption;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
                        .then(Commands.literal("palettes").executes(shots::palettes))
                        .then(Commands.literal("try")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(shots::tryMatrix)))
                        .then(Commands.literal("clicks")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                                .executes(shots::clicks))))
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

    /**
     * Plays a burst of camera clicks against a view the way the live preview handles
     * them: a 128 pixel render per click, each turning the view a step, a render still
     * copying cancelled by the next click and one already walking let finish, chunk
     * copies kept between clicks. Logs when each frame lands, so the preview's delay can
     * be measured without a player.
     */
    private int clicks(CommandContext<CommandSourceStack> ctx) {
        var name = StringArgumentType.getString(ctx, "name");
        var count = IntegerArgumentType.getInteger(ctx, "count");
        var shot = load().getConfigurationSection("shots." + name);
        if (shot == null) {
            plugin.getLogger().warning("dev: no such shot " + name);
            return 0;
        }
        var cache = new ChunkCache();
        var state = new ClickState();
        var started = System.nanoTime();
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (state.clicks >= count) {
                task.cancel();
                return;
            }
            var click = state.clicks++;
            if (state.running != null) {
                if (state.running.walking()) {
                    state.skipped++;
                    return;
                }
                state.running.cancel();
            }
            var cancellation = new RenderCancellation();
            state.running = cancellation;
            var spec = specOf(shot, (float) (shot.getDouble("yaw", 45.0) + click * plugin.config().angleStep()));
            if (spec == null) return;
            var clickedAt = System.nanoTime();
            plugin.render().capture(spec, 128, 72, null, cancellation, cache, null).whenComplete((result, error) -> {
                plugin.runOnMain(() -> {
                    if (state.running == cancellation) state.running = null;
                });
                if (error != null) return;
                state.frames++;
                plugin.getLogger().info(String.format(Locale.ROOT, "dev: click %d frame after %.0f ms (t=%.0f ms)",
                        click, (System.nanoTime() - clickedAt) / 1e6, (System.nanoTime() - started) / 1e6));
            });
        }, 1L, CLICK_TICKS);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Ticks between two clicks of {@link #clicks}: four clicks a second.
     */
    private static final long CLICK_TICKS = 5L;

    private static final class ClickState {
        private int clicks;
        private int frames;
        private int skipped;
        private RenderCancellation running;
    }

    /**
     * Writes {@code exports/filters.json}: for every filter, what it turns each palette
     * colour into. The site's try-it page shows a photo through any filter with it.
     */
    private int palettes(CommandContext<CommandSourceStack> ctx) {
        var json = new StringBuilder("{");
        var firstFilter = true;
        for (var filter : plugin.filters().all()) {
            json.append(firstFilter ? "" : ",").append('"').append(filter.id()).append("\":{");
            firstFilter = false;
            var firstColor = true;
            for (var entry : ColorPipeline.paletteTable(filter).entrySet()) {
                json.append(firstColor ? "" : ",")
                        .append(String.format(Locale.ROOT, "\"%06x\":\"%06x\"", entry.getKey(), entry.getValue()));
                firstColor = false;
            }
            json.append('}');
        }
        json.append('}');
        var file = new File(plugin.getDataFolder(), "exports/filters.json");
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), json.toString());
            plugin.getLogger().info("dev: wrote " + file.getName());
        } catch (IOException ex) {
            plugin.getLogger().warning("dev: filters.json failed: " + ex.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Shoots a view in every combination the site's try-it page offers: each style, each
     * sky with and without its bodies, fog and depth of field on and off. Named
     * {@code try-<style>-<sky>[-b][-fog][-dof]}; a transparent sky takes neither bodies
     * nor fog. Filters and ratios are left to the page.
     */
    private int tryMatrix(CommandContext<CommandSourceStack> ctx) {
        var name = StringArgumentType.getString(ctx, "name");
        var file = load();
        var base = file.getConfigurationSection("shots." + name);
        if (base == null) {
            plugin.getLogger().warning("dev: no such shot " + name);
            return 0;
        }
        List<String> names = new ArrayList<>();
        var matrix = new YamlConfiguration();
        for (var style : List.of("SHARP", "FAST", "SHADER")) {
            for (var sky : List.of("NONE", "DAWN", "DAY", "DUSK", "NIGHT")) {
                var drawn = !sky.equals("NONE");
                for (var bodies : drawn ? List.of(false, true) : List.of(false)) {
                    for (var fog : drawn ? List.of(false, true) : List.of(false)) {
                        for (var focus : List.of(false, true)) {
                            var id = "try-" + style.toLowerCase(Locale.ROOT) + "-" + sky.toLowerCase(Locale.ROOT)
                                     + (bodies ? "-b" : "") + (fog ? "-fog" : "") + (focus ? "-dof" : "");
                            var shot = matrix.createSection("shots." + id);
                            for (var key : base.getKeys(false))
                                if (!List.of("fog-start", "fog-end", "focus", "style", "sky", "bodies").contains(key))
                                    shot.set(key, base.get(key));
                            shot.set("style", style);
                            shot.set("sky", sky);
                            shot.set("bodies", bodies);
                            if (fog) {
                                shot.set("fog-start", base.getDouble("fog-start", 128));
                                shot.set("fog-end", base.getDouble("fog-end", 2048));
                            }
                            if (focus) shot.set("focus", base.getDouble("focus", 64));
                            names.add(id);
                        }
                    }
                }
            }
        }
        // A shot already on disk is kept, so a stopped run picks up where it left off.
        var exports = new File(plugin.getDataFolder(), "exports");
        names.removeIf(id -> new File(exports, id + ".png").exists());
        plugin.getLogger().info("dev: " + names.size() + " try shots to take");
        if (names.isEmpty()) return Command.SINGLE_SUCCESS;

        // The first fills the cache alone; the rest render side by side from it.
        var cache = ChunkCache.keeping();
        var queue = new java.util.ArrayDeque<>(names);
        var started = System.nanoTime();
        var first = queue.poll();
        shoot(first, matrix.getConfigurationSection("shots." + first), cache).whenComplete((ignored, failure) ->
                plugin.runOnMain(() -> {
                    for (var lane = 0; lane < PARALLEL_SHOTS; lane++)
                        lane(matrix, queue, cache, started);
                }));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Photos the try-it matrix renders at once once the world is copied. Each walk splits
     * over the render pool, so a few side by side keep every core busy while one of
     * them is between passes.
     */
    private static final int PARALLEL_SHOTS = 3;

    private void lane(YamlConfiguration matrix, java.util.ArrayDeque<String> queue, ChunkCache cache, long started) {
        var name = queue.poll();
        if (name == null) {
            plugin.getLogger().info(String.format(Locale.ROOT, "dev: try lane done after %.0f s",
                    (System.nanoTime() - started) / 1e9));
            return;
        }
        shoot(name, matrix.getConfigurationSection("shots." + name), cache).whenComplete((ignored, failure) -> {
            if (failure != null)
                plugin.getLogger().warning("dev: " + name + " failed: " + failure.getMessage());
            plugin.runOnMain(() -> lane(matrix, queue, cache, started));
        });
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
        return shoot(name, shot, null);
    }

    private CompletableFuture<?> shoot(String name, ConfigurationSection shot, ChunkCache cache) {
        if (shot == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("no such shot"));

        var world = plugin.getServer().getWorld(shot.getString("world", "world"));
        if (world == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("world is not loaded"));

        var spec = specOf(shot, (float) shot.getDouble("yaw", 45.0));
        var render = plugin.render();

        // A grid of maps, or a size in pixels for pictures that are not printed.
        var size = shot.getString("size");
        var grid = shot.getString("grid", "4x2").split("x");
        var width = size != null ? Integer.parseInt(size.split("x")[0]) : Integer.parseInt(grid[0]) * 128;
        var height = size != null ? Integer.parseInt(size.split("x")[1]) : Integer.parseInt(grid[1]) * 128;

        var started = System.nanoTime();
        return render.capture(spec, width, height, null, null, cache, null)
                .thenCompose(result -> exporter.write(result, name, name))
                .thenAccept(path -> plugin.getLogger().info("dev: " + name + " -> " + path.getFileName()
                        + " (" + width + "x" + height + ", " + (System.nanoTime() - started) / 1_000_000 + " ms)"));
    }

    /**
     * The view a shot describes, turned to {@code yaw}; {@code null} when its world is not
     * loaded.
     */
    private CaptureSpec specOf(ConfigurationSection shot, float yaw) {
        var world = plugin.getServer().getWorld(shot.getString("world", "world"));
        if (world == null)
            return null;

        var anchor = new Location(world, shot.getDouble("x"), shot.getDouble("y"), shot.getDouble("z"));
        return plugin.render().specFor(anchor,
                yaw, (float) shot.getDouble("pitch", 30.0),
                (float) shot.getDouble("zoom", 1.0),
                plugin.filters().byId(shot.getString("filter", "ORIGINAL"), plugin.filters().all().getFirst()),
                PhotoStyle.valueOf(shot.getString("style", "SHARP").toUpperCase(Locale.ROOT)),
                SkyOption.valueOf(shot.getString("sky", "DAY").toUpperCase(Locale.ROOT)),
                shot.getBoolean("bodies", true),
                shot.contains("focus"), shot.getDouble("focus", 0.0),
                (float) shot.getDouble("sun-yaw", plugin.config().shading().sunYaw()),
                (float) shot.getDouble("sun-pitch", plugin.config().shading().sunPitch()),
                shot.contains("fog-start"),
                shot.getDouble("fog-start", plugin.config().defaultFogStart()),
                shot.getDouble("fog-end", plugin.config().defaultFogEnd()));
    }

    private YamlConfiguration load() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "dev-shots.yml"));
    }
}
