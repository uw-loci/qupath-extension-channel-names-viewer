package qupath.ext.channelnamesviewer.preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent settings of the auto contrast and colour wheel tools.
 */
public final class ChannelToolsPreferences {

    private static final String PREFIX = "channelnamesviewer.tools.";

    private ChannelToolsPreferences() {
    }

    /** Noise widths above the background peak at which the display minimum sits. */
    public static final DoubleProperty NOISE_MULTIPLE =
            PathPrefs.createPersistentPreference(PREFIX + "noiseMultiple", 3.0);

    /** Percent of the pixels above the minimum allowed to saturate. */
    public static final DoubleProperty SATURATED_PERCENT =
            PathPrefs.createPersistentPreference(PREFIX + "saturatedPercent", 0.5);

    /** True to apply to every channel, false to the visible ones. */
    public static final BooleanProperty ALL_CHANNELS =
            PathPrefs.createPersistentPreference(PREFIX + "allChannels", false);

    public static final StringProperty WHEEL_MODE =
            PathPrefs.createPersistentPreference(PREFIX + "wheelMode", "HSV");

    public static final DoubleProperty WHEEL_VALUE =
            PathPrefs.createPersistentPreference(PREFIX + "wheelValue", 1.0);

    public static final DoubleProperty WHEEL_LIGHTNESS =
            PathPrefs.createPersistentPreference(PREFIX + "wheelLightness", 65.0);

    public static final BooleanProperty WHEEL_SPREAD =
            PathPrefs.createPersistentPreference(PREFIX + "wheelSpread", false);
}
