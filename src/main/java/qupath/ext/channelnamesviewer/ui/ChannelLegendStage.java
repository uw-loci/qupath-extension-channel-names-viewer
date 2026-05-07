package qupath.ext.channelnamesviewer.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.preferences.ChannelNamesViewerPreferences;

import java.util.List;

/**
 * The Channel Names Viewer legend window.
 *
 * <p>This is a small, always-visible tool window that lists currently-selected
 * fluorescence channels in their display colors. It is non-modal and uses
 * {@link StageStyle#UTILITY} so it gets a thin native title bar (drag-to-move),
 * native resize chrome, and an opaque near-black background.</p>
 *
 * <p>Design source: {@code agent-reports/extension-team/channel-names-viewer/02_design.md}
 * sections 5-9, and {@code 02_design.ui-ux-draft.md} sections 1, 4, 5, 7, 8, 10, 12.</p>
 *
 * <p>Key invariants enforced here:</p>
 * <ul>
 *   <li>Stage style: {@link StageStyle#UTILITY} (NOT TRANSPARENT, NOT DECORATED).</li>
 *   <li>Background: opaque {@code rgb(13, 13, 13)}.</li>
 *   <li>Font size = {@code clamp(min(width, height) / 8, 10, 72)} pt, per-Label.</li>
 *   <li>Min size 120 x 60 px.</li>
 *   <li>White-fallback rule: if a channel color does not clear WCAG AA (4.5:1)
 *       against the rgb(13,13,13) background, fall back to white. (Phase 3
 *       self-refinement found a simple HSB-brightness threshold was insufficient --
 *       pure blues at brightness=1.0 still fail AA -- so the rule uses WCAG
 *       relative luminance directly. See {@code 04_self_refinement.md}.)</li>
 *   <li>Esc / double-click closes; accelerator handled by the controller (toggle).</li>
 *   <li>Position/size persistence via {@link ChannelNamesViewerPreferences} on
 *       WINDOW_HIDDEN, restore on first show with multi-monitor clamp.</li>
 * </ul>
 *
 * <p><b>UI/UX QA pass note (Phase 2):</b> this file is owned by the UI/UX
 * Designer in Phase 2. The Java Developer is welcome to wire it from
 * {@code ChannelLegendController} and {@code ChannelNamesViewerExtension},
 * but font-binding math, color contrast threshold, empty-state copy,
 * and prefs-restore logic should not be edited without re-spinning the
 * design doc.</p>
 */
public class ChannelLegendStage {

    private static final Logger logger = LoggerFactory.getLogger(ChannelLegendStage.class);

    // ---- Design constants ----
    // Per design section 6 (resize-binding spec) + section 7 (color contrast)
    // + section 1 (background).

    /** Floor for the dynamic font-size binding (pt). Below this, channel names start to clip. */
    private static final double MIN_FONT_PT = 10.0;
    /** Ceiling for the dynamic font-size binding (pt). Above this, even on 4K the window fills the screen. */
    private static final double MAX_FONT_PT = 72.0;
    /** Divisor in fontSize = min(width, height) / FONT_DIVISOR. Tuned so a ~150x150 window yields ~18-19 pt. */
    private static final double FONT_DIVISOR = 8.0;
    /** Minimum window width (px). Floor for native UTILITY chrome to prevent dragging to 0x0. */
    public static final double MIN_WIDTH = 120.0;
    /** Minimum window height (px). */
    public static final double MIN_HEIGHT = 60.0;

    /**
     * WCAG AA contrast ratio for normal text. Phase 3 self-refinement (HSB sweep) showed that
     * a simple HSB-brightness threshold (e.g. the originally-proposed 0.4) does NOT guarantee
     * AA against the rgb(13,13,13) background -- pure-blue rgb(0,0,255) has HSB brightness 1.0
     * but only 2.26:1 contrast (B has the lowest WCAG luminance weight, 0.0722). The fix is
     * to use the WCAG relative-luminance contrast ratio directly: if the channel color does
     * not clear AA against the background, fall back to white (which clears AA at ~19:1).
     * <p>See the HSB-sweep results in {@code 04_self_refinement.md}.
     */
    private static final double WCAG_AA_NORMAL = 4.5;

    /** Background color for the legend window. Used both for the scene fill and for contrast checks. */
    private static final Color BACKGROUND_COLOR = Color.rgb(13, 13, 13);

