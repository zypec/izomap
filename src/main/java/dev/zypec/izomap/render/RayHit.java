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
     * Whether the ray hit anything at all; the other fields only hold when it did.
     */
    boolean hit;

    Material material;

    MapBaseColor base;

    Face face;

    /**
     * How many brightness steps down the face's own shade the surface goes, from the
     * sun shadow and the neighbours around it. Zero is the plain face brightness.
     */
    int darken;

    /**
     * How far the ray travelled to reach the surface, in blocks, measured from where it
     * started. The walk keeps that number anyway to know when to give up, so depth of
     * field costs it nothing; a ray that was pulled back has its backoff subtracted by
     * the caller, which is what turns this into a distance from the camera plane.
     */
    double distance;
}
