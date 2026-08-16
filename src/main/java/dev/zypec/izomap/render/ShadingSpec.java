package dev.zypec.izomap.render;

/**
 * What the walk is allowed to darken a surface for, beyond the face it entered through.
 *
 * <h2>Why everything here only ever darkens by whole steps</h2>
 *
 * <p>The map palette carries exactly four brightnesses per colour. There is no "slightly
 * darker": a surface either keeps its brightness or drops to the next one down. Every
 * technique here therefore decides <i>which of the four</i> a face takes, and none of
 * them produces a gradient — a shading model that wanted one would leave the palette,
 * and the maps could not store the result.</p>
 *
 * <p>Frozen into {@link CaptureSpec} like the other settings the image depends on, so a
 * photo re-rendered after the server retunes its lighting still looks like the photo
 * that was hung on the wall.</p>
 *
 * @param sunShadow        whether a second ray is cast towards the sun
 * @param sunYaw           direction the sun sits in, degrees
 * @param sunPitch         height of the sun above the horizon, degrees
 * @param shadowDistance   how far a shadow ray travels before giving up, in blocks
 * @param ambientOcclusion whether faces boxed in by their neighbours darken
 * @param blockLight       whether poorly lit surfaces darken
 * @param dimBelow          light level under which a surface loses one step
 * @param darkBelow         light level under which it loses two
 */
public record ShadingSpec(
        boolean sunShadow,
        float sunYaw,
        float sunPitch,
        int shadowDistance,
        boolean ambientOcclusion,
        boolean blockLight,
        int dimBelow,
        int darkBelow) {

    /**
     * Face brightness alone, as the renderer has always done it.
     */
    public static final ShadingSpec NONE =
            new ShadingSpec(false, 0.0f, 0.0f, 0, false, false, 0, 0);

    /**
     * Whether the walk has to do anything extra at all.
     */
    public boolean enabled() {
        return sunShadow || ambientOcclusion || blockLight;
    }
}
