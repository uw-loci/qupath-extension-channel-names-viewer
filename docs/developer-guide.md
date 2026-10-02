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

JDK 21 or newer is required; the build is tested on JDK 25 (Gradle 9.2.1 wrapper). To use a specific JDK, set `JAVA_HOME` or pass `-Dorg.gradle.java.home=/path/to/jdk` on the Gradle command line.

Run unit tests with `./gradlew test`. The tests do not start the JavaFX toolkit, so no extra JVM arguments are needed.

</details>

<details>
<summary><strong>Architecture overview</strong></summary>

All classes are under `qupath.ext.channelnamesviewer`.

## Entry point

- **`ChannelNamesViewerExtension`** — `QuPathExtension` entry point. Builds the **Extensions > Channel Names Viewer** submenu (the legend item, a separator, and the three tool items), binds the keyboard accelerator (`shortcut+shift+c`), and inserts the toolbar button next to Brightness/Contrast. Owns the singleton `ChannelLegendStage` and the three tool windows, each created on first use. The right-click handler on the toolbar button builds the stage lazily, so the menu can open without showing the legend.

## Legend

- **`core.ChannelLegendController`** — listener lifecycle. Binds to `imageDataProperty`, `viewerProperty`, and `imageDisplay.selectedChannels()`. Rebinds on image switch and viewer switch. Owns the empty-state branching (no image / RGB image / no selected channels) and channel ordering (`orderChannels`, for **Preserve channel order**).
- **`ui.ChannelLegendStage`** — the JavaFX `Stage` (`StageStyle.TRANSPARENT`, scene fill `Color.TRANSPARENT`). The root `StackPane` paints the rounded translucent fill (`rgba(0, 0, 0, opacity)` + `-fx-background-radius: 10`); the inner `content` `VBox` is transparent so it does not fight the root background. Owns the height-driven font binding (`clamp((height - 30) / rowCount * 0.7, 10pt, 72pt)`), edge/corner resize on all 8 sides via scene-level mouse handlers (8 px hot zone), drag-to-move, the right-click settings menu (`buildSettingsMenu`: a **Channel tools** section filled by the extension through `setToolItems`, then a **Legend window** section), the dark-channel options (BT.601 luminance below 0.5), and persistence of geometry / lock state / locked font pt / opacity.

## Channel tools

- **`core.BackgroundContrast`** — pure function from sampled pixel values to a display range. A full-range histogram (one bin per value for integer data that fits in 65,536 bins), a coarse pass over 2,048 bins to find the tallest peak, then a re-measurement on bins sized to the peak (never narrower than the data's own bins). Noise is the half width at half maximum of the peak's low side divided by sqrt(2 ln 2), measured from the peak's centroid; the high side is used when the peak sits against the lowest value. Falls back to percentiles when the peak is broad (noise above 0.2 × (99.9th percentile − background)) or the maximum would not exceed the minimum. A narrow peak with under 0.2% of pixels above the minimum is kept, with `empty` set.
- **`core.ChannelSampler`** — reads full-resolution pixels for every channel from a 6 × 6 grid of 170 px tiles (the whole image if it is smaller), at the given z and t, reporting progress per tile.
- **`core.TissueMask`** — finds off-tissue samples (empty glass, unscanned area) so they are left out of every channel's histogram. Each pixel's rank within each channel (from a 65,536-bin cumulative histogram) is taken; the median rank across channels is low only where a pixel is dark in nearly every channel. If the histogram of these medians has a peak below 0.4 with a valley above it lower than 0.35 × both peaks, pixels below the valley are excluded. All-tissue images get no mask; fewer than three channels, no mask.
- **`core.WheelColors`** — HSV and CIELAB wheel colors (D65 white, sRGB matrix and gamma; maximum in-gamut chroma per hue at a fixed L*; hue offset 40 degrees so red is near the top), spoke angles, and the spread order (greedy: each channel takes the free spoke farthest from the previous one). Ported from Sara McArdle's MIT-licensed Channel Color Chooser; see `THIRD-PARTY-NOTICES.md`.
- **`ui.AutoContrastWindow`**, **`ui.ColorWheelWindow`**, **`ui.ChannelGridWindow`** — the three tool windows. All are owned by the QuPath window and non-modal. The color wheel sets channel LUTs and calls `saveChannelColorProperties()` while you drag (which repaints the viewer), then writes the colors to the image metadata on release, as Brightness/Contrast does. The grid viewer paints each panel through the main viewer's `DefaultImageRegionStore.paintRegion` with its own `AbstractImageRenderer`, which calls `ImageDisplay.applyTransforms` for one channel (or a preset's own `DirectServerChannelInfo` copies) in the chosen mode. The renderer's change timestamp adds a local counter for grayscale and preset changes, because the region store caches rendered tiles by `getUniqueID()`. The grid polls the project's `resources/display` folder every 2 seconds for preset changes; QuPath fires no event for them.
- **`ui.Tooltips`** — tooltip helpers. JavaFX menu items have no tooltip property, so `Tooltips.on` stores the text on the item and `Tooltips.install` installs it on the item's node each time its menu is shown. Also provides the bold section headings and the tooltip with a picture (`histogram-legend.png`).

