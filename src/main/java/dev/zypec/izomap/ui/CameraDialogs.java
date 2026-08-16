package dev.zypec.izomap.ui;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.util.Format;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Paper Dialog API screens for taking photos and managing them.
 *
 * <p>Two screens. The <b>capture</b> one sets up the shot: a name, color filter and
 * grid, with aspect ratio as a button that reopens the dialog so the grid options
 * follow it. Confirming only <i>takes</i> the photo. Zoom is not among them — it is
 * adjusted by clicking the camera, where the preview shows the result, and a second
 * way to set it here only wrote that value back over the one being looked at.</p>
 *
 * <p>The <b>list</b> screen is what the photo goes into, one row per photo:
 * rename, hang or take down, delete, retake. Hanging leaves the dialog and hands over
 * to the ghost preview, so the player picks the spot themselves.</p>
 */
public final class CameraDialogs {

    private static final String INPUT_NAME = "photo_name";
    private static final String INPUT_FILTER = "filter";
    private static final String INPUT_GRID = "grid";
    private static final String INPUT_RENAME = "new_name";

    /**
     * A row is four buttons wide, so the list flows one photo per row.
     */
    private static final int LIST_COLUMNS = 4;

    /**
     * Beyond this the dialog turns into a wall of buttons; the rest are reachable by
     * name through the commands.
     */
    private static final int MAX_LIST_ROWS = 10;

    private final Izomap plugin;
    private final CameraManager cameraManager;
    private final PhotoManager photoManager;

    public CameraDialogs(Izomap plugin, CameraManager cameraManager, PhotoManager photoManager) {
        this.plugin = plugin;
        this.cameraManager = cameraManager;
        this.photoManager = photoManager;
    }

    // --- capture screen ---

    public void openCaptureDialog(Player player, Camera camera) {
        openCaptureDialog(player, camera, camera.name(), camera.colorFilter());
    }

