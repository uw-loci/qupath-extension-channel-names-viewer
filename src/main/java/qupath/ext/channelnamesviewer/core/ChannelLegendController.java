package qupath.ext.channelnamesviewer.core;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage.ChannelRow;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage.EmptyCause;
import qupath.lib.color.ColorToolsAwt;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Listener-lifecycle owner for the Channel Names Viewer.
 *
 * <p>Hooks {@link QuPathGUI#imageDataProperty()}, {@link QuPathGUI#viewerProperty()},
 * and the active {@link ImageDisplay#selectedChannels()} list. On any change, the
 * controller re-binds to the current viewer's display and re-renders the legend.</p>
 *
 * <p>Single {@link ListChangeListener} field instance (per Phase 0 feasibility
 * section 5) so the same listener can be removed and re-added cleanly when the
 * active image changes -- avoiding the per-image-switch listener leak the
 * Groovy script hits.</p>
 *
 * <p>{@link #install()} attaches; {@link #uninstall()} detaches and is safe to
 * call repeatedly. Both are idempotent.</p>
 *
 * <p>RGB / no-image / no-selected-channels each map to a different
 * {@link EmptyCause} subtitle.</p>
 *
 * @author Mike Nelson
 */
public class ChannelLegendController {

    private static final Logger logger = LoggerFactory.getLogger(ChannelLegendController.class);

    /**
     * Sink the controller renders into. Production code uses
     * {@link ChannelLegendStage}; tests use a no-op or recording stub.
     * <p>Package-private to keep the public surface unchanged. The interface
     * exists so the controller's listener lifecycle can be exercised in a
     * unit test without bringing up JavaFX or constructing a real
     * {@link javafx.stage.Stage}.
     */
    interface LegendSink {
        void renderChannels(List<ChannelRow> rows);
        void renderEmptyState(EmptyCause cause);
    }

    private final ObservableValue<ImageData<BufferedImage>> imageDataProperty;
    private final ObservableValue<QuPathViewer> viewerProperty;
    private final Supplier<QuPathViewer> viewerSupplier;
    private final LegendSink legendSink;

    /** Single instance reused across rebinds so removal works (NOT a lambda-per-call). */
    private final ListChangeListener<ChannelDisplayInfo> selectedChannelsListener =
            change -> renderCurrentChannels();

    private final ChangeListener<ImageData<BufferedImage>> imageDataListener =
            (obs, oldData, newData) -> rebindToCurrentImage();

    private final ChangeListener<QuPathViewer> viewerListener =
            (obs, oldViewer, newViewer) -> rebindToCurrentImage();

    /** The display the controller is currently listening to. Held weakly via reference equality. */
    private ImageDisplay currentDisplay;

    private boolean installed = false;

    /**
     * Production constructor. Wires the controller to QuPath's
     * {@link QuPathGUI#imageDataProperty()}, {@link QuPathGUI#viewerProperty()},
     * and {@link QuPathGUI#getViewer()}.
     *
     * @param qupath the QuPath GUI singleton
     * @param legendStage the stage to render into
     */
    public ChannelLegendController(QuPathGUI qupath, ChannelLegendStage legendStage) {
        this(
                Objects.requireNonNull(qupath, "qupath cannot be null").imageDataProperty(),
                qupath.viewerProperty(),
                qupath::getViewer,
                adaptStage(Objects.requireNonNull(legendStage, "legendStage cannot be null"))
        );
    }

    /**
     * Test seam constructor. Accepts the property/supplier/sink quartet
     * directly, so a unit test can substitute fake observables and a
     * recording sink to exercise the listener-rebind path without needing
     * a live QuPath GUI or JavaFX runtime initialization.
     *
     * <p>Package-private intentionally -- there is no production caller that
     * should be wiring its own sources.
     */
    ChannelLegendController(ObservableValue<ImageData<BufferedImage>> imageDataProperty,
                            ObservableValue<QuPathViewer> viewerProperty,
                            Supplier<QuPathViewer> viewerSupplier,
                            LegendSink legendSink) {
        this.imageDataProperty = Objects.requireNonNull(imageDataProperty, "imageDataProperty cannot be null");
        this.viewerProperty = Objects.requireNonNull(viewerProperty, "viewerProperty cannot be null");
        this.viewerSupplier = Objects.requireNonNull(viewerSupplier, "viewerSupplier cannot be null");
        this.legendSink = Objects.requireNonNull(legendSink, "legendSink cannot be null");
    }

    private static LegendSink adaptStage(ChannelLegendStage stage) {
        return new LegendSink() {
            @Override
            public void renderChannels(List<ChannelRow> rows) {
                stage.renderChannels(rows);
            }

            @Override
            public void renderEmptyState(EmptyCause cause) {
                stage.renderEmptyState(cause);
            }
        };
    }

    /**
     * Attach the three listeners (image-data, viewer, selected-channels) and
     * render the current state. Idempotent: a second call does nothing.
     */
    public void install() {
        if (installed) {
            return;
        }
        imageDataProperty.addListener(imageDataListener);
        viewerProperty.addListener(viewerListener);
        rebindToCurrentImage();
        installed = true;
        logger.debug("ChannelLegendController installed");
    }

    /**
     * Detach all listeners and clear the bound display. Idempotent: safe to
     * call before {@link #install()} or after a previous {@code uninstall()}.
     */
    public void uninstall() {
        if (!installed) {
            return;
        }
        imageDataProperty.removeListener(imageDataListener);
        viewerProperty.removeListener(viewerListener);
        detachFromCurrentDisplay();
        installed = false;
        logger.debug("ChannelLegendController uninstalled");
    }

    /**
     * Force a re-bind to the currently-active viewer's image display and
     * re-render. Public so the menu action can request a refresh after Show
     * if the timing of the install listeners missed the initial state.
     */
    public void rebindToCurrentImage() {
        detachFromCurrentDisplay();

        QuPathViewer viewer = viewerSupplier.get();
        if (viewer == null) {
            legendSink.renderEmptyState(EmptyCause.NO_IMAGE);
            return;
        }
        ImageData<BufferedImage> imageData = viewer.getImageData();
        if (imageData == null) {
            legendSink.renderEmptyState(EmptyCause.NO_IMAGE);
            return;
        }

        // Empty state for RGB / brightfield. The Groovy script lacked this guard.
        if (imageData.getServer() != null && imageData.getServer().isRGB()) {
            legendSink.renderEmptyState(EmptyCause.RGB);
            return;
        }

        ImageDisplay display = viewer.getImageDisplay();
        if (display == null) {
            legendSink.renderEmptyState(EmptyCause.NO_IMAGE);
            return;
        }

        currentDisplay = display;
        display.selectedChannels().addListener(selectedChannelsListener);
        renderCurrentChannels();
    }

    /**
     * Render the channels currently bound. Public so smoke tests / scripts can
     * trigger a render without going through a listener event.
     */
    public void renderCurrentChannels() {
        if (currentDisplay == null) {
            // Shouldn't normally hit -- detach is supposed to clear before this is invoked.
            legendSink.renderEmptyState(EmptyCause.NO_IMAGE);
            return;
        }
        List<ChannelDisplayInfo> selected = currentDisplay.selectedChannels();
        if (selected.isEmpty()) {
            legendSink.renderEmptyState(EmptyCause.NO_SELECTION);
            return;
        }
        List<ChannelRow> rows = new ArrayList<>(selected.size());
        for (ChannelDisplayInfo info : selected) {
            String name = info.getName();
            if (name == null) {
                name = "(unnamed channel)";
            }
            Color jfxColor = jfxColorFromChannelInfo(info);
            rows.add(new ChannelRow(name, jfxColor));
        }
        legendSink.renderChannels(rows);
    }

    private void detachFromCurrentDisplay() {
        if (currentDisplay != null) {
            currentDisplay.selectedChannels().removeListener(selectedChannelsListener);
            currentDisplay = null;
        }
    }

    /**
     * Convert a {@link ChannelDisplayInfo} display color (packed ARGB int, may be null)
     * to a JavaFX {@link Color}. A null or transparent channel color falls back to white.
     */
    private static Color jfxColorFromChannelInfo(ChannelDisplayInfo info) {
        Integer packed = info.getColor();
        if (packed == null) {
            return Color.WHITE;
        }
        java.awt.Color awt = ColorToolsAwt.getCachedColor(packed);
        return Color.rgb(awt.getRed(), awt.getGreen(), awt.getBlue());
    }

    // -------------------------------------------------------------------------
    // Test seams
    // -------------------------------------------------------------------------

    /**
     * Test seam: returns whether the controller's listener is currently
     * attached to a non-null {@code ImageDisplay}. The listener-leak unit test
     * uses this to assert detach-on-rebind correctness.
     *
     * @return true iff the controller has an active selected-channels subscription
     */
    public boolean isAttachedToDisplay() {
        return currentDisplay != null;
    }
}
