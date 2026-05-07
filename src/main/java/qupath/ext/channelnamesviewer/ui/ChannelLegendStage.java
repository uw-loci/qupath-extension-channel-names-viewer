package qupath.ext.channelnamesviewer.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.preferences.ChannelNamesViewerPreferences;
import qupath.lib.gui.prefs.PathPrefs;

import java.util.Collections;
import java.util.List;

/**
 * The Channel Names Viewer legend window.
 *
 * <p>Small undecorated transparent floating tool window that lists the
 * currently-selected fluorescence channels in their display colors. Modeled on
 * Sara McArdle's {@code FluorescentChannelNames.groovy} (originally Pete
 * Bankhead's 2022 QuPath Hackathon script): {@link StageStyle#TRANSPARENT}
 * stage, semi-transparent dark fill, rounded corners, drag-anywhere to move,
 * double-click to close.</p>
 *
 * <p>v1.0.3 reverted from the v1.0.0-1.0.2 {@code StageStyle.UTILITY} after
 * user feedback that the chrome-laden look diverged too far from the original
 * aesthetic. The trade is real: TRANSPARENT means we own the drag handler and
 * resize grip ourselves (no native title bar / resize chrome), and certain
 * Linux compositors render TRANSPARENT poorly. UI configuration -- background
 * opacity, lock font size -- now lives in a right-click context menu rather
 * than a bottom controls bar.</p>
 *
 * <p>Design source for v1.0.0-1.0.2: {@code agent-reports/extension-team/channel-names-viewer/02_design.md}.
 * v1.0.3 ships against user-driven iteration rather than a re-spun design phase.</p>
 *
 * <p>Key invariants enforced here:</p>
 * <ul>
 *   <li>Stage style: {@link StageStyle#TRANSPARENT}.</li>
 *   <li>Background: rounded {@code rgba(0, 0, 0, opacity)} where opacity is
 *       persisted via {@link ChannelNamesViewerPreferences#backgroundOpacityProperty()}.</li>
 *   <li>Font size = either the locked value or
 *       {@code clamp((height - VERTICAL_OVERHEAD) / rowCount * ROW_HEIGHT_FACTOR, 10, 72)} pt.</li>
 *   <li>Min size 120 x 60 px.</li>
 *   <li>White-fallback rule: WCAG AA luminance contrast against the dark fill;
 *       channels that fall below render as white text.</li>
 *   <li>Esc / double-click closes; primary-press-and-drag on body moves;
 *       primary-press-and-drag on the corner grip resizes.</li>
 *   <li>Position/size/lock-state/locked-pt/opacity persistence on WINDOW_HIDDEN.</li>
 * </ul>
 */
public class ChannelLegendStage {

    private static final Logger logger = LoggerFactory.getLogger(ChannelLegendStage.class);

    // ---- Design constants ----

    /** Floor for the dynamic font-size binding (pt). */
    private static final double MIN_FONT_PT = 10.0;
    /** Ceiling for the dynamic font-size binding (pt). */
    private static final double MAX_FONT_PT = 72.0;
    /** Per-row height multiplier on font size; 0.7 leaves breathing room between rows. */
    private static final double ROW_HEIGHT_FACTOR = 0.7;
    /**
     * Vertical overhead px subtracted from stage height before computing per-row space.
     * v1.0.3: TRANSPARENT stage has no native chrome, so overhead is just our own padding
     * plus a tiny slack for the resize grip.
     */
    private static final double VERTICAL_OVERHEAD_PX = 30.0;
    public static final double MIN_WIDTH = 120.0;
    public static final double MIN_HEIGHT = 60.0;

    /** WCAG AA contrast ratio for normal text (4.5:1). */
    private static final double WCAG_AA_NORMAL = 4.5;

    /** Background base color (opacity is mixed in via CSS). */
    private static final Color BACKGROUND_BASE = Color.BLACK;

