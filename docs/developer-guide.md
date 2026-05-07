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

- **`ChannelNamesViewerExtension`** — `QuPathExtension` entry point. Registers the menu item under `Extensions`, binds the keyboard accelerator (`shortcut+shift+c`), and inserts the toolbar button next to brightness/contrast. Owns the singleton `ChannelLegendStage` instance and toggles its visibility on each launch-surface activation.
- **`core.ChannelLegendController`** — listener lifecycle. Binds to `imageDataProperty`, `viewerProperty`, and `imageDisplay.selectedChannels()`. Rebinds on image switch and viewer switch. Owns the empty-state branching (no image / RGB image / no selected channels). Renders the channel rows into the stage's content `VBox`.
- **`ui.ChannelLegendStage`** — the JavaFX `Stage` (`StageStyle.UTILITY`). Owns the resize-binding (font size = `clamp(min(width, height) / 8, 10pt, 72pt)`) and the position/size persistence wiring.
- **`preferences.ChannelNamesViewerPreferences`** — four `DoubleProperty` keys (windowX, windowY, windowWidth, windowHeight) using sentinel `-1` for "no saved value." Pattern source: `qupath-extension-confusion-matrix/preferences/CMPreferences.java`.

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

JavaFX `Font` is not directly bindable to a `DoubleProperty`, so we go through one indirection:

```java
DoubleBinding rawSize = Bindings.min(stage.widthProperty(), stage.heightProperty()).divide(8.0);
DoubleBinding fontSize = Bindings.createDoubleBinding(
    () -> Math.max(MIN_FONT_PT, Math.min(MAX_FONT_PT, rawSize.get())),
    rawSize);

label.fontProperty().bind(Bindings.createObjectBinding(
    () -> Font.font(fontSize.get()),
    fontSize));
```

`MIN_FONT_PT = 10.0` and `MAX_FONT_PT = 72.0`. Divisor `8.0` produces ~18pt at the auto-sized first-show window and scales naturally from there. JavaFX coalesces width/height pulse updates so a fast drag produces at most one font rebuild per layout pulse — no performance concern.

`stage.setMinWidth(120)` and `stage.setMinHeight(60)` enforce the floor; without them the native UTILITY chrome can be dragged below the size at which the font clamp can produce a legible result.

</details>

<details>
<summary><strong>Toolbar button injection</strong></summary>

The button is inserted by walking `qupath.getToolBar().getItems()`, finding the `ButtonBase` whose `getProperties().get("controlsfx.actions.action")` reference-equals the brightness/contrast `Action` instance, and inserting a fresh `Button` at `index + 1`. The lookup is wrapped in `Platform.runLater` twice to defer until after QuPath finishes its toolbar build at startup.

The `Action`-reference identity check is locale-stable; tooltip-text matching is fragile because the brightness/contrast button's tooltip is resource-bundle-driven and varies by locale. Reference precedents for the toolbar-mutation pattern: `qupath-extension-wizard-wand/WizardWandExtension.java` and `qupath-extension-polyline-wand/PolylineWandExtension.java`.

If the lookup fails (a future QuPath release may reorganize the toolbar build sequence) the failure path is silent: the extension logs a WARN naming the heuristic and skips the button injection. The menu item and keyboard shortcut are unaffected.

</details>

<details>
<summary><strong>Stage style</strong></summary>

The window uses `StageStyle.UTILITY` on every platform. This gives a thin native title bar — used for drag-to-move — and native edge/corner resize chrome. The original Groovy script used `StageStyle.TRANSPARENT` plus a custom `MoveablePaneHandler`; we evaluated that path and concluded the operational cost (custom corner-grabber, Wayland transparent-stage rendering edge cases, HiDPI grabber sizing) outweighs the aesthetic gain. The trade-off is documented in `02_design.md` section 4 of this feature's design folder.

If a future v1.x reverts to `TRANSPARENT`, plan to add: a custom corner-grabber node (16x16 px in the bottom-right, `Cursor.SE_RESIZE` on hover, `setOnMousePressed` / `setOnMouseDragged` adjusting `stage.setWidth` / `setHeight`); a `MoveablePaneHandler`-style drag-anywhere handler on the root pane; and a Wayland-fallback hidden preference.

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

Catalog wiring uses `.github/workflows/notify-catalog.yml` to dispatch `repository_dispatch: extension-release` to `qupath-catalog-mikenelson`. The workflow needs the org-level `CATALOG_DISPATCH_TOKEN` secret. After the first release on this repo, verify (1) the workflow ran, (2) the catalog received an `Auto-bump qupath-extension-channel-names-viewer -> {tag}` commit within ~1 minute, and (3) the new entry in the catalog's `catalog.json` matches the published asset. The catalog workflow also exposes `workflow_dispatch` for manual recovery.

Per the root `CLAUDE.md` policy, every release verification should also confirm a fresh catalog install pulls the new jar — older catalog setups have served stale jars when the dispatch hook was missing.

</details>
