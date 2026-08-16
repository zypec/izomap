package dev.zypec.izomap.ui;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.config.Permissions;
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.Photo;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
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
import java.util.function.Predicate;

/**
 * Paper Dialog API screens for taking photos and managing them.
 *
 * <p>Two screens. The <b>capture</b> one sets up the shot: a name, color filter, photo
 * style, sky and grid, with aspect ratio as a button that reopens the dialog so the grid options
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
    private static final String INPUT_GRID = "grid";
    private static final String INPUT_RENAME = "new_name";
    private static final String INPUT_FOCUS = "focus";

    /**
     * How the focus slider prints itself: the label, then the value. Minecraft fills the
     * two {@code %s} itself, so this is a format and not a message.
     */
    private static final String FOCUS_FORMAT = "%s: %s";

    /**
     * Buttons per row on the capture screen: the ratios, the three look settings and
     * the three actions each come to three, so the grid reads as rows of like things.
     */
    private static final int COLUMNS = 3;

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
        openCaptureDialog(player, camera, camera.name());
    }

    private void openCaptureDialog(Player player, Camera camera, String initialName) {
        var width = plugin.config().dialogBodyWidth();
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text(INPUT_NAME, plugin.messages().get("dialog.name-label"))
                .initial(initialName).width(width).build());
        inputs.add(DialogInput.singleOption(INPUT_GRID, width, gridEntries(player, camera),
                plugin.messages().get("dialog.grid-label"), true));
        // Only while the effect is on: a slider that moves nothing is a question the
        // player has to answer before they have decided to ask it.
        if (camera.focusEnabled()) {
            inputs.add(focusInput(camera, width));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.title"))
                        .body(List.of(DialogBody.plainMessage(infoLine(camera), width)))
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(captureButtons(player, camera), cancelButton(), COLUMNS)));

        player.showDialog(dialog);
    }

    /**
     * The focus slider, in blocks from the camera.
     *
     * <p>Its far end is this camera's own ray distance rather than a fixed number, so
     * every part of the slider points at something the photo can actually contain: on a
     * zoomed-in shot the whole travel covers a few dozen blocks, and dragging it moves
     * the focus a block at a time instead of skipping over the subject.</p>
     *
     * <p>The value only reaches the server when a button is pressed — the dialog has no
     * live channel — so the focus moves on the next click, together with everything else
     * on the form.</p>
     */
    private DialogInput focusInput(Camera camera, int width) {
        var range = plugin.render().focusRange(camera);
        var initial = camera.focusDistance() > 0.0f ? camera.focusDistance() : range.suggested();
        return DialogInput.numberRange(INPUT_FOCUS, plugin.messages().get("dialog.focus-label"),
                        1.0f, (float) Math.ceil(range.limit()))
                .width(width)
                .labelFormat(FOCUS_FORMAT)
                .initial((float) Math.min(initial, range.limit()))
                .step(1.0f)
                .build();
    }

    /**
     * The capture screen's buttons, in the order the three-column grid lays them out:
     * the aspect ratios, then what the shot looks like, then what to do with it, and
     * the two that undo a camera last.
     */
    private List<ActionButton> captureButtons(Player viewer, Camera camera) {
        List<ActionButton> buttons = new ArrayList<>(ratioButtons(viewer, camera));

        // Cycled rather than picked from a list: each is a handful of values, and a
        // button can show the one in force while a closed dropdown cannot.
        var filters = choices(plugin.filters().all(), camera.colorFilter(),
                filter -> Permissions.filter(viewer, filter));
        if (filters.size() > 1) {
            buttons.add(cycleButton(camera, "dialog.filter-button", "filter.",
                    camera.colorFilter().id(),
                    target -> target.colorFilter(after(filters, target.colorFilter()))));
        }

        var styles = choices(List.of(PhotoStyle.values()), camera.style(),
                style -> Permissions.style(viewer, style));
        if (styles.size() > 1) {
            buttons.add(cycleButton(camera, "dialog.style-button", "style.",
                    camera.style().name(),
                    target -> target.style(after(styles, target.style()))));
        }

        var skies = choices(List.of(SkyOption.values()), camera.sky(),
                sky -> Permissions.sky(viewer, sky));
        if (skies.size() > 1) {
            buttons.add(cycleButton(camera, "dialog.sky-button", "sky.",
                    camera.sky().name(),
                    target -> target.sky(after(skies, target.sky()))));
        }

        // Shown to a camera that already has it on even without the permission, for the
        // same reason the cycled settings are: an effect nobody can see is an effect
        // nobody can take off, and every capture would keep paying for it.
        if (Permissions.focus(viewer) || camera.focusEnabled()) {
            buttons.add(button(plugin.messages().get(
                            camera.focusEnabled() ? "dialog.focus-button-active" : "dialog.focus-button"),
                    (view, audience) -> applyAndReopen(view, audience, camera, this::toggleFocus)));
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

        buttons.add(button(plugin.messages().get("dialog.pickup-button"),
                (view, audience) -> applyAndRun(view, audience, camera,
                        player -> openPickupDialog(player, camera))));
        buttons.add(button(plugin.messages().get("dialog.reset-button"),
                (view, audience) -> applyAndRun(view, audience, camera,
                        player -> resetAiming(player, camera))));
        return buttons;
    }

    /**
     * Turns depth of field on or off, giving it somewhere to focus the first time.
     *
     * <p>A camera switched on with no distance yet would put its focus plane at the lens
     * and blur the entire photo, which reads as a broken render rather than an effect it
     * has to be told about. It starts on the ground the frame is aimed at instead — the
     * subject, on nearly every shot — and the slider takes it from there. Main thread
     * only: the suggestion reads the world.</p>
     */
    private void toggleFocus(Camera camera) {
        var enabled = !camera.focusEnabled();
        camera.focusEnabled(enabled);
        if (enabled && camera.focusDistance() <= 0.0f) {
            camera.focusDistance((float) plugin.render().focusRange(camera).suggested());
        }
    }

    private List<ActionButton> ratioButtons(Player viewer, Camera camera) {
        List<ActionButton> buttons = new ArrayList<>();
        for (AspectRatio ratio : choices(List.of(AspectRatio.values()), camera.aspectRatio(),
                allowed -> Permissions.ratio(viewer, allowed))) {
            var current = ratio == camera.aspectRatio();
            var label = plugin.messages().get(current ? "dialog.ratio-button-active" : "dialog.ratio-button",
                    Placeholder.unparsed("ratio", ratio.label()));
            buttons.add(button(label, (view, audience) ->
                    applyAndReopen(view, audience, camera, target -> target.aspectRatio(ratio))));
        }
        return buttons;
    }

    /**
     * A button that shows a setting's current value and moves to the next on click.
     *
     * <p>The value's own colour comes from its display message ({@code filter.WARM} and
     * friends), so a server owner recolours a setting where they rename it.</p>
     */
    private ActionButton cycleButton(Camera camera, String labelKey, String valuePrefix,
                                     String valueName, Consumer<Camera> advance) {
        var label = plugin.messages().get(labelKey, Placeholder.component("value",
                plugin.messages().getOr(valuePrefix + valueName, valueName)));
        return button(label, (view, audience) -> applyAndReopen(view, audience, camera, advance));
    }

    /**
     * The values this player may cycle through, in their own order.
     *
     * <p>The camera's current value is always among them, even when the player may not
     * use it: a camera set to something expensive by somebody else would otherwise show
     * no button at all, leaving them looking at a setting they can neither see nor
     * change while the capture keeps being refused. Included, it is visible and one
     * click from being replaced — and the capture stays refused until it is.</p>
     */
    private static <T> List<T> choices(List<T> all, T current, Predicate<T> allowed) {
        List<T> choices = new ArrayList<>(all.size());
        for (var value : all) {
            if (value.equals(current) || allowed.test(value))
                choices.add(value);
        }
        return choices;
    }

    /**
     * The value after this one, wrapping at the end. A current value that is not on the
     * list — nothing here puts one there, but a reload might — moves to the first.
     */
    private static <T> T after(List<T> values, T current) {
        var index = values.indexOf(current);
        return values.get(index < 0 ? 0 : (index + 1) % values.size());
    }

    /**
     * Puts the aiming back where a new camera starts: pointing the way the player is
     * facing, at the configured pitch, unzoomed.
     *
     * <p>Only what a click adjusts is reset. The ratio, colour, style and sky are picked
     * deliberately and are one click from being changed back, so throwing them away too
     * would make this button the more destructive one. Main thread only.</p>
     */
    private void resetAiming(Player player, Camera camera) {
        camera.camYaw(player.getLocation().getYaw());
        camera.camPitch((float) plugin.config().defaultPitch());
        camera.zoom(1.0f);
        cameraManager.applyAndPersist(camera);
        plugin.preview().refresh(player, camera);
        plugin.messages().send(player, "camera.reset", Placeholder.unparsed("name", camera.name()));
        openCaptureDialog(player, camera);
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

    /**
     * Asks before taking a camera back, since its photos go with it.
     *
     * <p>Reached from the capture screen and from {@code /izocam pickup}; the command
     * skips it when there is nothing to lose.</p>
     */
    public void openPickupDialog(Player player, Camera camera) {
        var photos = photoManager.countFor(camera.owner(), camera.name());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.pickup-title"))
                        .body(List.of(DialogBody.plainMessage(plugin.messages().get(
                                photos > 0 ? "dialog.pickup-body" : "dialog.pickup-body-empty",
                                Placeholder.unparsed("name", camera.name()),
                                Placeholder.unparsed("count", String.valueOf(photos))))))
                        .build())
                .type(DialogType.multiAction(List.of(
                        button(plugin.messages().get("dialog.pickup-confirm"),
                                (view, audience) -> onPlayer(audience, p -> pickUp(p, camera))),
                        button(plugin.messages().get("dialog.pickup-cancel"),
                                (view, audience) -> onPlayer(audience,
                                        p -> openCaptureDialog(p, camera)))), cancelButton(), 2)));

        player.showDialog(dialog);
    }

    /**
     * Takes the camera back and reports what became of it. Main thread only.
     */
    public void pickUp(Player player, Camera camera) {
        var result = cameraManager.pickup(player, camera);
        plugin.messages().send(player,
                result.itemReturned() ? "camera.picked-up" : "camera.picked-up-no-item",
                Placeholder.unparsed("name", camera.name()),
                Placeholder.unparsed("count", String.valueOf(result.photos())));
        if (result.dropped()) {
            plugin.messages().send(player, "camera.item-dropped");
        }
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

    private List<SingleOptionDialogInput.OptionEntry> gridEntries(Player viewer, Camera camera) {
        List<GridOption> options = GridLayouts.allowedFor(
                viewer, camera.aspectRatio(), plugin.config().maxMapTiles());
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
        applyForm(view, audience, camera, change, (player, name) ->
                openCaptureDialog(player, camera, name));
    }

    /**
     * Keeps the form's edits without reopening the capture screen, for buttons that
     * lead somewhere else.
     */
    private void applyAndRun(DialogResponseView view, Audience audience, Camera camera,
                             Consumer<Player> next) {
        applyForm(view, audience, camera, target -> {
        }, (player, name) -> next.accept(player));
    }

    private void applyForm(DialogResponseView view, Audience audience, Camera camera,
                           Consumer<Camera> change, FormAction then) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        // Null whenever the slider is not on the screen, which is whenever focus is off.
        Float focus = view.getFloat(INPUT_FOCUS);

        plugin.runOnMain(() -> {
            var before = imageState(camera);
            if (focus != null) {
                camera.focusDistance(focus);
            }
            change.accept(camera);
            applyIfChanged(player, camera, before);
            then.run(player, name);
        });
    }

    /**
     * Everything the previewed image depends on, as a value to compare against.
     */
    private static List<Object> imageState(Camera camera) {
        return List.of(camera.aspectRatio(), camera.thirdsGuide(), camera.zoom(),
                camera.colorFilter(), camera.style(), camera.sky(),
                camera.focusEnabled(), camera.focusDistance());
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
        void run(Player player, String name);
    }

    private void onCapture(DialogResponseView view, Audience audience, Camera camera) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        String gridLabel = view.getText(INPUT_GRID);
        Float focus = view.getFloat(INPUT_FOCUS);

        plugin.runOnMain(() -> {
            // Taking the photo does not go through the form, so the slider has to be read
            // here too: dragging it and pressing capture straight away would otherwise
            // shoot the distance it was left at last time. No preview refresh — the photo
            // being taken is the answer.
            if (focus != null && focus != camera.focusDistance()) {
                camera.focusDistance(focus);
                cameraManager.applyAndPersist(camera);
            }

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
