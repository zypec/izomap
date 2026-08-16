package dev.zypec.izomap.map;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.render.MapColorConverter;
import dev.zypec.izomap.render.RenderResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The frames a server offers, read from {@code frames.yml}, and the drawing of one onto
 * a finished photo.
 *
 * <p>Not to be confused with the item frames a photo hangs in; this is the border drawn
 * into the picture. {@code PhotoFrameListener} owns the other kind.</p>
 *
 * <h2>Three ways to describe one, all compiled into the same thing</h2>
 *
 * <p>Every frame becomes an <b>edge strip</b> and an optional <b>corner sprite</b>, so
 * there is one drawing routine no matter how the frame was written:</p>
 *
 * <ul>
 *   <li>{@code rings} — concentric bands, a colour and a width each. Three of them make
 *       a passable wooden frame in four lines of YAML, and this is what most frames
 *       want. Compiles to a strip one pixel long, since every column is the same.</li>
 *   <li>{@code edge} / {@code corner} — pixel art as rows of characters against a
 *       {@code palette} legend. The edge repeats along all four sides, the corner is
 *       drawn into all four corners, mirrored. This is what rings cannot do: dashes,
 *       rope twists, chequers, ornamented corners.</li>
 *   <li>{@code texture} — a PNG under {@code frames/}, nine-sliced at {@code inset}:
 *       the top-left {@code inset} square becomes the corner, the strip between the
 *       insets along the top becomes the repeating edge. For frames drawn in an image
 *       editor rather than typed.</li>
 * </ul>
 *
 * <p>A transparent art pixel leaves the photo showing through, so a corner can be cut
 * or organic rather than a solid square.</p>
 *
 * <h2>Colours are snapped once, at load</h2>
 *
 * <p>The cache stores one palette index per pixel and matches colours <b>exactly</b>, so
 * a frame colour that is not a palette entry would be written as a transparent hole.
 * Every colour, from all three sources, is snapped to the nearest palette entry here —
 * once per file, not per photo and not per pixel.</p>
 *
 * <p>The frame is drawn <b>over</b> the outermost pixels rather than shrinking the image
 * into it. Shrinking looks better but needs the photo re-rendered at the inner size or
 * resampled; the cache holds it at full size, so covering the border costs one pass and
 * no quality anywhere else.</p>
 */
public final class PhotoFrames {

    private static final String FILE_NAME = "frames.yml";
    /**
     * Where {@code texture} frames look for their PNGs.
     */
    private static final String TEXTURE_FOLDER = "frames";

    /**
     * A frame may not eat more than this much of the shorter side, halved across the two
     * opposite edges. A frame thicker than its photo would leave nothing to look at, and
     * on a 1x1 photo that is easy to write by accident.
     */
    private static final double MAX_SHARE = 0.4;

    /**
     * Photo pixels one art pixel covers at {@code scale: auto}, per step. A 1x1 photo
     * (128 px) draws the art as written; a 8x6 (768 px tall) draws it three times the
     * size, so a frame stays a frame instead of thinning into a hairline.
     */
    private static final int AUTO_SCALE_STEP = 256;
    private static final int MAX_SCALE = 8;

    /**
     * Transparent: the photo shows through this art pixel.
     */
    private static final int CLEAR = 0;

    private final Map<String, Frame> byId = new LinkedHashMap<>();

    /**
     * One band of a ring-described frame, drawn inward from where the previous ended.
     *
     * @param argb  palette colour, already snapped, with full alpha
     * @param width thickness in pixels
     */
    public record Ring(int argb, int width) {
    }

    /**
     * A frame compiled into the form it is drawn from.
     *
     * @param id         its id in the file
     * @param thickness  border depth in art pixels, before {@code scale}
     * @param edge       {@code thickness × edgeLength} pixels, row 0 outermost, repeated
     *                   along every side
     * @param edgeLength how many pixels the edge covers before it repeats
     * @param corner     {@code thickness × thickness} pixels drawn into each corner and
     *                   mirrored into the other three, or {@code null} for none
     * @param scale      how many photo pixels one art pixel covers, or
     *                   {@link #SCALE_AUTO} to pick from the photo's size
     */
    public record Frame(String id, int thickness, int[] edge, int edgeLength, int[] corner, int scale) {

        /**
         * Grow the art with the photo instead of keeping a fixed pixel size.
         */
        public static final int SCALE_AUTO = 0;

