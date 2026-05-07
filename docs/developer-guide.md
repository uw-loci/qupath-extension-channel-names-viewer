# Channel Names Viewer — Developer Guide

This guide is for developers who want to build the extension from source, contribute code, or understand how the binding lifecycle works.

<details>
<summary><strong>Building from source</strong></summary>

```bash
git clone https://github.com/uw-loci/qupath-extension-channel-names-viewer
cd qupath-extension-channel-names-viewer
./gradlew shadowJar
```

The output jar is at `build/libs/qupath-extension-channel-names-viewer-{version}-all.jar`.

JDK 21 is required. If your default JDK is newer or older, set `JAVA_HOME` or pass `-Dorg.gradle.java.home=/path/to/jdk21` on the gradle command line.

Run unit tests with `./gradlew test`. Tests run on JavaFX; CI / headless invocations need the standard `--add-modules` and `-Dprism.order=sw` JVM args called out in the team's `REFERENCES.md` Part B.

</details>

<details>
<summary><strong>Architecture overview</strong></summary>

Three top-level classes plus one preferences class, all under `qupath.ext.channelnamesviewer`:

- **`ChannelNamesViewerExtension`** — `QuPathExtension` entry point. Registers the menu item under `Extensions`, binds the keyboard accelerator (`shortcut+shift+c`), and inserts the toolbar button next to brightness/contrast. The toolbar button uses a `StackPane` graphic (`"Ch"` label + a small right-pointing `Path` triangle in the bottom-right corner) to signal that right-click reveals the settings menu, mirroring QuPath's tool-button convention. Owns the singleton `ChannelLegendStage` instance and toggles its visibility on each launch-surface activation; the right-click handler on the toolbar button lazily constructs the stage so the menu can be opened without showing the window.
- **`core.ChannelLegendController`** — listener lifecycle. Binds to `imageDataProperty`, `viewerProperty`, and `imageDisplay.selectedChannels()`. Rebinds on image switch and viewer switch. Owns the empty-state branching (no image / RGB image / no selected channels). Renders the channel rows into the stage's content `VBox`.
- **`ui.ChannelLegendStage`** — the JavaFX `Stage` (`StageStyle.TRANSPARENT`, scene fill `Color.TRANSPARENT`). The root `StackPane` paints the rounded translucent fill (`rgba(0, 0, 0, opacity)` + `-fx-background-radius: 10`); the inner `content` `VBox` is explicitly transparent so it does not fight the root background. Owns the height-driven font binding (`clamp((height - 30) / rowCount * 0.7, 10pt, 72pt)`), edge/corner resize on all 8 sides via scene-level mouse handlers (8 px hot zone), drag-to-move on the body, the right-click context menu, and persistence of geometry / lock state / locked font pt / opacity.
- **`preferences.ChannelNamesViewerPreferences`** — `DoubleProperty` / `BooleanProperty` keys for `windowX`, `windowY`, `windowWidth`, `windowHeight` (sentinel `-1.0` meaning "no saved value"), `fontLocked` (boolean), `lockedFontPt` (double, default 20.0), and `backgroundOpacity` (double, default 0.75). Pattern source: `qupath-extension-confusion-matrix/preferences/CMPreferences.java`.

A machine-readable `codemap/codemap.json` is generated for v1.0 and committed to the repo. Use it to navigate dependencies between subpackages.

</details>

<details>
<summary><strong>Listener lifecycle</strong></summary>

The controller attaches a `ListChangeListener<ChannelDisplayInfo>` to `imageDisplay.selectedChannels()` on the active viewer's `ImageDisplay`. Three triggers cause the binding to be rebuilt:

1. The active image changes (`qupath.imageDataProperty()` fires).
2. The active viewer changes (`qupath.viewerProperty()` fires — relevant in multi-viewer split-pane layouts).
3. The window is closed and reopened.