    /** Subtitle is one font-size step smaller than headline. */
    private static final double SUBTITLE_SCALE = 0.65;
    /** Floor for the empty-state subtitle font (pt). At clampedSize=MIN_FONT_PT (10),
     *  raw subtitle would be 6.5 pt -- below comfortable reading. Floor it at 9 pt. */
    private static final double MIN_SUBTITLE_PT = 9.0;

    /** Empty-state text color (calm light gray, distinct from any channel color). */
    private static final Color EMPTY_STATE_COLOR = Color.rgb(180, 180, 180);

    /** Window body tooltip. UTILITY has a native title bar; drag-anywhere body affordance removed. */
    private static final String BODY_TOOLTIP = "Drag title bar to move. Double-click to close.";

    /** Window title (UTILITY shows a title bar). ASCII only. */
    private static final String WINDOW_TITLE = "Channel Names";

    // ---- Empty-state subtitles (per design section 4 / UI/UX draft section 8) ----
    private static final String EMPTY_HEADLINE = "No fluorescence channels";
    private static final String EMPTY_SUBTITLE_RGB =
            "(this image is RGB; channels do not apply)";
    private static final String EMPTY_SUBTITLE_NO_IMAGE =
            "(open an image to see its channels)";
    private static final String EMPTY_SUBTITLE_NO_SELECTION =
            "(open Brightness/Contrast and select channels)";

    // ---- Stage / scene plumbing ----

    private final Stage stage;
    private final VBox content;
    private final Scene scene;

    /** Current dynamic font size (pt). Bound to {@code clamp(min(w,h)/divisor, min, max)}. */
    private final DoubleBinding clampedSize;

    /** Set true after the first {@link #show()} call so we don't re-apply restore-from-prefs every show. */
    private boolean firstShowApplied = false;

    /**
     * Cause for the empty-state rendering. The UI/UX design specifies three
     * subtitle variants depending on why no channel rows are showing.
     */
    public enum EmptyCause {
        /** Image is RGB (no fluorescence channels available). */
        RGB,
        /** No image is open in any viewer. */
        NO_IMAGE,
        /** Image is fluorescence but the user has zero channels selected. */
        NO_SELECTION
    }

    /**
     * Create the stage. Does NOT show it -- caller invokes {@link #show()}.
     *
     * @param owner the QuPath main stage to use as the owner (for window-manager grouping).
     *              May be null; if null, the legend behaves as a top-level non-owned window.
     */
    public ChannelLegendStage(Stage owner) {
        this.stage = new Stage(StageStyle.UTILITY);
        if (owner != null) {
            this.stage.initOwner(owner);
        }
        this.stage.setTitle(WINDOW_TITLE);
        this.stage.setMinWidth(MIN_WIDTH);
        this.stage.setMinHeight(MIN_HEIGHT);
        this.stage.setAlwaysOnTop(false); // Pin-to-top is a v1.1 nice-to-have.

        this.content = new VBox();
        this.content.setAlignment(Pos.CENTER_LEFT);
        this.content.setPadding(new Insets(12, 12, 12, 12));
        this.content.setBackground(new Background(new BackgroundFill(
                BACKGROUND_COLOR, CornerRadii.EMPTY, Insets.EMPTY)));

        this.scene = new Scene(this.content);
        // Match window background even before first content fill, for cleaner first paint.
        this.scene.setFill(BACKGROUND_COLOR);
        this.stage.setScene(this.scene);

        // Body tooltip lives on the root VBox so any hover (even on padding) reveals it.
        Tooltip.install(this.content, new Tooltip(BODY_TOOLTIP));

        // Build the font-size binding once; per-Label fontProperty bindings reference clampedSize.
        // Note: Bindings.min(...).divide(double) returns NumberBinding -- wrap via createDoubleBinding
        // to land in DoubleBinding territory.
        var minDim = Bindings.min(
                this.stage.widthProperty(),
                this.stage.heightProperty()
        );
        DoubleBinding rawSize = Bindings.createDoubleBinding(
                () -> minDim.doubleValue() / FONT_DIVISOR,
                minDim
        );
        this.clampedSize = Bindings.createDoubleBinding(
                () -> Math.max(MIN_FONT_PT, Math.min(MAX_FONT_PT, rawSize.get())),
                rawSize
        );

        installKeyAndMouseHandlers();
        installPersistenceHooks();

        logger.debug("ChannelLegendStage constructed (UTILITY, MIN_WIDTH={}, MIN_HEIGHT={}, WCAG AA normal={})",
                MIN_WIDTH, MIN_HEIGHT, WCAG_AA_NORMAL);
    }

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    /**
     * Show the window. On first show, attempts to restore position/size from prefs;
     * if no saved state (sentinel -1) or saved state is off-screen, applies first-show
     * defaults (centered on owner, sized-to-content).
     */
    public void show() {
        if (!firstShowApplied) {
            applyFirstShowGeometry();
            firstShowApplied = true;
        }
        stage.show();
        stage.toFront();
    }

