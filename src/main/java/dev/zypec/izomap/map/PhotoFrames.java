package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.MapColorConverter;
import dev.zypec.izomap.render.RenderResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The frames a server offers, read from {@code frames.yml}, and the drawing of one onto
 * a finished photo.
 *
 * <h2>Rings, not pictures</h2>
 *
 * <p>A frame is a list of concentric bands drawn inward from the edge: a colour and a
 * width each. Three of them already make a passable wooden frame — dark outer edge,
 * broad middle, thin dark inner line — and the description costs four lines of YAML that
 * a server owner can retune without an image editor.</p>
 *
 * <p>The alternative was pixel art in a PNG, nine-sliced to the photo's size. It buys
 * corner ornaments and nothing else here: a photo can be 128 pixels wide or 2048, so the
 * art would have to tile or stretch along every edge anyway, and every colour it used
 * would be snapped to the map palette on the way in. Rings fit any size exactly, by
 * construction. A {@code texture} key can join {@code rings} later for the servers that
 * want the ornaments.</p>
 *
 * <h2>Colours are snapped once, at load</h2>
 *
 * <p>The cache stores one palette index per pixel and matches colours <b>exactly</b>, so
 * a frame colour that is not a palette entry would be written as a transparent hole. Each
 * ring's colour is therefore snapped to the nearest palette entry here, once, rather than
 * per pixel or per photo.</p>
 *
 * <p>The frame is drawn <b>over</b> the outermost pixels rather than shrinking the image
 * into it. Shrinking looks better but needs the photo re-rendered at the inner size or
 * resampled; the cache holds it at full size, so covering the border costs one pass and
 * no quality anywhere else.</p>
 */
public final class PhotoFrames {

    private static final String FILE_NAME = "frames.yml";

    /**
     * A frame may not eat more than this much of the shorter side, halved across the two
     * opposite edges. A frame thicker than its photo would leave nothing to look at, and
     * on a 1x1 photo that is easy to write by accident.
     */
    private static final double MAX_SHARE = 0.4;

    private final Map<String, Frame> byId = new LinkedHashMap<>();

    /**
     * One band of a frame, drawn inward from where the previous one ended.
     *
     * @param argb  palette colour, already snapped, with full alpha
     * @param width thickness in pixels
     */
    public record Ring(int argb, int width) {
    }

    /**
     * A frame as the file describes it.
     */
    public record Frame(String id, List<Ring> rings) {

        public Frame {
            rings = List.copyOf(rings);
        }

        /**
         * Total thickness of the border, in pixels.
         */
        public int thickness() {
            var total = 0;
            for (var ring : rings) {
                total += ring.width();
            }
            return total;
        }
    }

    private PhotoFrames() {
    }

    public static PhotoFrames load(Izomap plugin) {
        var frames = new PhotoFrames();
        var file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists())
            plugin.saveResource(FILE_NAME, false);

        var converter = new MapColorConverter();
        var cfg = YamlConfiguration.loadConfiguration(file);
        var root = cfg.getConfigurationSection("frames");
        if (root != null) {
            for (var key : root.getKeys(false)) {
                var section = root.getConfigurationSection(key);
                if (section == null) continue;

                var id = key.toUpperCase(Locale.ROOT);
                var rings = readRings(plugin, id, section.getMapList("rings"), converter);
                if (!rings.isEmpty())
                    frames.byId.put(id, new Frame(id, rings));
            }
        }
        plugin.messages().info("log.frames-loaded",
                Placeholder.unparsed("count", String.valueOf(frames.byId.size())));
        return frames;
    }

    private static List<Ring> readRings(Izomap plugin, String frameId, List<Map<?, ?>> raw,
                                        MapColorConverter converter) {
        List<Ring> rings = new ArrayList<>();
        for (var entry : raw) {
            var color = parseColor(String.valueOf(entry.get("color")));
            var width = parseWidth(entry.get("width"));
            if (color < 0 || width <= 0) {
                plugin.messages().warn("log.frame-ring-invalid",
                        Placeholder.unparsed("frame", frameId));
                continue;
            }
            rings.add(new Ring(0xFF000000 | converter.snap(color), width));
        }
        return rings;
    }

    /**
     * {@code #RRGGBB} or {@code RRGGBB}; {@code -1} when it is neither.
     */
    private static int parseColor(String raw) {
        if (raw == null) return -1;

        var text = raw.trim();
        if (text.startsWith("#"))
            text = text.substring(1);

        if (text.length() != 6) return -1;

        try {
            return Integer.parseInt(text, 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static int parseWidth(Object raw) {
        return raw instanceof Number number ? number.intValue() : -1;
    }

    /**
     * The frame with this id, or {@code null} for an unknown or absent one.
     */
    public Frame byId(String id) {
        return id == null ? null : byId.get(id.toUpperCase(Locale.ROOT));
    }

    public List<Frame> all() {
        return List.copyOf(byId.values());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /**
     * A copy of the image with the frame drawn around it; the original is left alone, so
     * an unframed photo can still be exported or reframed from the same cache entry.
     *
     * <p>Rings wider than the photo can bear are trimmed rather than refused: a frame is
     * a decoration, and a 1x1 photo carrying a frame meant for a 4x2 one should come out
     * thin, not rejected.</p>
     */
    public static RenderResult draw(RenderResult image, Frame frame) {
        if (frame == null || frame.rings().isEmpty())
            return image;

        var width = image.width();
        var height = image.height();
        var pixels = image.argb().clone();

        var budget = (int) (Math.min(width, height) * MAX_SHARE / 2.0);
        var offset = 0;
        for (var ring : frame.rings()) {
            var thickness = Math.min(ring.width(), budget - offset);
            for (var i = 0; i < thickness; i++) {
                drawBorder(pixels, width, height, offset + i, ring.argb());
            }
            offset += thickness;
            if (offset >= budget) break;
        }
        return new RenderResult(width, height, pixels);
    }

    /**
     * Paints the one-pixel rectangle {@code inset} pixels in from the edge.
     */
    private static void drawBorder(int[] pixels, int width, int height, int inset, int argb) {
        var last = height - 1 - inset;
        if (inset > last) return;

        for (var x = inset; x < width - inset; x++) {
            pixels[inset * width + x] = argb;
            pixels[last * width + x] = argb;
        }
        for (var y = inset; y <= last; y++) {
            pixels[y * width + inset] = argb;
            pixels[y * width + width - 1 - inset] = argb;
        }
    }
}