    /** Subtitle is one font-size step smaller than headline. */
    private static final double SUBTITLE_SCALE = 0.65;
    /** Floor for the empty-state subtitle font (pt). */
    private static final double MIN_SUBTITLE_PT = 9.0;
    /** Min and max for the opacity slider. */
    private static final double MIN_OPACITY = 0.05;
    private static final double MAX_OPACITY = 1.0;
    /** Original-Groovy default opacity. */
    private static final double DEFAULT_OPACITY = 0.75;
    /** Background-pane corner radius in px (matches the original Groovy script's `-fx-background-radius: 10`). */
    private static final int CORNER_RADIUS_PX = 10;

    /** Empty-state text color (calm light gray, distinct from any channel color). */
    private static final Color EMPTY_STATE_COLOR = Color.rgb(180, 180, 180);

    /** Window body tooltip. v1.0.3: drag-anywhere to move (no title bar). */
    private static final String BODY_TOOLTIP =
            "Drag to move. Double-click to close. Right-click for settings.";

    // ---- Empty-state subtitles ----
    private static final String EMPTY_HEADLINE = "No fluorescence channels";
    private static final String EMPTY_SUBTITLE_RGB =
            "(this image is RGB; channels do not apply)";
    private static final String EMPTY_SUBTITLE_NO_IMAGE =
            "(open an image to see its channels)";
    private static final String EMPTY_SUBTITLE_NO_SELECTION =
            "(open Brightness/Contrast and select channels)";

    // ---- Stage / scene plumbing ----

    private final Stage stage;
    /** Root layout: content centered, resize grip overlaid in bottom-right. */
    private final StackPane root;
    /** Container for channel labels (or empty-state labels). */
    private final VBox content;
    /** Bottom-right corner grip used to resize the stage. */
    private final Region resizeGrip;
    private final Scene scene;

    /** Number of rows currently rendered. Drives font binding. */
    private final IntegerProperty rowCount = new SimpleIntegerProperty(1);
    /** Whether the font size is locked at {@link #lockedFontPt}. */
    private final BooleanProperty fontLocked = new SimpleBooleanProperty(false);
    /** The locked font size (pt). Captured at lock time; persisted across sessions. */
    private final DoubleProperty lockedFontPt = new SimpleDoubleProperty(20.0);
    /** Background opacity (0.05-1.0); 0.75 default matches the original Groovy script. */
    private final DoubleProperty backgroundOpacity = new SimpleDoubleProperty(DEFAULT_OPACITY);
    /** Dynamic font size derived from height + rowCount, clamped 10-72 pt. */
    private final DoubleBinding dynamicSize;
    /** Effective font size: lockedFontPt when locked, else dynamicSize. */
    private final DoubleBinding clampedSize;

    /** The most recently rendered channel rows (used by {@link #applyDefaultGeometryIfUnlocked()}). */
    private List<ChannelRow> lastRows = Collections.emptyList();
    /** Whether the most recent render was the empty-state (vs. a list of channels). */
    private boolean lastWasEmpty = false;
    /** Set true after the first {@link #show()} call. */
    private boolean firstShowApplied = false;

    /** Drag-to-move offset accumulator. */
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;
    /** Resize-from-grip start values. */
    private double resizeStartScreenX = 0;
    private double resizeStartScreenY = 0;
    private double resizeStartWidth = 0;
    private double resizeStartHeight = 0;

    public enum EmptyCause { RGB, NO_IMAGE, NO_SELECTION }

    public ChannelLegendStage(Stage owner) {
        this.stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) {
            this.stage.initOwner(owner);
        }
        this.stage.setMinWidth(MIN_WIDTH);
        this.stage.setMinHeight(MIN_HEIGHT);
        this.stage.setAlwaysOnTop(false);

        // Restore lock-state and opacity from prefs.
        try {
            this.fontLocked.set(ChannelNamesViewerPreferences.getFontLocked());
            this.lockedFontPt.set(ChannelNamesViewerPreferences.getLockedFontPt());
            this.backgroundOpacity.set(clampOpacity(ChannelNamesViewerPreferences.getBackgroundOpacity()));
        } catch (Exception ex) {
            logger.warn("Failed to read prefs; using defaults: {}", ex.getMessage());
        }