    /** Hide the window. Position/size are persisted by the {@code setOnHidden} hook. */
    public void hide() {
        stage.hide();
    }

    /** @return true if the stage is currently showing. Used by the controller for accelerator-toggle. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /** @return the underlying JavaFX stage. Exposed so the controller can attach lifecycle listeners. */
    public Stage getStage() {
        return stage;
    }

    // ----------------------------------------------------------------------
    // Content rendering
    // ----------------------------------------------------------------------

    /**
     * Render a list of channel rows. Each entry is (display name, channel display color).
     * Replaces any prior content. Caller is responsible for filtering down to currently-
     * selected channels in the right order.
     *
     * @param rows list of (name, color) pairs in the order they should appear top-to-bottom.
     *             May be empty -- in that case caller should call
     *             {@link #renderEmptyState(EmptyCause)} instead for the proper subtitle.
     */
    public void renderChannels(List<ChannelRow> rows) {
        content.getChildren().clear();
        if (rows == null || rows.isEmpty()) {
            renderEmptyState(EmptyCause.NO_SELECTION);
            return;
        }
        for (ChannelRow row : rows) {
            Label label = new Label(row.name());
            label.setTextFill(textColorFor(row.color()));
            // Per-Label font binding -- font rebuilds on any clampedSize change.
            label.fontProperty().bind(Bindings.createObjectBinding(
                    () -> Font.font(clampedSize.get()),
                    clampedSize
            ));
            content.getChildren().add(label);
        }
    }

    /**
     * Render the empty state. Shows the shared headline plus the cause-specific subtitle.
     *
     * @param cause why the empty state is being shown -- determines subtitle copy.
     */
    public void renderEmptyState(EmptyCause cause) {
        content.getChildren().clear();
        Label headline = new Label(EMPTY_HEADLINE);
        headline.setTextFill(EMPTY_STATE_COLOR);
        headline.fontProperty().bind(Bindings.createObjectBinding(
                () -> Font.font(clampedSize.get()),
                clampedSize
        ));

        Label subtitle = new Label(subtitleFor(cause));
        subtitle.setTextFill(EMPTY_STATE_COLOR);
        subtitle.setWrapText(true);
        // Subtitle is one font-size step smaller -- multiply by SUBTITLE_SCALE,
        // but never below MIN_SUBTITLE_PT to keep the cause-specific guidance legible
        // even when the user has shrunk the window to MIN_WIDTH x MIN_HEIGHT.
        subtitle.fontProperty().bind(Bindings.createObjectBinding(
                () -> Font.font(Math.max(MIN_SUBTITLE_PT, clampedSize.get() * SUBTITLE_SCALE)),
                clampedSize
        ));

        content.getChildren().addAll(headline, subtitle);
    }

    private static String subtitleFor(EmptyCause cause) {
        switch (cause) {
            case RGB:
                return EMPTY_SUBTITLE_RGB;
            case NO_IMAGE:
                return EMPTY_SUBTITLE_NO_IMAGE;
            case NO_SELECTION:
            default:
                return EMPTY_SUBTITLE_NO_SELECTION;
        }
    }

    // ----------------------------------------------------------------------
    // Color contrast logic
    // ----------------------------------------------------------------------

    /**
     * Decide what color to render channel text in, given the channel's display color.
     * <p>If the channel color does not clear WCAG AA (4.5:1) against the legend background,
     * fall back to white (which always clears AA at ~19:1 against rgb(13,13,13)). Otherwise
     * use the channel color directly.
     * <p>Phase 3 brute-force HSB sweep (715 combinations of H 0..360 step 30, S 0..1 step 0.25,
     * V 0..1 step 0.1) confirmed zero AA failures with this rule. The earlier
     * brightness-only rule (0.4 threshold) failed for 180/715 combinations, including
     * pure blues at S=1.0, V=1.0 (HSB brightness=1.0 but luminance only 0.0722).
     */
    public static Color textColorFor(Color channelColor) {
        if (contrastRatio(channelColor, BACKGROUND_COLOR) < WCAG_AA_NORMAL) {
            return Color.WHITE;
        }
        return channelColor;
    }

