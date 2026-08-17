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
import java.util.Locale;
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
 * <p>{@link #FLOWER_COLORS} is the same switch for a different kind of disagreement:
 * vanilla paints every flower plant-green, which is right for a map and wrong for a
 * photograph.</p>
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
     * Flowers, whose vanilla map colour is the green of the plant they are rather than
     * the colour of the blossom they are grown for.
     *
     * <p>Vanilla gives every flower {@code PLANT} (#007C00), which is the right answer on
     * a map — one pixel per column, at which size a meadow is greenery — and the wrong one
     * in a photo, where a poppy is a red dot in the grass and a dandelion a yellow one.
     * With coverage the flower only holds about a third of its cell, so what this buys is
     * a tint of the right hue, not a block of colour.</p>
     *
     * <p><b>Measured from the blossom, not from the texture.</b> The plain texture average
     * is useless here: a flower texture is mostly stem and leaves, so red tulip averages
     * <i>green</i> (#5A8121) and vanilla's choice looks defensible. Green-dominant pixels
     * (g more than 12 over both r and b) are therefore dropped and the rest averaged, which
     * is the blossom. The ranking then uses the same redmean distance
     * {@link MapColorConverter} snaps with.</p>
     *
     * <p><b>Hue outranks lightness when the two disagree.</b> Nearest-by-distance sends
     * every pale blossom to a grey — pink petals (#F7B5DB) land on {@code WOOL} — because
     * the palette's chromatic entries are more saturated than a real petal. A grey flower
     * has lost the only thing it was in the frame for, so a chromatic entry wins whenever
     * the blossom itself has real chroma. Those picks are marked below.</p>
     *
     * <table>
     *   <caption>Blossom average and the entry chosen for it</caption>
     *   <tr><th>Blossom</th><th>Measured</th><th>Chosen</th></tr>
     *   <tr><td>dandelion #F5CE40, sunflower #F6C536</td><td>nearest</td><td>{@code COLOR_YELLOW}</td></tr>
     *   <tr><td>wildflowers #EDD675</td><td>{@code GOLD} 6074 ties {@code SAND} 6077</td><td>{@code GOLD}, the yellower half of the tie</td></tr>
     *   <tr><td>poppy #C92925, red tulip #D32D2A, rose bush #C12A24</td><td>nearest</td><td>{@code CRIMSON_NYLIUM}</td></tr>
     *   <tr><td>orange tulip #D98527</td><td>nearest</td><td>{@code COLOR_ORANGE}</td></tr>
     *   <tr><td>torchflower #A06956</td><td>{@code DIRT} 468</td><td>{@code TERRACOTTA_ORANGE} 8051 — hue; see below</td></tr>
     *   <tr><td>cornflower #546EDF</td><td>nearest</td><td>{@code LAPIS}</td></tr>
     *   <tr><td>blue orchid #25AAED</td><td>{@code LAPIS} 10991 vs {@code COLOR_LIGHT_BLUE} 11954</td><td>{@code COLOR_LIGHT_BLUE}, a 9% tie broken on hue</td></tr>
     *   <tr><td>pitcher plant #797EBA</td><td>nearest</td><td>{@code COLOR_LIGHT_BLUE}</td></tr>
     *   <tr><td>allium #BA85E5</td><td>{@code ICE} 6293</td><td>{@code COLOR_MAGENTA} 13555 — hue; ICE would read pale blue</td></tr>
     *   <tr><td>lilac #BE75C0</td><td>nearest</td><td>{@code COLOR_MAGENTA}</td></tr>
     *   <tr><td>pink tulip #EBC4FA, peony #E6B3F7, pink petals #F7B5DB</td><td>{@code WOOL}</td><td>{@code COLOR_PINK} — hue</td></tr>
     *   <tr><td>spore blossom #CF619F, cactus flower #D47889</td><td>nearest</td><td>{@code COLOR_PINK}</td></tr>
     *   <tr><td>white tulip #CDDFDF, open eyeblossom #C4BAC0</td><td>nearest, and no chroma to keep</td><td>{@code WOOL}</td></tr>
     *   <tr><td>lily of the valley #EDEDED</td><td>nearest</td><td>{@code QUARTZ}</td></tr>
     *   <tr><td>azure bluet #EEEFC1, oxeye daisy #E3E1BC</td><td>nearest</td><td>{@code SAND}</td></tr>
     *   <tr><td>closed eyeblossom #6C6265</td><td>nearest, distance 172</td><td>{@code DEEPSLATE}</td></tr>
     *   <tr><td>wither rose #292619</td><td>nearest</td><td>{@code TERRACOTTA_GRAY}</td></tr>
     * </table>
     *
     * <p>Torchflower is the one entry the measurement could not settle: its texture is a
     * dark purple body (#652D70) with a small bright bloom (#FCE257, #F6B927), so the
     * average lands almost exactly on {@code DIRT} — which would hide it in the ground it
     * grows out of. {@code TERRACOTTA_ORANGE} is the nearest warm entry and keeps the
     * bloom.</p>
     */
    private static final Map<Material, MapBaseColor> FLOWER_COLORS = Map.ofEntries(
            Map.entry(Material.DANDELION, MapBaseColor.COLOR_YELLOW),
            Map.entry(Material.SUNFLOWER, MapBaseColor.COLOR_YELLOW),
            Map.entry(Material.WILDFLOWERS, MapBaseColor.GOLD),
            Map.entry(Material.POPPY, MapBaseColor.CRIMSON_NYLIUM),
            Map.entry(Material.RED_TULIP, MapBaseColor.CRIMSON_NYLIUM),
            Map.entry(Material.ROSE_BUSH, MapBaseColor.CRIMSON_NYLIUM),
            Map.entry(Material.ORANGE_TULIP, MapBaseColor.COLOR_ORANGE),
            Map.entry(Material.TORCHFLOWER, MapBaseColor.TERRACOTTA_ORANGE),
            Map.entry(Material.CORNFLOWER, MapBaseColor.LAPIS),
            Map.entry(Material.BLUE_ORCHID, MapBaseColor.COLOR_LIGHT_BLUE),
            Map.entry(Material.PITCHER_PLANT, MapBaseColor.COLOR_LIGHT_BLUE),
            Map.entry(Material.ALLIUM, MapBaseColor.COLOR_MAGENTA),
            Map.entry(Material.LILAC, MapBaseColor.COLOR_MAGENTA),
            Map.entry(Material.PINK_TULIP, MapBaseColor.COLOR_PINK),
            Map.entry(Material.PEONY, MapBaseColor.COLOR_PINK),
            Map.entry(Material.PINK_PETALS, MapBaseColor.COLOR_PINK),
            Map.entry(Material.SPORE_BLOSSOM, MapBaseColor.COLOR_PINK),
            Map.entry(Material.CACTUS_FLOWER, MapBaseColor.COLOR_PINK),
            Map.entry(Material.WHITE_TULIP, MapBaseColor.WOOL),
            Map.entry(Material.OPEN_EYEBLOSSOM, MapBaseColor.WOOL),
            Map.entry(Material.LILY_OF_THE_VALLEY, MapBaseColor.QUARTZ),
            Map.entry(Material.AZURE_BLUET, MapBaseColor.SAND),
            Map.entry(Material.OXEYE_DAISY, MapBaseColor.SAND),
            Map.entry(Material.CLOSED_EYEBLOSSOM, MapBaseColor.DEEPSLATE),
            Map.entry(Material.WITHER_ROSE, MapBaseColor.TERRACOTTA_GRAY));

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
     *
     * <p>{@link #FLOWER_COLORS} joins them: same criterion, different reason for vanilla
     * having got there.</p>
     */
    private static final Map<Material, MapBaseColor> CORRECTIONS = corrections();

    private static Map<Material, MapBaseColor> corrections() {
        Map<Material, MapBaseColor> map = new EnumMap<>(Material.class);
        map.putAll(FLOWER_COLORS);
        map.putAll(Map.ofEntries(
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
            Map.entry(Material.CHISELED_TUFF_BRICKS, MapBaseColor.DEEPSLATE)));
        return map;
    }

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
                Material.OPEN_EYEBLOSSOM, Material.CLOSED_EYEBLOSSOM, Material.CACTUS_FLOWER,
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

    /**
     * Blocks the world tints by biome, and which of the biome's three colours each one
     * takes.
     *
     * <p>Hand-kept for the same reason coverage is: which blocks the client runs through
     * a colormap is decided in its rendering code, not in anything a server can be asked.
     * The list is the colormap-tinted ones only — spruce, birch, cherry and azalea leaves
     * carry a <b>fixed</b> colour of their own in vanilla and are left alone, and so is
     * every block whose colour never depended on where it stands.</p>
     */
    private static final Map<Material, BiomeTints.Channel> DEFAULT_TINTS = defaultTints();

    private static Map<Material, BiomeTints.Channel> defaultTints() {
        Map<Material, BiomeTints.Channel> map = new EnumMap<>(Material.class);
        for (var material : List.of(
                Material.GRASS_BLOCK, Material.SHORT_GRASS, Material.TALL_GRASS,
                Material.FERN, Material.LARGE_FERN, Material.SUGAR_CANE)) {
            map.put(material, BiomeTints.Channel.GRASS);
        }
        for (var material : List.of(
                Material.OAK_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES,
                Material.DARK_OAK_LEAVES, Material.VINE)) {
            map.put(material, BiomeTints.Channel.FOLIAGE);
        }
        for (var material : List.of(Material.WATER, Material.BUBBLE_COLUMN)) {
            map.put(material, BiomeTints.Channel.WATER);
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
     * Biome channel per {@link Material#ordinal()}, or {@code null} for the blocks that
     * look the same wherever they stand — which is nearly all of them.
     */
    private final BiomeTints.Channel[] tints = new BiomeTints.Channel[Material.values().length];

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

        table.applyTints(plugin, file);

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

    /**
     * Fills the biome-channel table: the built-in list, then {@code tint:} from the file
     * over it. Read whether or not the tint is switched on, so turning it on needs no
     * colour reload of its own.
     */
    private void applyTints(Izomap plugin, YamlConfiguration cfg) {
        DEFAULT_TINTS.forEach((material, channel) -> {
            if (colors.containsKey(material))
                tints[material.ordinal()] = channel;
        });

        var section = cfg.getConfigurationSection("tint");
        if (section == null) return;

        for (var key : section.getKeys(false)) {
            var material = Material.matchMaterial(key);
            if (material == null || !material.isBlock()) {
                plugin.messages().warn("log.override-unknown-block",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key));
                continue;
            }
            var raw = String.valueOf(section.getString(key)).trim().toUpperCase(Locale.ROOT);
            if (raw.equals("NONE")) {
                tints[material.ordinal()] = null;
                continue;
            }
            var channel = channelByName(raw);
            if (channel == null) {
                plugin.messages().warn("log.tint-channel-invalid",
                        Placeholder.unparsed("file", FILE_NAME),
                        Placeholder.unparsed("block", key),
                        Placeholder.unparsed("channel", String.valueOf(section.get(key))));
                continue;
            }
            tints[material.ordinal()] = channel;
        }
    }

    private static BiomeTints.Channel channelByName(String name) {
        for (var channel : BiomeTints.Channel.values()) {
            if (channel.name().equals(name))
                return channel;
        }
        return null;
    }

    /**
     * Which of a biome's colours this block is painted with, or {@code null} when it
     * looks the same everywhere.
     */
    public BiomeTints.Channel tintChannelOf(Material material) {
        return tints[material.ordinal()];
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