On every rebuild the previous binding is torn down (the listener is removed from the previous `ImageDisplay`'s observable list), then a new binding is installed against the current active display. Failing to remove the listener leaks one stale listener per image switch — subtle, accumulates fast in long sessions. The unit test for this counts attached listeners after N image switches and asserts the count stays at one.

The listener instance is stored in a field, not constructed inline at registration time, so removal can find it. The controller uses a strong reference rather than a `WeakListChangeListener`: the controller object is owned by the stage and lives exactly as long as the window, so a strong reference is correct and predictable.

</details>

<details>
<summary><strong>Font-binding pattern</strong></summary>

JavaFX `Font` is not directly bindable to a `DoubleProperty`, so we go through one indirection. Two stacked bindings drive font size: a `dynamicSize` derived from window height + row count, and a `clampedSize` that returns the locked value or the dynamic value depending on the lock toggle.

```java
DoubleBinding dynamicSize = Bindings.createDoubleBinding(
    () -> {
        double avail = stage.getHeight() - VERTICAL_OVERHEAD_PX; // 30 px
        if (avail <= 0) return MIN_FONT_PT;
        int rows = Math.max(rowCount.get(), 1);
        double raw = (avail / rows) * ROW_HEIGHT_FACTOR; // 0.7
        return Math.max(MIN_FONT_PT, Math.min(MAX_FONT_PT, raw));
    },
    stage.heightProperty(), rowCount);

DoubleBinding clampedSize = Bindings.createDoubleBinding(
    () -> fontLocked.get() ? lockedFontPt.get() : dynamicSize.get(),
    fontLocked, lockedFontPt, dynamicSize);

label.fontProperty().bind(Bindings.createObjectBinding(
    () -> Font.font(clampedSize.get()),
    clampedSize));
```

`MIN_FONT_PT = 10.0`, `MAX_FONT_PT = 72.0`, `VERTICAL_OVERHEAD_PX = 30.0`, `ROW_HEIGHT_FACTOR = 0.7`. The height-driven formula was chosen over `min(width, height) / 8` because horizontal width tracks longest channel name, not number of rows — scaling font with width meant a long channel name forced a comically tall window. JavaFX coalesces height pulse updates so a fast drag produces at most one font rebuild per layout pulse.

`stage.setMinWidth(120)` and `stage.setMinHeight(60)` enforce the floor; the resize handler in `applyResize` clamps to those values and pins stage origin when shrinking from the W/N/NW/SW/NE edges so the window edge under the cursor stays put.

When the user opens the window with **Lock font size** off, `applyDefaultGeometryIfUnlocked()` sizes the stage to fit the longest channel name × current row count at QuPath's `PathPrefs.locationFontSizeProperty()` value, mapped to pt via a switch (TINY=10, SMALL=12, MEDIUM=14, LARGE=18, HUGE=24). Reading the live preference rather than copying its CSS value keeps the legend in sync with user preferences.

</details>

<details>
<summary><strong>Toolbar button injection</strong></summary>

The button is inserted by walking `qupath.getToolBar().getItems()`, finding the `ButtonBase` whose `ActionTools.getActionProperty(node)` reference-equals `CommonActions.BRIGHTNESS_CONTRAST`, and inserting a fresh `Button` at `index + 1`. The lookup is wrapped in `Platform.runLater` twice to defer until after QuPath finishes its toolbar build at startup. (v1.0.0 mistakenly read the raw `"controlsfx.actions.action"` properties key, which is *not* what `ActionTools` writes; the lookup found nothing and the button never appeared. v1.0.1 switched to `ActionTools.getActionProperty`.)

The `Action`-reference identity check is locale-stable; tooltip-text matching is fragile because the brightness/contrast button's tooltip is resource-bundle-driven and varies by locale. Reference precedents for the toolbar-mutation pattern: `qupath-extension-wizard-wand/WizardWandExtension.java` and `qupath-extension-polyline-wand/PolylineWandExtension.java`.

The button's graphic is a `StackPane` containing a `Label("Ch")` centered and a small right-pointing `Path` triangle (5 px, 0.55 opacity, fill bound to `button.textFillProperty()` so it tracks light / dark themes) anchored bottom-right. This mirrors the affordance QuPath itself uses on its line / polyline tool button (see `ToolBarComponent#addContextMenuDecoration`); we picked an inline `StackPane` graphic over the ControlsFX `Decorator` API used there because the `Decorator` path requires juggling scene-listener and graphic-property listeners to keep the decoration stable across `setGraphic` calls. With everything baked into the graphic itself the decoration cannot drift.

The right-click handler on the button calls `legendStage.buildSettingsMenu()` and shows it anchored to the button — without showing the legend window itself. The legend stage is constructed lazily on first right-click if the user has not yet opened the legend.

If the lookup fails (a future QuPath release may reorganize the toolbar build sequence) the failure path is silent: the extension logs a WARN naming the heuristic and skips the button injection. The menu item and keyboard shortcut are unaffected.

</details>

<details>
<summary><strong>Stage style</strong></summary>

The window uses `StageStyle.TRANSPARENT` (v1.0.3+). v1.0.0–1.0.2 shipped `StageStyle.UTILITY` for free native title bar / resize chrome; user feedback that the chrome diverged too far from the original Groovy aesthetic prompted the switch. The TRANSPARENT path requires us to own three things the OS would otherwise provide:

1. **Background paint.** `Scene.setFill(Color.TRANSPARENT)` makes the scene transparent, but JavaFX's modena.css applies an opaque `.root` background-color that defeats it. The fix is an inline `setStyle("-fx-background-color: rgba(0, 0, 0, %.3f); -fx-background-radius: 10; -fx-background-insets: 0;")` on the root `StackPane`. v1.0.3 painted the rgba background on the inner content `VBox` instead; the StackPane's modena gray remained behind it, so the opacity slider only changed the apparent brightness of that gray. v1.0.4 moved the paint to the root, giving real transparency.
2. **Drag-to-move.** Scene-level `setOnMousePressed` / `setOnMouseDragged`. The press handler captures `stage.getX/Y - event.getScreenX/Y` (modeled on the original Groovy `MoveablePaneHandler`); the drag handler updates `stage.setX/Y`. Children inside `content` (Labels, etc.) are explicitly `setMouseTransparent(true)` so events always reach the scene-level handler.
3. **Edge/corner resize.** Scene-level `setOnMouseMoved` detects whether the cursor is within `EDGE_RESIZE_HOTSPOT_PX` (8 px) of any edge or corner and updates `scene.setCursor(...)` to the corresponding `Cursor.X_RESIZE`. Press initiates a resize with `resizingFrom = ResizeEdge.{N,S,E,W,NE,NW,SE,SW}`; `applyResize` computes new width / height / origin (origin shifts for W / N / NW / SW / NE edges where shrinking the stage means moving its top-left corner). Min-width / min-height clamps pin the origin so the cursor-side edge stays under the mouse.

Linux compositors that lack a compositor-side alpha channel (some pure-X11 setups) may render TRANSPARENT stages as solid black. There is no programmatic detection; if a user reports this we add a hidden preference fallback to `StageStyle.UTILITY`. As of v1.0.4 no such reports have surfaced.

</details>

<details>
<summary><strong>Empty-state handling</strong></summary>

The controller renders one of three empty-state placeholders when no channels are available:

- **No image is open** (`viewer.getImageData() == null`): headline *No fluorescence channels*, subtitle *(open an image to see its channels)*.
- **Active image is RGB** (`imageData.getServer().isRGB() == true`) or has no channels selected: headline *No fluorescence channels*, subtitle *(image is RGB or has no channels selected)*.
- **Image is fluorescence with zero channels selected**: headline *No fluorescence channels*, subtitle *(open Brightness/Contrast and select channels)*.

All three use the same font-binding as channel rows. Subtitles use a slightly smaller multiplier (`fontSize * 0.65`) for visual hierarchy. Light-gray text (`rgb(180, 180, 180)`) on the dark background, not the channel-color logic.

The window does not auto-close in the empty state — the user opened it deliberately and may switch to a fluorescence image next.

</details>

<details>
<summary><strong>Scripting API</strong></summary>

**v1.0 ships no scripting API.** This is intentional, not an oversight.

The legend window is a GUI affordance with no headless equivalent. The original Groovy script *is* the scripting equivalent for users who want to invoke the same effect from a script — see [Sara McArdle's `FluorescentChannelNames.groovy`](https://github.com/saramcardle/Image-Analysis-Scripts/blob/master/QuPath%20Groovy%20Scripts/FluorescentChannelNames.groovy).

If a future contributor identifies a use case for a `ChannelNamesViewerScripts.show()` / `.hide()` static facade — for example, programmatically opening the window as part of a project-wide setup — a small `scripting/` subpackage can be added in a v1.x release. Until then, no public API is exposed.

</details>

<details>
<summary><strong>Running tests</strong></summary>

```bash
./gradlew test
```

Tests cover:

- **Listener cleanup**: count of listeners attached to `imageDisplay.selectedChannels()` after N image switches stays at one.
- **Font binding**: clamp at minimum and maximum bounds; `min(width, height)` aspect handling.
- **Channel rendering**: against a mock `ImageDisplay` with a known set of `ChannelDisplayInfo` instances; verifies row count, label text, and text fill (channel display color or white-fallback for low-luminance channels).
- **Empty-state branching**: each of the three subtitles is rendered for the corresponding cause.

Tests are pure JavaFX with a mocked QuPath surface — no running QuPath instance required. JVM args for headless JavaFX (per `REFERENCES.md` Part B) are configured in `build.gradle.kts`.

</details>

<details>
<summary><strong>Contributing</strong></summary>

For substantial changes, open a [GitHub issue](https://github.com/uw-loci/qupath-extension-channel-names-viewer/issues) first so we can discuss scope before you write code. Smaller fixes can go straight to a pull request.

For general support and feature discussion, post on the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag and mention `@Mike_Nelson`.

Pull requests should:

- Match the project's code style (run `./gradlew check` before pushing).
- Include unit tests for any logic in `core` (listener lifecycle, empty-state branching). UI changes do not require tests.
- Keep ASCII-only in `LOGGER.*` calls, exception messages, internal strings, and paths. Channel names themselves are user-supplied data and may contain non-ASCII characters; the UI must render them, but they must not be concatenated into log lines without sanitization. Unicode is allowed in JavaFX user-visible labels and in Markdown documentation.
- Use `qupath.fx.dialogs.Dialogs`, never the deprecated `qupath.lib.gui.dialogs.Dialogs`.

</details>

<details>
<summary><strong>Releasing</strong></summary>

Tag conventions: `v{major}.{minor}.{patch}` (for example, `v1.0.0`). The maintainer cuts releases.

Release artifact: `build/libs/qupath-extension-channel-names-viewer-{version}-all.jar` from `./gradlew shadowJar`. Attach to the GitHub Release.

This extension is distributed as a manual jar drop via GitHub Releases, not through a QuPath extension catalog.

</details>