        // Channel-list container.
        this.content = new VBox();
        this.content.setAlignment(Pos.TOP_LEFT);
        this.content.setPadding(new Insets(12, 18, 12, 12)); // extra right padding for the grip
        Tooltip.install(this.content, new Tooltip(BODY_TOOLTIP));

        // Resize grip in the bottom-right corner.
        this.resizeGrip = new Region();
        this.resizeGrip.setPrefSize(14, 14);
        this.resizeGrip.setMinSize(14, 14);
        this.resizeGrip.setMaxSize(14, 14);
        this.resizeGrip.setCursor(Cursor.SE_RESIZE);
        this.resizeGrip.setStyle(
                "-fx-background-color: rgba(180, 180, 180, 0.55);"
                        + " -fx-background-radius: 0 0 " + CORNER_RADIUS_PX + " 0;");

        // StackPane holds the rounded background, the content, and the grip overlay.
        this.root = new StackPane();
        this.root.getChildren().addAll(this.content, this.resizeGrip);
        StackPane.setAlignment(this.content, Pos.TOP_LEFT);
        StackPane.setAlignment(this.resizeGrip, Pos.BOTTOM_RIGHT);

        applyBackgroundCss();
        // Re-apply CSS whenever opacity changes so the user sees the slider live.
        this.backgroundOpacity.addListener((obs, oldVal, newVal) -> applyBackgroundCss());

        this.scene = new Scene(this.root);
        this.scene.setFill(Color.TRANSPARENT);
        this.stage.setScene(this.scene);

        // Dynamic font: scale per-row height with stage height divided by rowCount.
        this.dynamicSize = Bindings.createDoubleBinding(
                () -> {
                    double avail = this.stage.getHeight() - VERTICAL_OVERHEAD_PX;
                    if (avail <= 0) return MIN_FONT_PT;
                    int rows = Math.max(rowCount.get(), 1);
                    double raw = (avail / rows) * ROW_HEIGHT_FACTOR;
                    return Math.max(MIN_FONT_PT, Math.min(MAX_FONT_PT, raw));
                },
                this.stage.heightProperty(),
                rowCount
        );
        // Effective font: locked value or dynamic.
        this.clampedSize = Bindings.createDoubleBinding(
                () -> fontLocked.get() ? lockedFontPt.get() : dynamicSize.get(),
                fontLocked, lockedFontPt, dynamicSize
        );

        installKeyAndMouseHandlers();
        installPersistenceHooks();

