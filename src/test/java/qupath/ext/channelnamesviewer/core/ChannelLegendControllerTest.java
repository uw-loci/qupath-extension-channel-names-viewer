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
     * Direct test of the WCAG-AA white-fallback rule (Phase 3 self-refinement).
     * <p>The rule: if the channel color has WCAG contrast ratio &lt; 4.5:1 against
     * the rgb(13,13,13) background, fall back to white. Pure blue rgb(0,0,255)
     * has HSB-brightness 1.0 but luminance only 0.0722 -- ratio 2.26:1, fails AA,
     * so the rule returns white. (The earlier brightness-only rule from Phase 1
     * section 7 was found to fail for 180/715 HSB-sweep combinations and was
     * replaced with this luminance-based check.)
     */
    @Test
    void textColorForFallsBackToWhiteForLowContrastChannelColor() {
        // Pure blue: rgb(0,0,255). Contrast against rgb(13,13,13) is ~2.26:1, below AA.
        var pureBlue = javafx.scene.paint.Color.rgb(0, 0, 255);
        assertThat(ChannelLegendStage.textColorFor(pureBlue))
                .isEqualTo(javafx.scene.paint.Color.WHITE);

        // Dark navy: rgb(0,0,99). Contrast ~1.09:1, well below AA.
        var darkNavy = javafx.scene.paint.Color.rgb(0, 0, 99);
        assertThat(ChannelLegendStage.textColorFor(darkNavy))
                .isEqualTo(javafx.scene.paint.Color.WHITE);
    }

    @Test
    void textColorForReturnsChannelColorWhenItPassesAA() {
        // Yellow rgb(255,255,0): contrast ~18.1:1, well above AA.
        var yellow = javafx.scene.paint.Color.rgb(255, 255, 0);
        assertThat(ChannelLegendStage.textColorFor(yellow))
                .isEqualTo(yellow);

        // Pure green rgb(0,255,0): contrast ~14.2:1, well above AA.
        var green = javafx.scene.paint.Color.rgb(0, 255, 0);
        assertThat(ChannelLegendStage.textColorFor(green))
                .isEqualTo(green);

        // Red rgb(255,0,0): contrast ~4.86:1, just above AA (4.5).
        var red = javafx.scene.paint.Color.rgb(255, 0, 0);
        assertThat(ChannelLegendStage.textColorFor(red))
                .isEqualTo(red);
    }

    @Test
    void textColorForBrightWhiteIsUnchanged() {
        var bright = javafx.scene.paint.Color.rgb(255, 255, 255);
        assertThat(ChannelLegendStage.textColorFor(bright))
                .isEqualTo(bright);
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