        /**
         * How many photo pixels one art pixel covers on an image this size.
         */
        public int scaleFor(int width, int height) {
            if (scale != SCALE_AUTO)
                return Math.max(1, scale);

            var shorter = Math.min(width, height);
            return Math.clamp(shorter / AUTO_SCALE_STEP, 1, MAX_SCALE);
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
                var frame = read(plugin, id, section, converter);
                if (frame != null)
                    frames.byId.put(id, frame);
            }
        }
        plugin.messages().info("log.frames-loaded",
                Placeholder.unparsed("count", String.valueOf(frames.byId.size())));
        return frames;
    }

    /**
     * Compiles one entry, whichever of the three ways it was written.
     */
    private static Frame read(Izomap plugin, String id, ConfigurationSection section,
                              MapColorConverter converter) {
        var scale = readScale(section.get("scale"));
        if (section.isString("texture"))
            return fromTexture(plugin, id, section, converter, scale);

        if (section.isList("edge"))
            return fromArt(plugin, id, section, converter, scale);

        return fromRings(plugin, id, section.getMapList("rings"), converter, scale);
    }

    /**
     * {@code scale: 2}, or {@code scale: auto} to follow the photo's size.
     */
    private static int readScale(Object raw) {
        if (raw instanceof Number number)
            return Math.clamp(number.intValue(), 1, MAX_SCALE);

        return Frame.SCALE_AUTO;
    }

    // --- rings ---

    private static Frame fromRings(Izomap plugin, String id, List<Map<?, ?>> raw,
                                   MapColorConverter converter, int scale) {
        List<Ring> rings = new ArrayList<>();
        for (var entry : raw) {
            var color = parseColor(String.valueOf(entry.get("color")));
            var width = entry.get("width") instanceof Number number ? number.intValue() : -1;
            if (color < 0 || width <= 0) {
                warn(plugin, "log.frame-ring-invalid", id);
                continue;
            }
            rings.add(new Ring(0xFF000000 | converter.snap(color), width));
        }
        return rings.isEmpty() ? null : ringFrame(id, rings, scale);
    }

    /**
     * Compiles concentric rings into the general form.
     *
     * <p>Every column of a ring frame is the same, so the strip is one pixel long and
     * the drawing routine needs to know nothing about rings at all.</p>
     */
    public static Frame ringFrame(String id, List<Ring> rings, int scale) {
        var thickness = rings.stream().mapToInt(Ring::width).sum();
        var edge = new int[thickness];
        var depth = 0;
        for (var ring : rings) {
            for (var i = 0; i < ring.width(); i++) {
                edge[depth++] = ring.argb();
            }
        }
        return new Frame(id, thickness, edge, 1, null, scale);
    }

    // --- pixel art in the file ---

    private static Frame fromArt(Izomap plugin, String id, ConfigurationSection section,
                                 MapColorConverter converter, int scale) {
        var palette = readPalette(section.getConfigurationSection("palette"), converter);
        var edgeRows = section.getStringList("edge");
        var thickness = edgeRows.size();
        if (thickness == 0) {
            warn(plugin, "log.frame-art-invalid", id);
            return null;
        }

        var edgeLength = edgeRows.getFirst().length();
        var edge = new int[thickness * edgeLength];
        for (var row = 0; row < thickness; row++) {
            var line = edgeRows.get(row);
            if (line.length() != edgeLength) {
                // Ragged rows would silently shift the pattern along one side.
                warn(plugin, "log.frame-art-invalid", id);
                return null;
            }
            for (var col = 0; col < edgeLength; col++) {
                edge[row * edgeLength + col] = palette.getOrDefault(line.charAt(col), CLEAR);
            }
        }

        int[] corner = null;
        var cornerRows = section.getStringList("corner");
        if (!cornerRows.isEmpty()) {
            // Square, and the same depth as the edge: anything else has no meaning at a
            // corner, where the two sides have to meet.
            if (cornerRows.size() != thickness || cornerRows.stream().anyMatch(r -> r.length() != thickness)) {
                warn(plugin, "log.frame-corner-invalid", id);
            } else {
                corner = new int[thickness * thickness];
                for (var row = 0; row < thickness; row++) {
                    for (var col = 0; col < thickness; col++) {
                        corner[row * thickness + col] =
                                palette.getOrDefault(cornerRows.get(row).charAt(col), CLEAR);
                    }
                }
            }
        }
        return new Frame(id, thickness, edge, edgeLength, corner, scale);
    }

    /**
     * The {@code palette} legend: one character to one snapped colour. Anything that is
     * not a colour — {@code transparent}, {@code none}, an empty value — is left out, so
     * its character shows the photo through.
     */
    private static Map<Character, Integer> readPalette(ConfigurationSection section,
                                                       MapColorConverter converter) {
        Map<Character, Integer> palette = new LinkedHashMap<>();
        if (section == null)
            return palette;

        for (var key : section.getKeys(false)) {
            if (key.length() != 1) continue;

            var color = parseColor(section.getString(key));
            if (color >= 0)
                palette.put(key.charAt(0), 0xFF000000 | converter.snap(color));
        }
        return palette;
    }

    // --- pixel art in a PNG ---

    /**
     * Nine-slices a PNG: the top-left {@code inset} square is the corner, and the strip
     * between the two insets along the top edge is what repeats.
     *
     * <p>Only the top row of the image is read. The other three sides are this one
     * rotated and mirrored, which is what a frame does anyway and saves the author from
     * drawing the same border four times in four orientations.</p>
     */
    private static Frame fromTexture(Izomap plugin, String id, ConfigurationSection section,
                                     MapColorConverter converter, int scale) {
        var name = section.getString("texture", "");
        var file = new File(new File(plugin.getDataFolder(), TEXTURE_FOLDER), name);
        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException | RuntimeException ex) {
            image = null;
        }
        if (image == null) {
            plugin.messages().warn("log.frame-texture-missing",
                    Placeholder.unparsed("frame", id),
                    Placeholder.unparsed("file", name));
            return null;
        }

        var inset = section.getInt("inset", Math.min(image.getWidth(), image.getHeight()) / 3);
        var maxInset = Math.min(image.getWidth(), image.getHeight()) / 2;
        if (inset <= 0 || inset > maxInset) {
            warn(plugin, "log.frame-texture-inset", id);
            return null;
        }

        var edgeLength = Math.max(1, image.getWidth() - 2 * inset);
        var edge = new int[inset * edgeLength];
        for (var row = 0; row < inset; row++) {
            for (var col = 0; col < edgeLength; col++) {
                edge[row * edgeLength + col] = snapPixel(image.getRGB(inset + col, row), converter);
            }
        }
        var corner = new int[inset * inset];
        for (var row = 0; row < inset; row++) {
            for (var col = 0; col < inset; col++) {
                corner[row * inset + col] = snapPixel(image.getRGB(col, row), converter);
            }
        }
        return new Frame(id, inset, edge, edgeLength, corner, scale);
    }

    /**
     * A PNG pixel as the frame stores it: snapped to the palette, or transparent when
     * the source was.
     */
    private static int snapPixel(int argb, MapColorConverter converter) {
        // Half-transparent art has nowhere to go on a palette with no alpha, so anything
        // below solid is treated as a hole rather than blended into a colour it is not.
        return (argb >>> 24) < 0xFF ? CLEAR : 0xFF000000 | converter.snap(argb & 0xFFFFFF);
    }

    // --- shared parsing ---

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

    private static void warn(Izomap plugin, String key, String frameId) {
        plugin.messages().warn(key, Placeholder.unparsed("frame", frameId));
    }

    // --- lookup ---

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

    // --- drawing ---

    /**
     * A copy of the image with the frame drawn around it; the original is left alone, so
     * an unframed photo can still be exported or reframed from the same cache entry.
     *
     * <p>Art too thick for the photo is <b>clipped from the inside out</b>: the outermost
     * rows are the ones that survive. A frame is a decoration, and a 1x1 photo wearing
     * one meant for a 4x2 should come out shallow rather than rejected — and it is the
     * outer edge that reads as a frame, so that is the half worth keeping.</p>
     */
    public static RenderResult draw(RenderResult image, Frame frame) {
        if (frame == null || frame.thickness() <= 0)
            return image;

        var width = image.width();
        var height = image.height();
        var pixels = image.argb().clone();

        var scale = frame.scaleFor(width, height);
        var budget = (int) (Math.min(width, height) * MAX_SHARE / 2.0);
        var depth = Math.min(frame.thickness() * scale, budget);
        if (depth <= 0)
            return image;

        drawEdges(pixels, width, height, frame, scale, depth);
        if (frame.corner() != null)
            drawCorners(pixels, width, height, frame, scale, depth);

        return new RenderResult(width, height, pixels);
    }

    /**
     * The four sides. The vertical ones use the same strip transposed, so a repeating
     * pattern runs the same way round the whole frame.
     */
    private static void drawEdges(int[] pixels, int width, int height,
                                  Frame frame, int scale, int depth) {
        for (var d = 0; d < depth; d++) {
            var row = d / scale;
            for (var x = 0; x < width; x++) {
                var argb = art(frame, row, x / scale);
                set(pixels, width, height, x, d, argb);
                set(pixels, width, height, x, height - 1 - d, argb);
            }
            for (var y = 0; y < height; y++) {
                var argb = art(frame, row, y / scale);
                set(pixels, width, height, d, y, argb);
                set(pixels, width, height, width - 1 - d, y, argb);
            }
        }
    }

    /**
     * The corner sprite in all four corners, mirrored into the other three so the author
     * draws it once.
     */
    private static void drawCorners(int[] pixels, int width, int height,
                                    Frame frame, int scale, int depth) {
        var corner = frame.corner();
        var thickness = frame.thickness();
        for (var y = 0; y < depth; y++) {
            for (var x = 0; x < depth; x++) {
                var argb = corner[(y / scale) * thickness + (x / scale)];
                set(pixels, width, height, x, y, argb);
                set(pixels, width, height, width - 1 - x, y, argb);
                set(pixels, width, height, x, height - 1 - y, argb);
                set(pixels, width, height, width - 1 - x, height - 1 - y, argb);
            }
        }
    }

    /**
     * The art pixel at this depth and position along the edge, repeating.
     */
    private static int art(Frame frame, int row, int along) {
        return frame.edge()[row * frame.edgeLength() + (along % frame.edgeLength())];
    }

    /**
     * Paints one pixel, unless the art leaves it clear or the coordinates fell outside —
     * which happens on a photo narrower than twice the frame.
     */
    private static void set(int[] pixels, int width, int height, int x, int y, int argb) {
        if (argb == CLEAR || x < 0 || y < 0 || x >= width || y >= height)
            return;

        pixels[y * width + x] = argb;
    }
}
