package dev.zypec.izomap.camera;

import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Bir kamerayı temsil eden veri modeli.
 *
 * <p>Kamera dünyada iki entity ile modellenir: görsel model ({@code ItemDisplay}
 * veya {@code BlockDisplay}) ve tık algılama için bir {@code Interaction} entity.
 * Bu sınıf yalnızca durum tutar; entity işlemleri {@link CameraManager}
 * tarafından yürütülür.</p>
 */
public final class Camera {

    /** En geniş manzara: kadraj yüksekliğinin 50 katı (48 blok -> 2400 blok). */
    public static final float MIN_ZOOM = 0.02f;
    /** En yakın plan: kadraj yüksekliğinin 1/16'sı (48 blok -> 3 blok). */
    public static final float MAX_ZOOM = 16.0f;

    private final UUID id;
    private final UUID owner;
    private String name;

    /** Entity'lerin bulunduğu dünya konumu (çıpa). Kamera taşınınca güncellenir. */
    private Location anchor;

    private UUID displayEntityId;
    private UUID interactionEntityId;

    /** Kameranın bakış açıları ve yakınlaştırması (fotoğraf yönü bunlardan türetilir). */
    private float camYaw;
    private float camPitch;
    private float zoom;

    private AspectRatio aspectRatio;
    private ColorFilter colorFilter;

    /** Önizlemede üçler kuralı kılavuzu gösterilsin mi (fotoğrafa girmez). */
    private boolean thirdsGuide;

    /** Kalıcı değildir: hangi özelliğin ayarlandığını tutar. */
    private transient EditProperty editProperty = EditProperty.YAW;

    public Camera(UUID id, UUID owner, String name, Location anchor) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.anchor = anchor;
        this.camYaw = anchor.getYaw();
        this.camPitch = anchor.getPitch();
        this.zoom = 1.0f;
        this.aspectRatio = AspectRatio.RATIO_1_1;
        this.colorFilter = ColorFilter.ORIGINAL;
        this.thirdsGuide = false;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public Location anchor() {
        return anchor.clone();
    }

    public void anchor(Location anchor) {
        this.anchor = anchor.clone();
    }

    public UUID displayEntityId() {
        return displayEntityId;
    }

    public void displayEntityId(UUID displayEntityId) {
        this.displayEntityId = displayEntityId;
    }

    public UUID interactionEntityId() {
        return interactionEntityId;
    }

    public void interactionEntityId(UUID interactionEntityId) {
        this.interactionEntityId = interactionEntityId;
    }

    public float camYaw() {
        return camYaw;
    }

    public void camYaw(float camYaw) {
        this.camYaw = normalizeYaw(camYaw);
    }

    public float camPitch() {
        return camPitch;
    }

    public void camPitch(float camPitch) {
        this.camPitch = Math.max(-90.0f, Math.min(90.0f, camPitch));
    }

    /**
     * Yakınlaştırma çarpanı. Kadrajın kapsadığı alan
     * {@code photo.frame-height / zoom} bloktur: 1.0 varsayılan, 0.25 dört kat
     * geniş manzara, 4.0 dört kat yakın plan.
     *
     * <p>Kamera modelinin görsel boyutuyla ilgisi yoktur; model boyutu
     * {@code camera.model-scale} ile ayarlanır.</p>
     */
    public float zoom() {
        return zoom;
    }

    public void zoom(float zoom) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    public AspectRatio aspectRatio() {
        return aspectRatio;
    }

    public void aspectRatio(AspectRatio aspectRatio) {
        this.aspectRatio = aspectRatio;
    }

    public ColorFilter colorFilter() {
        return colorFilter;
    }

    public void colorFilter(ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
    }

    /**
     * Üçler kuralı kılavuzu (fotoğrafçılıktaki 3×3 kadraj çizgileri).
     * Yalnızca <b>canlı önizlemeye</b> çizilir; çekilen fotoğrafa asla girmez.
     * Varsayılan kapalıdır, Dialog'daki butonla açılır.
     */
    public boolean thirdsGuide() {
        return thirdsGuide;
    }

    public void thirdsGuide(boolean thirdsGuide) {
        this.thirdsGuide = thirdsGuide;
    }

    public EditProperty editProperty() {
        return editProperty;
    }

    public void editProperty(EditProperty editProperty) {
        this.editProperty = editProperty;
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw % 360.0f;
        if (y >= 180.0f) {
            y -= 360.0f;
        } else if (y < -180.0f) {
            y += 360.0f;
        }
        return y;
    }
}
