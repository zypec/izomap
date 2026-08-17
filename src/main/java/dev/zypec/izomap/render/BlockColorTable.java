package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Material} to map {@link MapBaseColor} table.
 *
 * <p>Nothing is guessed: every block's color comes from the server via
 * {@code BlockData#getMapColor()}, which is exactly the
 * <a href="https://minecraft.wiki/w/Map_item_format#Base_colors">base color</a>
 * vanilla maps use, so stair/slab/wall variants and blocks added in newer versions
 * are all correct.</p>
 *
 * <h2>Blocks whose color depends on their state</h2>
 *
 * <p>A material is not always one color. Wheat is green while it grows and yellow once
 * ripe, so reading the default state alone paints a ripe field the color of seedlings.
 * The states that matter are the ages, and rather than keep a list of which blocks have
 * age-dependent colors — a list that would rot with every version — the load
 * <b>asks</b>: every ageable block is probed at each of its ages, and only the ones
 * that actually answer differently get a table. On a vanilla server that is wheat and
 * whatever else grows into a new color.</p>
 *
 * <p>The ray walk pays for this only where it applies: the extra state lookup happens
 * on a hit, and only for the handful of materials that came back varying.</p>
 *
 * <h2>The handful vanilla gets wrong</h2>
 *
 * <p>A base colour is assigned by hand, not derived from the block's texture, and for a
 * few blocks the two disagree badly enough to look like a bug in this plugin. Tuff is
 * the case that prompted this: all fourteen tuff blocks report {@code TERRACOTTA_GRAY}
 * (#392923, a near-black brown) against a texture averaging #6C6D66, a light grey-green.
 * A photo of a tuff tower came out the colour of rust.</p>
 *
 * <p>{@link #CORRECTIONS} replaces those, measured rather than guessed: each entry is
 * the palette colour nearest the texture's own average. It can be switched off with
 * {@code settings.correct-vanilla-colors} by anyone who wants a photo to match a vanilla
 * map exactly, wart and all, and {@code block-colors.yml} still wins over both.</p>
 *
 * <p>{@code block-colors.yml} exists only for overrides.</p>
 *
 * <h2>How much of its cell a block fills</h2>
 *
 * <p>A colour is not the whole story: the voxel walk used to treat every block as a full
 * cube, so a single grass tuft painted its entire cell a saturated green. The second
 * half of the table is therefore a <b>coverage</b> per material — the fraction of its
 * cell it really fills — which the walk spends as alpha: a thin block does not stop the
 * ray, it tints whatever stands behind it. {@link #DEFAULT_COVERAGE} carries the blocks
 * that need it, {@code coverage:} in the file overrides them, and
 * {@code photo.coverage.enabled} turns the whole idea off.</p>
 *
 * <p>The table is read-only once loaded, so render threads may use it.</p>
 */
public final class BlockColorTable {

    private static final String FILE_NAME = "block-colors.yml";

    /**
     * A block filling its whole cell, which is what every block was before coverage.
     */
    private static final float FULL = 1.0f;

    /**
     * Coverage from which a block counts as solid to everything that asks whether one
     * can be seen past: shadows and ambient occlusion.
     */
    private static final float OPAQUE_ENOUGH = 0.5f;

    /**
     * Blocks whose vanilla map colour does not resemble them, and what to use instead.
     *
     * <p>Tuff averages #6C6D66 and its bricks #62665F. {@code DEEPSLATE} (#646464) is
     * nearest to the bricks by a distance of 6 and within one unit of nearest for the
     * plain block, where {@code STONE} (#707070) ties it. Deepslate wins the tie on
     * what a photo is for: tuff sits next to stone, cobblestone and andesite in almost
     * every build that uses it, and giving it {@code STONE} would erase the wall it was
     * chosen to distinguish. Colliding with deepslate instead costs less, since the two
     * rarely share a surface.</p>
     */
    private static final Map<Material, MapBaseColor> CORRECTIONS = Map.ofEntries(
            Map.entry(Material.TUFF, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_SLAB, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_STAIRS, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_WALL, MapBaseColor.DEEPSLATE),
            Map.entry(Material.POLISHED_TUFF, MapBaseColor.DEEPSLATE),
            Map.entry(Material.POLISHED_TUFF_SLAB, MapBaseColor.DEEPSLATE),
            Map.entry(Material.POLISHED_TUFF_STAIRS, MapBaseColor.DEEPSLATE),
            Map.entry(Material.POLISHED_TUFF_WALL, MapBaseColor.DEEPSLATE),
            Map.entry(Material.CHISELED_TUFF, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_BRICKS, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_BRICK_SLAB, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_BRICK_STAIRS, MapBaseColor.DEEPSLATE),
            Map.entry(Material.TUFF_BRICK_WALL, MapBaseColor.DEEPSLATE),
            Map.entry(Material.CHISELED_TUFF_BRICKS, MapBaseColor.DEEPSLATE));

    /**
     * Blocks that do not fill the cell they stand in, and how much of it they do fill.
     *
     * <p>Two tiers, both drawn along one question — can you really see past it? A tuft of
     * grass or a flower leaves most of its cell to whatever stands behind it, a vine or a
     * ladder nearly all of it. Half blocks are deliberately absent: a slab's lower half
     * really is stone, and mixing it into the ground would wash out a colour that is
     * already right.</p>
     *
     * <p>Absent for a different reason: carpets, snow layers, lily pads, redstone dust.
     * They cover the face they lie on completely and only look thin from the side, so one
     * number is wrong for them whichever way it leans; they wait for the face-aware
     * coverage the walk cannot express yet. Underwater plants stay out too, because the
     * water column is measured as one body and a thin cell in the middle of it would cut
     * that measurement in half.</p>
     */
    private static final Map<Material, Float> DEFAULT_COVERAGE = defaultCoverage();

    /**
     * Thin, scattered plants: a stalk or a blossom in an otherwise empty cell.
     */
    private static final float SCATTERED = 0.30f;

    /**
     * Things clinging to a surface or strung across a cell, seen past from any angle.
     */
    private static final float CLINGING = 0.15f;

    private static Map<Material, Float> defaultCoverage() {
        Map<Material, Float> map = new EnumMap<>(Material.class);
        for (var material : List.of(
                Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
                Material.DEAD_BUSH, Material.SUGAR_CANE, Material.COBWEB,
                Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
                Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
                Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
                Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE,
                Material.TORCHFLOWER, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH,
                Material.PEONY,
                Material.OAK_SAPLING, Material.SPRUCE_SAPLING, Material.BIRCH_SAPLING,
                Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
                Material.CHERRY_SAPLING, Material.PALE_OAK_SAPLING, Material.MANGROVE_PROPAGULE,
                Material.PITCHER_PLANT,
                Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
                Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
                Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS)) {
            map.put(material, SCATTERED);
        }
        for (var material : List.of(
                Material.VINE, Material.GLOW_LICHEN, Material.SCULK_VEIN, Material.LADDER,
                Material.IRON_CHAIN, Material.HANGING_ROOTS, Material.SPORE_BLOSSOM,
                Material.TWISTING_VINES, Material.WEEPING_VINES,
                Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
                Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL,
                Material.ACTIVATOR_RAIL)) {
            map.put(material, CLINGING);
        }
        return map;
    }

    private final Map<Material, MapBaseColor> colors = new EnumMap<>(Material.class);
    /**
     * Colors per age, for the materials that have more than one. Absent means the
     * material's color is its own, whatever state it is in.
     */
    private final Map<Material, MapBaseColor[]> byAge = new EnumMap<>(Material.class);

    /**
     * Coverage per {@link Material#ordinal()}, an array rather than a map because the
     * walk reads it for every block of every ray. All {@link #FULL} until something asks
     * otherwise, which is also the whole table when coverage is switched off.
     */
    private final float[] coverage = new float[Material.values().length];

    /**
     * Whether anything at all covers less than its cell, so the walk can keep its old
     * fast path when nothing does.
     */
    private boolean anyPartial;

    /**
     * Blocks reporting a base color this build's table does not know, counted across
     * the whole load so one warning covers them all.
     */
    private int unknown;

    private BlockColorTable() {
        Arrays.fill(coverage, FULL);
    }

    /**
     * Reads real map colors from the server, then applies user overrides.
     */
    public static BlockColorTable load(Izomap plugin) {
        var table = new BlockColorTable();
        table.readFromServer(plugin);
        if (plugin.config().correctVanillaColors())
            table.applyCorrections(plugin);

        var file = loadFile(plugin);
        table.applyOverrides(plugin, file);
        if (plugin.config().partialCoverage())
            table.applyCoverage(plugin, file);

        plugin.messages().info("log.block-colors-ready",
                Placeholder.unparsed("count", String.valueOf(table.colors.size())));
        return table;
    }

    /**
     * A table built from values rather than from a server, for tests: they have neither
     * a plugin to read the file with nor a registry to ask block colours of.
     */
    static BlockColorTable of(Map<Material, MapBaseColor> colors, Map<Material, Float> coverage) {
        var table = new BlockColorTable();
        table.colors.putAll(colors);
        coverage.forEach(table::setCoverage);
        return table;
    }

    /**
     * Map base color of a material. Blocks that do not show on maps (air, glass,
     * torches, saplings) return {@link MapBaseColor#NONE} and the render must treat
     * them as transparent and continue the ray, as vanilla maps do.
     */
    public MapBaseColor baseColorOf(Material material) {
        var color = colors.get(material);
        return color != null ? color : MapBaseColor.NONE;
    }

    /**
     * How much of its own cell a block fills, {@code 1.0} for the great majority.
     *
     * <p>The walk spends anything below that as alpha: the ray carries on and the colour
     * is mixed into what stands behind it, which is how a tuft of grass tints the ground
     * it grows on instead of replacing it.</p>
     */
    public float coverageOf(Material material) {
        return coverage[material.ordinal()];
    }

    /**
     * Whether anything in this table covers less than its whole cell.
     */
    public boolean anyPartial() {
        return anyPartial;
    }

    /**
     * Whether a block hides what is behind it, which is the only sense in which one is
     * solid to shadows and ambient occlusion.
     *
     * <p>Colourless blocks never did — glass hides nothing on a map. Thin ones now do not
     * either: a tuft of grass that lets the ground show through it has no business casting
     * a block-sized shadow across that ground.</p>
     */
    public boolean occludes(Material material) {
        return baseColorOf(material) != MapBaseColor.NONE
               && coverage[material.ordinal()] >= OPAQUE_ENOUGH;
    }

    /**
     * Whether this material's color depends on the state of the individual block, and
     * the walk therefore has to look that state up.
     */
    public boolean variesByState(Material material) {
        return byAge.containsKey(material);
    }

    /**
     * Color of one particular block. Falls back to the material's own color for a
     * state that carries no age, so a caller may always ask.
     */
    public MapBaseColor baseColorOf(Material material, BlockData data) {
        var ages = byAge.get(material);
        if (ages == null || !(data instanceof Ageable ageable))
            return baseColorOf(material);

        var age = ageable.getAge();
        return age >= 0 && age < ages.length ? ages[age] : baseColorOf(material);
    }

    /**
     * Reads the map color of every material, and of every age of the ones that grow
     * into a different one.
     */
    private void readFromServer(Izomap plugin) {
        var varying = 0;
        for (var material : Material.values()) {
            if (material.isLegacy() || !material.isBlock()) continue;

            BlockData data;
            try {
                data = material.createBlockData();
            } catch (RuntimeException ex) {
                // Materials without a usable block state do not show on maps either.
                continue;
            }
            var base = colorOf(data);
            if (base == null) continue;

            colors.put(material, base);

            var ages = readAgedColors(data, base);
            if (ages != null) {
                byAge.put(material, ages);
                varying++;
            }
        }
        if (unknown > 0) {
            plugin.messages().warn("log.unknown-base-colors",
                    Placeholder.unparsed("count", String.valueOf(unknown)));
        }
        if (varying > 0) {
            plugin.messages().info("log.state-colors-ready",
                    Placeholder.unparsed("count", String.valueOf(varying)));
        }
    }

    /**
     * One color per age, or {@code null} when the block has no age or wears the same
     * color at all of them — which is nearly every ageable block, and the case the ray
     * walk must not pay for.
     */
    private MapBaseColor[] readAgedColors(BlockData data, MapBaseColor base) {
        if (!(data instanceof Ageable ageable))
            return null;

        var ages = new MapBaseColor[ageable.getMaximumAge() + 1];
        var varies = false;
        for (var age = 0; age < ages.length; age++) {
            var probe = (Ageable) data.clone();
            probe.setAge(age);
            var color = colorOf((BlockData) probe);
            ages[age] = color != null ? color : base;
            varies |= ages[age] != base;
        }
        return varies ? ages : null;
    }

    /**
     * The base color a block state reports, or {@code null} when it has none to give.
     * An unrecognized one falls back to the nearest and is counted for the warning.
     */
    private MapBaseColor colorOf(BlockData data) {
        int rgb;
        try {
            rgb = data.getMapColor().asRGB();
        } catch (RuntimeException ex) {
            return null;
        }
        var base = MapBaseColor.byBaseRgb(rgb);
        if (base != null)
            return base;

        unknown++;
        return nearestBase(rgb);
    }

    /**
     * Replaces the colours vanilla assigns to blocks it does not resemble.
     *
     * <p>Applied before {@code block-colors.yml}, so a server owner overrides the
     * correction as easily as the original.</p>
     */
    private void applyCorrections(Izomap plugin) {
        var applied = 0;
        for (var entry : CORRECTIONS.entrySet()) {
            // A material this build does not know is not an error; the list outlives
            // the versions it was written against.
            if (!colors.containsKey(entry.getKey())) continue;

            colors.put(entry.getKey(), entry.getValue());
            byAge.remove(entry.getKey());
            applied++;
        }
        if (applied > 0) {
            plugin.messages().info("log.block-colors-corrected",
                    Placeholder.unparsed("count", String.valueOf(applied)));
        }
    }

    /**
     * Applies the overrides from {@code block-colors.yml}.
     */
    private void applyOverrides(Izomap plugin, YamlConfiguration cfg) {
        var section = cfg.getConfigurationSection("overrides");
        if (section == null) return;

        var applied = 0;
        for (var key : section.getKeys(false)) {
            var material = Material.matchMaterial(key);
            if (material == null || !material.isBlock()) {
                plugin.messages().warn("log.override-unknown-block",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key));
                continue;
            }
            var raw = section.getString(key);
            var base = parseBaseColor(raw);
            if (base == null) {
                plugin.messages().warn("log.override-invalid-color",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key),
                        Placeholder.unparsed("color", String.valueOf(raw)));
                continue;
            }
            colors.put(material, base);
            // An override is one color for the whole material, ages included; keeping
            // the age table would let it win back over what the owner asked for.
            byAge.remove(material);
            applied++;
        }
        if (applied > 0) {
            plugin.messages().info("log.block-colors-overridden",
                    Placeholder.unparsed("count", String.valueOf(applied)));
        }
    }

    /**
     * Fills the coverage table: the built-in list first, then {@code coverage:} from the
     * file over it.
     *
     * <p>Unlike the colours, this cannot be read off the server. {@code BlockData#getCollisionShape}
     * gives a <b>collision</b> box rather than a visual one, and for exactly the blocks
     * this is about — grass, flowers, glow lichen, vines — that box is <b>empty</b>. An
     * automatic derivation would therefore miss every block it was meant to fix, so the
     * list is kept by hand.</p>
     */
    private void applyCoverage(Izomap plugin, YamlConfiguration cfg) {
        DEFAULT_COVERAGE.forEach((material, value) -> {
            // A block this build does not know, or one an override turned colourless,
            // has no cell to cover.
            if (colors.containsKey(material))
                setCoverage(material, value);
        });

        var section = cfg.getConfigurationSection("coverage");
        if (section != null) {
            for (var key : section.getKeys(false)) {
                var material = Material.matchMaterial(key);
                if (material == null || !material.isBlock()) {
                    plugin.messages().warn("log.override-unknown-block",
                            Placeholder.unparsed("file", FILE_NAME),
                            Placeholder.unparsed("block", key));
                    continue;
                }
                var value = section.getDouble(key, -1.0);
                if (value < 0.0 || value > 1.0) {
                    plugin.messages().warn("log.coverage-invalid",
                            Placeholder.unparsed("file", FILE_NAME),
                            Placeholder.unparsed("block", key),
                            Placeholder.unparsed("value", String.valueOf(section.get(key))));
                    continue;
                }
                setCoverage(material, (float) value);
            }
        }
        if (anyPartial) {
            plugin.messages().info("log.coverage-ready",
                    Placeholder.unparsed("count", String.valueOf(partialCount())));
        }
    }

    private void setCoverage(Material material, float value) {
        coverage[material.ordinal()] = value;
        anyPartial |= value < FULL;
    }

    private int partialCount() {
        var count = 0;
        for (var value : coverage) {
            if (value < FULL) count++;
        }
        return count;
    }

    /**
     * Loads the file, writing the default one out the first time.
     *
     * <p>The file carries a {@code version} field that nothing reads yet. Until the
     * plugin is released there are no installs to migrate, so the loader takes whatever
     * is on disk; the field is there for the day migrations become real.</p>
     */
    private static YamlConfiguration loadFile(Izomap plugin) {
        var file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists())
            plugin.saveResource(FILE_NAME, false);

        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Accepts a base color name ("GRASS") or hex ("#7FB238").
     */
    private static MapBaseColor parseBaseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var value = raw.trim();
        var named = MapBaseColor.byName(value);
        if (named != null) return named;

        var hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            var rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
            var exact = MapBaseColor.byBaseRgb(rgb);
            return exact != null ? exact : nearestBase(rgb);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Nearest base color to an arbitrary RGB, excluding transparent NONE.
     */
    private static MapBaseColor nearestBase(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        var best = MapBaseColor.STONE;
        var bestDistance = Long.MAX_VALUE;
        for (var candidate : MapBaseColor.values()) {
            if (candidate == MapBaseColor.NONE) continue;

            var cr = (candidate.baseRgb() >> 16) & 0xFF;
            var cg = (candidate.baseRgb() >> 8) & 0xFF;
            var cb = candidate.baseRgb() & 0xFF;
            var dr = r - cr;
            var dg = g - cg;
            var db = b - cb;
            var distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
