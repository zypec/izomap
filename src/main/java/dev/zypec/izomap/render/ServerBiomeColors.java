package dev.zypec.izomap.render;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftServer;

/**
 * The colours the server itself tints grass, foliage and water with, per biome.
 *
 * <h2>The only class in the plugin that touches server internals</h2>
 *
 * <p>Bukkit does not expose them: {@code org.bukkit.block.Biome} is a key and nothing
 * else, and the numbers live in the biome's special effects on the server side. The
 * alternative was a table of hex values shipped in the jar, which would have to be
 * rewritten every time Mojang adds a biome and would leave every datapack biome
 * untinted — so this asks the server, exactly as the block colours do.</p>
 *
 * <p>Everything that could break with a server update is therefore confined to this one
 * class, it is touched <b>once at load</b> rather than per pixel, and
 * {@link BiomeTints} catches whatever it throws — including the {@link LinkageError} a
 * renamed class gives — and carries on with the tint switched off. That is also why the
 * internals are not touched from a field initializer or a static block: the class must
 * not load until somebody calls it inside a try.</p>
 *
 * <h2>Why the grass colour is asked for at the origin</h2>
 *
 * <p>{@code getGrassColor} takes coordinates because two biomes modify it per position:
 * swamp picks between two greens by noise, and dark forest darkens whatever it gets.
 * Asking per block would mean a server-internals call in the ray loop, so the table takes
 * the colour at (0,0) and a swamp comes out in one of its two greens rather than both.</p>
 */
final class ServerBiomeColors {

    private ServerBiomeColors() {
    }

    /**
     * Grass, foliage and water colour of a biome (0xRRGGBB each), or {@code null} when
     * the server has no such biome.
     */
    static int[] read(NamespacedKey key) {
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var biome = server.registryAccess().lookupOrThrow(Registries.BIOME)
                .getValue(Identifier.fromNamespaceAndPath(key.getNamespace(), key.getKey()));
        if (biome == null)
            return null;

        return new int[]{
                biome.getGrassColor(0.0, 0.0) & 0xFFFFFF,
                biome.getFoliageColor() & 0xFFFFFF,
                biome.getWaterColor() & 0xFFFFFF};
    }
}
