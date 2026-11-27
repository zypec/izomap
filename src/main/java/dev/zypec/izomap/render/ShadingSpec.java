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
 * @param skyLight          how bright the sky's light is, 0-15, from the hour of the
 *                          photo's sky: 15 by day, low at night. Below 15 the light is
 *                          read for every hit even with {@code blockLight} off, so a
 *                          night shot darkens outdoors and torches keep what they light
 */
public record ShadingSpec(
        boolean sunShadow,
        float sunYaw,
        float sunPitch,
        int shadowDistance,
        boolean ambientOcclusion,
        boolean blockLight,
        int dimBelow,
        int darkBelow,
        int skyLight) {

    /**
     * Full daylight: the sky's light is what the world stored.
     */
    public static final int DAYLIGHT = 15;

    /**
     * Face brightness alone, as the renderer has always done it.
     */
    public static final ShadingSpec NONE =
            new ShadingSpec(false, 0.0f, 0.0f, 0, false, false, 0, 0, DAYLIGHT);

    /**
     * The same shading with the sun put somewhere else; the sun belongs to the camera,
     * everything else here to the server.
     */
    public ShadingSpec withSun(float yaw, float pitch) {
        return new ShadingSpec(sunShadow, yaw, pitch, shadowDistance, ambientOcclusion,
                blockLight, dimBelow, darkBelow, skyLight);
    }

    /**
     * The same shading with the sun's shadow and ambient occlusion on, as the shader
     * style draws, at least {@code minDistance} blocks of shadow.
     */
    public ShadingSpec withShadows(int minDistance) {
        return new ShadingSpec(true, sunYaw, sunPitch, Math.max(shadowDistance, minDistance), true,
                blockLight, dimBelow, darkBelow, skyLight);
    }

    /**
     * The same shading under a sky this bright.
     */
    public ShadingSpec withSkyLight(int level) {
        return new ShadingSpec(sunShadow, sunYaw, sunPitch, shadowDistance, ambientOcclusion,
                blockLight, dimBelow, darkBelow, Math.max(0, Math.min(DAYLIGHT, level)));
    }

    /**
     * Whether the walk reads light levels: for block light, or for a dim sky.
     */
    public boolean readsLight() {
        return blockLight || skyLight < DAYLIGHT;
    }

    /**
     * Whether the walk has to do anything extra at all.
     */
    public boolean enabled() {
        return sunShadow || ambientOcclusion || readsLight();
    }
}
