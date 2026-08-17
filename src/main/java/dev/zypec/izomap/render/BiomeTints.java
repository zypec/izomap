package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * What colour each biome paints its grass, its leaves and its water, and how that turns
 * into a map colour.
 *
 * <h2>This is a deliberate departure from vanilla</h2>
 *
 * <p>A vanilla map has <b>no</b> biome tint: it draws one fixed colour per block, so a
 * swamp, a desert and a snowfield are the same green. In the world they are not, and a
 * photo is supposed to show the landscape rather than the map of it. The tint can be
 * switched off with {@code photo.biome-tint.enabled} for anyone who wants the vanilla
 * answer, and softened with {@code strength} by anyone who wants half of it.</p>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>From the server, through {@link ServerBiomeColors}, once at load. Datapack and
 * custom biomes are therefore tinted with everybody else and nothing here has to be kept
 * up to date by hand. When that fails — a server this build's internals do not fit — the
 * tint switches itself off with a warning rather than guessing, and
 * {@code biome-tints.yml} can still name colours by hand.</p>
 *
 * <h2>How a tint becomes a map colour</h2>
 *
 * <p>The tint cannot simply replace the block's colour: the biome colour is what the
 * client <i>multiplies a greyscale texture by</i>, so using it raw would repaint a
 * meadow in a brightness that has nothing to do with the palette. Nor can it be
 * multiplied into the base colour: vanilla's {@code WATER} (#4040FF) is a stylised blue
 * that shares no hue with real water (#3F76E4 in plains), and scaling one by the other
 * turns a swamp's green water <b>purple</b>.</p>
 *
 * <p>So the tint keeps its own hue and borrows the block's brightness:</p>
 *
 * <pre>tinted = biomeTint × luma(blockBaseColour) / luma(referenceTint)</pre>
 *
 * <p>where the reference is {@link #REFERENCE} — plains, the biome vanilla's colours look
 * like they were chosen in. Two things fall out of that. A plains photo comes out about
 * where it always did, because the reference cancels. And blocks keep telling each other
 * apart: a grass tuft ({@code PLANT}) is darker than a grass block ({@code GRASS}) in
 * every biome, because their own brightness is what is being tinted.</p>
 */
public final class BiomeTints {

    private static final String FILE_NAME = "biome-tints.yml";

    /**
     * The biome the block colours are taken to have been chosen in, and therefore the one
     * that comes out unchanged.
     */
    private static final NamespacedKey REFERENCE = NamespacedKey.minecraft("plains");

    /**
     * No tint at all: every block keeps the colour vanilla gives it.
     */
    public static final BiomeTints NONE = new BiomeTints(Map.of(), new int[0], new double[0], 0.0);

    /**
     * Which of a biome's three colours a block is painted with.
     */
    public enum Channel {
        GRASS,
        FOLIAGE,
        WATER;

        static final Channel[] VALUES = values();
    }

    /**
     * Biome to one tint index per {@link Channel}, in that enum's order. A biome the
     * server does not know is simply absent and its blocks stay untinted.
     */
    private final Map<Biome, int[]> byBiome;
    /**
     * Tint colour per index (0xRRGGBB).
     */
    private final int[] rgb;
    /**
     * {@code 1 / luma(referenceTint)} of the index's channel, kept per index so the
     * colour maths is one multiply rather than a channel lookup.
     */
    private final double[] factor;
    /**
     * How much of the tint is applied, 0 to 1.
     */
    private final double strength;

    private BiomeTints(Map<Biome, int[]> byBiome, int[] rgb, double[] factor, double strength) {
        this.byBiome = byBiome;
        this.rgb = rgb;
        this.factor = factor;
        this.strength = strength;
    }

    /**
     * Reads every biome the server has, then applies {@code biome-tints.yml} over it.
     * Main thread only, and only worth calling once.
     */
    public static BiomeTints load(Izomap plugin) {
        if (!plugin.config().biomeTint())
            return NONE;

        Map<NamespacedKey, int[]> colors = new HashMap<>();
        List<Biome> biomes = new ArrayList<>();
        for (var biome : RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)) {
            biomes.add(biome);
        }
        try {
            for (var biome : biomes) {
                var read = ServerBiomeColors.read(biome.getKey());
                if (read != null)
                    colors.put(biome.getKey(), read);
            }
        } catch (Throwable failure) {
            // Server internals this build does not fit. The photo is still a photo
            // without the tint, so this is a warning rather than a failure.
            plugin.messages().warn("log.biome-tint-unavailable",
                    Placeholder.unparsed("reason", String.valueOf(failure)));
            colors.clear();
        }

        applyOverrides(plugin, colors);
        var reference = colors.get(REFERENCE);
        if (reference == null || !usable(reference[0]) || !usable(reference[1]) || !usable(reference[2])) {
            plugin.messages().warn("log.biome-tint-no-reference",
                    Placeholder.unparsed("biome", REFERENCE.toString()));
            return NONE;
        }

        var tints = build(biomes, colors, reference, plugin.config().biomeTintStrength());
        plugin.messages().info("log.biome-tints-ready",
                Placeholder.unparsed("count", String.valueOf(tints.byBiome.size())));
        return tints;
    }

    /**
     * Turns the read colours into the flat arrays the render reads: one index per biome
     * and channel, so everything past this point is an {@code int}.
     */
    private static BiomeTints build(List<Biome> biomes, Map<NamespacedKey, int[]> colors,
                                    int[] reference, double strength) {
        Map<Biome, int[]> byBiome = new HashMap<>(biomes.size() * 2);
        List<Integer> rgb = new ArrayList<>();
        List<Double> factor = new ArrayList<>();
        var factors = new double[Channel.VALUES.length];
        for (var channel : Channel.VALUES) {
            factors[channel.ordinal()] = 1.0 / Math.max(1.0, luma(reference[channel.ordinal()]));
        }

        for (var biome : biomes) {
            var read = colors.get(biome.getKey());
            if (read == null)
                continue;

            var indices = new int[Channel.VALUES.length];
            for (var channel : Channel.VALUES) {
                var index = channel.ordinal();
                if (!usable(read[index])) {
                    // Nobody's grass is black. A colour that comes back as one means
                    // something did not answer, and the block keeps its own colour rather
                    // than being painted with the failure.
                    indices[index] = NO_TINT;
                    continue;
                }
                rgb.add(read[index]);
                factor.add(factors[index]);
                indices[index] = rgb.size() - 1;
            }
            byBiome.put(biome, indices);
        }

        var rgbArray = new int[rgb.size()];
        var factorArray = new double[factor.size()];
        for (var i = 0; i < rgbArray.length; i++) {
            rgbArray[i] = rgb.get(i);
            factorArray[i] = factor.get(i);
        }
        return new BiomeTints(byBiome, rgbArray, factorArray, strength);
    }

    /**
     * Reads the file, writing the default one out the first time. It carries no table of
     * its own: the server already answered, and this is only for disagreeing with it.
     */
    private static void applyOverrides(Izomap plugin, Map<NamespacedKey, int[]> colors) {
        var file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists())
            plugin.saveResource(FILE_NAME, false);

        var section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("overrides");
        if (section == null) return;

        var applied = 0;
        for (var name : section.getKeys(false)) {
            var key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            if (key == null) {
                plugin.messages().warn("log.biome-tint-unknown",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("biome", name));
                continue;
            }
            // A biome the server never reported can still be written here; it simply
            // never comes up in a render.
            var current = colors.computeIfAbsent(key, ignored -> new int[Channel.VALUES.length]);
            for (var channel : Channel.VALUES) {
                var raw = section.getString(name + "." + channel.name().toLowerCase(Locale.ROOT));
                var value = parseRgb(raw);
                if (raw != null && value < 0) {
                    plugin.messages().warn("log.biome-tint-invalid",
                            Placeholder.unparsed("file", FILE_NAME),
                            Placeholder.unparsed("biome", name),
                            Placeholder.unparsed("color", raw));
                } else if (value >= 0) {
                    current[channel.ordinal()] = value;
                    applied++;
                }
            }
        }
        if (applied > 0) {
            plugin.messages().info("log.biome-tints-overridden",
                    Placeholder.unparsed("count", String.valueOf(applied)));
        }
    }

    private static int parseRgb(String raw) {
        if (raw == null || raw.isBlank()) return -1;

        var hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * A table built from colours rather than from a server, for tests: they have no
     * registry to ask and no {@link Biome} instances to key on.
     */
    static BiomeTints of(int referenceRgb, int[] tints, double strength) {
        var factor = new double[tints.length];
        Arrays.fill(factor, 1.0 / Math.max(1.0, luma(referenceRgb)));
        return new BiomeTints(Map.of(), tints.clone(), factor, strength);
    }

    /**
     * Whether anything is tinted at all, which is what lets the walk skip biome lookups
     * and the capture skip copying biomes.
     */
    public boolean enabled() {
        return !byBiome.isEmpty() && strength > 0.0;
    }

    /**
     * Whether a colour is one a biome could actually have named. Black is the shape a
     * missing answer takes, here and in the server.
     */
    private static boolean usable(int rgb) {
        return rgb != 0;
    }

    /**
     * What an untinted channel is recorded as, matching {@link RayHit#NO_TINT}.
     */
    private static final int NO_TINT = -1;

    /**
     * Tint index for a biome's channel, or {@code -1} when that biome is not tinted.
     * {@code biome} may be {@code null}, which is what an uncaptured chunk answers.
     */
    int indexOf(Biome biome, Channel channel) {
        if (biome == null) return -1;

        var indices = byBiome.get(biome);
        return indices == null ? -1 : indices[channel.ordinal()];
    }

    /**
     * The colour a block of this base takes in the biome behind {@code index}, before
     * shading (0xRRGGBB).
     *
     * <p>{@link #strength} pulls the result back towards the untinted colour, so a server
     * can have a hint of the biome rather than all of it.</p>
     */
    int tinted(int index, int baseRgb) {
        var scale = luma(baseRgb) * factor[index];
        var tint = rgb[index];
        var r = mix((tint >> 16) & 0xFF, (baseRgb >> 16) & 0xFF, scale);
        var g = mix((tint >> 8) & 0xFF, (baseRgb >> 8) & 0xFF, scale);
        var b = mix(tint & 0xFF, baseRgb & 0xFF, scale);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * One channel of the tint at {@code scale}, brought back towards the block's own
     * value by whatever {@link #strength} withholds.
     */
    private int mix(int tintChannel, int baseChannel, double scale) {
        var tinted = tintChannel * scale;
        return clamp(baseChannel + (tinted - baseChannel) * strength);
    }

    private static int clamp(double value) {
        return (int) Math.clamp(Math.round(value), 0, 255);
    }

    /**
     * Perceived brightness, which is what the tint borrows from the block.
     */
    private static double luma(int rgb) {
        return 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
    }

    /**
     * How many tints the render may be asked for, which is how long a cache row has to
     * be.
     */
    int count() {
        return rgb.length;
    }
}
