package qupath.ext.channelnamesviewer.ui;

import javafx.event.Event;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

/**
 * Tooltips, including on menu items, which JavaFX gives no tooltip property: the text is
 * stored on the item and installed on its node each time its menu is shown.
 */
public final class Tooltips {

    private static final String KEY = "channelnamesviewer.tooltip";

    private Tooltips() {
    }

    /** A wrapping tooltip that stays up long enough to read. */
    public static Tooltip of(String text) {
        var tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        tip.setShowDuration(Duration.seconds(30));
        return tip;
    }

    /** A tooltip showing a picture above its text. */
    public static Tooltip withImage(String text, String resource) {
        var tip = of(text);
        var url = Tooltips.class.getResource(resource);
        if (url != null) {
            var view = new ImageView(new Image(url.toExternalForm()));
            tip.setGraphic(view);
            tip.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
            tip.setMaxWidth(view.getImage().getWidth() + 30);
        }
        return tip;
    }

    /** Attach tooltip text to a menu item; shown once the menu goes through {@link #install}. */
    public static <T extends MenuItem> T on(T item, String text) {
        item.getProperties().put(KEY, text);
        return item;
    }

    /** A bold, non-clickable heading for a section of a menu. */
    public static MenuItem heading(String text) {
        var label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-mid-text-color;");
        var item = new CustomMenuItem(label, false);
        item.getStyleClass().add("channel-tools-heading");
        return item;
    }

    /** Show the tooltips of a context menu's items, and of its submenus', when it opens. */
    public static ContextMenu install(ContextMenu menu) {
        menu.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> installOn(menu.getItems()));
        return menu;
    }

    /** As {@link #install(ContextMenu)}, for a menu in a menu bar or a submenu. */
    public static Menu install(Menu menu) {
        menu.addEventHandler(Menu.ON_SHOWN, e -> installOn(menu.getItems()));
        return menu;
    }

    private static void installOn(java.util.List<MenuItem> items) {
        for (var item : items) {
            Object text = item.getProperties().get(KEY);
            var node = item instanceof CustomMenuItem custom ? custom.getContent() : item.getStyleableNode();
            if (text != null && node != null) {
                Tooltip.install(node, of(text.toString()));
            }
            if (item instanceof Menu sub && !sub.getProperties().containsKey(KEY + ".installed")) {
                sub.getProperties().put(KEY + ".installed", true);
                sub.addEventHandler(Menu.ON_SHOWN, (Event e) -> installOn(sub.getItems()));
            }
        }
    }
}