    /**
     * WCAG 2.x contrast ratio between two JavaFX colors. Returns a value in [1.0, 21.0].
     * Used internally by {@link #textColorFor(Color)}; package-private for unit tests.
     */
    static double contrastRatio(Color a, Color b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** WCAG 2.x relative luminance for an sRGB color in [0, 1]. */
    private static double relativeLuminance(Color c) {
        return 0.2126 * srgbToLinear(c.getRed())
                + 0.7152 * srgbToLinear(c.getGreen())
                + 0.0722 * srgbToLinear(c.getBlue());
    }

    /** sRGB-to-linear transform per the WCAG definition. Input and output are in [0, 1]. */
    private static double srgbToLinear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    // ----------------------------------------------------------------------
    // Key + mouse handlers
    // ----------------------------------------------------------------------

    private void installKeyAndMouseHandlers() {
        // Esc closes -- standard tool-window close. Use event filter so we catch it
        // before any focusable child consumes it (none expected, but defensive).
        EventHandler<KeyEvent> escFilter = e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, escFilter);

        // Double-click anywhere on the body closes the window. Preserved from the
        // Groovy script -- discoverable affordance Sara's users know.
        content.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                hide();
                e.consume();
            }
        });
    }

    // ----------------------------------------------------------------------
    // Position/size persistence
    // ----------------------------------------------------------------------

    private void installPersistenceHooks() {
        // Save on hide. This covers user-clicking-X, Esc, double-click, and accelerator-toggle.
        // Use addEventHandler (not setOnHidden) so the controller can also register a
        // WINDOW_HIDDEN handler for listener cleanup without overwriting this one.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> savePosition());
    }

    private void savePosition() {
        try {
            ChannelNamesViewerPreferences.setWindowX(stage.getX());
            ChannelNamesViewerPreferences.setWindowY(stage.getY());
            ChannelNamesViewerPreferences.setWindowWidth(stage.getWidth());
            ChannelNamesViewerPreferences.setWindowHeight(stage.getHeight());
            logger.debug("Saved window geometry: x={}, y={}, w={}, h={}",
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        } catch (Exception ex) {
            // Defensive: if prefs aren't installed yet (e.g. test harness), don't crash hide().
            logger.warn("Failed to save window position: {}", ex.getMessage());
        }
    }

    private void applyFirstShowGeometry() {
        double savedX, savedY, savedW, savedH;
        try {
            savedX = ChannelNamesViewerPreferences.getWindowX();
            savedY = ChannelNamesViewerPreferences.getWindowY();
            savedW = ChannelNamesViewerPreferences.getWindowWidth();
            savedH = ChannelNamesViewerPreferences.getWindowHeight();
        } catch (Exception ex) {
            // Prefs not installed -- caller owns sizeToScene + centering.
            logger.warn("Failed to read window position prefs: {}", ex.getMessage());
            return;
        }

        // Sentinel: any -1 means we have no saved value; let caller / autoSize handle defaults.
        if (savedX < 0 || savedY < 0 || savedW <= 0 || savedH <= 0) {
            logger.debug("No saved window geometry -- using first-show defaults");
            // Caller (controller) is responsible for sizeToScene + center-on-viewer.
            return;
        }

        // Multi-monitor clamp: ensure the saved position intersects at least one screen.
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
            logger.info("Saved window position ({}, {}) is off-screen -- using first-show defaults", savedX, savedY);
            return;
        }

        // Size clamp: don't exceed primary screen visual bounds (minus 100 px slack);
        // floor to MIN_WIDTH/MIN_HEIGHT.
        Rectangle2D primary = Screen.getPrimary().getVisualBounds();
        double w = Math.max(MIN_WIDTH, Math.min(savedW, primary.getWidth() - 100));
        double h = Math.max(MIN_HEIGHT, Math.min(savedH, primary.getHeight() - 100));

        stage.setX(savedX);
        stage.setY(savedY);
        stage.setWidth(w);
        stage.setHeight(h);
        logger.debug("Restored window geometry from prefs: x={}, y={}, w={}, h={}", savedX, savedY, w, h);
    }

    // ----------------------------------------------------------------------
    // Value type for one rendered row
    // ----------------------------------------------------------------------

    /**
     * One channel row: display name + the channel's display color.
     * The display color is the channel's selected color from QuPath's brightness/contrast
     * dialog -- the controller is responsible for converting from
     * {@code ChannelDisplayInfo.getColor()} (packed ARGB int) to a {@link Color}.
     */
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
