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

    private final UUID id;
    private final UUID owner;
    private String name;

    /** Entity'lerin bulunduğu dünya konumu (çıpa). Kamera taşınınca güncellenir. */
    private Location anchor;

    private UUID displayEntityId;
    private UUID interactionEntityId;

    /** Kameranın bakış açıları ve ölçeği (fotoğraf yönü bunlardan türetilir). */
    private float camYaw;
    private float camPitch;
    private float scale;

    private AspectRatio aspectRatio;
    private ColorFilter colorFilter;

    /** Kalıcı değildir: hangi özelliğin ayarlandığını tutar. */
    private transient EditProperty editProperty = EditProperty.YAW;

    public Camera(UUID id, UUID owner, String name, Location anchor) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.anchor = anchor;
        this.camYaw = anchor.getYaw();
        this.camPitch = anchor.getPitch();
        this.scale = 1.0f;
        this.aspectRatio = AspectRatio.RATIO_1_1;
        this.colorFilter = ColorFilter.ORIGINAL;
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

    public float scale() {
        return scale;
    }

    public void scale(float scale) {
        this.scale = Math.max(0.1f, scale);
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
