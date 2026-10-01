package qupath.ext.channelnamesviewer.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.core.WheelColors;
import qupath.ext.channelnamesviewer.preferences.ChannelToolsPreferences;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerMetadata;

/**
 * Colours the visible channels with evenly spaced hues from a colour wheel; drag a spoke
 * to rotate them all. Port of Sara McArdle's Channel Color Chooser
 * (https://saramcardle.github.io/ColorWheelPicker/, MIT). Like Brightness/Contrast, colours
 * apply as they change; Revert restores those the channels had when the window opened.
 */
public class ColorWheelWindow {

    private static final Logger logger = LoggerFactory.getLogger(ColorWheelWindow.class);

    private static final String TITLE = "Channel Color Wheel";
    private static final int WHEEL = 300;

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final Canvas canvas = new Canvas(WHEEL, WHEEL);
    private final ComboBox<WheelColors.Mode> modeCombo =
            new ComboBox<>(javafx.collections.FXCollections.observableArrayList(WheelColors.Mode.values()));
    private final Slider lightnessSlider = new Slider();
    private final Label lightnessLabel = new Label();
    private final CheckBox spreadCheck = new CheckBox("Spread neighbouring channels apart");
    private final GridPane rowsPane = new GridPane();
    private final Label status = new Label();

    private WheelColors wheel;
    private WritableImage wheelImage;
    private double baseAngle;
    private int dragIndex;
    private List<DirectServerChannelInfo> channels = List.of();
    private ImageDisplay display;
    private ImageData<BufferedImage> imageData;
    /** Colours by server channel index before this window changed them, for Revert. */
    private final Map<Integer, Integer> original = new java.util.HashMap<>();

    /** True while this window's own metadata write is updating the display's channels. */
    private boolean writing;

    private final ListChangeListener<ChannelDisplayInfo> selectionListener = c -> {
        if (!writing) {
            javafx.application.Platform.runLater(this::rebind);
        }
    };
    private final ChangeListener<ImageData<BufferedImage>> imageListener = (o, a, b) -> {
        original.clear();
        rebind();
    };