    private void openCaptureDialog(Player player, Camera camera,
                                   String initialName, ColorFilter initialFilter) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.title"))
                        .body(List.of(DialogBody.plainMessage(infoLine(camera))))
                        .inputs(List.of(
                                DialogInput.text(INPUT_NAME, plugin.messages().get("dialog.name-label"))
                                        .initial(initialName).width(220).build(),
                                DialogInput.singleOption(INPUT_FILTER, 220, filterEntries(initialFilter),
                                        plugin.messages().get("dialog.filter-label"), true),
                                DialogInput.singleOption(INPUT_GRID, 220, gridEntries(camera),
                                        plugin.messages().get("dialog.grid-label"), true)))
                        .build())
                .type(DialogType.multiAction(captureButtons(player, camera), cancelButton(), 2)));

        player.showDialog(dialog);
    }

    private List<ActionButton> captureButtons(Player viewer, Camera camera) {
        List<ActionButton> buttons = new ArrayList<>();
        for (AspectRatio ratio : AspectRatio.values()) {
            boolean current = ratio == camera.aspectRatio();
            Component label = plugin.messages().get(current ? "dialog.ratio-button-active" : "dialog.ratio-button",
                    Placeholder.unparsed("ratio", ratio.label()));
            buttons.add(button(label, (view, audience) ->
                    applyAndReopen(view, audience, camera, target -> target.aspectRatio(ratio))));
        }
        buttons.add(button(plugin.messages().get(
                        camera.thirdsGuide() ? "dialog.thirds-button-active" : "dialog.thirds-button"),
                (view, audience) -> applyAndReopen(view, audience, camera,
                        target -> target.thirdsGuide(!target.thirdsGuide()))));
        buttons.add(button(plugin.messages().get("dialog.photos-button",
                        Placeholder.unparsed("count", String.valueOf(
                                photoManager.countFor(camera.owner(), camera.name())))),
                (view, audience) -> applyAndRun(view, audience, camera, player -> openPhotoList(player, camera))));

        // A full camera keeps the button, so the row does not reshuffle, but says what
        // it would do instead of doing it.
        var full = photoManager.atLimit(viewer, camera);
        buttons.add(button(plugin.messages().get(full ? "dialog.capture-full" : "dialog.capture",
                        Placeholder.unparsed("limit", String.valueOf(photoManager.limitFor(viewer)))),
                (view, audience) -> onCapture(view, audience, camera)));
        return buttons;
    }

    // --- list screen ---

    /**
     * Lists the camera's photos, one row of actions each.
     */
    public void openPhotoList(Player player, Camera camera) {
        var photos = photoManager.takenWith(player.getUniqueId(), camera.name());
        var shown = photos.size() > MAX_LIST_ROWS ? photos.subList(0, MAX_LIST_ROWS) : photos;

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(plugin.messages().get(
                photos.isEmpty() ? "dialog.photos-empty" : "dialog.photos-body",
                Placeholder.unparsed("camera", camera.name()),
                Placeholder.unparsed("count", String.valueOf(photos.size())))));
        if (photos.size() > shown.size()) {
            body.add(DialogBody.plainMessage(plugin.messages().get("dialog.photos-truncated",
                    Placeholder.unparsed("count", String.valueOf(photos.size() - shown.size())))));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (shown.isEmpty()) {
            // A multi-action dialog needs at least one button, and "go back and shoot
            // one" is the only thing left to do here anyway.
            buttons.add(button(plugin.messages().get("dialog.photos-back"),
                    (view, audience) -> onPlayer(audience, p -> openCaptureDialog(p, camera))));
        }
        for (var photo : shown) {
            buttons.add(button(plugin.messages().get("dialog.photo-name",
                            Placeholder.unparsed("name", photo.name())),
                    (view, audience) -> onPhoto(audience, photo.id(),
                            (p, current) -> openRenameDialog(p, camera, current))));
            buttons.add(button(plugin.messages().get(
                            photo.isPlaced() ? "dialog.photo-unplace" : "dialog.photo-place"),
                    (view, audience) -> onPhoto(audience, photo.id(),
                            (p, current) -> togglePlacement(p, camera, current))));
            buttons.add(button(plugin.messages().get("dialog.photo-delete"),
                    (view, audience) -> onPhoto(audience, photo.id(),
                            (p, current) -> openDeleteDialog(p, camera, current))));
            buttons.add(button(plugin.messages().get("dialog.photo-retake"),
                    (view, audience) -> onPhoto(audience, photo.id(), (p, current) -> {
                        photoManager.retake(p, current, camera);
                        openPhotoList(p, camera);
                    })));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.photos-title"))
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, cancelButton(), LIST_COLUMNS)));

        player.showDialog(dialog);
    }

    /**
     * Hangs an unplaced photo through the ghost preview, or takes a hanging one down.
     */
    private void togglePlacement(Player player, Camera camera, Photo photo) {
        if (photo.isPlaced()) {
            photoManager.unplace(photo);
            plugin.messages().send(player, "map.photo-taken-down",
                    Placeholder.unparsed("name", photo.name()));
            openPhotoList(player, camera);
            return;
        }
        plugin.placement().start(player, photo);
    }

    private void openRenameDialog(Player player, Camera camera, Photo photo) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.rename-title"))
                        .body(List.of(DialogBody.plainMessage(plugin.messages().get("dialog.rename-body",
                                Placeholder.unparsed("name", photo.name())))))
                        .inputs(List.of(DialogInput.text(INPUT_RENAME,
                                        plugin.messages().get("dialog.name-label"))
                                .initial(photo.name()).width(220).build()))
                        .build())
                .type(DialogType.multiAction(List.of(
                        button(plugin.messages().get("dialog.rename-confirm"), (view, audience) -> {
                            var name = view.getText(INPUT_RENAME);
                            onPhoto(audience, photo.id(), (p, current) -> {
                                if (name == null || name.isBlank()) {
                                    openPhotoList(p, camera);
                                    return;
                                }
                                if (!photoManager.rename(current, name)) {
                                    plugin.messages().send(p, "map.name-taken",
                                            Placeholder.unparsed("name", name));
                                }
                                openPhotoList(p, camera);
                            });
                        })), cancelButton(), 1)));

        player.showDialog(dialog);
    }

    private void openDeleteDialog(Player player, Camera camera, Photo photo) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.delete-title"))
                        .body(List.of(DialogBody.plainMessage(plugin.messages().get("dialog.delete-body",
                                Placeholder.unparsed("name", photo.name())))))
                        .build())
                .type(DialogType.multiAction(List.of(
                        button(plugin.messages().get("dialog.delete-confirm"),
                                (view, audience) -> onPhoto(audience, photo.id(), (p, current) -> {
                                    photoManager.delete(current);
                                    plugin.messages().send(p, "map.photo-deleted",
                                            Placeholder.unparsed("name", current.name()));
                                    openPhotoList(p, camera);
                                })),
                        button(plugin.messages().get("dialog.delete-cancel"),
                                (view, audience) -> onPhoto(audience, photo.id(),
                                        (p, current) -> openPhotoList(p, camera)))), cancelButton(), 2)));

        player.showDialog(dialog);
    }

    // --- inputs ---

    private List<SingleOptionDialogInput.OptionEntry> filterEntries(ColorFilter initial) {
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (ColorFilter filter : ColorFilter.values()) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(
                    filter.name(), plugin.messages().get("filter." + filter.name()), filter == initial));
        }
        return entries;
    }

    private List<SingleOptionDialogInput.OptionEntry> gridEntries(Camera camera) {
        List<GridOption> options = GridLayouts.optionsFor(camera.aspectRatio());
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            GridOption option = options.get(i);
            entries.add(SingleOptionDialogInput.OptionEntry.create(
                    option.label(),
                    plugin.messages().get("dialog.grid-option",
                            Placeholder.unparsed("grid", option.label()),
                            Placeholder.unparsed("count", String.valueOf(option.tileCount())),
                            Placeholder.unparsed("wpx", String.valueOf(option.widthPx())),
                            Placeholder.unparsed("hpx", String.valueOf(option.heightPx()))),
                    i == 0));
        }
        return entries;
    }

    // --- button actions; the thread is not guaranteed, so work moves to the main one ---

    /**
     * Shared flow for buttons that keep the dialog open: read the form, apply the
     * change, refresh the preview and reopen the dialog in the new state, which also
     * refreshes the grid options when the ratio changed.
     */
    private void applyAndReopen(DialogResponseView view, Audience audience, Camera camera,
                                Consumer<Camera> change) {
        applyForm(view, audience, camera, change, (player, name, filter) ->
                openCaptureDialog(player, camera, name, filter));
    }

    /**
     * Keeps the form's edits without reopening the capture screen, for buttons that
     * lead somewhere else.
     */
    private void applyAndRun(DialogResponseView view, Audience audience, Camera camera,
                             Consumer<Player> next) {
        applyForm(view, audience, camera, target -> {
        }, (player, name, filter) -> next.accept(player));
    }

    private void applyForm(DialogResponseView view, Audience audience, Camera camera,
                           Consumer<Camera> change, FormAction then) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        ColorFilter filter = ColorFilter.fromString(view.getText(INPUT_FILTER), camera.colorFilter());

        plugin.runOnMain(() -> {
            var before = imageState(camera);
            change.accept(camera);
            camera.colorFilter(filter);
            applyIfChanged(player, camera, before);
            then.run(player, name, filter);
        });
    }

    /**
     * Everything the previewed image depends on, as a value to compare against.
     */
    private static List<Object> imageState(Camera camera) {
        return List.of(camera.aspectRatio(), camera.thirdsGuide(), camera.zoom(), camera.colorFilter());
    }

    /**
     * Persists and re-renders only when the shot actually changed.
     *
     * <p>Every button on the capture screen goes through the form, and a re-render is
     * the most expensive thing this plugin does — it copies the chunks the frame covers
     * on the main thread. Firing one for a button that only navigates somewhere else
     * meant the next screen opened behind that copy, which is what made the photo list
     * feel slow to reach. Main thread only.</p>
     */
    private void applyIfChanged(Player player, Camera camera, List<Object> before) {
        if (imageState(camera).equals(before))
            return;

        cameraManager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
    }

    private interface FormAction {
        void run(Player player, String name, ColorFilter filter);
    }

    private void onCapture(DialogResponseView view, Audience audience, Camera camera) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        ColorFilter filter = ColorFilter.fromString(view.getText(INPUT_FILTER), camera.colorFilter());
        String gridLabel = view.getText(INPUT_GRID);

        plugin.runOnMain(() -> {
            var before = imageState(camera);
            camera.colorFilter(filter);
            // The capture below renders the same frame; refreshing an unchanged preview
            // first would render it twice for one click.
            applyIfChanged(player, camera, before);

            GridOption grid = GridOption.parse(gridLabel);
            if (grid == null || !GridLayouts.isValid(camera.aspectRatio(), grid)) {
                grid = GridLayouts.optionsFor(camera.aspectRatio()).get(0);
            }
            photoManager.capture(player, camera, name, grid);
        });
    }

    /**
     * Runs a photo action on the main thread, against the record as it stands now: the
     * dialog was built from a copy, and a retake or a rename may have replaced it since.
     */
    private void onPhoto(Audience audience, UUID photoId, PhotoAction action) {
        if (!(audience instanceof Player player)) {
            return;
        }
        plugin.runOnMain(() -> photoManager.byId(photoId)
                .ifPresent(photo -> action.run(player, photo)));
    }

    private interface PhotoAction {
        void run(Player player, Photo photo);
    }

    private void onPlayer(Audience audience, Consumer<Player> action) {
        if (audience instanceof Player player) {
            plugin.runOnMain(() -> action.accept(player));
        }
    }

    // --- helpers ---

    private ActionButton button(Component label, ButtonAction action) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick(action::run, ClickCallback.Options.builder().build()))
                .build();
    }

    private interface ButtonAction {
        void run(DialogResponseView view, Audience audience);
    }

    private ActionButton cancelButton() {
        return ActionButton.builder(plugin.messages().get("dialog.cancel")).build();
    }

    /**
     * What the shot is set to, for a screen that no longer lets any of it be edited:
     * zoom and the angles are all adjusted by clicking the camera.
     */
    private Component infoLine(Camera camera) {
        return plugin.messages().get("dialog.info",
                Placeholder.unparsed("camera", camera.name()),
                Placeholder.unparsed("ratio", camera.aspectRatio().label()),
                Placeholder.unparsed("scale", Format.zoom(camera.zoom())),
                Placeholder.unparsed("blocks",
                        Format.blocks(plugin.config().frameHeight(), camera.zoom())),
                Placeholder.unparsed("yaw", Format.degrees(camera.camYaw())),
                Placeholder.unparsed("pitch", Format.degrees(camera.camPitch())));
    }

    private static String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

}
