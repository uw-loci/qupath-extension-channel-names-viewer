package qupath.ext.channelnamesviewer.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.core.BackgroundContrast;
import qupath.ext.channelnamesviewer.core.ChannelSampler;
import qupath.ext.channelnamesviewer.preferences.ChannelToolsPreferences;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;

/**
 * Sets each channel's display range from its background peak, so background renders black
 * and many channels do not add up to a haze. Changes apply to the viewer as they are made;
 * Revert restores the ranges the channels had before.
 */
public class AutoContrastWindow {

    private static final Logger logger = LoggerFactory.getLogger(AutoContrastWindow.class);

    private static final String TITLE = "Background-Aware Auto Contrast";
    private static final double FALLBACK_SATURATED = 0.001;
    private static final int HIST_W = 170;
    private static final int HIST_H = 30;
    private static final int HIST_BINS = 85;

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final TableView<Row> table = new TableView<>();
    private final Label status = new Label();
    private final Slider noiseSlider = new Slider(0, 8, 3);
    private final Label noiseLabel = new Label();
    private final Spinner<Double> saturatedSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 5, 0.5, 0.1));
    private final RadioButton visibleRadio = new RadioButton("Visible channels");
    private final RadioButton allRadio = new RadioButton("All channels");
    private final Button applyButton = new Button("Apply");
    private final Button revertButton = new Button("Revert");

    /** Samples of the current image, per server channel; null until read. */
    private float[][] samples;
    private ImageData<BufferedImage> sampledImage;
    /** Ranges before this window changed them, per channel of the current image. */
    private final Map<ChannelDisplayInfo, float[]> original = new IdentityHashMap<>();
    /** True once Apply was pressed for the current image: later changes apply live. */
    private boolean live;
    private final AtomicInteger generation = new AtomicInteger();
    private final ChangeListener<ImageData<BufferedImage>> imageListener =
            (obs, was, now) -> Platform.runLater(this::sampleCurrentImage);

    /** One channel's result. */
    private record Row(ChannelDisplayInfo info, String name, Color color, float[] values,
                       BackgroundContrast.Result result) {
    }

    public AutoContrastWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);
        stage.setScene(new Scene(buildContent(), 940, 560));
        stage.setOnHidden(e -> qupath.imageDataProperty().removeListener(imageListener));
        stage.setOnShown(e -> qupath.imageDataProperty().addListener(imageListener));
    }

    public void show() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        stage.show();
        live = true; // opening the tool applies it
        sampleCurrentImage();
    }

    private VBox buildContent() {
        var intro = new Label("Sets each channel's minimum just above its background peak -- the tall, "
                + "narrow peak of the histogram -- so background shows as black and many channels do not "
                + "add up to a haze. The maximum is set from the brightest pixels above that minimum. "
                + "Changes show in the viewer immediately; Revert restores the previous ranges.");
        intro.setWrapText(true);
        intro.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        intro.setStyle("-fx-text-fill: -fx-mid-text-color;");

        var group = new ToggleGroup();
        visibleRadio.setToggleGroup(group);
        allRadio.setToggleGroup(group);
        (ChannelToolsPreferences.ALL_CHANNELS.get() ? allRadio : visibleRadio).setSelected(true);
        group.selectedToggleProperty().addListener((o, a, b) -> {
            ChannelToolsPreferences.ALL_CHANNELS.set(allRadio.isSelected());
            recompute();
        });

        noiseSlider.setValue(ChannelToolsPreferences.NOISE_MULTIPLE.get());
        noiseSlider.setMajorTickUnit(1);
        noiseSlider.setShowTickMarks(true);
        noiseSlider.setShowTickLabels(true);
        noiseSlider.setBlockIncrement(0.5);
        noiseSlider.setPrefWidth(260);
        noiseSlider.setTooltip(new Tooltip("How far above the background peak the minimum sits, in "
                + "multiples of the background noise (its standard deviation, measured from the peak's "
                + "rising edge). 3 hides about 99.9% of background pixels; raise it if haze remains."));
        noiseSlider.valueProperty().addListener((o, a, b) -> {
            ChannelToolsPreferences.NOISE_MULTIPLE.set(b.doubleValue());
            recompute();
        });
        saturatedSpinner.getValueFactory().setValue(ChannelToolsPreferences.SATURATED_PERCENT.get());
        saturatedSpinner.setEditable(true);
        saturatedSpinner.setPrefWidth(80);
        saturatedSpinner.setTooltip(new Tooltip("Percent of the pixels above the minimum that may "
                + "saturate at the maximum."));
        saturatedSpinner.valueProperty().addListener((o, a, b) -> {
            ChannelToolsPreferences.SATURATED_PERCENT.set(b);
            recompute();
        });

        var controls = new HBox(10, new Label("Apply to:"), visibleRadio, allRadio);
        controls.setAlignment(Pos.CENTER_LEFT);
        var noiseBox = new HBox(10, new Label("Minimum:"), noiseSlider, noiseLabel);
        noiseBox.setAlignment(Pos.CENTER_LEFT);
        var satBox = new HBox(10, new Label("Saturate brightest (%):"), saturatedSpinner);
        satBox.setAlignment(Pos.CENTER_LEFT);

        buildTable();

        applyButton.setOnAction(e -> {
            live = true;
            applyRanges();
        });
        applyButton.setTooltip(new Tooltip("Set the listed display ranges; later changes apply as you make them."));
        revertButton.setOnAction(e -> revert());
        revertButton.setTooltip(new Tooltip("Restore the ranges the channels had before this tool changed them."));
        var resample = new Button("Resample");
        resample.setTooltip(new Tooltip("Read the pixels again, e.g. after moving to another z-slice or timepoint."));
        resample.setOnAction(e -> sampleCurrentImage());
        var buttons = new HBox(8, applyButton, revertButton, resample);
        status.setWrapText(true);

        var box = new VBox(10, intro, controls, noiseBox, satBox, table, buttons, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        updateNoiseLabel();
        return box;
    }

    private void buildTable() {
        var nameCol = new TableColumn<Row, Row>("Channel");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        nameCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                var swatch = new javafx.scene.shape.Rectangle(10, 10, row.color());
                setGraphic(swatch);
                setText(row.name());
            }
        });
        nameCol.setPrefWidth(140);

        var histCol = new TableColumn<Row, Row>("Histogram");
        histCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        histCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : histogram(row));
            }
        });
        histCol.setPrefWidth(HIST_W + 12);

        table.getColumns().add(nameCol);
        table.getColumns().add(histCol);
        table.getColumns().add(numberColumn("Background", r -> r.result().background()));
        table.getColumns().add(numberColumn("Noise", r -> r.result().noise()));
        table.getColumns().add(numberColumn("Min", r -> r.result().min()));
        table.getColumns().add(numberColumn("Max", r -> r.result().max()));
        var noteCol = new TableColumn<Row, String>("Note");
        noteCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(note(c.getValue())));
        noteCol.setPrefWidth(230);
        table.getColumns().add(noteCol);
        table.setPlaceholder(new Label("No fluorescence channels to adjust"));
        table.setFixedCellSize(HIST_H + 8);
    }

    private static TableColumn<Row, String> numberColumn(String title,
                                                         java.util.function.ToDoubleFunction<Row> f) {
        var col = new TableColumn<Row, String>(title);
        col.setCellValueFactory(c -> {
            double v = f.applyAsDouble(c.getValue());
            return new javafx.beans.property.SimpleStringProperty(
                    Double.isNaN(v) ? "-" : v >= 100 ? String.format("%.0f", v) : String.format("%.3g", v));
        });
        col.setPrefWidth(84);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private static String note(Row row) {
        var r = row.result();
        if (!r.backgroundPeak()) {
            return "No background peak -- percentiles used";
        }
        return String.format("%.1f%% of pixels above min", 100 * r.foreground());
    }

    /**
     * Square-root-count histogram around the display range, with the background
     * peak (grey), minimum (orange) and maximum (blue) marked.
     */
    private static Canvas histogram(Row row) {
        var canvas = new Canvas(HIST_W, HIST_H);
        var gc = canvas.getGraphicsContext2D();
        float[] v = row.values();
        var r = row.result();
        double lo = Math.min(r.min(), Double.isNaN(r.background()) ? r.min() : r.background());
        double hi = r.max();
        double span = hi - lo;
        lo -= 0.15 * span;
        hi += 0.1 * span;
        if (!(hi > lo)) {
            return canvas;
        }
        long[] counts = new long[HIST_BINS];
        for (float x : v) {
            int i = (int) ((x - lo) / (hi - lo) * HIST_BINS);
            if (i >= 0 && i < HIST_BINS) {
                counts[i]++;
            }
        }
        // Square root keeps the background peak standing out, which a log scale flattens
        double maxRoot = 0;
        for (long c : counts) {
            maxRoot = Math.max(maxRoot, Math.sqrt(c));
        }
        double bw = (double) HIST_W / HIST_BINS;
        gc.setFill(Color.gray(0.55));
        for (int i = 0; i < HIST_BINS; i++) {
            double h = maxRoot > 0 ? Math.sqrt(counts[i]) / maxRoot * (HIST_H - 2) : 0;
            gc.fillRect(i * bw, HIST_H - h, Math.max(1, bw - 0.5), h);
        }
        double scale = HIST_W / (hi - lo);
        if (!Double.isNaN(r.background())) {
            marker(gc, (r.background() - lo) * scale, Color.gray(0.3));
        }
        marker(gc, (r.min() - lo) * scale, Color.web("#e07000"));
        marker(gc, (r.max() - lo) * scale, Color.web("#1f6fd6"));
        return canvas;
    }

    private static void marker(javafx.scene.canvas.GraphicsContext gc, double x, Color c) {
        gc.setStroke(c);
        gc.setLineWidth(2);
        gc.strokeLine(x, 0, x, HIST_H);
    }

    private void updateNoiseLabel() {
        noiseLabel.setText(String.format("background + %.1f x noise", noiseSlider.getValue()));
    }

    /** The viewer's display, or null. */
    private ImageDisplay display() {
        var viewer = qupath.getViewer();
        return viewer == null ? null : viewer.getImageDisplay();
    }

    /** Read the current image's pixels off the FX thread, then recompute. */
    private void sampleCurrentImage() {
        var viewer = qupath.getViewer();
        ImageData<BufferedImage> imageData = viewer == null ? null : viewer.getImageData();
        int gen = generation.incrementAndGet();
        samples = null;
        table.getItems().clear();
        if (imageData == null || imageData.getServer().isRGB()) {
            sampledImage = imageData;
            status.setText(imageData == null ? "No image open." : "This image is RGB; there are no channels to adjust.");
            return;
        }
        if (imageData != sampledImage) {
            original.clear();
            // Another image: show its ranges, but change nothing until Apply
            if (sampledImage != null) {
                live = false;
            }
        }
        sampledImage = imageData;
        int z = viewer.getZPosition();
        int t = viewer.getTPosition();
        status.setText("Reading pixels...");
        var server = imageData.getServer();
        var thread = new Thread(() -> {
            try {
                float[][] values = ChannelSampler.sample(server, z, t, () -> generation.get() != gen);
                Platform.runLater(() -> {
                    if (generation.get() != gen || values == null) {
                        return;
                    }
                    samples = values;
                    recompute();
                });
            } catch (Exception ex) {
                logger.warn("Could not sample {}: {}", server.getPath(), ex.getMessage());
                Platform.runLater(() -> status.setText("Could not read pixels: " + ex.getMessage()));
            }
        }, "channel-auto-contrast");
        thread.setDaemon(true);
        thread.start();
    }

    /** Recompute every row from the samples, and apply if live. */
    private void recompute() {
        updateNoiseLabel();
        var display = display();
        if (samples == null || display == null) {
            return;
        }
        List<ChannelDisplayInfo> channels = allRadio.isSelected()
                ? display.availableChannels() : display.selectedChannels();
        double k = noiseSlider.getValue();
        double saturated = saturatedSpinner.getValue() / 100.0;
        List<Row> rows = new ArrayList<>();
        int fallbacks = 0;
        for (var info : channels) {
            if (!(info instanceof DirectServerChannelInfo direct) || direct.getChannel() >= samples.length) {
                continue;
            }
            float[] v = samples[direct.getChannel()];
            var result = BackgroundContrast.compute(v, k, saturated, FALLBACK_SATURATED);
            if (!result.backgroundPeak()) {
                fallbacks++;
            }
            int rgb = info.getColor() == null ? 0xffffff : info.getColor();
            rows.add(new Row(info, info.getName(), Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255),
                    v, result));
        }
        table.setItems(FXCollections.observableArrayList(rows));
        status.setText(String.format("%d channel(s) from %,d sampled pixels each%s%s.", rows.size(),
                samples.length > 0 ? samples[0].length : 0,
                fallbacks > 0 ? "; " + fallbacks + " without a background peak used percentiles" : "",
                live ? "" : "; press Apply to set them"));
        if (live) {
            applyRanges();
        }
    }

    private void applyRanges() {
        var display = display();
        if (display == null) {
            return;
        }
        for (var row : table.getItems()) {
            original.computeIfAbsent(row.info(),
                    i -> new float[] {i.getMinDisplay(), i.getMaxDisplay()});
            display.setMinMaxDisplay(row.info(), (float) row.result().min(), (float) row.result().max());
        }
    }

    private void revert() {
        var display = display();
        if (display == null) {
            return;
        }
        for (var e : original.entrySet()) {
            display.setMinMaxDisplay(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        original.clear();
        live = false;
        status.setText("Restored the previous ranges. Press Apply to set them again.");
    }
}
