package qupath.ext.channelnamesviewer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import org.controlsfx.control.action.Action;
import org.controlsfx.control.decoration.Decorator;
import org.controlsfx.control.decoration.GraphicDecoration;
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
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

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
            // Right-click on the window body opens the same settings menu as the toolbar button.
            legendStage.installContextMenuOnBody();
        }
        if (legendStage.isShowing()) {
            legendStage.hide();
        } else {
            controller.install(); // populates content via renderChannels / renderEmptyState
            // v1.0.2: when the font is NOT locked, ignore any saved geometry and
            // size the window fresh from the current channel set + QuPath's
            // "Location text font size" preference. When the font IS locked,
            // the stage's applyFirstShowGeometry restores the saved geometry.
            boolean unlocked = !ChannelNamesViewerPreferences.getFontLocked();
            if (unlocked) {
                legendStage.applyDefaultGeometryIfUnlocked();
            }
            legendStage.show();
            if (unlocked) {
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

    /**
     * Light-theme bar palette: red / green / blue. Vivid against QuPath's
     * light toolbar background.
     */
    private static final Color[] LIGHT_BARS = {
            Color.web("#cc0000"), Color.web("#00b400"), Color.web("#0a0acc")
    };

    /**
     * Dark-theme bar palette: cyan / magenta / yellow. Vivid against QuPath's
     * dark toolbar background; mirrors the supplied {@code Chext_clear.svg}.
     */
    private static final Color[] DARK_BARS = {
            Color.web("#33ffff"), Color.web("#ff33ff"), Color.web("#ffff33")
    };

    private ButtonBase buildToolbarButton(QuPathGUI qupath) {
        // Vector graphic -- no font and no image asset, so it stays crisp at any
        // display scale. The button's tooltip carries the action language.
        Button button = new Button();
        button.setTooltip(new Tooltip(resources.getString("tooltip.toolbar")));
        button.setAccessibleText(resources.getString("tooltip.toolbar"));
        button.setOnAction(e -> toggleLegend(qupath));
        // Match QuPath's existing toolbar button sizing.
        button.getStyleClass().add("toolbar-button");

        // Graphic = three rounded "channel" bars (a stylized stack of selected
        // channels). The bar palette follows the active QuPath theme -- see
        // buildChannelIcon.
        button.setGraphic(buildChannelIcon(button));

        // The small right-click indicator triangle is added as a ControlsFX
        // GraphicDecoration anchored to the button's bottom-right CORNER -- the
        // same mechanism QuPath uses for its Line/Polyline tool buttons, so the
        // triangle sits at the button edge rather than floating inside the icon.
        addContextMenuDecoration(qupath, button);

        // Right-click anywhere on the button also opens the legend window's
        // settings menu (background opacity, lock-font toggle, reset).
        button.setOnContextMenuRequested(e -> {
            showSettingsMenu(qupath, button, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return button;
    }

    /**
     * Add the right-click indicator triangle to the toolbar button as a
     * ControlsFX {@link GraphicDecoration} anchored at {@link Pos#BOTTOM_RIGHT}.
     * This mirrors QuPath's own {@code ToolBarComponent.addContextMenuDecoration}
     * (geometry, rotation and opacity copied verbatim) so the triangle sits at
     * the button corner -- clear of the icon and flush with the button edge --
     * rather than floating inside the icon graphic.
     *
     * <p>ControlsFX decorations require the node to be in a scene, and are lost
     * when it leaves one, so the decoration is (re-)applied via a
     * {@code sceneProperty} listener as well as eagerly on the FX thread.</p>
     */
    private void addContextMenuDecoration(QuPathGUI qupath, Button button) {
        double width = 6;
        Path triangle = new Path(
                new MoveTo(0, 0),
                new LineTo(width, 0),
                new LineTo(width / 2.0, Math.sqrt(width * width / 2.0)),
                new ClosePath());
        triangle.setTranslateX(-width);
        triangle.setTranslateY(-width);
        triangle.setRotate(-90);
        triangle.setStroke(null);
        triangle.setOpacity(0.5);
        triangle.fillProperty().bind(button.textFillProperty());
        triangle.setOnMouseClicked(e -> {
            showSettingsMenu(qupath, button, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        GraphicDecoration decoration = new GraphicDecoration(triangle, Pos.BOTTOM_RIGHT);
        button.sceneProperty().addListener((obs, oldScene, newScene) -> Platform.runLater(() -> {
            if (newScene != null) {
                Decorator.addDecoration(button, decoration);
            } else {
                Decorator.removeDecoration(button, decoration);
            }
        }));
        Platform.runLater(() -> Decorator.addDecoration(button, decoration));
    }

    /**
     * Lazily create the legend stage and controller if they do not yet exist,
     * then show the legend's settings menu at the given screen coordinates.
     */
    private void showSettingsMenu(QuPathGUI qupath, Button button, double screenX, double screenY) {
        // We don't show the legend here, just need the stage so its property
        // values back the menu. Lazily create on first menu open.
        if (legendStage == null) {
            legendStage = new ChannelLegendStage(qupath.getStage());
            controller = new ChannelLegendController(qupath, legendStage);
            legendStage.getStage().addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, ev -> {
                if (controller != null) {
                    controller.uninstall();
                }
            });
            legendStage.installContextMenuOnBody();
        }
        legendStage.buildSettingsMenu().show(button, screenX, screenY);
    }

    /**
     * Build the toolbar button's icon: three rounded horizontal bars stacked
     * vertically, evoking a stack of selected fluorescence channels. The bar
     * fills follow the active QuPath theme -- {@link #LIGHT_BARS} (RGB) on the
     * light theme, {@link #DARK_BARS} (CMY) on the dark theme.
     *
     * <p>Theme detection uses the button's text fill: QuPath drives that color
     * from theme CSS, so a light text fill reliably indicates the dark theme.
     * The fill is not resolved until the button is in a scene and CSS has run,
     * so a listener re-applies the palette whenever the text fill changes --
     * this also handles the user switching themes mid-session.</p>
     *
     * @param button the toolbar button whose text fill tracks the theme
     * @return a mouse-transparent {@link Node} suitable as the button graphic
     */
    private static Node buildChannelIcon(Button button) {
        Rectangle[] bars = new Rectangle[3];
        VBox stack = new VBox(2.2);
        stack.setAlignment(Pos.CENTER);
        stack.setMouseTransparent(true);
        for (int i = 0; i < bars.length; i++) {
            Rectangle bar = new Rectangle(14.5, 3.6);
            bar.setArcWidth(2.6);
            bar.setArcHeight(2.6);
            bar.setMouseTransparent(true);
            bars[i] = bar;
            stack.getChildren().add(bar);
        }
        Runnable applyPalette = () -> {
            Color[] palette = isDarkTheme(button) ? DARK_BARS : LIGHT_BARS;
            for (int i = 0; i < bars.length; i++) {
                bars[i].setFill(palette[i]);
            }
        };
        applyPalette.run();
        button.textFillProperty().addListener((obs, oldFill, newFill) -> applyPalette.run());
        return stack;
    }

    /**
     * Return true when the supplied button is being rendered under a dark
     * theme, judged by the luminance of its (theme-driven) text fill. A light
     * text fill means a dark background.
     */
    private static boolean isDarkTheme(Button button) {
        Paint fill = button.getTextFill();
        if (fill instanceof Color c) {
            // BT.601 luminance.
            double luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            return luminance > 0.5;
        }
        return false;
    }
}
