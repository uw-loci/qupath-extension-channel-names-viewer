package qupath.ext.channelnamesviewer.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.EventHandler;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.preferences.ChannelToolsPreferences;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ChannelDisplayMode;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.display.settings.DisplaySettingUtils;
import qupath.lib.display.settings.ImageDisplaySettings;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.images.stores.AbstractImageRenderer;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Projects;

/**
 * A grid of panels, one per channel, following the main viewer. Like QuPath's channel
 * viewer, but any channel -- or all of them -- can be shown in grayscale without changing
 * the main viewer. Built only on QuPath's public viewer API.
 */
public class ChannelGridWindow implements QuPathViewerListener {

    private static final Logger logger = LoggerFactory.getLogger(ChannelGridWindow.class);

    private static final String TITLE = "Channel Grid Viewer";

    /** Where the panels centre. */
    enum Sync {
        CURSOR("Cursor"), VIEWER_CENTER("Viewer center"), SELECTED_OBJECT("Selected object"), NONE("Do not sync");

        private final String label;

        Sync(String label) {
            this.label = label;
        }
    }

    /** What the grid shows. */
    enum Panels {
        VISIBLE("Visible channels"), ALL("All channels"), PRESETS("One per display preset");

        private final String label;

        Panels(String label) {
            this.label = label;
        }
    }

    private static Panels panelsMode() {
        try {
            return Panels.valueOf(ChannelToolsPreferences.GRID_PANELS.get());
        } catch (IllegalArgumentException e) {
            return Panels.VISIBLE;
        }
    }

    /** Zoom menu entries: label and downsample; 0 = same as the main viewer. */
    private static final Object[][] ZOOMS = {
            {"Same as main viewer", 0.0}, {"400 %", 0.25}, {"200 %", 0.5}, {"100 %", 1.0}, {"50 %", 2.0},
            {"25 %", 4.0}, {"10 %", 10.0}, {"5 %", 20.0}, {"2 %", 50.0}, {"1 %", 100.0}};

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final GridPane grid = new GridPane();
    private final List<Panel> panels = new ArrayList<>();

    private QuPathViewer viewer;
    private ImageDisplay display;
    private final Point2D center = new Point2D.Double();
    private Point2D mouse;
    private boolean updateRequested;

    /** Channels shown in grayscale individually, by name, for the current image. */
    private final Set<String> grayChannels = new HashSet<>();
    /** Counts grayscale and preset changes, so rendered tiles cached before one are not reused. */
    private final AtomicLong localChanges = new AtomicLong();

    /** Preset shown in a panel instead of its own channel, by the panel's key. */
    private final Map<String, String> panelPresets = new HashMap<>();
    /** Panels removed from the grid, by key; kept across images until restored. */
    private final Set<String> removed = new java.util.LinkedHashSet<>();
    /** True while this window changes the main viewer's channels. */
    private boolean changingMain;
    /**
     * Channels the grid keeps after this window changed the main viewer's selection, so
     * "Use in main viewer" does not collapse the grid to one panel; null to follow the
     * main viewer.
     */
    private List<String> keptChannels;

    /** Seconds between checks of the project's preset folder. */
    private static final int PRESET_POLL_SECONDS = 2;
    private java.util.concurrent.ScheduledExecutorService presetPoller;
    /** Names, sizes and modification times of the preset files at the last check. */
    private String presetSignature = "";

    private final ListChangeListener<ChannelDisplayInfo> channelListener = c -> {
        if (changingMain) {
            return;
        }
        keptChannels = null;
        Platform.runLater(this::rebuildPanels);
    };
    private final ChangeListener<Number> displayListener = (o, a, b) -> requestUpdate();
    private final ChangeListener<QuPathViewer> viewerListener = (o, a, b) -> bindViewer(b);
    private final InvalidationListener prefListener = o -> requestUpdate();
    private final EventHandler<MouseEvent> mouseMoved = this::handleMouseMoved;

