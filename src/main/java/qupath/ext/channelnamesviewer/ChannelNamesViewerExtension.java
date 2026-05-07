package qupath.ext.channelnamesviewer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.channelnamesviewer.core.ChannelLegendController;
import qupath.ext.channelnamesviewer.preferences.ChannelNamesViewerPreferences;
import qupath.ext.channelnamesviewer.ui.ChannelLegendStage;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.actions.CommonActions;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ResourceBundle;

/**
 * QuPath extension entry point for the Channel Names Viewer.
 *
 * <p>Registers a menu item under {@code Extensions -> Channel Names Viewer},
 * the keyboard accelerator {@code shortcut+shift+c} ({@code Cmd+Shift+C} on
 * macOS, {@code Ctrl+Shift+C} elsewhere), and a toolbar button positioned
 * immediately to the right of QuPath's brightness/contrast button. A single
 * {@link ChannelLegendStage} is created per session; opening the viewer when
 * it is already showing brings it forward, and pressing the accelerator a
 * second time toggles it closed.</p>
 *
 * <p>The toolbar-button injection walks {@link QuPathGUI#getToolBar()} looking
 * for the {@link ButtonBase} whose ControlsFX action property
 * ({@code "controlsfx.actions.action"}) equals
 * {@link CommonActions#BRIGHTNESS_CONTRAST}. If lookup fails the menu item
 * and accelerator continue to work; the failure is logged at WARN level and
 * the toolbar button is silently skipped.</p>
 *
 * @author Mike Nelson
 */
