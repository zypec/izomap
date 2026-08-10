package dev.zypec.izomap.camera;

import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import org.bukkit.Location;

import java.util.UUID;

/**
 * State of a single camera.
 *
 * <p>A camera exists in the world as two entities: a display model and an
 * {@code Interaction} entity for clicks. This class only holds state; entity work
 * belongs to {@link CameraManager}.</p>
 */
public final class Camera {

    /** Widest shot: 50x the frame height. */
    public static final float MIN_ZOOM = 0.02f;
    /** Closest shot: 1/16 of the frame height. */
    public static final float MAX_ZOOM = 16.0f;

    /** No preview map has been created for this camera yet. */
    public static final int NO_PREVIEW_MAP = -1;

    private final UUID id;
    private final UUID owner;
    private String name;

    /** World location the entities sit at. */
    private Location anchor;

    private UUID displayEntityId;
    private UUID interactionEntityId;

    /** View angles the photo direction is derived from. */
    private float camYaw;
    private float camPitch;
    private float zoom;

    private AspectRatio aspectRatio;
    private ColorFilter colorFilter;

    private boolean thirdsGuide;

    /** Id of the reused preview map, or {@link #NO_PREVIEW_MAP}. */
    private int previewMapId = NO_PREVIEW_MAP;

    /** Not persisted: which property the owner is currently adjusting. */
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
        this.camPitch = Math.clamp(camPitch, -90.0f, 90.0f);
    }

    /**
     * Zoom multiplier: the frame covers {@code photo.frame-height / zoom} blocks.
     * Unrelated to the model's visual size, which is {@code camera.model-scale}.
     */
    public float zoom() {
        return zoom;
    }

    public void zoom(float zoom) {
        this.zoom = Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
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
     * Rule-of-thirds guide. Drawn on the live preview only, never on a capture.
     */
    public boolean thirdsGuide() {
        return thirdsGuide;
    }

    public void thirdsGuide(boolean thirdsGuide) {
        this.thirdsGuide = thirdsGuide;
    }

    /**
     * Map the live preview is drawn on, reused for the camera's whole life. Every
     * session creating its own would spend a map id per open and close.
     */
    public int previewMapId() {
        return previewMapId;
    }

    public void previewMapId(int previewMapId) {
        this.previewMapId = previewMapId;
    }

    public EditProperty editProperty() {
        return editProperty;
    }

    public void editProperty(EditProperty editProperty) {
        this.editProperty = editProperty;
    }

    private static float normalizeYaw(float yaw) {
        var y = yaw % 360.0f;
        if (y >= 180.0f) {
            y -= 360.0f;
        } else if (y < -180.0f) {
            y += 360.0f;
        }
        return y;
    }
}
