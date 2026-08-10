package dev.zypec.izomap.ui;

import dev.zypec.izomap.Izomap;
import dev.zypec.izomap.camera.Camera;
import dev.zypec.izomap.camera.CameraManager;
import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.map.PhotoManager;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
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
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Paper Dialog API interface for capturing and placing photos.
 *
 * <p>The player types a name and picks zoom, color filter and grid. Aspect ratio is
 * a button instead: clicking one reopens the dialog with that ratio's grid options
 * while keeping the entered name, zoom and filter.</p>
 */
public final class CameraDialogs {

    private static final String INPUT_NAME = "photo_name";
    private static final String INPUT_ZOOM = "zoom";
    private static final String INPUT_FILTER = "filter";
    private static final String INPUT_GRID = "grid";

    // Widely shot to close up; the frame covers frame-height / zoom blocks.
    private static final float[] ZOOM_PRESETS =
            {0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f};

    private final Izomap plugin;
    private final CameraManager cameraManager;
    private final PhotoManager photoManager;

    public CameraDialogs(Izomap plugin, CameraManager cameraManager, PhotoManager photoManager) {
        this.plugin = plugin;
        this.cameraManager = cameraManager;
        this.photoManager = photoManager;
    }

    public void openCaptureDialog(Player player, Camera camera) {
        openCaptureDialog(player, camera, camera.name(), camera.zoom(), camera.colorFilter());
    }

    private void openCaptureDialog(Player player, Camera camera,
                                   String initialName, float initialZoom, ColorFilter initialFilter) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(plugin.messages().get("dialog.title"))
                        .body(List.of(DialogBody.plainMessage(infoLine(camera))))
                        .inputs(List.of(
                                DialogInput.text(INPUT_NAME, plugin.messages().get("dialog.name-label"))
                                        .initial(initialName).width(220).build(),
                                DialogInput.singleOption(INPUT_ZOOM, 220, zoomEntries(initialZoom),
                                        plugin.messages().get("dialog.scale-label"), true),
                                DialogInput.singleOption(INPUT_FILTER, 220, filterEntries(initialFilter),
                                        plugin.messages().get("dialog.filter-label"), true),
                                DialogInput.singleOption(INPUT_GRID, 220, gridEntries(camera),
                                        plugin.messages().get("dialog.grid-label"), true)))
                        .build())
                .type(DialogType.multiAction(buttons(player, camera), cancelButton(), 2)));

        player.showDialog(dialog);
    }

    private List<ActionButton> buttons(Player player, Camera camera) {
        List<ActionButton> buttons = new ArrayList<>();
        for (AspectRatio ratio : AspectRatio.values()) {
            boolean current = ratio == camera.aspectRatio();
            Component label = plugin.messages().get(current ? "dialog.ratio-button-active" : "dialog.ratio-button",
                    Placeholder.unparsed("ratio", ratio.label()));
            buttons.add(ActionButton.builder(label)
                    .action(DialogAction.customClick(
                            (view, audience) -> applyAndReopen(view, audience, camera,
                                    target -> target.aspectRatio(ratio)),
                            ClickCallback.Options.builder().build()))
                    .build());
        }
        buttons.add(ActionButton.builder(plugin.messages().get(
                        camera.thirdsGuide() ? "dialog.thirds-button-active" : "dialog.thirds-button"))
                .action(DialogAction.customClick(
                        (view, audience) -> applyAndReopen(view, audience, camera,
                                target -> target.thirdsGuide(!target.thirdsGuide())),
                        ClickCallback.Options.builder().build()))
                .build());
        buttons.add(ActionButton.builder(plugin.messages().get("dialog.confirm"))
                .action(DialogAction.customClick(
                        (view, audience) -> onConfirm(view, audience, camera),
                        ClickCallback.Options.builder().build()))
                .build());
        return buttons;
    }

    private ActionButton cancelButton() {
        return ActionButton.builder(plugin.messages().get("dialog.cancel")).build();
    }

    // --- inputs ---

    private List<SingleOptionDialogInput.OptionEntry> zoomEntries(float initial) {
        float nearest = nearestZoom(initial);
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (float value : ZOOM_PRESETS) {
            String id = String.format(Locale.ROOT, "%.2f", value);
            // The label also states the covered area, e.g. "0.25x - 192 blok".
            String label = String.format(Locale.ROOT, "%sx - %.0f blok",
                    id, plugin.config().frameHeight() / value);
            entries.add(SingleOptionDialogInput.OptionEntry.create(
                    id, Component.text(label), value == nearest));
        }
        return entries;
    }

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
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        Float zoom = parseFloat(view.getText(INPUT_ZOOM));
        ColorFilter filter = ColorFilter.fromString(view.getText(INPUT_FILTER), camera.colorFilter());
        float resolvedZoom = zoom != null ? zoom : camera.zoom();

        plugin.runOnMain(() -> {
            change.accept(camera);
            camera.zoom(resolvedZoom);
            camera.colorFilter(filter);
            cameraManager.applyAndPersist(camera);
            plugin.preview().refresh(player, camera);
            openCaptureDialog(player, camera, name, resolvedZoom, filter);
        });
    }

    private void onConfirm(DialogResponseView view, Audience audience, Camera camera) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String name = valueOr(view.getText(INPUT_NAME), camera.name());
        Float zoom = parseFloat(view.getText(INPUT_ZOOM));
        ColorFilter filter = ColorFilter.fromString(view.getText(INPUT_FILTER), camera.colorFilter());
        String gridLabel = view.getText(INPUT_GRID);

        plugin.runOnMain(() -> {
            if (zoom != null) {
                camera.zoom(zoom);
            }
            camera.colorFilter(filter);
            cameraManager.applyAndPersist(camera);
            plugin.preview().refresh(player, camera);

            GridOption grid = GridOption.parse(gridLabel);
            if (grid == null || !GridLayouts.isValid(camera.aspectRatio(), grid)) {
                grid = GridLayouts.optionsFor(camera.aspectRatio()).get(0);
            }
            photoManager.captureAndPlace(player, camera, name, grid);
        });
    }

    // --- helpers ---

    private Component infoLine(Camera camera) {
        return plugin.messages().get("dialog.info",
                Placeholder.unparsed("camera", camera.name()),
                Placeholder.unparsed("ratio", camera.aspectRatio().label()),
                Placeholder.unparsed("scale", String.format(Locale.ROOT, "%.2f", camera.zoom())));
    }

    private static String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static float nearestZoom(float zoom) {
        var best = ZOOM_PRESETS[0];
        for (float value : ZOOM_PRESETS) {
            if (Math.abs(value - zoom) < Math.abs(best - zoom)) {
                best = value;
            }
        }
        return best;
    }

    private static Float parseFloat(String raw) {
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
