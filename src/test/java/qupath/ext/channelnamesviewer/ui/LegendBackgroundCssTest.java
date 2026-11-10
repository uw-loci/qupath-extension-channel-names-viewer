package qupath.ext.channelnamesviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The legend background CSS keeps a decimal point whatever the default locale is. */
class LegendBackgroundCssTest {

    private static final String EXPECTED =
            "-fx-background-color: rgba(0, 0, 0, 0.750); -fx-background-radius: 12; -fx-background-insets: 0;";

    private static String cssIn(Locale locale) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            return ChannelLegendStage.backgroundCss(0.75, 12);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void germanKeepsTheDecimalPoint() {
        assertEquals(EXPECTED, cssIn(Locale.GERMANY));
    }

    @Test
    void turkishKeepsTheDecimalPoint() {
        assertEquals(EXPECTED, cssIn(Locale.forLanguageTag("tr-TR")));
    }
}