public class ChannelNamesViewerExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(ChannelNamesViewerExtension.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.channelnamesviewer.ui.strings");

    private static final String EXTENSION_NAME = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-channel-names-viewer");

    /** Default accelerator: Cmd+Shift+C / Ctrl+Shift+C. */
    private static final String ACCELERATOR_COMBO = "shortcut+shift+c";

    private boolean installed = false;

    /** Singleton stage owned by the extension. Created lazily on first menu invocation. */
    private ChannelLegendStage legendStage;

    /** Listener owner for the legend stage; lifecycle is paired with the stage. */
    private ChannelLegendController controller;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            logger.debug("ChannelNamesViewerExtension.installExtension called twice; ignoring");
            return;
        }
        installed = true;

        logger.info("Installing extension: {}", EXTENSION_NAME);
        ChannelNamesViewerPreferences.installPreferences();

        Platform.runLater(() -> {
            try {
                MenuItem menuItem = registerMenuItem(qupath);
                bindAccelerator(qupath, menuItem);
                // Defer toolbar lookup so QuPath finishes building its toolbar first.
                Platform.runLater(() -> Platform.runLater(() -> tryInsertToolbarButton(qupath, 0)));
            } catch (Exception ex) {
                logger.warn("Failed to install Channel Names Viewer UI hooks: {}", ex.getMessage(), ex);
            }
        });
    }

    private MenuItem registerMenuItem(QuPathGUI qupath) {
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        MenuItem item = new MenuItem(resources.getString("menu.open"));
        item.setOnAction(e -> toggleLegend(qupath));
        // Tooltip is exposed via the menu item user-data; QuPath's menu rendering
        // does not show tooltips on MenuItem directly, but we attach for parity.
        item.getProperties().put("tooltip", resources.getString("tooltip.menu"));
        extensionMenu.getItems().add(item);
        logger.info("Registered menu item: Extensions > {}", EXTENSION_NAME);
        return item;
    }

    private void bindAccelerator(QuPathGUI qupath, MenuItem menuItem) {
        try {
            KeyCombination combo = KeyCombination.valueOf(ACCELERATOR_COMBO);
            // Defensive: log if the combo is already taken by another action.
            Object existing = qupath.lookupAccelerator(combo);
            if (existing != null && existing != menuItem) {
                logger.warn("Accelerator {} appears to be in use by {}; binding anyway",
                        ACCELERATOR_COMBO, existing);
            }
            // Use QuPath's setAccelerator so the keystroke is plumbed into the
            // main scene via registerAccelerator(action). Calling
            // MenuItem.setAccelerator directly only works when the menu is in
            // the menu bar; for nested submenus the keystroke never fires.
            qupath.setAccelerator(menuItem, combo);
            logger.info("Bound accelerator {} to menu item", ACCELERATOR_COMBO);
        } catch (Exception ex) {
            logger.warn("Failed to bind accelerator {}: {}", ACCELERATOR_COMBO, ex.getMessage());
        }
    }

    /**
     * Toggle the legend window. On first invocation, lazily constructs the
     * stage and the controller. Subsequent invocations bring the stage forward
     * if hidden, or hide it if shown.
     */
    private synchronized void toggleLegend(QuPathGUI qupath) {
        if (legendStage == null) {
            legendStage = new ChannelLegendStage(qupath.getStage());
            controller = new ChannelLegendController(qupath, legendStage);
            // Detach controller listeners when the stage is hidden by the user
            // (close button, Esc, double-click, accelerator-toggle) to keep listener
            // counts honest. addEventHandler (NOT setOnHidden) so the stage's own
            // persistence-save WINDOW_HIDDEN handler is preserved -- setOnHidden
            // would clobber whichever handler was registered second.
            legendStage.getStage().addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
                if (controller != null) {
                    controller.uninstall();
                }
            });
        }
        if (legendStage.isShowing()) {
            legendStage.hide();
        } else {
            controller.install();
            legendStage.show();
            // First-show may have left geometry up to caller; ensure auto-fit
            // and centered when prefs were sentinels.
            if (ChannelNamesViewerPreferences.isSentinelGeometry()) {
                legendStage.getStage().sizeToScene();
                centerOnMainStage(qupath);
            }
        }
    }

    /**
     * Center the legend stage over QuPath's main window, then clamp to the visual
     * bounds of whichever screen contains the resulting center point. This handles
     * the case where the QuPath stage is partially off-screen (e.g. a multi-monitor
     * disconnect on a previous session): the legend would otherwise be placed at a
     * negative coordinate and become unreachable. If no screen contains the
     * computed center, fall back to the primary screen's visual bounds.
     */
    private void centerOnMainStage(QuPathGUI qupath) {
        var owner = qupath.getStage();
        var stage = legendStage.getStage();
        if (owner == null || !owner.isShowing()) {
            return;
        }
        double sw = stage.getWidth();
        double sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        double cx = owner.getX() + owner.getWidth() / 2.0;
        double cy = owner.getY() + owner.getHeight() / 2.0;
        double targetX = cx - sw / 2.0;
        double targetY = cy - sh / 2.0;

        // Find the screen that contains the centerpoint; fall back to primary.
        javafx.geometry.Rectangle2D bounds = null;
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            javafx.geometry.Rectangle2D vb = screen.getVisualBounds();
            if (vb.contains(cx, cy)) {
                bounds = vb;
                break;
            }
        }
        if (bounds == null) {
            bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        }

        // Clamp so the entire legend window is on-screen.
        double minX = bounds.getMinX();
        double minY = bounds.getMinY();
        double maxX = bounds.getMaxX() - sw;
        double maxY = bounds.getMaxY() - sh;
        double clampedX = Math.max(minX, Math.min(maxX, targetX));
        double clampedY = Math.max(minY, Math.min(maxY, targetY));

        stage.setX(clampedX);
        stage.setY(clampedY);
    }

    /**
     * Best-effort toolbar-button insertion. Walks the toolbar looking for the
     * brightness/contrast button by ControlsFX action identity; inserts a new
     * button at the position immediately following. Retries up to 10 times to
     * accommodate toolbar build sequencing on slow startups.
     */
    private void tryInsertToolbarButton(QuPathGUI qupath, int attempt) {
        ToolBar toolBar = qupath.getToolBar();
        if (toolBar == null) {
            logger.warn("Cannot inject Channel Names Viewer toolbar button: toolbar is null");
            return;
        }

        Action bcAction = brightnessContrastAction(qupath);
        if (bcAction == null) {
            if (attempt < 10) {
                Platform.runLater(() -> tryInsertToolbarButton(qupath, attempt + 1));
                return;
            }
            logger.warn("Brightness/Contrast action not found after {} attempts; "
                    + "skipping Channel Names Viewer toolbar button (menu + accelerator still work)",
                    attempt);
            return;
        }

        int index = findActionButtonIndex(toolBar, bcAction);
        if (index < 0) {
            if (attempt < 10) {
                Platform.runLater(() -> tryInsertToolbarButton(qupath, attempt + 1));
                return;
            }
            logger.warn("Brightness/Contrast toolbar button not found after {} attempts; "
                    + "skipping Channel Names Viewer toolbar button (menu + accelerator still work)",
                    attempt);
            return;
        }

        ButtonBase newButton = buildToolbarButton(qupath);
        toolBar.getItems().add(index + 1, newButton);
        logger.info("Inserted Channel Names Viewer toolbar button at index {}", index + 1);
    }

    private static Action brightnessContrastAction(QuPathGUI qupath) {
        try {
            CommonActions actions = qupath.getCommonActions();
            return actions != null ? actions.BRIGHTNESS_CONTRAST : null;
        } catch (Exception ex) {
            logger.debug("CommonActions not yet available: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Walk the toolbar and return the index of the {@link ButtonBase} whose
     * stored Action matches {@code action}. QuPath stores the action under
     * {@link ActionTools#getActionProperty(Node)} (the property key
     * {@code "qupath.lib.gui.actions.ActionTools"}), not under the raw
     * ControlsFX key the v1.0.0 design originally assumed. Returns -1 if not
     * found.
     */
    private static int findActionButtonIndex(ToolBar toolBar, Action action) {
        var items = toolBar.getItems();
        for (int i = 0; i < items.size(); i++) {
            ButtonBase b = findButton(items.get(i));
            if (b == null) {
                continue;
            }
            if (ActionTools.getActionProperty(b) == action) {
                return i;
            }
        }
        return -1;
    }

    private static ButtonBase findButton(Node node) {
        if (node instanceof ButtonBase b) {
            return b;
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                ButtonBase found = findButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ButtonBase buildToolbarButton(QuPathGUI qupath) {
        // Plain text glyph -- no font dependency. The button's tooltip carries the action language.
        javafx.scene.control.Button button = new javafx.scene.control.Button("Ch");
        button.setTooltip(new Tooltip(resources.getString("tooltip.toolbar")));
        button.setAccessibleText(resources.getString("tooltip.toolbar"));
        button.setOnAction(e -> toggleLegend(qupath));
        // Match QuPath's existing toolbar button sizing.
        button.getStyleClass().add("toolbar-button");
        return button;
    }
}
