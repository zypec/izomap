package dev.zypec.izomap.camera;

/**
 * Camera properties that can be adjusted by interacting with the model.
 */
public enum EditProperty {

    YAW,
    PITCH,
    ZOOM,
    /**
     * Moves the camera along the line the <b>player</b> is looking down, which carries
     * height with it: one property covers every direction, and where the camera ends up
     * is aimed rather than assembled from separate axes.
     */
    MOVE;

    /**
     * Returns the next property, wrapping around.
     */
    public EditProperty next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
