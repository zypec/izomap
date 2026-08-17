package dev.zypec.izomap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * A chunk copy made of a function instead of a world, so the voxel walk can be driven
 * against a landscape written out in a test.
 *
 * <p>Everything the walk does not ask for throws rather than returning a plausible
 * zero: a walk that starts reading biomes or block states should fail the test that
 * covers it, not quietly render something.</p>
 */
record FakeChunk(int cx, int cz, FakeChunk.Blocks blocks) implements ChunkSnapshot {

    /**
     * The landscape, addressed in world coordinates.
     */
    interface Blocks {
        Material at(int x, int y, int z);
    }

    /**
     * A one-chunk snapshot at the origin, which is where the tests put their columns.
     */
    static WorldSnapshot world(int minY, int maxY, Blocks blocks) {
        return WorldSnapshot.of(List.of(new FakeChunk(0, 0, blocks)), minY, maxY);
    }

    @Override
    public Material getBlockType(int x, int y, int z) {
        return blocks.at((cx << 4) + x, y, (cz << 4) + z);
    }

    @Override
    public int getX() {
        return cx;
    }

    @Override
    public int getZ() {
        return cz;
    }

    @Override
    public int getBlockSkyLight(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getBlockEmittedLight(int x, int y, int z) {
        return 0;
    }

    @Override
    public String getWorldName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamespacedKey getWorldKey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BlockData getBlockData(int x, int y, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("removal")
    public int getData(int x, int y, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getHighestBlockYAt(int x, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Biome getBiome(int x, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Biome getBiome(int x, int y, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double getRawBiomeTemperature(int x, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double getRawBiomeTemperature(int x, int y, int z) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCaptureFullTime() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSectionEmpty(int sectionY) {
        return false;
    }

    @Override
    public boolean contains(BlockData block) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Biome biome) {
        throw new UnsupportedOperationException();
    }
}
