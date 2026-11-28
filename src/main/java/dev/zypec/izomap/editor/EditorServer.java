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
import dev.zypec.izomap.place.Protection;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.RenderResult;
import dev.zypec.izomap.render.SkyOption;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
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
    /** Widest top-down map one request may ask for, in blocks per side. */
    private static final int MAX_MAP_BLOCKS = 1024;
    private static final int MAP_PIXELS = 512;
    /** How far above the ground a camera placed from the map stands. */
    private static final int PLACE_HEIGHT = 12;
    /** The version of this API, which the page's {@code relay.configure({api})} must name. */
    public static final int API = 1;
    /** The kit's tunnel, served beside the page when there is no relay to serve it. */
    private static final String KIT_PATH = "kit/net/relay.js";
    private static final String KIT_RESOURCE = "editorkit/web/net/relay.js";

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

        var options = new JsonObject();
        options.add("filters", gson.toJsonTree(plugin.filters().all().stream().map(ColorFilter::id).toList()));
        options.add("styles", gson.toJsonTree(List.of(PhotoStyle.values()).stream().map(Enum::name).toList()));
        options.add("skies", gson.toJsonTree(List.of(SkyOption.values()).stream().map(Enum::name).toList()));
        var ratios = new JsonObject();
        for (var ratio : AspectRatio.values())
            ratios.add(ratio.label(), gson.toJsonTree(GridLayouts.allowedFor(player, ratio, plugin.config().maxMapTiles())
                    .stream().map(GridOption::label).toList()));
        options.add("grids", ratios);
        out.add("options", options);
        return out;
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
        return out;
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
        var future = onMain(() -> {
            var camera = camera(player, id);
            var ratio = camera.aspectRatio().value();
            return plugin.render().capture(plugin.render().specFor(camera), width, Math.max(1, (int) Math.round(width / ratio)));
        });
        return encode(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
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
        var kit = file.equals(KIT_PATH);
        if (!kit && (file.contains("..") || file.contains("/"))) {
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = plugin.getResource(kit ? KIT_RESOURCE : "editor/" + file)) {
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
