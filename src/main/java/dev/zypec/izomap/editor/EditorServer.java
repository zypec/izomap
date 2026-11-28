package dev.zypec.izomap.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.zypec.editorkit.RelayLink;
import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PhotoFrames;
import dev.zypec.izomap.place.Protection;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.RenderResult;
import dev.zypec.izomap.render.SkyOption;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The web editor's API, served by the plugin itself on {@code editor.bind}:{@code editor.port}.
 *
 * <p>A small JSON API: a top-down map of the world rendered by the plugin's own ray walk,
 * the player's cameras on it, a click to place one, every setting of a camera with its
 * preview, and the shutter. The page reaches it one of two ways. Through the relay in
 * {@code editor.relay} (the editor kit's {@link RelayLink}), from any browser: the relay
 * serves the page, and what the page asks arrives here from this machine with the link's
 * token and the id of the player who asked for it. Or, with no relay, directly: this
 * server serves the page too, and every request carries a session key from the link.</p>
 *
 * <p>Either way a request acts as the player who asked for the link, with their
 * permissions, while they are online. Requests are served on a small pool; anything that
 * touches the world or a camera hops to the main thread and is waited for, and renders run
 * on the render pool as they always do.</p>
 */
public final class EditorServer {

    /** How long a request waits for the main thread or a render before giving up. */
    private static final long TIMEOUT_SECONDS = 120;
    /** How long an export waits: a wide photo at full size takes minutes, and waits in line first. */
    private static final long EXPORT_SECONDS = 600;
    /** Widest picture of a photo the list asks for without the export permission. */
    private static final int THUMBNAIL_PIXELS = 480;
    /** Widest top-down map one request may ask for, in blocks per side. */
    private static final int MAX_MAP_BLOCKS = 1024;
    private static final int MAP_PIXELS = 512;
    /** How far above the ground a camera placed from the map stands. */
    private static final int PLACE_HEIGHT = 12;
    /** The capture screen's side-light suggestion: the sun this far off the camera's facing, this high. */
    private static final float SIDE_LIGHT = 60.0f;
    private static final float SIDE_LIGHT_HEIGHT = 35.0f;
    /** The version of this API, which the page's {@code relay.configure({api})} must name. */
    public static final int API = 1;
    /** The kit's tunnel and interface, served beside the page when there is no relay to serve them. */
    private static final Pattern KIT_PATH = Pattern.compile("kit/(net|ui)/[a-z]+\\.(js|css)");

    private final Izomap plugin;
    private final EditorSessions sessions;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService pool;
    private String address;
    /** What the relay's requests carry; a request with it comes from {@link #relayLink}. */
    private String token;
    private RelayLink relayLink;

    public EditorServer(Izomap plugin) {
        this.plugin = plugin;
        this.sessions = new EditorSessions(plugin);
    }

    /**
     * Starts listening, or does nothing when the editor is switched off. Main thread.
     */
    public void start() {
        stop();
        if (!plugin.config().editorEnabled()) return;

        var bind = plugin.config().editorBind();
        var port = plugin.config().editorPort();
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException | RuntimeException ex) {
            plugin.messages().warn("log.editor-failed",
                    Placeholder.unparsed("address", bind + ":" + port),
                    Placeholder.unparsed("reason", plugin.messages().reason(ex)));
            server = null;
            return;
        }
        pool = Executors.newFixedThreadPool(4, runnable -> {
            var thread = new Thread(runnable, "izomap-editor");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        address = "http://" + ("0.0.0.0".equals(bind) ? "localhost" : bind) + ":" + port + "/";
        var random = new byte[32];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        if (plugin.config().editorRelay().isEmpty())
            plugin.messages().info("log.editor-started", Placeholder.unparsed("address", address));
    }

    public synchronized void stop() {
        if (relayLink != null) {
            relayLink.close();
            relayLink = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /**
     * The link that opens the editor as this player, or {@code null} when it is off. Any
     * older link of theirs stops working. Through the relay, a link opens one tab.
     */
    public synchronized String linkFor(Player player) {
        if (server == null) return null;
        if (plugin.config().editorRelay().isEmpty()) return address + "#" + sessions.open(player);
        var link = relay();
        link.revokeAll(player.getUniqueId());
        return link.link(player.getName(), player.getUniqueId(), 0, Map::of);
    }

    /**
     * The relay, dialed the first time a link is asked for rather than when the server starts,
     * so a server nobody edits from keeps no socket open.
     */
    private RelayLink relay() {
        if (relayLink != null) return relayLink;
        var relay = plugin.config().editorRelay();
        var bind = plugin.config().editorBind();
        var local = "http://" + ("0.0.0.0".equals(bind) ? "127.0.0.1" : bind) + ":" + plugin.config().editorPort();
        relayLink = RelayLink.to(relay)
                .local(local, token)
                .product("Izomap")
                .api(API)
                .say(failure -> {
                    if (failure == null) plugin.messages().info("log.editor-relay", Placeholder.unparsed("address", relay));
                    else plugin.messages().warn("log.editor-relay-lost", Placeholder.unparsed("address", relay),
                            Placeholder.unparsed("reason", failure));
                })
                .open();
        relayLink.idle(plugin.config().editorIdleMinutes());
        return relayLink;
    }

    /**
     * Who a request acts for: the player whose relay link it came through, when it carries
     * the token only the relay link has, otherwise the one whose session key it carries.
     */
    private Player who(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        var bearer = headers.getFirst("Authorization");
        if (bearer != null && token != null && MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
            var id = headers.getFirst(RelayLink.WHO_ID_HEADER);
            if (id == null) return null;
            try {
                return plugin.getServer().getPlayer(UUID.fromString(id));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return sessions.player(headers.getFirst("X-Izomap-Session"));
    }

    // --- routing ---

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/api/")) {
                serveStatic(exchange, path);
                return;
            }
            var player = who(exchange);
            if (player == null) {
                json(exchange, 401, error("The session has ended. Run /izocam editor in the game for a new link."));
                return;
            }
            var method = exchange.getRequestMethod();
            var parts = path.substring("/api/".length()).split("/");
            switch (parts[0]) {
                case "alive" -> json(exchange, 200, new JsonObject());
                case "state" -> json(exchange, 200, onMain(() -> state(player)));
                case "map" -> png(exchange, map(player, query(exchange)));
                case "cameras" -> cameras(exchange, player, method, parts);
                case "photos" -> photos(exchange, player, method, parts);
                default -> json(exchange, 404, error("No such call."));
            }
        } catch (EditorException ex) {
            json(exchange, ex.status, error(ex.getMessage()));
        } catch (Exception ex) {
            json(exchange, 500, error("Something went wrong on the server: " + ex.getMessage()));
        }
    }

    private void cameras(HttpExchange exchange, Player player, String method, String[] parts) throws Exception {
        if (parts.length == 1 && method.equals("POST")) {
            var body = body(exchange);
            json(exchange, 200, place(player, body.get("x").getAsDouble(), body.get("z").getAsDouble()));
            return;
        }
        if (parts.length < 2) throw new EditorException(404, "No such call.");

        var id = parseId(parts[1]);
        if (parts.length == 2 && method.equals("GET")) {
            json(exchange, 200, onMain(() -> describe(player, camera(player, id))));
            return;
        }
        if (parts.length == 2 && method.equals("PATCH")) {
            var body = body(exchange);
            json(exchange, 200, onMain(() -> edit(player, camera(player, id), body)));
            return;
        }
        if (parts.length == 3 && parts[2].equals("preview")) {
            png(exchange, preview(player, id, query(exchange)));
            return;
        }
        if (parts.length == 3 && parts[2].equals("photo") && method.equals("POST")) {
            var body = body(exchange);
            json(exchange, 200, onMain(() -> shoot(player, camera(player, id), body)));
            return;
        }
        if (parts.length == 3 && parts[2].equals("photos")) {
            json(exchange, 200, onMain(() -> photoList(player, camera(player, id))));
            return;
        }
        if (parts.length == 3 && parts[2].equals("reset") && method.equals("POST")) {
            json(exchange, 200, onMain(() -> resetAim(player, camera(player, id))));
            return;
        }
        if (parts.length == 3 && parts[2].equals("export") && method.equals("POST")) {
            var body = body(exchange);
            png(exchange, exportShot(player, id, body));
            return;
        }
        throw new EditorException(404, "No such call.");
    }

    private void photos(HttpExchange exchange, Player player, String method, String[] parts) throws Exception {
        if (parts.length < 2) throw new EditorException(404, "No such call.");
        var id = parseId(parts[1]);
        if (parts.length == 2 && method.equals("PATCH")) {
            var body = body(exchange);
            json(exchange, 200, onMain(() -> reframe(player, photo(player, id), body)));
            return;
        }
        if (parts.length == 3 && parts[2].equals("image")) {
            png(exchange, photoImage(player, id, query(exchange)));
            return;
        }
        throw new EditorException(404, "No such call.");
    }

    // --- the calls ---

    /**
     * Who is editing, where they stand, their cameras and the choices the page offers.
     */
    private JsonObject state(Player player) {
        var out = new JsonObject();
        out.addProperty("player", player.getName());
        var at = player.getLocation();
        out.addProperty("world", at.getWorld().getName());
        out.addProperty("x", at.getX());
        out.addProperty("z", at.getZ());
        var cameras = new JsonArray();
        for (var camera : plugin.cameras().ownedBy(player.getUniqueId()))
            if (camera.anchor().getWorld() == at.getWorld()) cameras.add(describe(player, camera));
        out.add("cameras", cameras);

        out.add("options", options(player));
        out.addProperty("defaultPitch", plugin.config().defaultPitch());
        return out;
    }

    /**
     * Every choice the capture screen offers, each with its name in the server's language and
     * whether this player may use it, so the page shows what is there and why a choice is shut
     * rather than leaving it out.
     */
    private JsonObject options(Player player) {
        var options = new JsonObject();
        var filters = new JsonArray();
        for (var filter : plugin.filters().all())
            filters.add(choice(filter.id(), "filter." + filter.id(), Permissions.filter(player, filter)));
        options.add("filters", filters);
        var styles = new JsonArray();
        for (var style : PhotoStyle.values())
            styles.add(choice(style.name(), "style." + style.name(), Permissions.style(player, style)));
        options.add("styles", styles);
        var skies = new JsonArray();
        for (var sky : SkyOption.values())
            skies.add(choice(sky.name(), "sky." + sky.name(), Permissions.sky(player, sky)));
        options.add("skies", skies);
        var ratios = new JsonArray();
        var grids = new JsonObject();
        for (var ratio : AspectRatio.values()) {
            var entry = new JsonObject();
            entry.addProperty("id", ratio.label());
            entry.addProperty("name", ratio.label());
            entry.addProperty("allowed", Permissions.ratio(player, ratio));
            ratios.add(entry);
            var list = new JsonArray();
            for (var grid : GridLayouts.allowedFor(player, ratio, plugin.config().maxMapTiles())) {
                var option = new JsonObject();
                option.addProperty("id", grid.label());
                option.addProperty("maps", grid.tileCount());
                option.addProperty("width", grid.widthPx());
                option.addProperty("height", grid.heightPx());
                list.add(option);
            }
            grids.add(ratio.label(), list);
        }
        options.add("ratios", ratios);
        options.add("grids", grids);
        var frames = new JsonArray();
        for (var frame : plugin.frames().all())
            frames.add(choice(frame.id(), "frame." + frame.id(), Permissions.frame(player, frame.id())));
        options.add("frames", frames);
        var may = new JsonObject();
        may.addProperty("fog", Permissions.fog(player));
        may.addProperty("focus", Permissions.focus(player));
        may.addProperty("bodies", Permissions.skyBodies(player));
        may.addProperty("export", player.hasPermission(Permissions.EXPORT));
        options.add("may", may);
        // The sun's sliders change the picture only through shadows or the sun drawn in the sky.
        options.addProperty("sunShadow", plugin.config().shading().sunShadow());
        options.addProperty("sunDrawn", plugin.config().skyBodies().sun());
        return options;
    }

    private JsonObject choice(String id, String key, boolean allowed) {
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("name", named(key, id));
        out.addProperty("allowed", allowed);
        return out;
    }

    /** A message's text without its colors: the page draws names in its own look. */
    private String named(String key, String fallback) {
        return PlainTextComponentSerializer.plainText().serialize(plugin.messages().getOr(key, fallback));
    }

    private JsonObject describe(Player player, Camera camera) {
        var out = new JsonObject();
        var anchor = camera.anchor();
        out.addProperty("id", camera.id().toString());
        out.addProperty("name", camera.name());
        out.addProperty("x", anchor.getX());
        out.addProperty("y", anchor.getY());
        out.addProperty("z", anchor.getZ());
        out.addProperty("yaw", camera.camYaw());
        out.addProperty("pitch", camera.camPitch());
        out.addProperty("zoom", camera.zoom());
        out.addProperty("ratio", camera.aspectRatio().label());
        out.addProperty("filter", camera.colorFilter().id());
        out.addProperty("style", camera.style().name());
        out.addProperty("sky", camera.sky().name());
        out.addProperty("bodies", camera.skyBodies());
        out.addProperty("fog", camera.fogEnabled());
        out.addProperty("fogStart", camera.fogStart());
        out.addProperty("fogEnd", camera.fogEnd());
        out.addProperty("focus", camera.focusEnabled());
        out.addProperty("focusDistance", camera.focusDistance());
        out.addProperty("sunYaw", camera.sunYaw());
        out.addProperty("sunPitch", camera.sunPitch());
        out.addProperty("blocks", plugin.config().frameHeight() / camera.zoom());
        out.addProperty("photos", plugin.photos().takenWith(player.getUniqueId(), camera.name()).size());
        // What the picture is of: the point its centre is aimed at and the frame on the ground.
        var frame = plugin.render().frame(camera);
        var target = new JsonObject();
        target.addProperty("x", frame.target().getX());
        target.addProperty("y", frame.target().getY());
        target.addProperty("z", frame.target().getZ());
        out.add("target", target);
        out.addProperty("distance", frame.distance());
        var corners = new JsonArray();
        for (var corner : frame.corners()) {
            var point = new JsonArray();
            point.add(corner.getX());
            point.add(corner.getZ());
            corners.add(point);
        }
        out.add("frame", corners);
        out.addProperty("thirds", camera.thirdsGuide());
        var limit = Math.ceil(plugin.render().focusRange(camera).limit());
        out.addProperty("depthLimit", limit);
        out.add("suggest", suggestions(camera, limit));
        return out;
    }

    /**
     * What the capture screen suggests, read off the camera's last preview: where to focus,
     * where fog should begin and end, and two places for the sun. Empty where the last
     * preview has not said yet how far the frame sees.
     */
    private JsonObject suggestions(Camera camera, double limit) {
        var out = new JsonObject();
        var scene = camera.sceneDepth();
        if (scene != null) {
            var focus = new JsonObject();
            if (scene.center() > 0.0) focus.addProperty("center", Math.min(limit, Math.round(scene.center())));
            focus.addProperty("near", Math.min(limit, Math.round(scene.near())));
            focus.addProperty("median", Math.min(limit, Math.round(scene.median())));
            out.add("focus", focus);
            if (scene.deep()) {
                var fog = new JsonObject();
                fog.addProperty("start", scene.suggestedStart());
                fog.addProperty("end", (int) Math.min(scene.suggestedEnd(), limit));
                out.add("fog", fog);
            }
            var depth = new JsonObject();
            depth.addProperty("near", Math.round(scene.near()));
            depth.addProperty("median", Math.round(scene.median()));
            depth.addProperty("far", Math.round(scene.far()));
            depth.addProperty("sky", Math.round(scene.skyShare() * 100));
            out.add("depth", depth);
        }
        var side = new JsonObject();
        side.addProperty("sunYaw", wrap(camera.camYaw() + SIDE_LIGHT));
        side.addProperty("sunPitch", SIDE_LIGHT_HEIGHT);
        out.add("sideLight", side);
        var framed = new JsonObject();
        framed.addProperty("sunYaw", wrap(camera.camYaw() + 180.0f));
        framed.addProperty("sunPitch", Math.max(5.0f, Math.min(60.0f, 30.0f - camera.camPitch())));
        out.add("sunInFrame", framed);
        return out;
    }

    private static float wrap(float degrees) {
        var turned = degrees % 360.0f;
        return turned < 0 ? turned + 360.0f : turned;
    }

    /**
     * A top-down picture of the world around {@code x, z}, {@code size} blocks across,
     * north at the top. The walk's top-down axes put north at the foot and east on the
     * left, so the picture is turned half round before it is sent.
     */
    private byte[] map(Player player, Map<String, String> query) throws Exception {
        var x = number(query, "x");
        var z = number(query, "z");
        var size = Math.max(16, Math.min(MAX_MAP_BLOCKS, number(query, "size")));
        var world = player.getWorld();
        var future = onMain(() -> {
            var top = world.getMaxHeight() - 1;
            var spec = plugin.render().specFor(new Location(world, x, top, z), 0.0f, 90.0f,
                    (float) (plugin.config().frameHeight() / size), ColorFilter.ORIGINAL, PhotoStyle.SHARP,
                    SkyOption.NONE, false, false, 0.0, 135.0f, 60.0f, false, 0.0, 0.0);
            return plugin.render().capture(spec, MAP_PIXELS, MAP_PIXELS);
        });
        return encode(rotateHalf(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)));
    }

    /**
     * Places a camera above the ground at a spot clicked on the map. The column's chunk is
     * loaded off the main thread first, since reading its height would load it on it.
     */
    private JsonObject place(Player player, double x, double z) throws Exception {
        var world = player.getWorld();
        var loaded = onMain(() -> world.getChunkAtAsync((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, false));
        loaded.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return onMain(() -> {
            var ground = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            var anchor = new Location(world, Math.floor(x) + 0.5, ground + PLACE_HEIGHT, Math.floor(z) + 0.5, 45.0f, 0.0f);
            if (!Protection.mayBuild(player, anchor.getBlock(), plugin.cameras().createCameraItem()))
                throw new EditorException(403, "A protection plugin does not let you build there.");

            var name = "web-" + (plugin.cameras().ownedCount(player.getUniqueId()) + 1);
            var camera = plugin.cameras().create(player, name, anchor);
            if (camera == null)
                throw new EditorException(409, "You have as many cameras as you may have.");
            return describe(player, camera);
        });
    }

    /**
     * Applies what the page changed. Only the fields present are touched.
     */
    private JsonObject edit(Player player, Camera camera, JsonObject body) {
        // Aimed at a point rather than placed: turning, tilting or moving the target keeps
        // the camera the same distance from what it shows and moves it to suit.
        var before = plugin.render().frame(camera);
        var orbit = body.has("orbit") && body.get("orbit").getAsBoolean();
        if (body.has("yaw")) camera.camYaw(body.get("yaw").getAsFloat());
        if (body.has("pitch")) camera.camPitch(body.get("pitch").getAsFloat());
        if (body.has("targetX") && body.has("targetZ")) {
            var world = camera.anchor().getWorld();
            var tx = body.get("targetX").getAsDouble();
            var tz = body.get("targetZ").getAsDouble();
            var ty = world != null && world.isChunkLoaded((int) Math.floor(tx) >> 4, (int) Math.floor(tz) >> 4)
                    ? world.getHighestBlockYAt((int) Math.floor(tx), (int) Math.floor(tz)) + 1.0
                    : before.target().getY();
            moveTo(player, camera, plugin.render().standFor(new Location(world, tx, ty, tz),
                    camera.camYaw(), camera.camPitch(), before.distance()));
        } else if (orbit && (body.has("yaw") || body.has("pitch"))) {
            var target = before.target();
            moveTo(player, camera, plugin.render().standFor(
                    new Location(camera.anchor().getWorld(), target.getX(), target.getY(), target.getZ()),
                    camera.camYaw(), camera.camPitch(), before.distance()));
        }
        if (body.has("zoom")) camera.zoom(body.get("zoom").getAsFloat());
        if (body.has("ratio")) {
            var ratio = AspectRatio.fromLabel(body.get("ratio").getAsString());
            if (ratio != null && Permissions.ratio(player, ratio)) camera.aspectRatio(ratio);
        }
        if (body.has("filter")) camera.colorFilter(plugin.filters().byId(body.get("filter").getAsString(), camera.colorFilter()));
        if (body.has("style")) camera.style(PhotoStyle.fromString(body.get("style").getAsString(), camera.style()));
        if (body.has("sky")) camera.sky(SkyOption.fromString(body.get("sky").getAsString(), camera.sky()));
        if (body.has("bodies")) camera.skyBodies(body.get("bodies").getAsBoolean());
        if (body.has("fog")) camera.fogEnabled(body.get("fog").getAsBoolean());
        if (body.has("fogStart") || body.has("fogEnd"))
            camera.fogRange(body.has("fogStart") ? body.get("fogStart").getAsFloat() : camera.fogStart(),
                    body.has("fogEnd") ? body.get("fogEnd").getAsFloat() : camera.fogEnd());
        if (body.has("focus")) camera.focusEnabled(body.get("focus").getAsBoolean());
        if (body.has("focusDistance")) camera.focusDistance(body.get("focusDistance").getAsFloat());
        if (body.has("sunYaw")) camera.sunYaw(body.get("sunYaw").getAsFloat());
        if (body.has("sunPitch")) camera.sunPitch(body.get("sunPitch").getAsFloat());
        if (body.has("thirds")) camera.thirdsGuide(body.get("thirds").getAsBoolean());
        if (body.has("x") || body.has("y") || body.has("z")) {
            var anchor = camera.anchor();
            if (body.has("x")) anchor.setX(body.get("x").getAsDouble());
            if (body.has("y")) anchor.setY(body.get("y").getAsDouble());
            if (body.has("z")) anchor.setZ(body.get("z").getAsDouble());
            moveTo(player, camera, anchor);
        }
        plugin.cameras().applyAndPersist(camera);
        plugin.preview().refresh(camera);
        return describe(player, camera);
    }

    private void moveTo(Player player, Camera camera, Location anchor) {
        anchor.setYaw(camera.camYaw());
        if (!anchor.getBlock().equals(camera.anchor().getBlock())
            && !Protection.mayBuild(player, anchor.getBlock(), plugin.cameras().createCameraItem()))
            throw new EditorException(403, "A protection plugin does not let you build there.");
        plugin.cameras().reposition(camera, anchor);
    }

    private byte[] preview(Player player, UUID id, Map<String, String> query) throws Exception {
        var width = (int) Math.max(64, Math.min(1024, number(query, "w")));
        var frameId = query.get("frame");
        var gridLabel = query.get("grid");
        var future = onMain(() -> {
            var camera = camera(player, id);
            var ratio = camera.aspectRatio().value();
            return plugin.render().capture(plugin.render().specFor(camera), width, Math.max(1, (int) Math.round(width / ratio)),
                    null, null, null, camera::sceneDepth);
        });
        var image = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var frame = frameId == null || frameId.isEmpty() ? null : plugin.frames().byId(frameId);
        if (frame == null || !Permissions.frame(player, frame.id())) return encode(image);

        // Drawn at the photo's own size and shrunk back, so the frame is as thick against the
        // picture here as it will be on the photo.
        var camera = plugin.cameras().byId(id);
        var grid = camera == null || gridLabel == null ? null : GridLayouts.find(camera.aspectRatio(), gridLabel);
        if (grid == null) return encode(PhotoFrames.draw(image, frame));
        var full = PhotoFrames.draw(resized(image, grid.widthPx(), grid.heightPx(), false), frame);
        return encode(resized(full, image.width(), image.height(), true));
    }

    /** The image at another size: blocky when grown, smoothed when shrunk. */
    private static RenderResult resized(RenderResult image, int width, int height, boolean smooth) {
        if (image.width() == width && image.height() == height) return image;
        var source = image.toImage();
        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        var pixels = out.getRGB(0, 0, width, height, null, 0, width);
        return new RenderResult(width, height, pixels);
    }

    /**
     * The camera's photos, newest last, as the list on the capture screen has them.
     */
    private JsonArray photoList(Player player, Camera camera) {
        var out = new JsonArray();
        for (var photo : plugin.photos().takenWith(camera.owner(), camera.name())) out.add(describe(photo));
        return out;
    }

    private JsonObject describe(Photo photo) {
        var out = new JsonObject();
        out.addProperty("id", photo.id().toString());
        out.addProperty("shortId", photo.shortId());
        out.addProperty("name", photo.name());
        out.addProperty("grid", photo.grid().label());
        out.addProperty("width", photo.grid().widthPx());
        out.addProperty("height", photo.grid().heightPx());
        out.addProperty("maps", photo.grid().tileCount());
        out.addProperty("placed", photo.isPlaced());
        if (photo.frameId() != null) out.addProperty("frame", photo.frameId());
        out.addProperty("frameEditable", photo.frameEditable());
        return out;
    }

    private Photo photo(Player player, UUID id) {
        var photo = plugin.photos().byId(id).orElseThrow(() -> new EditorException(404, "That photo is gone."));
        if (!photo.owner().equals(player.getUniqueId()) && !player.hasPermission(Permissions.ADMIN))
            throw new EditorException(403, "That is not your photo.");
        return photo;
    }

    /**
     * A photo as it hangs, frame and all: at full size to save or copy, which takes the
     * export permission, or shrunk to {@code w} for the list.
     */
    private byte[] photoImage(Player player, UUID id, Map<String, String> query) throws Exception {
        var width = query.containsKey("w") ? (int) Math.max(32, Math.min(THUMBNAIL_PIXELS, number(query, "w"))) : 0;
        var photo = onMain(() -> photo(player, id));
        if (width == 0 && !player.hasPermission(Permissions.EXPORT))
            throw new EditorException(403, "Saving photos takes the izomap.export permission.");
        var image = plugin.photos().image(photo).get(EXPORT_SECONDS, TimeUnit.SECONDS);
        if (width == 0 || width >= image.width()) return encode(image);
        return encode(resized(image, width, Math.max(1, (int) Math.round((double) image.height() * width / image.width())), true));
    }

    /** Puts a frame on a photo, changes it, or takes it off ({@code frame: ""}). */
    private JsonObject reframe(Player player, Photo photo, JsonObject body) {
        if (!body.has("frame")) throw new EditorException(400, "Which frame?");
        if (!photo.frameEditable()) throw new EditorException(409, "This photo's frame is part of the photo; it cannot be changed.");
        var wanted = body.get("frame").isJsonNull() ? "" : body.get("frame").getAsString();
        if (!wanted.isEmpty()) {
            var frame = plugin.frames().byId(wanted);
            if (frame == null) throw new EditorException(404, "There is no such frame.");
            if (!Permissions.frame(player, frame.id())) throw new EditorException(403, "That frame is not one you can use.");
        }
        plugin.photos().applyFrame(player, photo, wanted.isEmpty() ? null : wanted);
        return describe(plugin.photos().byId(photo.id()).orElse(photo));
    }

    /** What the capture screen's Reset aim does: facing the player's way, the default tilt, zoom 1. */
    private JsonObject resetAim(Player player, Camera camera) {
        camera.camYaw(player.getLocation().getYaw());
        camera.camPitch((float) plugin.config().defaultPitch());
        camera.zoom(1.0f);
        plugin.cameras().applyAndPersist(camera);
        plugin.preview().refresh(camera);
        return describe(player, camera);
    }

    /**
     * The camera shot at a layout's full size with a frame, as a PNG for the page to save or
     * copy, filing no photo. Waits in the capture queue like any photo.
     */
    private byte[] exportShot(Player player, UUID id, JsonObject body) throws Exception {
        if (!player.hasPermission(Permissions.EXPORT))
            throw new EditorException(403, "Saving photos takes the izomap.export permission.");
        var frameId = body.has("frame") && !body.get("frame").isJsonNull() && !body.get("frame").getAsString().isEmpty()
                ? body.get("frame").getAsString() : null;
        var future = onMain(() -> {
            var camera = camera(player, id);
            var grid = body.has("grid") ? GridLayouts.find(camera.aspectRatio(), body.get("grid").getAsString()) : null;
            if (grid == null) grid = GridLayouts.optionsFor(camera.aspectRatio()).getFirst();
            return plugin.photos().shootForExport(player, camera, grid, frameId);
        });
        try {
            return encode(future.get(EXPORT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException ex) {
            var cause = ex.getCause();
            if (cause instanceof IllegalStateException refused) throw new EditorException(409, refused.getMessage());
            throw ex;
        }
    }

    /**
     * Takes the photo as the player would from the capture screen; the game says how it
     * went, in chat, as it always does.
     */
    private JsonObject shoot(Player player, Camera camera, JsonObject body) {
        var name = body.has("name") && !body.get("name").getAsString().isBlank() ? body.get("name").getAsString() : camera.name();
        var grid = body.has("grid") ? GridLayouts.find(camera.aspectRatio(), body.get("grid").getAsString()) : null;
        if (grid == null) grid = GridLayouts.optionsFor(camera.aspectRatio()).getFirst();
        plugin.photos().capture(player, camera, name, grid);
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("message", "Taking the photo; the game will say when it is in the camera's list.");
        return out;
    }

    // --- helpers ---

    private Camera camera(Player player, UUID id) {
        var camera = plugin.cameras().byId(id);
        if (camera == null) throw new EditorException(404, "That camera is gone.");
        if (!Permissions.edit(player, camera)) throw new EditorException(403, "That is not your camera.");
        return camera;
    }

    private <T> T onMain(Supplier<T> work) throws Exception {
        var future = new CompletableFuture<T>();
        plugin.runOnMain(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof EditorException editor) throw editor;
            throw ex;
        }
    }

    private static RenderResult rotateHalf(RenderResult image) {
        var source = image.argb();
        var turned = new int[source.length];
        for (var i = 0; i < source.length; i++)
            turned[source.length - 1 - i] = source[i];
        return new RenderResult(image.width(), image.height(), turned);
    }

    private static byte[] encode(RenderResult image) throws IOException {
        var buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, image.width(), image.height(), image.argb(), 0, image.width());
        var out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        var file = path.equals("/") ? "index.html" : path.substring(1);
        var kit = KIT_PATH.matcher(file).matches();
        if (!kit && (file.contains("..") || file.contains("/"))) {
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = plugin.getResource(kit ? "editorkit/web/" + file.substring("kit/".length()) : "editor/" + file)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            var type = file.endsWith(".html") ? "text/html; charset=utf-8"
                    : file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css"
                    : file.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            send(exchange, 200, type, in.readAllBytes());
        }
    }

    private JsonObject body(HttpExchange exchange) throws IOException {
        var text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            var parsed = gson.fromJson(text, JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (RuntimeException ex) {
            throw new EditorException(400, "The request was not valid JSON.");
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        var raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (var pair : raw.split("&")) {
            var at = pair.indexOf('=');
            if (at > 0)
                out.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static double number(Map<String, String> query, String key) {
        try {
            return Double.parseDouble(query.getOrDefault(key, ""));
        } catch (NumberFormatException ex) {
            throw new EditorException(400, "Missing or bad number: " + key);
        }
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new EditorException(404, "No such camera.");
        }
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void png(HttpExchange exchange, byte[] image) throws IOException {
        send(exchange, 200, "image/png", image);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static JsonObject error(String message) {
        var out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    /**
     * A refusal the page shows as it is.
     */
    private static final class EditorException extends RuntimeException {
        private final int status;

        private EditorException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