    public ChannelGridWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);
        grid.setHgap(SEPARATOR_PX);
        grid.setVgap(SEPARATOR_PX);
        updateSeparators();
        stage.setScene(new Scene(grid, 640, 520));
        stage.setOnShown(e -> {
            qupath.viewerProperty().addListener(viewerListener);
            for (var p : List.of(ChannelToolsPreferences.GRID_NAMES, ChannelToolsPreferences.GRID_CURSOR,
                    ChannelToolsPreferences.GRID_OVERLAYS, ChannelToolsPreferences.GRID_DOWNSAMPLE,
                    ChannelToolsPreferences.GRID_SYNC)) {
                p.addListener(prefListener);
            }
            ChannelToolsPreferences.GRID_PANELS.addListener(prefRebuild);
            ChannelToolsPreferences.GRID_MERGED.addListener(prefRebuild);
            ChannelToolsPreferences.GRID_GRAYSCALE.addListener(grayscaleListener);
            bindViewer(qupath.getViewer());
            startPresetPolling();
        });
        stage.setOnHidden(e -> {
            qupath.viewerProperty().removeListener(viewerListener);
            for (var p : List.of(ChannelToolsPreferences.GRID_NAMES, ChannelToolsPreferences.GRID_CURSOR,
                    ChannelToolsPreferences.GRID_OVERLAYS, ChannelToolsPreferences.GRID_DOWNSAMPLE,
                    ChannelToolsPreferences.GRID_SYNC)) {
                p.removeListener(prefListener);
            }
            ChannelToolsPreferences.GRID_PANELS.removeListener(prefRebuild);
            ChannelToolsPreferences.GRID_MERGED.removeListener(prefRebuild);
            ChannelToolsPreferences.GRID_GRAYSCALE.removeListener(grayscaleListener);
            stopPresetPolling();
            bindViewer(null);
        });
        stage.widthProperty().addListener((o, a, b) -> requestUpdate());
        stage.heightProperty().addListener((o, a, b) -> requestUpdate());
    }

    private final InvalidationListener prefRebuild = o -> rebuildPanels();
    private final InvalidationListener grayscaleListener = o -> {
        localChanges.incrementAndGet();
        updateSeparators();
        requestUpdate();
    };

    /** Width of the lines between panels. */
    private static final int SEPARATOR_PX = 2;

    /**
     * Color the lines between panels: yellow when every channel is gray, where dark lines
     * would vanish between dark panels; dark gray otherwise, so they do not compete with
     * channel colors. The gaps show the grid's background.
     */
    private void updateSeparators() {
        grid.setStyle("-fx-background-color: "
                + (ChannelToolsPreferences.GRID_GRAYSCALE.get() ? "#ffd400" : "#3a3a3a") + ";");
    }

    public void show() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        stage.show();
    }

    // ------------------------------------------------------------------
    // Binding to the main viewer
    // ------------------------------------------------------------------

    private void bindViewer(QuPathViewer newViewer) {
        if (viewer != null) {
            viewer.removeViewerListener(this);
            viewer.getView().removeEventFilter(MouseEvent.MOUSE_MOVED, mouseMoved);
            viewer.repaintTimestamp().removeListener(displayListener);
            viewer.zPositionProperty().removeListener(displayListener);
            viewer.tPositionProperty().removeListener(displayListener);
        }
        if (display != null) {
            display.eventCountProperty().removeListener(displayListener);
            display.availableChannels().removeListener(channelListener);
            display.selectedChannels().removeListener(channelListener);
        }
        viewer = newViewer;
        display = newViewer == null ? null : newViewer.getImageDisplay();
        if (viewer != null) {
            viewer.addViewerListener(this);
            viewer.getView().addEventFilter(MouseEvent.MOUSE_MOVED, mouseMoved);
            viewer.repaintTimestamp().addListener(displayListener);
            viewer.zPositionProperty().addListener(displayListener);
            viewer.tPositionProperty().addListener(displayListener);
            center.setLocation(viewer.getCenterPixelX(), viewer.getCenterPixelY());
        }
        if (display != null) {
            display.eventCountProperty().addListener(displayListener);
            display.availableChannels().addListener(channelListener);
            display.selectedChannels().addListener(channelListener);
        }
        rebuildPanels();
    }

    @Override
    public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld,
                                 ImageData<BufferedImage> imageDataNew) {
        grayChannels.clear();
        keptChannels = null;
        Platform.runLater(this::rebuildPanels);
    }

    @Override
    public void visibleRegionChanged(QuPathViewer viewer, Shape shape) {
        if (sync() != Sync.CURSOR) {
            requestUpdate();
        }
    }

    @Override
    public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {
        if (sync() == Sync.SELECTED_OBJECT) {
            requestUpdate();
        }
    }

    @Override
    public void viewerClosed(QuPathViewer viewer) {
        bindViewer(null);
    }

    private void handleMouseMoved(MouseEvent e) {
        if (viewer == null) {
            return;
        }
        mouse = viewer.componentPointToImagePoint(e.getX(), e.getY(), null, false);
        if (sync() == Sync.CURSOR && !e.isShiftDown()) {
            center.setLocation(mouse);
        }
        requestUpdate();
    }

    private static Sync sync() {
        try {
            return Sync.valueOf(ChannelToolsPreferences.GRID_SYNC.get());
        } catch (IllegalArgumentException e) {
            return Sync.CURSOR;
        }
    }

    /** Downsample of the panels: the chosen zoom, or the main viewer's. */
    private double downsample() {
        double ds = ChannelToolsPreferences.GRID_DOWNSAMPLE.get();
        return ds > 0 || viewer == null ? Math.max(ds, 1e-3) : viewer.getDownsampleFactor();
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    /** One panel per channel shown, or per display preset, plus the merged image if wanted. */
    private void rebuildPanels() {
        panels.clear();
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        if (display == null || viewer == null || viewer.getImageData() == null) {
            return;
        }
        var mode = panelsMode();
        if (mode == Panels.PRESETS) {
            for (String name : presetNames()) {
                panels.add(new Panel(null, name));
            }
            panels.removeIf(p -> removed.contains(p.key()));
            if (panels.isEmpty()) {
                showEmptyMessage(qupath.getProject() == null ? "Display presets need an open project."
                        : !removed.isEmpty() && !presetNames().isEmpty() ? "All presets were removed from the grid."
                        : allPresetNames().isEmpty() ? "No display presets in this project -- save one in Brightness/Contrast."
                        : "No display preset in this project fits this image's channels.");
                return;
            }
        }
        List<ChannelDisplayInfo> channels;
        if (mode == Panels.PRESETS) {
            channels = new ArrayList<>();
        } else if (mode == Panels.ALL) {
            channels = new ArrayList<>(display.availableChannels());
        } else if (keptChannels != null) {
            channels = new ArrayList<>(display.availableChannels().stream()
                    .filter(c -> keptChannels.contains(c.getName())).toList());
        } else {
            channels = new ArrayList<>(display.selectedChannels());
        }
        if (mode == Panels.VISIBLE) {
            // Keep the image's channel order, not the order they were switched on
            channels.sort((a, b) -> Integer.compare(display.availableChannels().indexOf(a),
                    display.availableChannels().indexOf(b)));
        }
        for (var c : channels) {
            panels.add(new Panel(c, null));
        }
        panels.removeIf(p -> removed.contains(p.key()));
        if (ChannelToolsPreferences.GRID_MERGED.get() || panels.isEmpty()) {
            panels.add(new Panel(null, null));
        }
        panels.removeIf(p -> removed.contains(p.key()));
        if (panels.isEmpty()) {
            showEmptyMessage("All panels were removed from the grid.");
            return;
        }
        int n = panels.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        for (int c = 0; c < cols; c++) {
            var cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / cols);
            grid.getColumnConstraints().add(cc);
        }
        for (int r = 0; r < rows; r++) {
            var rc = new RowConstraints();
            rc.setPercentHeight(100.0 / rows);
            grid.getRowConstraints().add(rc);
        }
        for (int i = 0; i < n; i++) {
            var cell = new Pane(panels.get(i));
            cell.setMinSize(0, 0);
            panels.get(i).widthProperty().bind(cell.widthProperty());
            panels.get(i).heightProperty().bind(cell.heightProperty());
            GridPane.setHgrow(cell, Priority.ALWAYS);
            GridPane.setVgrow(cell, Priority.ALWAYS);
            grid.add(cell, i % cols, i / cols);
        }
        // Unused cells stay black rather than showing the separator color
        for (int i = n; i < rows * cols; i++) {
            var filler = new Pane();
            filler.setStyle("-fx-background-color: black;");
            filler.setMinSize(0, 0);
            grid.add(filler, i % cols, i / cols);
        }
        requestUpdate();
    }

    /** A message in place of the grid, whose right-click menu can restore panels or change mode. */
    private void showEmptyMessage(String text) {
        var label = new javafx.scene.control.Label(text + "\nRight-click for options.");
        label.setTextFill(Color.WHITE);
        label.setWrapText(true);
        label.setPadding(new javafx.geometry.Insets(12));
        label.setOnContextMenuRequested(e -> {
            buildMenu(new Panel(null, null)).show(label, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        grid.add(label, 0, 0);
    }

    private void requestUpdate() {
        if (updateRequested) {
            return;
        }
        updateRequested = true;
        Platform.runLater(() -> {
            updateRequested = false;
            updateCenter();
            for (var p : panels) {
                p.repaint();
            }
        });
    }

    private void updateCenter() {
        if (viewer == null) {
            return;
        }
        switch (sync()) {
            case VIEWER_CENTER -> center.setLocation(viewer.getCenterPixelX(), viewer.getCenterPixelY());
            case SELECTED_OBJECT -> {
                var selected = viewer.getSelectedObject();
                if (selected != null && selected.hasROI()) {
                    center.setLocation(selected.getROI().getCentroidX(), selected.getROI().getCentroidY());
                } else {
                    center.setLocation(viewer.getCenterPixelX(), viewer.getCenterPixelY());
                }
            }
            default -> {
                // CURSOR follows mouse moves; NONE keeps the last centre
            }
        }
    }

    private boolean isGrayscale(ChannelDisplayInfo channel) {
        return channel != null && (ChannelToolsPreferences.GRID_GRAYSCALE.get()
                || grayChannels.contains(channel.getName()));
    }

    /** One channel's panel; a null channel shows the merged image. */
    private class Panel extends Canvas {

        private final ChannelDisplayInfo channel;
        private final Renderer renderer;
        private BufferedImage img;
        private WritableImage imgFX;
        /** The preset shown instead of the channel; null to show the channel. */
        private PresetView preset;
        /** The preset this tile is for, in one-per-preset mode; null otherwise. */
        private final String tilePreset;

        Panel(ChannelDisplayInfo channel, String tilePreset) {
            this.channel = channel;
            this.tilePreset = tilePreset;
            this.renderer = new Renderer(this);
            // In one-per-preset mode the tiles are the presets; the merged panel stays merged
            String presetName = tilePreset != null ? tilePreset
                    : panelsMode() == Panels.PRESETS ? null : panelPresets.get(key());
            if (presetName != null) {
                preset = loadPreset(presetName);
            }
            widthProperty().addListener((o, a, b) -> requestUpdate());
            heightProperty().addListener((o, a, b) -> requestUpdate());
            setOnContextMenuRequested(e -> {
                buildMenu(this).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            Tooltip.install(this, Tooltips.of("Right-click for options: show this in the main viewer, "
                    + "choose a preset, remove it from the grid, grayscale."));
        }

        /** Identifies the panel across rebuilds: its channel's name, or the merged panel. */
        String key() {
            return tilePreset != null ? "<preset>" + tilePreset : channel == null ? "<merged>" : channel.getName();
        }

        void showPreset(String name) {
            if (name == null) {
                panelPresets.remove(key());
                preset = null;
            } else {
                panelPresets.put(key(), name);
                // Null if the preset does not fit this image; the choice is kept for images it fits
                preset = loadPreset(name);
            }
            localChanges.incrementAndGet();
            requestUpdate();
        }

        void repaint() {
            int w = (int) Math.ceil(getWidth());
            int h = (int) Math.ceil(getHeight());
            if (w <= 0 || h <= 0 || viewer == null || viewer.getServer() == null) {
                return;
            }
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            }
            Graphics2D g2d = img.createGraphics();
            int bg = PathPrefs.viewerBackgroundColorProperty().get();
            g2d.setColor(new java.awt.Color(bg));
            g2d.fillRect(0, 0, w, h);
            g2d.setClip(0, 0, w, h);
            double ds = downsample();
            var transform = new AffineTransform();
            transform.translate(w * .5, h * .5);
            transform.scale(1.0 / ds, 1.0 / ds);
            transform.translate(-center.getX(), -center.getY());
            double rotation = viewer.getRotation();
            if (rotation != 0) {
                transform.rotate(rotation, center.getX(), center.getY());
            }
            g2d.transform(transform);
            viewer.getImageRegionStore().paintRegion(viewer.getServer(), g2d, g2d.getClip(),
                    viewer.getZPosition(), viewer.getTPosition(), ds, viewer.getThumbnail(), null, renderer);
            var gammaOp = viewer.getGammaOp();
            if (gammaOp != null) {
                gammaOp.filter(img.getRaster(), img.getRaster());
            }
            float opacity = viewer.getOverlayOptions().getOpacity();
            if (ChannelToolsPreferences.GRID_OVERLAYS.get() && opacity > 0) {
                if (opacity < 1f) {
                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
                }
                var region = AwtTools.getImageRegion(g2d.getClipBounds(), viewer.getZPosition(), viewer.getTPosition());
                for (var overlay : viewer.getOverlayLayers()) {
                    overlay.paintOverlay(g2d, region, ds, viewer.getImageData(), false);
                }
            }
            g2d.dispose();
            imgFX = SwingFXUtils.toFXImage(img, imgFX != null && imgFX.getWidth() == w
                    && imgFX.getHeight() == h ? imgFX : null);

            GraphicsContext gc = getGraphicsContext2D();
            gc.clearRect(0, 0, getWidth(), getHeight());
            gc.drawImage(imgFX, 0, 0);
            if (ChannelToolsPreferences.GRID_CURSOR.get() && mouse != null) {
                Point2D p = transform.transform(mouse, null);
                drawCursor(gc, p.getX(), p.getY());
            }
            if (ChannelToolsPreferences.GRID_NAMES.get()) {
                drawName(gc, w, h);
            }
        }

        private void drawCursor(GraphicsContext gc, double x, double y) {
            double len = 4;
            gc.setLineWidth(4);
            gc.setStroke(Color.BLACK);
            gc.strokeLine(x, y - len, x, y + len);
            gc.strokeLine(x - len, y, x + len, y);
            gc.setLineWidth(2);
            gc.setStroke(Color.WHITE);
            gc.strokeLine(x, y - len, x, y + len);
            gc.strokeLine(x - len, y, x + len, y);
        }

        private void drawName(GraphicsContext gc, int w, int h) {
            String name = preset != null ? preset.name() : channel == null ? "Merged" : channel.getName();
            Color color = Color.WHITE;
            if (preset == null && channel != null && !isGrayscale(channel) && channel.getColor() != null) {
                int rgb = channel.getColor();
                color = Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
            }
            double size = Math.max(10, Math.min(18, h / 14.0));
            gc.setFont(Font.font("System", FontWeight.BOLD, size));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.BOTTOM);
            gc.setLineWidth(3);
            gc.setStroke(Color.rgb(0, 0, 0, 0.8));
            gc.strokeText(name, w / 2.0, h - 4);
            gc.setFill(color);
            gc.fillText(name, w / 2.0, h - 4);
        }
    }

    /** Renders a panel: its preset, or its channel (in grayscale if asked) from the main viewer's display. */
    private class Renderer extends AbstractImageRenderer {

        private final Panel panel;

        Renderer(Panel panel) {
            this.panel = panel;
        }

        @Override
        public long getLastChangeTimestamp() {
            // Both counts only increase, so the sum changes whenever either does
            long main = display == null ? 0 : display.getLastChangeTimestamp();
            return main + localChanges.get();
        }

        @Override
        public BufferedImage applyTransforms(BufferedImage imgInput, BufferedImage imgOutput) {
            if (display == null) {
                return imgInput;
            }
            var preset = panel.preset;
            if (preset != null) {
                return ImageDisplay.applyTransforms(imgInput, imgOutput, preset.channels(),
                        preset.inverted() ? ChannelDisplayMode.INVERTED_COLOR : ChannelDisplayMode.COLOR);
            }
            var channel = panel.channel;
            if (channel == null) {
                return display.applyTransforms(imgInput, imgOutput);
            }
            var mode = display.displayMode().getValue();
            if (isGrayscale(channel)) {
                mode = mode.invertColors() ? ChannelDisplayMode.INVERTED_GRAYSCALE : ChannelDisplayMode.GRAYSCALE;
            }
            return ImageDisplay.applyTransforms(imgInput, imgOutput, List.of(channel), mode);
        }
    }

    // ------------------------------------------------------------------
    // Presets and the main viewer
    // ------------------------------------------------------------------

    /** A display preset's showing channels, with its colours and ranges, for one image. */
    private record PresetView(String name, List<ChannelDisplayInfo> channels, boolean inverted) {
    }

    /**
     * Names of the saved display presets that fit the current image: QuPath's own test,
     * the same channels by number and name. A preset with any other channel is left out.
     */
    private List<String> presetNames() {
        if (display == null) {
            return List.of();
        }
        return allPresetNames().stream()
                .filter(name -> DisplaySettingUtils.settingsCompatibleWithDisplay(display, readPreset(name)))
                .toList();
    }

    /** Names of every display preset saved in the project (Brightness/Contrast settings). */
    private List<String> allPresetNames() {
        var project = qupath.getProject();
        if (project == null) {
            return List.of();
        }
        try {
            return DisplaySettingUtils.getResourcesForProject(project).getNames().stream().sorted().toList();
        } catch (IOException e) {
            logger.warn("Could not list display presets: {}", e.getMessage());
            return List.of();
        }
    }

    private ImageDisplaySettings readPreset(String name) {
        var project = qupath.getProject();
        if (project == null) {
            return null;
        }
        try {
            return DisplaySettingUtils.getResourcesForProject(project).get(name);
        } catch (IOException e) {
            logger.warn("Could not read display preset {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * The preset's showing channels as channel objects of their own, so drawing them never
     * changes the main viewer's display.
     *
     * @return null if the preset is missing or does not fit the current image
     */
    private PresetView loadPreset(String name) {
        var settings = readPreset(name);
        var imageData = viewer == null ? null : viewer.getImageData();
        if (settings == null || imageData == null
                || !DisplaySettingUtils.settingsCompatibleWithDisplay(display, settings)) {
            return null;
        }
        List<ChannelDisplayInfo> channels = new ArrayList<>();
        for (int i = 0; i < imageData.getServer().nChannels(); i++) {
            var info = new DirectServerChannelInfo(imageData, i);
            var cs = settings.getChannels().stream()
                    .filter(c -> c.getName().equals(info.getName()) && c.isShowing())
                    .findFirst().orElse(null);
            if (cs == null) {
                continue;
            }
            if (cs.getColor() != null) {
                info.setLUTColor(cs.getColor().getRed(), cs.getColor().getGreen(), cs.getColor().getBlue());
            }
            info.setMinDisplay(cs.getMinDisplay());
            info.setMaxDisplay(cs.getMaxDisplay());
            channels.add(info);
        }
        return new PresetView(name, channels, settings.invertBackground());
    }

    /**
     * Watch the project's preset folder by polling: QuPath fires no event when a preset is
     * saved, and file-system watch events are unreliable on network drives and WSL mounts.
     */
    private void startPresetPolling() {
        presetSignature = presetSignature();
        presetPoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "channel-grid-presets");
            t.setDaemon(true);
            return t;
        });
        presetPoller.scheduleWithFixedDelay(() -> {
            String now = presetSignature();
            if (!now.equals(presetSignature)) {
                presetSignature = now;
                Platform.runLater(this::refreshPresets);
            }
        }, PRESET_POLL_SECONDS, PRESET_POLL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopPresetPolling() {
        if (presetPoller != null) {
            presetPoller.shutdownNow();
            presetPoller = null;
        }
    }

    /** The preset files' names, sizes and modification times; empty without a project. */
    private String presetSignature() {
        var project = qupath.getProject();
        if (project == null) {
            return "";
        }
        var dir = new java.io.File(Projects.getBaseDirectory(project), "resources/display");
        var files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null) {
            return project.getPath() + ":none";
        }
        java.util.Arrays.sort(files);
        var sb = new StringBuilder(String.valueOf(project.getPath()));
        for (var f : files) {
            sb.append('|').append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified());
        }
        return sb.toString();
    }

    /** Show presets as they are now saved: new and deleted tiles, edited settings. */
    private void refreshPresets() {
        if (panelsMode() == Panels.PRESETS) {
            rebuildPanels();
            return;
        }
        var names = presetNames();
        for (var panel : panels) {
            if (panel.preset != null) {
                panel.showPreset(names.contains(panel.preset.name()) ? panel.preset.name() : null);
            }
        }
    }

    /** Change the main viewer's channels, keeping the grid's panels as they are. */
    private void changeMain(Runnable change) {
        if (display == null) {
            return;
        }
        keptChannels = panels.stream().filter(p -> p.channel != null).map(Panel::key).toList();
        changingMain = true;
        try {
            change.run();
        } finally {
            changingMain = false;
        }
        requestUpdate();
    }

    private void useInMainViewer(Panel panel) {
        if (panel.preset != null) {
            var settings = readPreset(panel.preset.name());
            changeMain(() -> {
                if (!DisplaySettingUtils.applySettingsToDisplay(display, settings)) {
                    logger.warn("Display preset {} does not match this image's channels", panel.preset.name());
                }
            });
        } else if (panel.channel != null) {
            changeMain(() -> {
                for (var c : display.availableChannels()) {
                    display.setChannelSelected(c, c == panel.channel);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Context menu
    // ------------------------------------------------------------------

    private ContextMenu buildMenu(Panel panel) {
        var menu = new ContextMenu();

        String shown = panel.preset != null ? "preset " + panel.preset.name()
                : panel.channel != null ? panel.channel.getName() : null;
        var use = Tooltips.on(new MenuItem(shown == null ? "Use in main viewer" : "Use " + shown + " in main viewer"),
                "Show this panel's channel, or preset, in the main viewer. The grid keeps its panels.");
        use.setDisable(shown == null);
        use.setOnAction(e -> useInMainViewer(panel));
        menu.getItems().add(use);
        if (panels.contains(panel)) {
            var remove = Tooltips.on(new MenuItem("Remove from grid"),
                    "Take this panel out of the grid. The main viewer is not changed.");
            remove.setOnAction(e -> {
                removed.add(panel.key());
                rebuildPanels();
            });
            menu.getItems().add(remove);
        }
        if (!removed.isEmpty()) {
            var restore = Tooltips.on(new MenuItem("Restore removed panels (" + removed.size() + ")"),
                    "Bring back every panel removed from the grid.");
            restore.setOnAction(e -> {
                removed.clear();
                rebuildPanels();
            });
            menu.getItems().add(restore);
        }
        menu.getItems().add(new SeparatorMenuItem());

        if (panel.tilePreset == null && panelsMode() != Panels.PRESETS) {
            addShowChoices(menu, panel);
        }

        var panelsMenu = Tooltips.on(new Menu("Panels..."), "What the grid shows.");
        var panelsGroup = new ToggleGroup();
        for (Panels m : Panels.values()) {
            var item = Tooltips.on(new RadioMenuItem(m.label), switch (m) {
                case VISIBLE -> "One panel per channel shown in the main viewer.";
                case ALL -> "One panel per channel of the image, shown or not.";
                case PRESETS -> "One panel per display preset saved in the project that fits this image.";
            });
            item.setToggleGroup(panelsGroup);
            item.setSelected(panelsMode() == m);
            item.setOnAction(e -> {
                keptChannels = null;
                ChannelToolsPreferences.GRID_PANELS.set(m.name());
                // Choosing the current mode again re-reads the presets
                rebuildPanels();
            });
            panelsMenu.getItems().add(item);
        }

        var syncMenu = Tooltips.on(new Menu("Sync to..."), "What the panels centre on.");
        var syncGroup = new ToggleGroup();
        for (Sync s : Sync.values()) {
            var item = Tooltips.on(new RadioMenuItem(s.label), switch (s) {
                case CURSOR -> "Follow the mouse over the main viewer. Hold Shift to stop following.";
                case VIEWER_CENTER -> "The centre of the main viewer.";
                case SELECTED_OBJECT -> "The selected object, or the viewer centre if none is selected.";
                case NONE -> "Stay where they are.";
            });
            item.setToggleGroup(syncGroup);
            item.setSelected(sync() == s);
            item.setOnAction(e -> ChannelToolsPreferences.GRID_SYNC.set(s.name()));
            syncMenu.getItems().add(item);
        }

        var zoomMenu = Tooltips.on(new Menu("Zoom..."),
                "Magnification of the panels. 100% is one image pixel per screen pixel.");
        var zoomGroup = new ToggleGroup();
        for (Object[] z : ZOOMS) {
            double ds = (Double) z[1];
            var item = new RadioMenuItem((String) z[0]);
            item.setToggleGroup(zoomGroup);
            item.setSelected(Math.abs(ChannelToolsPreferences.GRID_DOWNSAMPLE.get() - ds) < 1e-9);
            item.setOnAction(e -> ChannelToolsPreferences.GRID_DOWNSAMPLE.set(ds));
            zoomMenu.getItems().add(item);
        }

        menu.getItems().addAll(syncMenu, zoomMenu, new SeparatorMenuItem(),
                panelsMenu,
                Tooltips.on(check("Show merged image", ChannelToolsPreferences.GRID_MERGED),
                        "Add a panel showing the main viewer's channels combined."),
                Tooltips.on(check("Show channel names", ChannelToolsPreferences.GRID_NAMES),
                        "Label each panel with its channel or preset."),
                Tooltips.on(check("Show cursor", ChannelToolsPreferences.GRID_CURSOR),
                        "Mark the mouse position from the main viewer in every panel."),
                Tooltips.on(check("Show overlays", ChannelToolsPreferences.GRID_OVERLAYS),
                        "Draw annotations and detections, as in the main viewer."),
                new SeparatorMenuItem(),
                Tooltips.on(check("All channels in grayscale", ChannelToolsPreferences.GRID_GRAYSCALE),
                        "Show every channel panel in grayscale; often easier to read than dark colours. "
                        + "The main viewer keeps its colours."));

        if (panel.channel != null && panel.preset == null) {
            var one = Tooltips.on(new CheckMenuItem("This channel in grayscale (" + panel.channel.getName() + ")"),
                    "Show only this panel in grayscale.");
            one.setSelected(isGrayscale(panel.channel));
            one.setDisable(ChannelToolsPreferences.GRID_GRAYSCALE.get());
            one.setOnAction(e -> {
                if (one.isSelected()) {
                    grayChannels.add(panel.channel.getName());
                } else {
                    grayChannels.remove(panel.channel.getName());
                }
                localChanges.incrementAndGet();
                requestUpdate();
            });
            menu.getItems().add(one);
        }
        return Tooltips.install(menu);
    }

    /** The panel's own channel and every saved preset, to choose what the panel shows. */
    private void addShowChoices(ContextMenu menu, Panel panel) {
        var showGroup = new ToggleGroup();
        var own = Tooltips.on(new RadioMenuItem(panel.channel == null ? "Merged image" : panel.channel.getName()),
                "Show the panel's own channel.");
        own.setToggleGroup(showGroup);
        own.setSelected(panel.preset == null);
        own.setOnAction(e -> panel.showPreset(null));
        menu.getItems().add(own);
        var names = presetNames();
        if (names.isEmpty()) {
            var none = new MenuItem(qupath.getProject() == null ? "Display presets need an open project"
                    : allPresetNames().isEmpty() ? "No display presets -- save one in Brightness/Contrast"
                    : "No display preset fits this image's channels");
            none.setDisable(true);
            menu.getItems().add(none);
        }
        for (String name : names) {
            var item = Tooltips.on(new RadioMenuItem("Preset: " + name),
                    "Show this display preset's channels, colours and ranges in this panel.");
            item.setToggleGroup(showGroup);
            item.setSelected(panel.preset != null && name.equals(panel.preset.name()));
            item.setOnAction(e -> panel.showPreset(name));
            menu.getItems().add(item);
        }
        menu.getItems().add(new SeparatorMenuItem());

    }

    private static CheckMenuItem check(String text, BooleanProperty property) {
        var item = new CheckMenuItem(text);
        item.setSelected(property.get());
        item.setOnAction(e -> property.set(item.isSelected()));
        return item;
    }
}
