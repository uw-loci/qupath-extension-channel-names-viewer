package qupath.ext.channelnamesviewer.core;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage.ChannelRow;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage.EmptyCause;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChannelLegendController}'s listener-lifecycle invariants.
 *
 * <p>Constructing a real {@link qupath.lib.gui.QuPathGUI} from a unit test
 * is impractical -- it owns the live application instance and a JavaFX stage.
 * This test focuses on the smaller invariant the design hinges on: a single
 * {@link ListChangeListener} instance is attached/detached symmetrically and
 * the listener slot's add/remove count returns to zero across N rebinds.</p>
 *
 * <p>The test exercises the same {@code ObservableList} API the controller
 * uses against {@link ChannelLegendStage#textColorFor} and observable-list
 * counting; it does NOT spin up the JavaFX toolkit.</p>
 */
class ChannelLegendControllerTest {

    /**
     * v1.0.7: {@code textColorFor} is now an identity function. The pre-v1.0.7
     * WCAG-AA flip-to-white was opaque to users -- selecting a dark-blue
     * channel produced a white legend row. Readability for dark channels is
     * now an opt-in white-outline effect applied by the stage (preference
     * {@code channelnamesviewer.whiteTextOutline}); the channel hue itself
     * is never replaced.
     */
    @Test
    void textColorForReturnsLiteralChannelColor() {
        // Pure blue used to flip to white pre-v1.0.7; now passes through unchanged.
        var pureBlue = javafx.scene.paint.Color.rgb(0, 0, 255);
        assertThat(ChannelLegendStage.textColorFor(pureBlue)).isEqualTo(pureBlue);

        // Dark navy: similarly low-contrast vs the dark background, still rendered as-is.
        var darkNavy = javafx.scene.paint.Color.rgb(0, 0, 99);
        assertThat(ChannelLegendStage.textColorFor(darkNavy)).isEqualTo(darkNavy);

        // Yellow: high-contrast, returned as-is (parity with pre-v1.0.7).
        var yellow = javafx.scene.paint.Color.rgb(255, 255, 0);
        assertThat(ChannelLegendStage.textColorFor(yellow)).isEqualTo(yellow);

        // Red was borderline AA pre-v1.0.7; still passes through.
        var red = javafx.scene.paint.Color.rgb(255, 0, 0);
        assertThat(ChannelLegendStage.textColorFor(red)).isEqualTo(red);
    }

    // ------------------------------------------------------------------
    // Order-preservation helper (v1.0.6) -- tests for orderChannels(...)
    // ------------------------------------------------------------------

    /**
     * The core invariant of the "Preserve channel order" feature: a channel
     * that is deselected and re-selected returns to its canonical row, not
     * to the bottom of the legend. The helper takes the canonical available
     * list and the user's current (possibly out-of-order) selection and
     * returns rows in canonical order, filtered to selected channels only.
     */
    @Test
    void orderChannelsPreservesCanonicalOrderWhenEnabled() {
        var dapi = new StubChannelDisplayInfo("DAPI");
        var fitc = new StubChannelDisplayInfo("FITC");
        var cy3 = new StubChannelDisplayInfo("Cy3");
        var cy5 = new StubChannelDisplayInfo("Cy5");
        List<ChannelDisplayInfo> available = List.of(dapi, fitc, cy3, cy5);

        // User deselected FITC then reselected -- QuPath puts FITC at the end.
        List<ChannelDisplayInfo> selected = List.of(dapi, cy3, cy5, fitc);

        List<ChannelDisplayInfo> ordered =
                ChannelLegendController.orderChannels(available, selected, true);

        // Canonical order: DAPI, FITC, Cy3, Cy5 (filtered to those selected = all four).
        assertThat(ordered).containsExactly(dapi, fitc, cy3, cy5);
    }

    @Test
    void orderChannelsReturnsSelectionVerbatimWhenDisabled() {
        var dapi = new StubChannelDisplayInfo("DAPI");
        var fitc = new StubChannelDisplayInfo("FITC");
        var cy3 = new StubChannelDisplayInfo("Cy3");
        List<ChannelDisplayInfo> available = List.of(dapi, fitc, cy3);
        // Click order: FITC, DAPI (Cy3 not selected).
        List<ChannelDisplayInfo> selected = List.of(fitc, dapi);

        List<ChannelDisplayInfo> ordered =
                ChannelLegendController.orderChannels(available, selected, false);

        // Legacy behavior preserved: returns the selection list unchanged.
        assertThat(ordered).containsExactly(fitc, dapi);
    }

    @Test
    void orderChannelsFiltersToSelectionWhenPreservingOrder() {
        var dapi = new StubChannelDisplayInfo("DAPI");
        var fitc = new StubChannelDisplayInfo("FITC");
        var cy3 = new StubChannelDisplayInfo("Cy3");
        var cy5 = new StubChannelDisplayInfo("Cy5");
        List<ChannelDisplayInfo> available = List.of(dapi, fitc, cy3, cy5);
        // Only Cy3 and DAPI selected, click order Cy3->DAPI.
        List<ChannelDisplayInfo> selected = List.of(cy3, dapi);

        List<ChannelDisplayInfo> ordered =
                ChannelLegendController.orderChannels(available, selected, true);

        // Canonical order DAPI, Cy3 (skipping FITC and Cy5 since they're not selected).
        assertThat(ordered).containsExactly(dapi, cy3);
    }

    @Test
    void orderChannelsAppendsSelectedNotInAvailableAsFallback() {
        var dapi = new StubChannelDisplayInfo("DAPI");
        var fitc = new StubChannelDisplayInfo("FITC");
        // Imagine an exotic transform: it appears in selected but not in available.
        var phantom = new StubChannelDisplayInfo("phantom");
        List<ChannelDisplayInfo> available = List.of(dapi, fitc);
        List<ChannelDisplayInfo> selected = List.of(fitc, phantom, dapi);

        List<ChannelDisplayInfo> ordered =
                ChannelLegendController.orderChannels(available, selected, true);

        // Canonical channels first in canonical order, exotic ones appended (never dropped).
        assertThat(ordered).containsExactly(dapi, fitc, phantom);
    }

    @Test
    void orderChannelsFallsBackToSelectionWhenAvailableEmpty() {
        var dapi = new StubChannelDisplayInfo("DAPI");
        var fitc = new StubChannelDisplayInfo("FITC");
        List<ChannelDisplayInfo> selected = List.of(fitc, dapi);

        // Empty available list: we'd rather show channels in click order than nothing.
        List<ChannelDisplayInfo> ordered =
                ChannelLegendController.orderChannels(List.of(), selected, true);
        assertThat(ordered).containsExactly(fitc, dapi);

        // null is treated the same as empty.
        ordered = ChannelLegendController.orderChannels(null, selected, true);
        assertThat(ordered).containsExactly(fitc, dapi);
    }

    @Test
    void textColorForBrightWhiteIsUnchanged() {
        var bright = javafx.scene.paint.Color.rgb(255, 255, 255);
        assertThat(ChannelLegendStage.textColorFor(bright))
                .isEqualTo(bright);
    }

    @Test
    void textColorForBlackChannelIsReturnedAsIs() {
        // Even a fully-black channel passes through (a paranoid user could
        // pick #000000 to mean "this channel is off"; we don't second-guess).
        var black = javafx.scene.paint.Color.BLACK;
        assertThat(ChannelLegendStage.textColorFor(black)).isEqualTo(black);
    }

    // ------------------------------------------------------------------
    // Dark-color gate (v1.0.8) -- contrast assists only apply to dark channels.
    // ------------------------------------------------------------------

    /**
     * The outline + panel-backdrop contrast assists added in v1.0.7 / v1.0.8
     * are gated by {@code isDarkColor}: a BT.601 luminance check against the
     * 0.5 midpoint. Channels that are already bright enough to read against
     * the dark legend background render bare. This test pins the boundary so
     * a future tweak to the luminance formula or threshold can't quietly
     * change which channels get the assist.
     */
    @Test
    void isDarkColorClassifiesChannelsByBT601Luminance() {
        // Pure blue: luminance = 0.114 -- well below threshold, dark.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(0, 0, 255)))
                .isTrue();
        // Dark navy: even darker, definitely dark.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(0, 0, 99)))
                .isTrue();
        // Pure black: trivially dark.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.BLACK)).isTrue();
        // Pure red: luminance = 0.299, dark.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(255, 0, 0)))
                .isTrue();

        // Pure green: luminance = 0.587, NOT dark (the green channel is bright).
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(0, 255, 0)))
                .isFalse();
        // Yellow: luminance = 0.886, very bright.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(255, 255, 0)))
                .isFalse();
        // Pure white: luminance = 1.0, brightest.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.WHITE)).isFalse();
        // Mid-gray (128, 128, 128): luminance = 128/255 ~= 0.502, just above
        // threshold -- pinned here so a refactor that drifts the threshold or
        // formula fails loudly rather than silently flipping mid-gray's category.
        assertThat(ChannelLegendStage.isDarkColor(javafx.scene.paint.Color.rgb(128, 128, 128)))
                .isFalse();

        // null: defensive -- never treated as dark, no NPE.
        assertThat(ChannelLegendStage.isDarkColor(null)).isFalse();
    }

    /**
     * The controller relies on a single {@link ListChangeListener} field
     * instance so that {@code addListener}/{@code removeListener} pair across
     * multiple image switches without leaking. This test simulates N image
     * switches against a JavaFX {@link ObservableList} and confirms the
     * listener is removed cleanly each time -- the invariant the controller
     * promises in {@link ChannelLegendController#install()} and
     * {@link ChannelLegendController#uninstall()}.
     *
     * <p>Probing the listener slot directly is not exposed by JavaFX, so the
     * test instead sends a sentinel mutation, observes whether the listener
     * fires, and asserts that after detach it does NOT fire.</p>
     */
    @Test
    void singleListenerInstanceAttachAndDetachAcrossImageSwitches() {
        ObservableList<ChannelDisplayInfo> list = FXCollections.observableArrayList();

        int[] callCount = {0};
        ListChangeListener<ChannelDisplayInfo> listener = c -> callCount[0]++;

        // 50 attach/detach cycles -- mimics a long QuPath session with many image opens.
        for (int i = 0; i < 50; i++) {
            list.addListener(listener);
            list.add(new StubChannelDisplayInfo("ch" + i));
            // The listener fired once for the add.
            // After detach, further mutations must not increment.
            list.removeListener(listener);
        }

        int countAfterDetaches = callCount[0];

        // After all detaches, mutate again -- listener must NOT fire.
        list.add(new StubChannelDisplayInfo("after-detach"));
        assertThat(callCount[0]).isEqualTo(countAfterDetaches);

        // Each cycle fired exactly once (one add per cycle).
        assertThat(countAfterDetaches).isEqualTo(50);
    }

    /**
     * Re-using the same listener instance is required for symmetric
     * attach/detach. This regression test makes the requirement explicit:
     * if a future refactor accidentally creates a fresh lambda inside the
     * controller's listener field, the {@code removeListener} call would not
     * detach the *original* attachment -- this test demonstrates the failure
     * mode by attaching two distinct listener instances and showing that
     * removing the second leaves the first in place.
     */
    @Test
    void distinctListenerInstancesAreNotInterchangeable() {
        ObservableList<ChannelDisplayInfo> list = FXCollections.observableArrayList();
        int[] firstCount = {0};
        int[] secondCount = {0};
        ListChangeListener<ChannelDisplayInfo> first = c -> firstCount[0]++;
        ListChangeListener<ChannelDisplayInfo> second = c -> secondCount[0]++;

        list.addListener(first);
        list.removeListener(second); // does NOT remove `first`

        list.add(new StubChannelDisplayInfo("x"));
        assertThat(firstCount[0]).isEqualTo(1);
        assertThat(secondCount[0]).isZero();

        list.removeListener(first);
        list.add(new StubChannelDisplayInfo("y"));
        assertThat(firstCount[0]).isEqualTo(1); // detach worked when same instance is used
    }

    /**
     * Closes SC-M1 (Phase 4 scientist tester finding). The earlier
     * {@link #singleListenerInstanceAttachAndDetachAcrossImageSwitches} test
     * exercised an {@link ObservableList} directly, not the controller's real
     * binding path through {@code imageDataProperty()} -> {@code viewerProperty()}.
     * <p>This test plugs a {@link CountingObjectProperty} into the controller's
     * package-private constructor seam, then simulates N image switches by
     * mutating the property values. After every cycle the controller must have
     * exactly one image-data listener and one viewer listener attached -- if
     * the rebind path leaks, the count grows unbounded.
     * <p>The test uses {@code viewerSupplier = () -> null} so the controller's
     * {@code rebindToCurrentImage} short-circuits to the empty state without
     * needing a real {@link QuPathViewer}; that's the smallest viable shape
     * that still exercises the {@code addListener}/{@code removeListener} flow
     * on the two property objects. It deliberately runs without initialising
     * the JavaFX toolkit (no Stage, no Scene, no Platform.startup) so the test
     * stays runnable in any unit-test JVM.
     */
    @Test
    void controllerRebindAcrossViewerSwitchesDoesNotLeakImageDataOrViewerListeners() {
        var imageDataProp = new CountingObjectProperty<ImageData<BufferedImage>>();
        var viewerProp = new CountingObjectProperty<QuPathViewer>();
        var sink = new RecordingSink();

        ChannelLegendController controller = new ChannelLegendController(
                imageDataProp, viewerProp, () -> null, sink);

        // Pre-install: nothing is attached.
        assertThat(imageDataProp.changeListenerCount()).isZero();
        assertThat(viewerProp.changeListenerCount()).isZero();

        controller.install();

        // After install: exactly one of each.
        assertThat(imageDataProp.changeListenerCount()).isEqualTo(1);
        assertThat(viewerProp.changeListenerCount()).isEqualTo(1);

        // Simulate 50 image-data + viewer change events. Each fires a rebind
        // through the controller's listener path; we verify no listener leak
        // accumulates per cycle.
        for (int i = 0; i < 50; i++) {
            // Mutating the property fires the controller's image-data listener,
            // which calls rebindToCurrentImage(); the viewerSupplier returns null,
            // so the sink records an empty state and nothing further is attached.
            imageDataProp.set(null);
            viewerProp.set(null);
            // After every fire, the count must still be exactly one -- no leak.
            assertThat(imageDataProp.changeListenerCount())
                    .as("imageData listener count after iter %d", i)
                    .isEqualTo(1);
            assertThat(viewerProp.changeListenerCount())
                    .as("viewer listener count after iter %d", i)
                    .isEqualTo(1);
        }

        // Idempotent install: second install() must not double-attach.
        controller.install();
        assertThat(imageDataProp.changeListenerCount()).isEqualTo(1);
        assertThat(viewerProp.changeListenerCount()).isEqualTo(1);

        // Uninstall: counts go back to zero.
        controller.uninstall();
        assertThat(imageDataProp.changeListenerCount()).isZero();
        assertThat(viewerProp.changeListenerCount()).isZero();

        // Idempotent uninstall: second uninstall() is a no-op.
        controller.uninstall();
        assertThat(imageDataProp.changeListenerCount()).isZero();
        assertThat(viewerProp.changeListenerCount()).isZero();

        // Sanity: every rebind during the loop hit the empty-state path
        // (50 image-data fires + 50 viewer fires + 1 install + 1 second-install =
        //  102 renderEmptyState calls). Exact count is not load-bearing; we just
        //  check that the sink saw the right cause and that renderChannels was
        //  never invoked (no real image was ever attached).
        assertThat(sink.lastCause).isEqualTo(EmptyCause.NO_IMAGE);
        assertThat(sink.renderChannelsCallCount).isZero();
    }

    /**
     * Sibling test: re-attaching the controller after a full uninstall must
     * land back at exactly one listener of each kind. Catches a regression
     * where {@code installed} flag bookkeeping diverges from actual listener
     * registration.
     */
    @Test
    void controllerReinstallAfterUninstallReturnsToSingleListenerCount() {
        var imageDataProp = new CountingObjectProperty<ImageData<BufferedImage>>();
        var viewerProp = new CountingObjectProperty<QuPathViewer>();
        var sink = new RecordingSink();

        ChannelLegendController controller = new ChannelLegendController(
                imageDataProp, viewerProp, () -> null, sink);

        for (int cycle = 0; cycle < 5; cycle++) {
            controller.install();
            assertThat(imageDataProp.changeListenerCount())
                    .as("install cycle %d -- imageData", cycle)
                    .isEqualTo(1);
            assertThat(viewerProp.changeListenerCount())
                    .as("install cycle %d -- viewer", cycle)
                    .isEqualTo(1);

            // Fire a couple of mid-cycle changes; they must not leak.
            imageDataProp.set(null);
            viewerProp.set(null);

            controller.uninstall();
            assertThat(imageDataProp.changeListenerCount())
                    .as("uninstall cycle %d -- imageData", cycle)
                    .isZero();
            assertThat(viewerProp.changeListenerCount())
                    .as("uninstall cycle %d -- viewer", cycle)
                    .isZero();
        }
    }

    /**
     * Recording sink for the controller. The image-switch listener-leak tests
     * don't need a real {@link ChannelLegendStage} -- only that the controller
     * routes its render calls somewhere. Captures the most recent cause and
     * counts {@code renderChannels} invocations.
     */
    private static final class RecordingSink implements ChannelLegendController.LegendSink {
        EmptyCause lastCause;
        int renderChannelsCallCount;

        @Override
        public void renderChannels(List<ChannelRow> rows) {
            renderChannelsCallCount++;
        }

        @Override
        public void renderEmptyState(EmptyCause cause) {
            lastCause = cause;
        }
    }

    /**
     * {@link SimpleObjectProperty} subclass that exposes the number of
     * currently-attached {@link ChangeListener}s. JavaFX does not expose this
     * count on the public API, so we keep a parallel list updated via
     * {@link #addListener(ChangeListener)} / {@link #removeListener(ChangeListener)}.
     * <p>InvalidationListeners are not tracked because the controller does not
     * register any (it uses {@code ChangeListener} exclusively); the inherited
     * superclass methods handle them transparently.
     */
    private static final class CountingObjectProperty<T> extends SimpleObjectProperty<T> {
        private final List<ChangeListener<? super T>> changeListeners = new ArrayList<>();

        @Override
        public void addListener(ChangeListener<? super T> listener) {
            super.addListener(listener);
            changeListeners.add(listener);
        }

        @Override
        public void removeListener(ChangeListener<? super T> listener) {
            super.removeListener(listener);
            // List.remove(Object) removes the first occurrence -- correct here
            // because each (listener, instance) pair is added at most once by
            // the controller.
            changeListeners.remove(listener);
        }

        int changeListenerCount() {
            return changeListeners.size();
        }
    }

    /**
     * Minimal fake. Exists to satisfy the {@code <ChannelDisplayInfo>}
     * generic; we never actually inspect anything but the name.
     */
    private static final class StubChannelDisplayInfo implements ChannelDisplayInfo {
        private final String name;

        StubChannelDisplayInfo(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public float getMinDisplay() {
            return 0;
        }

        @Override
        public float getMaxDisplay() {
            return 255;
        }

        @Override
        public float getMinAllowed() {
            return 0;
        }

        @Override
        public float getMaxAllowed() {
            return 255;
        }

        @Override
        public boolean isAdditive() {
            return true;
        }

        @Override
        public boolean isBrightnessContrastRescaled() {
            return false;
        }

        @Override
        public String getValueAsString(java.awt.image.BufferedImage img, int x, int y) {
            return "";
        }

        @Override
        public int getRGB(java.awt.image.BufferedImage img, int x, int y, qupath.lib.display.ChannelDisplayMode mode) {
            return 0;
        }

        @Override
        public int[] getRGB(java.awt.image.BufferedImage img, int[] rgb, qupath.lib.display.ChannelDisplayMode mode) {
            return rgb == null ? new int[0] : rgb;
        }

        @Override
        public int updateRGBAdditive(java.awt.image.BufferedImage img, int x, int y, int rgb, qupath.lib.display.ChannelDisplayMode mode) {
            return rgb;
        }

        @Override
        public void updateRGBAdditive(java.awt.image.BufferedImage img, int[] rgb, qupath.lib.display.ChannelDisplayMode mode) {
            // No-op stub.
        }

        @Override
        public boolean doesSomething() {
            return false;
        }

        @Override
        public Integer getColor() {
            return null;
        }
    }
}
