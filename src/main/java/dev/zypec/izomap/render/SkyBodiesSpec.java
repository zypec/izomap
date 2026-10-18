package dev.zypec.izomap.render;

/**
 * What the sky carries besides its colour: a sun, a moon and stars.
 *
 * <p>Every one of them is the server's setting rather than the player's. The player
 * answers one question — whether this camera draws them at all — for the same reason
 * they answer only <i>which</i> sky: a size in pixels and a star density are not a
 * decision anyone makes while framing a shot.</p>
 *
 * <p>Frozen into {@link SkySpec} at capture like the sky colour itself, so a photo
 * re-rendered after the server retunes its sky keeps the one that was hung up.</p>
 *
 * @param sun          whether the sun disc is drawn while it is up
 * @param moon         whether the moon is drawn while it is up
 * @param stars        whether stars are drawn once the sky is dark enough
 * @param sunSize      sun diameter, as a ratio of the image's height
 * @param moonSize     moon diameter, as a ratio of the image's height
 * @param starDensity  share of the star grid's cells that hold a star, 0 to 1
 * @param sunRgb       0xRRGGBB the disc is painted with, before the palette snap
 * @param moonRgb      0xRRGGBB the lit part of the moon is painted with
 * @param starRgb      0xRRGGBB a star reaches at a fully dark sky
 */
public record SkyBodiesSpec(
        boolean sun,
        boolean moon,
        boolean stars,
        double sunSize,
        double moonSize,
        double starDensity,
        int sunRgb,
        int moonRgb,
        int starRgb) {

    /**
     * An empty sky: colour and gradient only, as it was before this existed.
     */
    public static final SkyBodiesSpec NONE =
            new SkyBodiesSpec(false, false, false, 0.0, 0.0, 0.0, 0, 0, 0);

    /**
     * Whether anything here would be drawn at all.
     */
    public boolean enabled() {
        return sun || moon || stars;
    }
}
