package qupath.ext.channelnamesviewer.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
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
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.images.stores.AbstractImageRenderer;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

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
    /** Counts grayscale changes, so rendered tiles cached before one are not reused. */
    private final AtomicLong grayscaleChanges = new AtomicLong();

    private final ListChangeListener<ChannelDisplayInfo> channelListener = c -> Platform.runLater(this::rebuildPanels);
    private final ChangeListener<Number> displayListener = (o, a, b) -> requestUpdate();
    private final ChangeListener<QuPathViewer> viewerListener = (o, a, b) -> bindViewer(b);
    private final InvalidationListener prefListener = o -> requestUpdate();
    private final EventHandler<MouseEvent> mouseMoved = this::handleMouseMoved;

    public ChannelGridWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);
        grid.setStyle("-fx-background-color: black;");
        stage.setScene(new Scene(grid, 640, 520));
        stage.setOnShown(e -> {
            qupath.viewerProperty().addListener(viewerListener);
            for (var p : List.of(ChannelToolsPreferences.GRID_NAMES, ChannelToolsPreferences.GRID_CURSOR,
                    ChannelToolsPreferences.GRID_OVERLAYS, ChannelToolsPreferences.GRID_DOWNSAMPLE,
                    ChannelToolsPreferences.GRID_SYNC)) {
                p.addListener(prefListener);
            }
            ChannelToolsPreferences.GRID_ALL_CHANNELS.addListener(prefRebuild);
            ChannelToolsPreferences.GRID_MERGED.addListener(prefRebuild);
            ChannelToolsPreferences.GRID_GRAYSCALE.addListener(grayscaleListener);
            bindViewer(qupath.getViewer());
        });
        stage.setOnHidden(e -> {
            qupath.viewerProperty().removeListener(viewerListener);
            for (var p : List.of(ChannelToolsPreferences.GRID_NAMES, ChannelToolsPreferences.GRID_CURSOR,
                    ChannelToolsPreferences.GRID_OVERLAYS, ChannelToolsPreferences.GRID_DOWNSAMPLE,
                    ChannelToolsPreferences.GRID_SYNC)) {
                p.removeListener(prefListener);
            }
            ChannelToolsPreferences.GRID_ALL_CHANNELS.removeListener(prefRebuild);
            ChannelToolsPreferences.GRID_MERGED.removeListener(prefRebuild);
            ChannelToolsPreferences.GRID_GRAYSCALE.removeListener(grayscaleListener);
            bindViewer(null);
        });
        stage.widthProperty().addListener((o, a, b) -> requestUpdate());
        stage.heightProperty().addListener((o, a, b) -> requestUpdate());
    }

    private final InvalidationListener prefRebuild = o -> rebuildPanels();
    private final InvalidationListener grayscaleListener = o -> {
        grayscaleChanges.incrementAndGet();
        requestUpdate();
    };

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

    /** One panel per channel shown, plus the merged image if wanted. */
    private void rebuildPanels() {
        panels.clear();
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        if (display == null || viewer == null || viewer.getImageData() == null) {
            return;
        }
        List<ChannelDisplayInfo> channels = new ArrayList<>(ChannelToolsPreferences.GRID_ALL_CHANNELS.get()
                ? display.availableChannels() : display.selectedChannels());
        if (!ChannelToolsPreferences.GRID_ALL_CHANNELS.get()) {
            // Keep the image's channel order, not the order they were switched on
            channels.sort((a, b) -> Integer.compare(display.availableChannels().indexOf(a),
                    display.availableChannels().indexOf(b)));
        }
        for (var c : channels) {
            panels.add(new Panel(c));
        }
        if (ChannelToolsPreferences.GRID_MERGED.get() || panels.isEmpty()) {
            panels.add(new Panel(null));
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
        requestUpdate();
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

        Panel(ChannelDisplayInfo channel) {
            this.channel = channel;
            this.renderer = new Renderer(channel);
            widthProperty().addListener((o, a, b) -> requestUpdate());
            heightProperty().addListener((o, a, b) -> requestUpdate());
            setOnContextMenuRequested(e -> {
                buildMenu(this).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
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
            String name = channel == null ? "Merged" : channel.getName();
            Color color = Color.WHITE;
            if (channel != null && !isGrayscale(channel) && channel.getColor() != null) {
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

    /** Renders one channel, in grayscale if asked, from the main viewer's display. */
    private class Renderer extends AbstractImageRenderer {

        private final ChannelDisplayInfo channel;

        Renderer(ChannelDisplayInfo channel) {
            this.channel = channel;
        }

        @Override
        public long getLastChangeTimestamp() {
            // Both counts only increase, so the sum changes whenever either does
            long main = display == null ? 0 : display.getLastChangeTimestamp();
            return main + grayscaleChanges.get();
        }

        @Override
        public BufferedImage applyTransforms(BufferedImage imgInput, BufferedImage imgOutput) {
            if (display == null) {
                return imgInput;
            }
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
    // Context menu
    // ------------------------------------------------------------------

    private ContextMenu buildMenu(Panel panel) {
        var menu = new ContextMenu();

        var syncMenu = new Menu("Sync to...");
        var syncGroup = new ToggleGroup();
        for (Sync s : Sync.values()) {
            var item = new RadioMenuItem(s.label);
            item.setToggleGroup(syncGroup);
            item.setSelected(sync() == s);
            item.setOnAction(e -> ChannelToolsPreferences.GRID_SYNC.set(s.name()));
            syncMenu.getItems().add(item);
        }

        var zoomMenu = new Menu("Zoom...");
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
                check("Show all channels", ChannelToolsPreferences.GRID_ALL_CHANNELS),
                check("Show merged image", ChannelToolsPreferences.GRID_MERGED),
                check("Show channel names", ChannelToolsPreferences.GRID_NAMES),
                check("Show cursor", ChannelToolsPreferences.GRID_CURSOR),
                check("Show overlays", ChannelToolsPreferences.GRID_OVERLAYS),
                new SeparatorMenuItem(),
                check("All channels in grayscale", ChannelToolsPreferences.GRID_GRAYSCALE));

        if (panel.channel != null) {
            var one = new CheckMenuItem("This channel in grayscale (" + panel.channel.getName() + ")");
            one.setSelected(isGrayscale(panel.channel));
            one.setDisable(ChannelToolsPreferences.GRID_GRAYSCALE.get());
            one.setOnAction(e -> {
                if (one.isSelected()) {
                    grayChannels.add(panel.channel.getName());
                } else {
                    grayChannels.remove(panel.channel.getName());
                }
                grayscaleChanges.incrementAndGet();
                requestUpdate();
            });
            menu.getItems().add(one);
        }
        return menu;
    }

    private static CheckMenuItem check(String text, BooleanProperty property) {
        var item = new CheckMenuItem(text);
        item.setSelected(property.get());
        item.setOnAction(e -> property.set(item.isSelected()));
        return item;
    }
}
