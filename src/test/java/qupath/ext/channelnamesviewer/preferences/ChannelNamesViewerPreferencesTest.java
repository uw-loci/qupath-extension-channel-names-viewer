package qupath.ext.channelnamesviewer.preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel-handling and round-trip tests for {@link ChannelNamesViewerPreferences}.
 * <p>
 * Uses {@link Preferences#userRoot()} (the same store {@code PathPrefs} writes to)
 * so the test exercises the real persistence path. Cleans up the keys it writes
 * after each test.
 */
class ChannelNamesViewerPreferencesTest {

    private static final String[] KEYS = {
            "channelnamesviewer.windowX",
            "channelnamesviewer.windowY",
            "channelnamesviewer.windowWidth",
            "channelnamesviewer.windowHeight"
    };

    @BeforeEach
    void clearKeys() {
        Preferences root = Preferences.userRoot();
        for (String key : KEYS) {
            root.remove(key);
        }
        ChannelNamesViewerPreferences.installPreferences();
        ChannelNamesViewerPreferences.clearGeometry();
    }

    @AfterEach
    void cleanup() {
        ChannelNamesViewerPreferences.clearGeometry();
        Preferences root = Preferences.userRoot();
        for (String key : KEYS) {
            root.remove(key);
        }
    }

    @Test
    void installPreferencesIsIdempotent() {
        ChannelNamesViewerPreferences.installPreferences();
        ChannelNamesViewerPreferences.installPreferences();
        ChannelNamesViewerPreferences.installPreferences();
        // No exception, properties available.
        assertThat(ChannelNamesViewerPreferences.windowXProperty()).isNotNull();
        assertThat(ChannelNamesViewerPreferences.windowYProperty()).isNotNull();
        assertThat(ChannelNamesViewerPreferences.windowWidthProperty()).isNotNull();
        assertThat(ChannelNamesViewerPreferences.windowHeightProperty()).isNotNull();
    }

    @Test
    void freshInstallReportsSentinelGeometry() {
        ChannelNamesViewerPreferences.clearGeometry();
        assertThat(ChannelNamesViewerPreferences.isSentinelGeometry()).isTrue();
        assertThat(ChannelNamesViewerPreferences.getWindowX())
                .isEqualTo(ChannelNamesViewerPreferences.SENTINEL);
        assertThat(ChannelNamesViewerPreferences.getWindowY())
                .isEqualTo(ChannelNamesViewerPreferences.SENTINEL);
        assertThat(ChannelNamesViewerPreferences.getWindowWidth())
                .isEqualTo(ChannelNamesViewerPreferences.SENTINEL);
        assertThat(ChannelNamesViewerPreferences.getWindowHeight())
                .isEqualTo(ChannelNamesViewerPreferences.SENTINEL);
    }

    @Test
    void saveGeometryRoundTrips() {
        ChannelNamesViewerPreferences.saveGeometry(123.0, 456.0, 200.0, 100.0);
        assertThat(ChannelNamesViewerPreferences.getWindowX()).isEqualTo(123.0);
        assertThat(ChannelNamesViewerPreferences.getWindowY()).isEqualTo(456.0);
        assertThat(ChannelNamesViewerPreferences.getWindowWidth()).isEqualTo(200.0);
        assertThat(ChannelNamesViewerPreferences.getWindowHeight()).isEqualTo(100.0);
        assertThat(ChannelNamesViewerPreferences.isSentinelGeometry()).isFalse();
    }

    @Test
    void clearGeometryResetsAllToSentinel() {
        ChannelNamesViewerPreferences.saveGeometry(50.0, 60.0, 300.0, 150.0);
        ChannelNamesViewerPreferences.clearGeometry();
        assertThat(ChannelNamesViewerPreferences.isSentinelGeometry()).isTrue();
    }

    @Test
    void zeroOrNegativeWidthIsTreatedAsSentinel() {
        // Cannot save a zero or negative dimension via saveGeometry directly,
        // but if someone tampers with the preferences the isSentinelGeometry
        // probe should still detect "no usable geometry".
        ChannelNamesViewerPreferences.setWindowX(100.0);
        ChannelNamesViewerPreferences.setWindowY(100.0);
        ChannelNamesViewerPreferences.setWindowWidth(0.0);
        ChannelNamesViewerPreferences.setWindowHeight(100.0);
        assertThat(ChannelNamesViewerPreferences.isSentinelGeometry()).isTrue();

        ChannelNamesViewerPreferences.setWindowWidth(100.0);
        ChannelNamesViewerPreferences.setWindowHeight(-1.0);
        assertThat(ChannelNamesViewerPreferences.isSentinelGeometry()).isTrue();
    }

    @Test
    void individualSettersUpdateOnlyTheirKey() {
        ChannelNamesViewerPreferences.saveGeometry(0.0, 0.0, 100.0, 100.0);
        ChannelNamesViewerPreferences.setWindowX(42.0);
        assertThat(ChannelNamesViewerPreferences.getWindowX()).isEqualTo(42.0);
        assertThat(ChannelNamesViewerPreferences.getWindowY()).isEqualTo(0.0);
        assertThat(ChannelNamesViewerPreferences.getWindowWidth()).isEqualTo(100.0);
        assertThat(ChannelNamesViewerPreferences.getWindowHeight()).isEqualTo(100.0);
    }
}
