package qupath.ext.channelnamesviewer.preferences;

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

    private static void ensureInstalled() {
        if (!installed) {
            installPreferences();
        }
    }
}