    public ColorWheelWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);
        stage.setScene(new Scene(buildContent()));
        stage.setOnShown(e -> {
            qupath.imageDataProperty().addListener(imageListener);
            rebind();
        });
        stage.setOnHidden(e -> {
            qupath.imageDataProperty().removeListener(imageListener);
            unbindDisplay();
            original.clear();
        });
    }

    public void show() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        stage.show();
    }

    private HBox buildContent() {
        try {
            modeCombo.setValue(WheelColors.Mode.valueOf(ChannelToolsPreferences.WHEEL_MODE.get()));
        } catch (IllegalArgumentException e) {
            modeCombo.setValue(WheelColors.Mode.HSV);
        }
        modeCombo.setTooltip(Tooltips.of("HSV: plain hues at full strength. CIELAB: hues of equal perceived "
                + "brightness, so no channel looks dimmer than another."));
        lightnessSlider.setTooltip(Tooltips.of("How bright the colours are: HSV value, or CIELAB lightness L*."));
        modeCombo.valueProperty().addListener((o, a, b) -> {
            ChannelToolsPreferences.WHEEL_MODE.set(b.name());
            configureSlider();
            rebuildWheel();
            commit();
        });
        lightnessSlider.setPrefWidth(200);
        lightnessSlider.valueProperty().addListener((o, a, b) -> {
            if (modeCombo.getValue() == WheelColors.Mode.CIELAB) {
                ChannelToolsPreferences.WHEEL_LIGHTNESS.set(b.doubleValue());
            } else {
                ChannelToolsPreferences.WHEEL_VALUE.set(b.doubleValue() / 100);
            }
            rebuildWheel();
        });
        spreadCheck.setSelected(ChannelToolsPreferences.WHEEL_SPREAD.get());
        spreadCheck.setTooltip(Tooltips.of("Give channels that are listed next to each other colours "
                + "far apart on the wheel, instead of neighbouring hues."));
        spreadCheck.selectedProperty().addListener((o, a, b) -> {
            ChannelToolsPreferences.WHEEL_SPREAD.set(b);
            preview();
            commit();
        });
        lightnessSlider.valueChangingProperty().addListener((o, a, changing) -> {
            if (!changing) {
                commit();
            }
        });
        configureSlider();

        canvas.setOnMousePressed(e -> {
            dragIndex = nearestSpoke(angleAt(e.getX(), e.getY()));
            rotateTo(e.getX(), e.getY());
        });
        canvas.setOnMouseDragged(e -> rotateTo(e.getX(), e.getY()));
        canvas.setOnMouseReleased(e -> commit());
        Tooltip.install(canvas, Tooltips.of("Drag any spoke to rotate all the colours together."));

        var revert = new Button("Revert");
        revert.setTooltip(Tooltips.of("Restore the colours the channels had when this window opened."));
        revert.setOnAction(e -> revert());
        var copy = new Button("Copy script");
        copy.setTooltip(Tooltips.of("Copy a Groovy script that sets these colours, for other images with the same channels."));
        copy.setOnAction(e -> copyScript());

        var modeBox = new HBox(8, new Label("Wheel:"), modeCombo);
        modeBox.setAlignment(Pos.CENTER_LEFT);
        var lightBox = new HBox(8, lightnessLabel, lightnessSlider);
        lightBox.setAlignment(Pos.CENTER_LEFT);
        rowsPane.setHgap(8);
        rowsPane.setVgap(4);
        var note = new Label("Colours apply as you change them, as in Brightness/Contrast; Revert "
                + "restores the ones the channels had when this window opened.\nColor wheel by Sara "
                + "McArdle (saramcardle.github.io/ColorWheelPicker).");
        note.setWrapText(true);
        note.setMaxWidth(320);
        note.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        note.setStyle("-fx-text-fill: -fx-mid-text-color;");
        status.setWrapText(true);
        status.setMaxWidth(320);
        var rowsScroll = new javafx.scene.control.ScrollPane(rowsPane);
        rowsScroll.setFitToWidth(true);
        rowsScroll.setPrefViewportHeight(WHEEL - 40);
        rowsScroll.setPrefViewportWidth(300);
        var right = new VBox(10, modeBox, lightBox, spreadCheck, rowsScroll,
                new HBox(8, revert, copy), status, note);
        var root = new HBox(14, canvas, right);
        root.setPadding(new Insets(12));
        return root;
    }

    private void configureSlider() {
        // Read the saved value first: narrowing the range clamps the value, and the
        // slider's listener would save the clamped one
        boolean lab = modeCombo.getValue() == WheelColors.Mode.CIELAB;
        double saved = lab ? ChannelToolsPreferences.WHEEL_LIGHTNESS.get()
                : ChannelToolsPreferences.WHEEL_VALUE.get() * 100;
        lightnessLabel.setText(lab ? "Lightness (L*):" : "Value (%):");
        lightnessSlider.setMin(20);
        lightnessSlider.setMax(lab ? 95 : 100);
        lightnessSlider.setValue(Math.max(20, Math.min(lightnessSlider.getMax(), saved)));
    }

    private void rebuildWheel() {
        var mode = modeCombo.getValue();
        double l = mode == WheelColors.Mode.CIELAB
                ? lightnessSlider.getValue() : lightnessSlider.getValue() / 100;
        wheel = new WheelColors(mode, l);
        wheelImage = new WritableImage(WHEEL, WHEEL);
        PixelWriter pw = wheelImage.getPixelWriter();
        double c = WHEEL / 2.0;
        double r = c - 6;
        for (int y = 0; y < WHEEL; y++) {
            for (int x = 0; x < WHEEL; x++) {
                double dx = x - c;
                double dy = y - c;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d <= r) {
                    double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
                    pw.setArgb(x, y, 0xff000000 | wheel.rgb(angle, d / r));
                }
            }
        }
        preview();
    }

    /** Current channels of the viewer's display, and their colours before this window. */
    private void rebind() {
        unbindDisplay();
        var viewer = qupath.getViewer();
        display = viewer == null ? null : viewer.getImageDisplay();
        imageData = viewer == null ? null : viewer.getImageData();
        List<DirectServerChannelInfo> list = new ArrayList<>();
        if (display != null && imageData != null && !imageData.getServer().isRGB()) {
            display.selectedChannels().addListener(selectionListener);
            for (var info : display.availableChannels()) {
                if (info instanceof DirectServerChannelInfo d && display.selectedChannels().contains(info)) {
                    list.add(d);
                    original.putIfAbsent(d.getChannel(), d.getColor());
                }
            }
        }
        channels = list;
        if (wheel == null) {
            rebuildWheel();
        } else {
            preview();
        }
    }

    private void unbindDisplay() {
        if (display != null) {
            display.selectedChannels().removeListener(selectionListener);
        }
    }

    /** Colour for each channel, index-aligned with {@link #channels}. */
    private int[] colors() {
        int n = channels.size();
        double[] angles = WheelColors.spokeAngles(Math.max(1, n), baseAngle);
        int[] order = WheelColors.spokeOrder(Math.max(1, n), spreadCheck.isSelected());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = wheel.rgb(angles[order[i]], 1);
        }
        return out;
    }

    private static void setColor(DirectServerChannelInfo channel, Integer rgb) {
        if (rgb != null) {
            channel.setLUTColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        }
    }


    /** Draw the wheel and set the channel LUTs to the current colours. */
    private void preview() {
        draw();
        rowsPane.getChildren().clear();
        if (channels.isEmpty()) {
            status.setText(imageData == null ? "No image open."
                    : "Select the channels to colour in Brightness/Contrast.");
            return;
        }
        int[] colors = colors();
        for (int i = 0; i < channels.size(); i++) {
            int rgb = colors[i];
            var ch = channels.get(i);
            ch.setLUTColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
            rowsPane.add(new Rectangle(14, 14, fx(rgb)), 0, i);
            rowsPane.add(new Label(ch.getName()), 1, i);
            rowsPane.add(new Label(String.format("#%06x", rgb)), 2, i);
        }
        status.setText(channels.size() + " visible channel(s), " + String.format("%.0f", 360.0 / channels.size())
                + " degrees apart.");
        if (display != null) {
            // Repaints the viewer and the legend; the metadata is written on release
            display.saveChannelColorProperties();
        }
    }

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, WHEEL, WHEEL);
        if (wheelImage != null) {
            gc.drawImage(wheelImage, 0, 0);
        }
        double c = WHEEL / 2.0;
        double r = c - 6;
        int n = Math.max(1, channels.size());
        for (double a : WheelColors.spokeAngles(n, baseAngle)) {
            double th = Math.toRadians(a - 90);
            double ex = c + Math.cos(th) * r;
            double ey = c + Math.sin(th) * r;
            gc.setStroke(Color.rgb(255, 255, 255, 0.65));
            gc.setLineWidth(4.5);
            gc.strokeLine(c, c, ex, ey);
            gc.setStroke(Color.rgb(27, 30, 35));
            gc.setLineWidth(2.5);
            gc.strokeLine(c, c, ex, ey);
            gc.setFill(Color.rgb(27, 30, 35));
            gc.fillOval(ex - 5, ey - 5, 10, 10);
        }
    }

    private double angleAt(double x, double y) {
        double c = WHEEL / 2.0;
        return normalize(Math.toDegrees(Math.atan2(y - c, x - c)) + 90);
    }

    private int nearestSpoke(double angle) {
        double[] angles = WheelColors.spokeAngles(Math.max(1, channels.size()), baseAngle);
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < angles.length; i++) {
            double d = Math.abs(angles[i] - angle);
            d = d > 180 ? 360 - d : d;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void rotateTo(double x, double y) {
        double spacing = 360.0 / Math.max(1, channels.size());
        baseAngle = normalize(angleAt(x, y) - dragIndex * spacing);
        preview();
    }

    private static double normalize(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    private static Color fx(int rgb) {
        return Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }

    /**
     * Write the channels' current colours to the image metadata, as Brightness/Contrast
     * does, so scripts and exports see them.
     */
    private void commit() {
        if (imageData == null || channels.isEmpty()) {
            return;
        }
        Map<Integer, Integer> colors = new java.util.HashMap<>();
        for (var ch : channels) {
            colors.put(ch.getChannel(), ch.getColor());
        }
        writeMetadata(colors);
    }

    private void writeMetadata(Map<Integer, Integer> colors) {
        var metadata = imageData.getServer().getMetadata();
        var list = new ArrayList<>(metadata.getChannels());
        boolean changed = false;
        for (var e : colors.entrySet()) {
            int index = e.getKey();
            if (e.getValue() != null && index < list.size()
                    && !e.getValue().equals(list.get(index).getColor())) {
                list.set(index, ImageChannel.getInstance(list.get(index).getName(), e.getValue()));
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        writing = true;
        try {
            imageData.updateServerMetadata(new ImageServerMetadata.Builder(metadata).channels(list).build());
            if (display != null) {
                display.saveChannelColorProperties();
            }
        } finally {
            writing = false;
        }
        // Updating the metadata can replace the display's channel objects
        javafx.application.Platform.runLater(this::rebindKeepingColors);
    }

    /** Rebind without recolouring from the wheel, so the committed colours stay. */
    private void rebindKeepingColors() {
        unbindDisplay();
        var viewer = qupath.getViewer();
        display = viewer == null ? null : viewer.getImageDisplay();
        if (display == null) {
            return;
        }
        display.selectedChannels().addListener(selectionListener);
        List<DirectServerChannelInfo> list = new ArrayList<>();
        for (var info : display.availableChannels()) {
            if (info instanceof DirectServerChannelInfo d && display.selectedChannels().contains(info)) {
                list.add(d);
            }
        }
        channels = list;
    }

    private void revert() {
        if (imageData == null || original.isEmpty()) {
            return;
        }
        if (display != null) {
            for (var info : display.availableChannels()) {
                if (info instanceof DirectServerChannelInfo d && original.containsKey(d.getChannel())) {
                    setColor(d, original.get(d.getChannel()));
                }
            }
            display.saveChannelColorProperties();
        }
        writeMetadata(new java.util.HashMap<>(original));
        rowsPane.getChildren().clear();
        status.setText("Restored the original colours. Drag a spoke to recolour.");
        logger.info("Colour wheel reverted {} channel colours", original.size());
    }

    private void copyScript() {
        if (channels.isEmpty() || imageData == null) {
            return;
        }
        int[] colors = colors();
        int nAll = imageData.getServer().nChannels();
        String[] args = new String[nAll];
        java.util.Arrays.fill(args, "null");
        var sb = new StringBuilder();
        sb.append("// Channel colours from the Channel Names Viewer colour wheel (")
                .append(wheel.mode().name()).append(String.format(", lightness %.2f", wheel.lightness()))
                .append(")\n// null leaves a channel's colour unchanged\n");
        for (int i = 0; i < channels.size(); i++) {
            int rgb = colors[i];
            int index = channels.get(i).getChannel();
            if (index < nAll) {
                args[index] = String.format("ColorTools.packRGB(%d, %d, %d)",
                        (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
                sb.append("// ").append(index + 1).append(": ").append(channels.get(i).getName())
                        .append(String.format("  #%06x", rgb)).append('\n');
            }
        }
        sb.append("setChannelColors(").append(String.join(", ", args)).append(")\n\n");
        sb.append("import qupath.lib.common.ColorTools\n");
        var content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        status.setText("Script copied to the clipboard.");
    }
}
