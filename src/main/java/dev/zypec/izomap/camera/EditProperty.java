package dev.zypec.izomap.camera;

/**
 * Kamera üzerinde etkileşimle ayarlanabilen özellikler.
 * Shift + sağ tık ile sıradaki özelliğe geçilir.
 */
public enum EditProperty {

    YAW,
    PITCH,
    SCALE;

    /** Döngüsel olarak bir sonraki özelliği döndürür. */
    public EditProperty next() {
        EditProperty[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
