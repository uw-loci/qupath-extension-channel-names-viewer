package qupath.ext.channelnamesviewer.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
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
 * <p>v1.0.4 paints the rgba background on the root {@link StackPane} (rather
 * than the inner content) so the entire rounded window surface is translucent.
 * Edge/corner resize is detected on the scene with an 8 px hot-zone, removing
 * the v1.0.3 corner grip. The opacity slider, lock-font checkbox, and reset
 * sit in a right-click context menu.</p>
 *
 * <p>Key invariants enforced here:</p>
 * <ul>
 *   <li>Stage style: {@link StageStyle#TRANSPARENT}; scene fill TRANSPARENT.</li>
 *   <li>Background: rounded {@code rgba(0, 0, 0, opacity)} painted on the root
 *       StackPane (overrides modena's opaque {@code .root}); persisted via
 *       {@link ChannelNamesViewerPreferences#backgroundOpacityProperty()}.</li>
 *   <li>Font size = either the locked value or
 *       {@code clamp((height - VERTICAL_OVERHEAD) / rowCount * ROW_HEIGHT_FACTOR, 10, 72)} pt.</li>
 *   <li>Min size 120 x 60 px.</li>
 *   <li>White-fallback rule: WCAG AA luminance contrast against the dark fill;
 *       channels that fall below render as white text.</li>
 *   <li>Esc / double-click closes; primary-press-and-drag on body moves;
 *       primary-press-and-drag within {@link #EDGE_RESIZE_HOTSPOT_PX} of any
 *       edge/corner resizes.</li>
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
     * TRANSPARENT stage has no native chrome, so overhead is just our own padding.
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
    /** How close (in px) to an edge before the cursor turns into a resize affordance. */
    private static final double EDGE_RESIZE_HOTSPOT_PX = 8.0;

    /** Empty-state text color (calm light gray, distinct from any channel color). */
    private static final Color EMPTY_STATE_COLOR = Color.rgb(180, 180, 180);

    /** Window body tooltip. */
    private static final String BODY_TOOLTIP =
            "Drag to move. Drag edges to resize. Double-click to close. Right-click for settings.";

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
    /** Root layout: paints the rounded translucent background; holds content. */
    private final StackPane root;
    /** Container for channel labels (or empty-state labels). Always transparent. */
    private final VBox content;
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
    /** Resize-from-edge start values. */
    private double resizeStartScreenX = 0;
    private double resizeStartScreenY = 0;
    private double resizeStartWidth = 0;
    private double resizeStartHeight = 0;
    private double resizeStartStageX = 0;
    private double resizeStartStageY = 0;
    /** Edge currently used for active resize, or NONE while moving / idle. */
    private ResizeEdge resizingFrom = ResizeEdge.NONE;

    public enum EmptyCause { RGB, NO_IMAGE, NO_SELECTION }

    private enum ResizeEdge {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

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

        // Channel-list container (transparent; root paints the background).
        this.content = new VBox();
        this.content.setAlignment(Pos.TOP_LEFT);
        this.content.setPadding(new Insets(12, 14, 12, 14));
        this.content.setStyle("-fx-background-color: transparent;");
        this.content.setMouseTransparent(false);

        // StackPane root paints the rounded translucent background.
        this.root = new StackPane();
        this.root.getChildren().add(this.content);
        StackPane.setAlignment(this.content, Pos.TOP_LEFT);
        Tooltip.install(this.root, new Tooltip(BODY_TOOLTIP));

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
        // Inline style on root overrides modena's opaque .root background-color.
        this.root.setStyle(String.format(
                "-fx-background-color: rgba(0, 0, 0, %.3f); -fx-background-radius: %d;"
                        + " -fx-background-insets: 0;",
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
            label.setMouseTransparent(true); // clicks/drags fall through to scene handlers
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
        headline.setMouseTransparent(true);
        headline.fontProperty().bind(Bindings.createObjectBinding(
                () -> Font.font(clampedSize.get()),
                clampedSize
        ));

        Label subtitle = new Label(subtitleFor(cause));
        subtitle.setTextFill(EMPTY_STATE_COLOR);
        subtitle.setWrapText(true);
        subtitle.setMouseTransparent(true);
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
    // Key + mouse handlers
    //
    // All mouse logic lives on the scene: edge detection picks resize over
    // move, double-click closes, right-click shows the settings menu. Labels
    // inside `content` are mouse-transparent so events always reach here.
    // ----------------------------------------------------------------------

    private void installKeyAndMouseHandlers() {
        // Esc closes (filter so the stage gets it before any focused control consumes it).
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });

        // Hover feedback: show resize cursor when within EDGE_RESIZE_HOTSPOT_PX of any edge.
        scene.setOnMouseMoved(e -> {
            if (resizingFrom != ResizeEdge.NONE) return;
            scene.setCursor(cursorFor(detectEdge(e.getSceneX(), e.getSceneY())));
        });
        scene.setOnMouseExited(e -> {
            if (resizingFrom == ResizeEdge.NONE) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });

        scene.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            // Double-click anywhere closes the window.
            if (e.getClickCount() == 2) {
                hide();
                e.consume();
                return;
            }
            ResizeEdge edge = detectEdge(e.getSceneX(), e.getSceneY());
            if (edge != ResizeEdge.NONE) {
                resizingFrom = edge;
                resizeStartScreenX = e.getScreenX();
                resizeStartScreenY = e.getScreenY();
                resizeStartWidth = stage.getWidth();
                resizeStartHeight = stage.getHeight();
                resizeStartStageX = stage.getX();
                resizeStartStageY = stage.getY();
                scene.setCursor(cursorFor(edge));
            } else {
                resizingFrom = ResizeEdge.NONE;
                dragOffsetX = stage.getX() - e.getScreenX();
                dragOffsetY = stage.getY() - e.getScreenY();
            }
            e.consume();
        });

        scene.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (resizingFrom != ResizeEdge.NONE) {
                applyResize(e.getScreenX(), e.getScreenY());
            } else {
                stage.setX(e.getScreenX() + dragOffsetX);
                stage.setY(e.getScreenY() + dragOffsetY);
            }
            e.consume();
        });

        scene.setOnMouseReleased(e -> {
            if (resizingFrom != ResizeEdge.NONE) {
                resizingFrom = ResizeEdge.NONE;
                scene.setCursor(cursorFor(detectEdge(e.getSceneX(), e.getSceneY())));
            }
        });

        // Right-click anywhere on the window opens the settings menu.
        scene.setOnContextMenuRequested(e -> {
            ContextMenu menu = buildSettingsMenu();
            menu.show(root, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private ResizeEdge detectEdge(double sceneX, double sceneY) {
        double w = scene.getWidth();
        double h = scene.getHeight();
        boolean onLeft = sceneX <= EDGE_RESIZE_HOTSPOT_PX;
        boolean onRight = sceneX >= w - EDGE_RESIZE_HOTSPOT_PX;
        boolean onTop = sceneY <= EDGE_RESIZE_HOTSPOT_PX;
        boolean onBottom = sceneY >= h - EDGE_RESIZE_HOTSPOT_PX;
        if (onTop && onLeft) return ResizeEdge.NW;
        if (onTop && onRight) return ResizeEdge.NE;
        if (onBottom && onLeft) return ResizeEdge.SW;
        if (onBottom && onRight) return ResizeEdge.SE;
        if (onTop) return ResizeEdge.N;
        if (onBottom) return ResizeEdge.S;
        if (onLeft) return ResizeEdge.W;
        if (onRight) return ResizeEdge.E;
        return ResizeEdge.NONE;
    }

    private static Cursor cursorFor(ResizeEdge edge) {
        switch (edge) {
            case N:  return Cursor.N_RESIZE;
            case S:  return Cursor.S_RESIZE;
            case E:  return Cursor.E_RESIZE;
            case W:  return Cursor.W_RESIZE;
            case NE: return Cursor.NE_RESIZE;
            case NW: return Cursor.NW_RESIZE;
            case SE: return Cursor.SE_RESIZE;
            case SW: return Cursor.SW_RESIZE;
            default: return Cursor.DEFAULT;
        }
    }

    private void applyResize(double screenX, double screenY) {
        double dx = screenX - resizeStartScreenX;
        double dy = screenY - resizeStartScreenY;
        double newW = resizeStartWidth;
        double newH = resizeStartHeight;
        double newX = resizeStartStageX;
        double newY = resizeStartStageY;
        switch (resizingFrom) {
            case N:
                newH = resizeStartHeight - dy;
                newY = resizeStartStageY + dy;
                break;
            case S:
                newH = resizeStartHeight + dy;
                break;
            case E:
                newW = resizeStartWidth + dx;
                break;
            case W:
                newW = resizeStartWidth - dx;
                newX = resizeStartStageX + dx;
                break;
            case NE:
                newW = resizeStartWidth + dx;
                newH = resizeStartHeight - dy;
                newY = resizeStartStageY + dy;
                break;
            case NW:
                newW = resizeStartWidth - dx;
                newX = resizeStartStageX + dx;
                newH = resizeStartHeight - dy;
                newY = resizeStartStageY + dy;
                break;
            case SE:
                newW = resizeStartWidth + dx;
                newH = resizeStartHeight + dy;
                break;
            case SW:
                newW = resizeStartWidth - dx;
                newX = resizeStartStageX + dx;
                newH = resizeStartHeight + dy;
                break;
            default:
                return;
        }
        // Clamp to MIN_WIDTH/HEIGHT. If a left/top-anchored resize hits the floor,
        // pin the stage origin so the right/bottom edge stays under the cursor.
        if (newW < MIN_WIDTH) {
            if (resizingFrom == ResizeEdge.W
                    || resizingFrom == ResizeEdge.NW
                    || resizingFrom == ResizeEdge.SW) {
                newX = resizeStartStageX + (resizeStartWidth - MIN_WIDTH);
            }
            newW = MIN_WIDTH;
        }
        if (newH < MIN_HEIGHT) {
            if (resizingFrom == ResizeEdge.N
                    || resizingFrom == ResizeEdge.NE
                    || resizingFrom == ResizeEdge.NW) {
                newY = resizeStartStageY + (resizeStartHeight - MIN_HEIGHT);
            }
            newH = MIN_HEIGHT;
        }
        stage.setX(newX);
        stage.setY(newY);
        stage.setWidth(newW);
        stage.setHeight(newH);
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

    /**
     * Retained for source compatibility with the v1.0.3 extension wiring.
     * The right-click handler is now installed unconditionally in the
     * constructor on the scene, so this method is a no-op.
     */
    public void installContextMenuOnBody() {
        // No-op: scene-level handler installed in installKeyAndMouseHandlers().
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