## Preferences

- **`preferences.ChannelNamesViewerPreferences`** — legend settings, prefix `channelnamesviewer.`: `windowX`, `windowY`, `windowWidth`, `windowHeight` (sentinel `-1.0` meaning "no saved value"), `fontLocked`, `lockedFontPt` (default 20.0), `backgroundOpacity` (default 0.75), `preserveChannelOrder` (default true), `whiteTextOutline` and `darkLabelPanel` (default false).
- **`preferences.ChannelToolsPreferences`** — tool settings, prefix `channelnamesviewer.tools.`: `noiseMultiple` (3.0), `saturatedPercent` (0.5), `allChannels`; `wheelMode` (HSV), `wheelValue` (1.0), `wheelLightness` (65), `wheelSpread`; and the grid's `grid.panels`, `grid.sync`, `grid.downsample`, `grid.merged`, `grid.grayscale`, `grid.names`, `grid.cursor`, `grid.overlays`.

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

The button's graphic is three rounded bars (`buildChannelIcon`; red, green and blue on the light theme, cyan, magenta and yellow on the dark theme). The right-click triangle is a ControlsFX `GraphicDecoration` at `BOTTOM_RIGHT` (a 6 px triangle at opacity 0.5), re-applied when the button's scene changes. This mirrors `ToolBarComponent#addContextMenuDecoration` in QuPath, which draws the same affordance on its line / polyline tool button.

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
- **Active image is RGB** (`imageData.getServer().isRGB() == true`): headline *No fluorescence channels*, subtitle *(this image is RGB; channels do not apply)*.
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

- **`ChannelLegendControllerTest`**: listener attach/detach across image and viewer switches; channel ordering with **Preserve channel order** on and off; names keep their literal channel color.
- **`ChannelNamesViewerPreferencesTest`**: preference sentinels and round-trips.
- **`BackgroundContrastTest`**: background peak and noise on synthetic data, the noise multiple, the dense-stain fallback, a channel with no signal, the zero-padding spike, background clipped at zero, float data, and low noise beside very bright signal.
- **`WheelColorsTest`**: spoke spacing, HSV primaries, equal CIELAB lightness across hues, and the spread order.
- **`TissueMaskTest`**: glass dark in every channel is excluded, the background is then measured on tissue, all-tissue images are left alone, negative cells in one channel are not taken for glass, and fewer than three channels get no mask.

The tests do not start the JavaFX toolkit and need no running QuPath instance.

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

Releases are listed in the [`uw-loci/qupath-catalog-mikenelson`](https://github.com/uw-loci/qupath-catalog-mikenelson) extension catalog. This repository has no `notify-catalog` workflow, so after each release, prepend the new release to that catalog's `catalog.json` by hand, keeping every earlier entry so installed versions can still be matched for updates. Set `version_range.min` to `v0.7.0`, the version the extension requires.

</details>
