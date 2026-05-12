package qupath.ext.channelnamesviewer.preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the Channel Names Viewer extension.
 * <p>
 * Stores the floating legend window's position and size across QuPath sessions.
 * Each of the four geometry keys uses a sentinel value of {@link #SENTINEL} (-1.0)
 * to mean "no saved value yet" -- on first show the stage falls back to an
 * auto-fit + center-on-main-window placement, and only persists user-chosen
 * values once the window has actually been shown and hidden.
 *
 * <p>Pattern source:
 * {@code qupath-extension-confusion-matrix/preferences/CMPreferences.java}.
 *
 * @author Mike Nelson
 */
public final class ChannelNamesViewerPreferences {

    private static final Logger logger = LoggerFactory.getLogger(ChannelNamesViewerPreferences.class);

    /** Preference-key namespace; collision-free within QuPath's flat key space. */
    private static final String PREFIX = "channelnamesviewer.";

    /** Sentinel meaning "no saved value yet" -- triggers first-show defaults. */
    public static final double SENTINEL = -1.0;

    private static DoubleProperty windowXProperty;
    private static DoubleProperty windowYProperty;
    private static DoubleProperty windowWidthProperty;
    private static DoubleProperty windowHeightProperty;
    /** Whether the user has locked the font size (v1.0.1+). */
    private static BooleanProperty fontLockedProperty;
    /** Locked font size in pt; only meaningful when {@link #fontLockedProperty} is true (v1.0.1+). */
    private static DoubleProperty lockedFontPtProperty;
    /** Background opacity (0.05-1.0); 0.75 default matches the original Groovy script. (v1.0.3+) */
    private static DoubleProperty backgroundOpacityProperty;
    /**
     * Whether the legend renders channels in the image's canonical channel order
     * (v1.0.6+). When true (default), the legend iterates the image's
     * {@code availableChannels()} and filters to those in the current selection,
     * so a channel that is deselected and reselected returns to its original
     * row -- not to the bottom. When false, the legend uses
     * {@code selectedChannels()} order verbatim (legacy behavior).
     */
    private static BooleanProperty preserveChannelOrderProperty;
    /**
     * Whether dark-colored channel names are drawn with a white outline
     * (v1.0.7+; v1.0.8 narrowed the gate to dark channels only). Default
     * false: text is rendered in the literal channel color (which may have
     * poor contrast against the dark background -- the user's choice). When
     * true a white drop-shadow halo is painted around each glyph that is
     * classified as dark (BT.601 luminance < 0.5), keeping the channel hue
     * intact while making dark-colored channels readable. Replaces the
     * v1.0.3-v1.0.6 WCAG auto-flip-to-white rule. Mutually exclusive with
     * {@link #darkLabelPanelProperty} at the UI layer.
     */
    private static BooleanProperty whiteTextOutlineProperty;
    /**
     * Whether dark-colored channel names get a translucent light backdrop
     * chip (v1.0.8+). Default false. Like {@link #whiteTextOutlineProperty}
     * this only affects channels whose color is classified as dark (BT.601
     * luminance < 0.5); light channels render bare regardless. The two
     * presentation prefs are mutually exclusive at the menu layer -- only
     * one may be enabled at a time.
     */
    private static BooleanProperty darkLabelPanelProperty;

    private static volatile boolean installed = false;

    private ChannelNamesViewerPreferences() {
        // Utility class.
    }

    /**
     * Install the four persistent geometry preferences. Idempotent: safe to call
     * more than once; subsequent calls are no-ops.
     */
    public static synchronized void installPreferences() {
        if (installed) {
            return;
        }
        logger.info("Installing Channel Names Viewer preferences");

        windowXProperty = PathPrefs.createPersistentPreference(
                PREFIX + "windowX", SENTINEL);
        windowYProperty = PathPrefs.createPersistentPreference(
                PREFIX + "windowY", SENTINEL);
        windowWidthProperty = PathPrefs.createPersistentPreference(
                PREFIX + "windowWidth", SENTINEL);
        windowHeightProperty = PathPrefs.createPersistentPreference(
                PREFIX + "windowHeight", SENTINEL);
        fontLockedProperty = PathPrefs.createPersistentPreference(
                PREFIX + "fontLocked", false);
        lockedFontPtProperty = PathPrefs.createPersistentPreference(
                PREFIX + "lockedFontPt", 20.0);
        backgroundOpacityProperty = PathPrefs.createPersistentPreference(
                PREFIX + "backgroundOpacity", 0.75);
        preserveChannelOrderProperty = PathPrefs.createPersistentPreference(
                PREFIX + "preserveChannelOrder", true);
        whiteTextOutlineProperty = PathPrefs.createPersistentPreference(
                PREFIX + "whiteTextOutline", false);
        darkLabelPanelProperty = PathPrefs.createPersistentPreference(
                PREFIX + "darkLabelPanel", false);

        installed = true;
        logger.info("Channel Names Viewer preferences installed");
    }

    public static DoubleProperty windowXProperty() {
        ensureInstalled();
        return windowXProperty;
    }

    public static DoubleProperty windowYProperty() {
        ensureInstalled();
        return windowYProperty;
    }

    public static DoubleProperty windowWidthProperty() {
        ensureInstalled();
        return windowWidthProperty;
    }

    public static DoubleProperty windowHeightProperty() {
        ensureInstalled();
        return windowHeightProperty;
    }

    public static double getWindowX() {
        return windowXProperty != null ? windowXProperty.get() : SENTINEL;
    }

    public static double getWindowY() {
        return windowYProperty != null ? windowYProperty.get() : SENTINEL;
    }

    public static double getWindowWidth() {
        return windowWidthProperty != null ? windowWidthProperty.get() : SENTINEL;
    }

    public static double getWindowHeight() {
        return windowHeightProperty != null ? windowHeightProperty.get() : SENTINEL;
    }

    public static void setWindowX(double v) {
        ensureInstalled();
        windowXProperty.set(v);
    }

    public static void setWindowY(double v) {
        ensureInstalled();
        windowYProperty.set(v);
    }

    public static void setWindowWidth(double v) {
        ensureInstalled();
        windowWidthProperty.set(v);
    }

    public static void setWindowHeight(double v) {
        ensureInstalled();
        windowHeightProperty.set(v);
    }

    /**
     * Save all four geometry values in one call (typical use: {@code stage.setOnHidden}).
     * Each value is recorded verbatim; the restore-side guard in
     * {@link qupath.ext.channelnamesviewer.ui.ChannelLegendStage} clamps to current
     * {@code Screen.getScreens()} bounds before applying.
     *
     * @param x window X
     * @param y window Y
     * @param width window width
     * @param height window height
     */
    public static void saveGeometry(double x, double y, double width, double height) {
        ensureInstalled();
        windowXProperty.set(x);
        windowYProperty.set(y);
        windowWidthProperty.set(width);
        windowHeightProperty.set(height);
    }

    /**
     * True if any of the four geometry values is the sentinel.
     *
     * @return whether the saved geometry is unusable / not yet set
     */
    public static boolean isSentinelGeometry() {
        return getWindowX() == SENTINEL
                || getWindowY() == SENTINEL
                || getWindowWidth() <= 0
                || getWindowHeight() <= 0;
    }

    /**
     * Reset all four geometry values to the sentinel. Subsequent shows fall back
     * to first-show defaults until the user moves or resizes the window again.
     */
    public static void clearGeometry() {
        ensureInstalled();
        windowXProperty.set(SENTINEL);
        windowYProperty.set(SENTINEL);
        windowWidthProperty.set(SENTINEL);
        windowHeightProperty.set(SENTINEL);
    }

    public static boolean getFontLocked() {
        return fontLockedProperty != null && fontLockedProperty.get();
    }

    public static void setFontLocked(boolean v) {
        ensureInstalled();
        fontLockedProperty.set(v);
    }

    public static double getLockedFontPt() {
        return lockedFontPtProperty != null ? lockedFontPtProperty.get() : 20.0;
    }

    public static void setLockedFontPt(double v) {
        ensureInstalled();
        lockedFontPtProperty.set(v);
    }

    public static double getBackgroundOpacity() {
        return backgroundOpacityProperty != null ? backgroundOpacityProperty.get() : 0.75;
    }

    public static void setBackgroundOpacity(double v) {
        ensureInstalled();
        backgroundOpacityProperty.set(v);
    }

    public static DoubleProperty backgroundOpacityProperty() {
        ensureInstalled();
        return backgroundOpacityProperty;
    }

    public static boolean getPreserveChannelOrder() {
        return preserveChannelOrderProperty == null || preserveChannelOrderProperty.get();
    }

    public static void setPreserveChannelOrder(boolean v) {
        ensureInstalled();
        preserveChannelOrderProperty.set(v);
    }

    public static BooleanProperty preserveChannelOrderProperty() {
        ensureInstalled();
        return preserveChannelOrderProperty;
    }

    public static boolean getWhiteTextOutline() {
        return whiteTextOutlineProperty != null && whiteTextOutlineProperty.get();
    }

    public static void setWhiteTextOutline(boolean v) {
        ensureInstalled();
        whiteTextOutlineProperty.set(v);
    }

    public static BooleanProperty whiteTextOutlineProperty() {
        ensureInstalled();
        return whiteTextOutlineProperty;
    }

    public static boolean getDarkLabelPanel() {
        return darkLabelPanelProperty != null && darkLabelPanelProperty.get();
    }

    public static void setDarkLabelPanel(boolean v) {
        ensureInstalled();
        darkLabelPanelProperty.set(v);
    }

    public static BooleanProperty darkLabelPanelProperty() {
        ensureInstalled();
        return darkLabelPanelProperty;
    }

    private static void ensureInstalled() {
        if (!installed) {
            installPreferences();
        }
    }
}
