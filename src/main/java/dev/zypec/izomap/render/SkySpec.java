package dev.zypec.izomap.render;

/**
 * The sky as one capture froze it: the colour, the moment it was taken from, and what
 * else the sky holds.
 *
 * <h2>Why the time is stored next to the colour</h2>
 *
 * <p>The colour alone used to be enough — it is what a missed ray is painted with, and
 * freezing it is what keeps a photo in the evening it was shot in. The bodies need the
 * clock itself: which of the two is above the horizon comes from the time of day, and
 * the moon's phase from the day count. Reading either again at render time would let a
 * photo drift with the world it was taken in, which is the one thing the frozen colour
 * exists to prevent.</p>
 *
 * @param argb      sky colour, or {@code 0} for no sky at all
 * @param ticks     time of day the shot was taken at, 0 to 23999
 * @param moonPhase moon phase at capture, 0 (full) to 7
 * @param bodies    what the sky carries besides its colour
 */
public record SkySpec(int argb, int ticks, int moonPhase, SkyBodiesSpec bodies) {

    /**
     * No sky: rays that reach nothing stay transparent, and nothing is drawn into them.
     */
    public static final SkySpec NONE = new SkySpec(0, 0, 0, SkyBodiesSpec.NONE);

    /**
     * Whether the photo has a sky to paint at all.
     */
    public boolean draws() {
        return argb != 0;
    }
}
