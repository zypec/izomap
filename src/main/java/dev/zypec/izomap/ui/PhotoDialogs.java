package dev.zypec.izomap.ui;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PhotoFrames;
import dev.zypec.izomap.map.PhotoManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The screen a photo on the wall opens when it is right-clicked: retake it, frame it,
 * or take it down.
 *
 * <p>Separate from {@link CameraDialogs} because it is reached from the other end. Those
 * screens belong to a camera and everything on them is about the next shot; this one
 * belongs to a picture that already hangs somewhere, and the player standing in front of
 * it may not have the camera anywhere near.</p>
 *
 * <p>Only the owner and {@code izomap.admin} get it. Right-clicking somebody else's
 * photo goes on doing what it did before — nothing — because a menu opening on every
 * picture in a public gallery is noise.</p>
 */
public final class PhotoDialogs {

    /**
     * Buttons per row. Four fits the actions without wrapping; the frame list uses the
     * same width so the two screens line up.
     */
    private static final int COLUMNS = 4;

    private final Izomap plugin;
    private final PhotoManager photos;
    private final CameraManager cameras;

    public PhotoDialogs(Izomap plugin, PhotoManager photos, CameraManager cameras) {
        this.plugin = plugin;
        this.photos = photos;
        this.cameras = cameras;
    }

    /**
     * Whether this player may open the screen of this photo at all.
     */
    public boolean mayOpen(Player player, Photo photo) {
        return photo.owner().equals(player.getUniqueId()) || player.hasPermission(Permissions.ADMIN);
    }

    public void openWallDialog(Player player, Photo photo) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(plugin.messages().get("dialog.wall-body",
                Placeholder.unparsed("name", photo.name()),
                Placeholder.unparsed("camera", photo.cameraName()),
                Placeholder.unparsed("grid", photo.grid().label()))));
        body.add(DialogBody.plainMessage(frameLine(photo)));

        List<ActionButton> buttons = new ArrayList<>();
        var camera = cameras.byOwnerAndName(photo.owner(), photo.cameraName()).orElse(null);
        // A retake needs something to shoot with: the parameters for the shot and a
        // camera to attribute it to. A photo whose camera was picked up has neither.
        if (camera != null && photo.spec() != null) {
            buttons.add(button(plugin.messages().get("dialog.wall-retake"), photo.id(),
                    (p, current) -> {
                        photos.retake(p, current, camera);
                        p.closeInventory();
                    }));
        }
        if (!plugin.frames().isEmpty()) {
            buttons.add(button(plugin.messages().get(
                            photo.frameId() == null ? "dialog.wall-frame" : "dialog.wall-frame-change"),
                    photo.id(), (p, current) -> openFrameDialog(p, current)));
        }
        buttons.add(button(plugin.messages().get("dialog.wall-take-down"), photo.id(),
                (p, current) -> {
                    photos.unplace(current);
                    plugin.messages().send(p, "map.photo-taken-down",
                            Placeholder.unparsed("name", current.name()));
                }));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.wall-title",
                                Placeholder.unparsed("name", photo.name())))
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, cancelButton(), COLUMNS)));

        player.showDialog(dialog);
    }

    /**
     * What the photo is wearing, and whether it can still be changed.
     */
    private Component frameLine(Photo photo) {
        if (photo.frameId() == null)
            return plugin.messages().get("dialog.wall-frame-none");

        return plugin.messages().get(
                photo.frameEditable() ? "dialog.wall-frame-current" : "dialog.wall-frame-locked",
                Placeholder.component("frame", photos.frameName(photo.frameId())));
    }

    /**
     * The frames this player may put on, plus the one that takes the current one off.
     *
     * <p>An embedded frame has no screen of its own: it is in the picture, and the only
     * honest thing to show is why it cannot be changed.</p>
     */
    private void openFrameDialog(Player player, Photo photo) {
        if (!photo.frameEditable()) {
            plugin.messages().send(player, "frame.locked");
            openWallDialog(player, photo);
            return;
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (PhotoFrames.Frame frame : plugin.frames().all()) {
            if (!Permissions.frame(player, frame.id())) continue;

            var current = frame.id().equals(photo.frameId());
            buttons.add(button(plugin.messages().get(
                            current ? "dialog.frame-option-active" : "dialog.frame-option",
                            Placeholder.component("frame", photos.frameName(frame.id()))),
                    photo.id(), (p, photoNow) -> {
                        photos.applyFrame(p, photoNow, frame.id());
                        openWallDialogLater(p, photoNow.id());
                    }));
        }
        if (buttons.isEmpty()) {
            // Every frame is somebody else's; say so rather than opening an empty screen.
            plugin.messages().send(player, "frame.none-allowed");
            openWallDialog(player, photo);
            return;
        }
        if (photo.frameId() != null) {
            buttons.add(button(plugin.messages().get("dialog.frame-remove"), photo.id(),
                    (p, photoNow) -> {
                        photos.applyFrame(p, photoNow, null);
                        openWallDialogLater(p, photoNow.id());
                    }));
        }
        buttons.add(button(plugin.messages().get("dialog.frame-back"), photo.id(),
                (p, photoNow) -> openWallDialog(p, photoNow)));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.frame-title"))
                        .body(List.of(DialogBody.plainMessage(plugin.messages().get(
                                plugin.config().framesEmbed() ? "dialog.frame-body-embed" : "dialog.frame-body"))))
                        .build())
                .type(DialogType.multiAction(buttons, cancelButton(), COLUMNS)));

        player.showDialog(dialog);
    }

    /**
     * Reopens the screen a tick later, once the frame change has landed in the record.
     *
     * <p>Embedding writes the cache before the record moves, so reopening straight away
     * would draw the screen from the photo as it was and show the old frame.</p>
     */
    private void openWallDialogLater(Player player, UUID photoId) {
        plugin.runOnMain(() -> photos.byId(photoId).ifPresent(photo -> openWallDialog(player, photo)));
    }

    /**
     * A button that acts on the photo as it stands now: the screen was built from a
     * copy, and a retake or a reframe may have replaced it since. The permission is
     * checked again on the way through, because a screen can stay open for a while.
     */
    private ActionButton button(Component label, UUID photoId, BiConsumer<Player, Photo> action) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick((view, audience) -> {
                    if (!(audience instanceof Player player)) return;

                    plugin.runOnMain(() -> photos.byId(photoId)
                            .filter(photo -> mayOpen(player, photo))
                            .ifPresent(photo -> action.accept(player, photo)));
                }, ClickCallback.Options.builder().build()))
                .build();
    }

    private ActionButton cancelButton() {
        return ActionButton.builder(plugin.messages().get("dialog.cancel")).build();
    }
}
