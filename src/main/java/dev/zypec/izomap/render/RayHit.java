package dev.zypec.izomap.render;

import org.bukkit.Material;

/**
 * What a single ray found, handed from the voxel walk to {@link ColorPipeline}.
 *
 * <p>Deliberately mutable and reused: one instance per render band is filled and read
 * again for every sample, so the walk can publish several values without allocating
 * millions of short-lived objects. An instance never leaves the thread that made it.</p>
 *
 * <p>{@link #base} travels alongside {@link #material} because the walk has to resolve
 * it anyway — a block with no map color is see-through and the ray continues past it —
 * so the color stage would only be repeating the lookup.</p>
 */
final class RayHit {

    /**
     * How many see-through blocks a ray may collect before the next one is taken as
     * opaque.
     *
     * <p>Without a cap a curtain of vines or a tall meadow would keep a ray walking to
     * the far side of the captured region, and three layers already reach 0.66 opacity at
     * the thinnest tier — beyond that the floor contributes less than the palette can
     * express.</p>
     */
    static final int MAX_LAYERS = 3;

    /**
     * Face of the block the ray entered through, with the two horizontal axes kept
     * apart because they take different brightnesses.
     */
    enum Face {
        TOP,
        BOTTOM,
        SIDE_X,
        SIDE_Z
    }

    /**
     * Whether the ray found anything to draw, whether a surface or thin layers alone.
     */
    boolean hit;

    /**
     * Whether {@link #base}, {@link #face} and {@link #darken} describe a surface the ray
     * actually stopped on. False when it only gathered layers and then ran out of world,
     * in which case those three mean nothing.
     */
    boolean opaque;

    /**
     * Which biome tint the surface takes, or {@link #NO_TINT} when it looks the same
     * wherever it stands — which is nearly every block.
     */
    int tint;

    /**
     * A surface no biome colours.
     */
    static final int NO_TINT = -1;

    /**
     * See-through blocks the ray passed on its way here, nearest the camera first: a
     * tuft of grass, a vine, or a column of water the floor shows through.
     */
    int layers;
    final MapBaseColor[] layerBase = new MapBaseColor[MAX_LAYERS];
    final Face[] layerFace = new Face[MAX_LAYERS];
    final int[] layerTint = new int[MAX_LAYERS];
    /**
     * Share of the pixel each layer holds — its own coverage times whatever the layers in
     * front of it left over — so the weights are already comparable and never renormalized.
     */
    final double[] layerWeight = new double[MAX_LAYERS];

    /**
     * Share of the pixel the layers left to whatever is behind them. 1.0 means there are
     * none, which is the case for nearly every ray.
     */
    double transmittance;

    Material material;

    MapBaseColor base;

    Face face;

    /**
     * How many brightness steps down the face's own shade the surface goes, from the
     * sun shadow and the neighbours around it. Zero is the plain face brightness.
     *
     * <p>The layers in front of it take the same number: a tuft of grass stands in the
     * light the ground under it stands in, and asking the shading again per layer would
     * cost a second shadow ray for every blade in the frame.</p>
     */
    int darken;

    /**
     * Clears the hit for a fresh ray. Only the counters have to go: the layer arrays are
     * read no further than {@link #layers}.
     */
    void reset() {
        hit = false;
        opaque = false;
        layers = 0;
        transmittance = 1.0;
        darken = 0;
        tint = NO_TINT;
    }

    /**
     * Records a see-through block and returns whether there was room for it. A refusal is
     * the caller's cue to treat the block as opaque, which is what caps the walk.
     */
    boolean addLayer(MapBaseColor base, Face face, int tint, double coverage) {
        if (layers >= MAX_LAYERS)
            return false;

        layerBase[layers] = base;
        layerFace[layers] = face;
        layerTint[layers] = tint;
        layerWeight[layers] = coverage * transmittance;
        transmittance -= layerWeight[layers];
        layers++;
        return true;
    }

    /**
     * How much of the pixel the layers have taken so far.
     */
    double covered() {
        return 1.0 - transmittance;
    }

    /**
     * How far the ray travelled to reach the surface, in blocks, measured from where it
     * started. The walk keeps that number anyway to know when to give up, so depth of
     * field costs it nothing; a ray that was pulled back has its backoff subtracted by
     * the caller, which is what turns this into a distance from the camera plane.
     */
    double distance;
}
