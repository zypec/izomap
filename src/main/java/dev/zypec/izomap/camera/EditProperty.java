package dev.zypec.izomap.camera;

/**
 * Camera properties that can be adjusted by interacting with the model.
 */
public enum EditProperty {

    YAW,
    PITCH,
    ZOOM,
    /**
     * Moves along the horizontal projection of the view direction: forward/back.
     */
    MOVE_X,
    /**
     * Moves straight up and down.
     */
    MOVE_Y;

    /**
     * Returns the next property, wrapping around.
     */
    public EditProperty next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