        logger.debug("ChannelLegendStage constructed (TRANSPARENT, MIN_WIDTH={}, MIN_HEIGHT={}, opacity={})",
                MIN_WIDTH, MIN_HEIGHT, this.backgroundOpacity.get());
    }

    private static double clampOpacity(double v) {
        if (Double.isNaN(v) || v < MIN_OPACITY) return MIN_OPACITY;
        if (v > MAX_OPACITY) return MAX_OPACITY;
        return v;
    }

    private void applyBackgroundCss() {
        double op = backgroundOpacity.get();
        // Background lives on `content` so the rounded corners + opacity sit behind the labels.
        this.content.setStyle(String.format(
                "-fx-background-color: rgba(0, 0, 0, %.3f); -fx-background-radius: %d;",
                op, CORNER_RADIUS_PX));
    }

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    public void show() {
        if (!firstShowApplied) {
            applyFirstShowGeometry();
            firstShowApplied = true;
        }
        stage.show();
        stage.toFront();
    }

    public void hide() {
        stage.hide();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public Stage getStage() {
        return stage;
    }

    // ----------------------------------------------------------------------
    // Content rendering
    // ----------------------------------------------------------------------

    public void renderChannels(List<ChannelRow> rows) {
        content.getChildren().clear();
        if (rows == null || rows.isEmpty()) {
            renderEmptyState(EmptyCause.NO_SELECTION);
            return;
        }
        rowCount.set(rows.size());
        lastRows = List.copyOf(rows);
        lastWasEmpty = false;
        for (ChannelRow row : rows) {
            Label label = new Label(row.name());
            label.setTextFill(textColorFor(row.color()));
            label.fontProperty().bind(Bindings.createObjectBinding(
                    () -> Font.font(clampedSize.get()),
                    clampedSize
            ));
            content.getChildren().add(label);
        }
    }

    public void renderEmptyState(EmptyCause cause) {
        content.getChildren().clear();
        rowCount.set(2);
        lastRows = Collections.emptyList();
        lastWasEmpty = true;

        Label headline = new Label(EMPTY_HEADLINE);
        headline.setTextFill(EMPTY_STATE_COLOR);
        headline.fontProperty().bind(Bindings.createObjectBinding(
                () -> Font.font(clampedSize.get()),
                clampedSize
        ));

        Label subtitle = new Label(subtitleFor(cause));
        subtitle.setTextFill(EMPTY_STATE_COLOR);
        subtitle.setWrapText(true);
        subtitle.fontProperty().bind(Bindings.createObjectBinding(
                () -> Font.font(Math.max(MIN_SUBTITLE_PT, clampedSize.get() * SUBTITLE_SCALE)),
                clampedSize
        ));

        content.getChildren().addAll(headline, subtitle);
    }

    private static String subtitleFor(EmptyCause cause) {
        switch (cause) {
            case RGB: return EMPTY_SUBTITLE_RGB;
            case NO_IMAGE: return EMPTY_SUBTITLE_NO_IMAGE;
            case NO_SELECTION:
            default: return EMPTY_SUBTITLE_NO_SELECTION;
        }
    }

    // ----------------------------------------------------------------------
    // Color contrast logic
    // ----------------------------------------------------------------------

    public static Color textColorFor(Color channelColor) {
        if (contrastRatio(channelColor, BACKGROUND_BASE) < WCAG_AA_NORMAL) {
            return Color.WHITE;
        }
        return channelColor;
    }

    static double contrastRatio(Color a, Color b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(Color c) {
        return 0.2126 * srgbToLinear(c.getRed())
                + 0.7152 * srgbToLinear(c.getGreen())
                + 0.0722 * srgbToLinear(c.getBlue());
    }

    private static double srgbToLinear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    // ----------------------------------------------------------------------
    // Key + mouse handlers (drag-to-move on body, drag-to-resize on grip)
    // ----------------------------------------------------------------------

    private void installKeyAndMouseHandlers() {
        // Esc closes.
        EventHandler<KeyEvent> escFilter = e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, escFilter);

        // Double-click anywhere on the body closes the window.
        content.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                hide();
                e.consume();
            }
        });

        // Drag-to-move: primary press on body captures the offset; drag updates stage X/Y.
        // Modeled on the original Groovy script's MoveablePaneHandler.
        content.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragOffsetX = stage.getX() - e.getScreenX();
            dragOffsetY = stage.getY() - e.getScreenY();
        });
        content.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            stage.setX(e.getScreenX() + dragOffsetX);
            stage.setY(e.getScreenY() + dragOffsetY);
        });

        // Drag-to-resize: primary press on the corner grip captures stage size + screen pos.
        resizeGrip.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            resizeStartScreenX = e.getScreenX();
            resizeStartScreenY = e.getScreenY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
            e.consume();
        });
        resizeGrip.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            double newWidth = resizeStartWidth + (e.getScreenX() - resizeStartScreenX);
            double newHeight = resizeStartHeight + (e.getScreenY() - resizeStartScreenY);
            stage.setWidth(Math.max(MIN_WIDTH, newWidth));
            stage.setHeight(Math.max(MIN_HEIGHT, newHeight));
            e.consume();
        });
    }

    // ----------------------------------------------------------------------
    // Position/size/lock/opacity persistence
    // ----------------------------------------------------------------------

    private void installPersistenceHooks() {
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> savePosition());
    }

    private void savePosition() {
        try {
            ChannelNamesViewerPreferences.setWindowX(stage.getX());
            ChannelNamesViewerPreferences.setWindowY(stage.getY());
            ChannelNamesViewerPreferences.setWindowWidth(stage.getWidth());
            ChannelNamesViewerPreferences.setWindowHeight(stage.getHeight());
            ChannelNamesViewerPreferences.setFontLocked(fontLocked.get());
            ChannelNamesViewerPreferences.setLockedFontPt(lockedFontPt.get());
            ChannelNamesViewerPreferences.setBackgroundOpacity(backgroundOpacity.get());
            logger.debug("Saved settings: x={}, y={}, w={}, h={}, fontLocked={}, lockedPt={}, opacity={}",
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                    fontLocked.get(), lockedFontPt.get(), backgroundOpacity.get());
        } catch (Exception ex) {
            logger.warn("Failed to save window settings: {}", ex.getMessage());
        }
    }

    private void applyFirstShowGeometry() {
        if (!fontLocked.get()) {
            logger.debug("Font is unlocked; skipping prefs-based geometry restore");
            return;
        }
        double savedX, savedY, savedW, savedH;
        try {
            savedX = ChannelNamesViewerPreferences.getWindowX();
            savedY = ChannelNamesViewerPreferences.getWindowY();
            savedW = ChannelNamesViewerPreferences.getWindowWidth();
            savedH = ChannelNamesViewerPreferences.getWindowHeight();
        } catch (Exception ex) {
            logger.warn("Failed to read window position prefs: {}", ex.getMessage());
            return;
        }
        if (savedX < 0 || savedY < 0 || savedW <= 0 || savedH <= 0) {
            return;
        }
        Rectangle2D savedBounds = new Rectangle2D(savedX, savedY, savedW, savedH);
        boolean visible = false;
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D vb = screen.getVisualBounds();
            if (vb.contains(savedX, savedY) || vb.intersects(savedBounds)) {
                visible = true;
                break;
            }
        }
        if (!visible) {
            return;
        }
        Rectangle2D primary = Screen.getPrimary().getVisualBounds();
        double w = Math.max(MIN_WIDTH, Math.min(savedW, primary.getWidth() - 100));
        double h = Math.max(MIN_HEIGHT, Math.min(savedH, primary.getHeight() - 100));

        stage.setX(savedX);
        stage.setY(savedY);
        stage.setWidth(w);
        stage.setHeight(h);
    }

    // ----------------------------------------------------------------------
    // Default geometry on unlocked open
    // ----------------------------------------------------------------------

    public void applyDefaultGeometryIfUnlocked() {
        if (fontLocked.get()) {
            return;
        }
        double pt = defaultFontPt();
        int n;
        double maxTextWidth;
        if (lastWasEmpty) {
            n = 2;
            maxTextWidth = measureTextWidth(EMPTY_HEADLINE, pt);
            double subtitlePt = Math.max(MIN_SUBTITLE_PT, pt * SUBTITLE_SCALE);
            for (String subtitle : List.of(
                    EMPTY_SUBTITLE_RGB, EMPTY_SUBTITLE_NO_IMAGE, EMPTY_SUBTITLE_NO_SELECTION)) {
                maxTextWidth = Math.max(maxTextWidth, measureTextWidth(subtitle, subtitlePt));
            }
        } else {
            n = Math.max(lastRows.size(), 1);
            maxTextWidth = 0;
            for (ChannelRow row : lastRows) {
                maxTextWidth = Math.max(maxTextWidth, measureTextWidth(row.name(), pt));
            }
        }
        double targetWidth = maxTextWidth + 48;
        double targetHeight = (pt / ROW_HEIGHT_FACTOR) * n + VERTICAL_OVERHEAD_PX;

        Rectangle2D primary = Screen.getPrimary().getVisualBounds();
        targetWidth = Math.max(MIN_WIDTH, Math.min(targetWidth, primary.getWidth() - 100));
        targetHeight = Math.max(MIN_HEIGHT, Math.min(targetHeight, primary.getHeight() - 100));

        stage.setWidth(targetWidth);
        stage.setHeight(targetHeight);
        logger.debug("Applied default geometry (unlocked): pt={}, rows={}, w={}, h={}",
                pt, n, targetWidth, targetHeight);
    }

    private static double defaultFontPt() {
        try {
            switch (PathPrefs.locationFontSizeProperty().get()) {
                case TINY: return 10.0;
                case SMALL: return 12.0;
                case MEDIUM: return 14.0;
                case LARGE: return 18.0;
                case HUGE: return 24.0;
                default: return 14.0;
            }
        } catch (Exception ex) {
            return 14.0;
        }
    }

    private static double measureTextWidth(String text, double pt) {
        Text measure = new Text(text);
        measure.setFont(Font.font(pt));
        return measure.getLayoutBounds().getWidth();
    }

    // ----------------------------------------------------------------------
    // Settings menu (right-click context menu)
    // ----------------------------------------------------------------------

    /**
     * Build a fresh settings menu wired to this stage's properties. Each call
     * returns a new {@link ContextMenu} instance so callers can attach the same
     * menu to multiple targets (toolbar button + window body) without sharing
     * a single instance.
     */
    public ContextMenu buildSettingsMenu() {
        ContextMenu menu = new ContextMenu();

        // --- Background opacity slider (CustomMenuItem so the slider stays in the menu) ---
        Slider opacitySlider = new Slider(MIN_OPACITY, MAX_OPACITY, backgroundOpacity.get());
        opacitySlider.setPrefWidth(180);
        opacitySlider.setShowTickLabels(false);
        opacitySlider.setShowTickMarks(false);
        opacitySlider.valueProperty().addListener((obs, oldVal, newVal) ->
                backgroundOpacity.set(clampOpacity(newVal.doubleValue())));
        // Keep slider in sync if opacity changes elsewhere (e.g. another menu instance).
        backgroundOpacity.addListener((obs, oldVal, newVal) -> {
            double v = newVal.doubleValue();
            if (Math.abs(opacitySlider.getValue() - v) > 1e-4) {
                opacitySlider.setValue(v);
            }
        });
        Label sliderLabel = new Label("Background opacity");
        VBox sliderBox = new VBox(2, sliderLabel, opacitySlider);
        sliderBox.setPadding(new Insets(4, 8, 4, 8));
        CustomMenuItem opacityItem = new CustomMenuItem(sliderBox, false);
        opacityItem.setHideOnClick(false);
        menu.getItems().add(opacityItem);

        menu.getItems().add(new SeparatorMenuItem());

        // --- Lock font size ---
        CheckMenuItem lockItem = new CheckMenuItem("Lock font size");
        lockItem.setSelected(fontLocked.get());
        lockItem.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal && !fontLocked.get()) {
                lockedFontPt.set(dynamicSize.get());
            }
            fontLocked.set(newVal);
        });
        // Keep menu state in sync if changed elsewhere.
        fontLocked.addListener((obs, oldVal, newVal) -> lockItem.setSelected(newVal));
        menu.getItems().add(lockItem);

        menu.getItems().add(new SeparatorMenuItem());

        // --- Reset opacity ---
        MenuItem resetItem = new MenuItem("Reset background opacity");
        resetItem.setOnAction(e -> backgroundOpacity.set(DEFAULT_OPACITY));
        menu.getItems().add(resetItem);

        return menu;
    }

    /** Install a right-click handler on the legend window body that shows the settings menu. */
    public void installContextMenuOnBody() {
        content.setOnContextMenuRequested(e -> {
            ContextMenu menu = buildSettingsMenu();
            menu.show(content, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // ----------------------------------------------------------------------
    // Value type for one rendered row
    // ----------------------------------------------------------------------

    public record ChannelRow(String name, Color color) {
        public ChannelRow {
            if (name == null) {
                throw new IllegalArgumentException("Channel row name cannot be null");
            }
            if (color == null) {
                throw new IllegalArgumentException("Channel row color cannot be null");
            }
        }
    }
}
