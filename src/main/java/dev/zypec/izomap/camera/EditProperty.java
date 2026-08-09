package dev.zypec.izomap.camera;

/** Camera properties that can be adjusted by interacting with the model. */
public enum EditProperty {

    YAW,
    PITCH,
    ZOOM;

    /** Returns the next property, wrapping around. */
    public EditProperty next() {
        EditProperty[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
