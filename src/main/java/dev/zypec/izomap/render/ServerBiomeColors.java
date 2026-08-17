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
 * <h2>What the server can and cannot answer</h2>
 *
 * <p>Water it always knows: every biome names its water colour. Grass and foliage it
 * mostly does <b>not</b> — a biome only names those when it wants an unusual one, and
 * otherwise the game samples a colormap texture that exists only on the client. The
 * server's own {@code getGrassColor} therefore returns <b>0</b> for most biomes, and
 * believing it painted every meadow black.</p>
 *
 * <p>So the base colour is taken from the biome when it declares one and from
 * {@link Colormaps} when it does not, and the biome's own <b>modifier</b> is then applied
 * to it by the server's code — which is what keeps a swamp's olive and a dark forest's
 * dulled green exactly as the game draws them, rather than as something reimplemented
 * here.</p>
 *
 * <p>The modifier takes coordinates because swamp picks between two greens by noise.
 * Calling it per block would put server internals in the ray loop, so the table asks at
 * the origin and a swamp comes out in one of its two greens rather than both.</p>
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

        var climate = biome.climateSettings;
        var effects = biome.getSpecialEffects();
        var grass = effects.grassColorOverride()
                .orElseGet(() -> Colormaps.grass(climate.temperature(), climate.downfall()));
        var foliage = effects.foliageColorOverride()
                .orElseGet(() -> Colormaps.foliage(climate.temperature(), climate.downfall()));

        return new int[]{
                effects.grassColorModifier().modifyColor(0.0, 0.0, grass) & 0xFFFFFF,
                foliage & 0xFFFFFF,
                effects.waterColor() & 0xFFFFFF};
    }
}
