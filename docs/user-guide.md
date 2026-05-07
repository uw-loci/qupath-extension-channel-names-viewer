# Channel Names Viewer — User Guide

This guide walks through the legend window one task at a time. Sections are collapsible; expand the ones you need.

<details open>
<summary><strong>Getting started</strong> (read this first)</summary>

### Prerequisites

- A multiplex or fluorescence image is open in QuPath. The window mirrors the channel display, so it has nothing to show until an image with channels is loaded.
- For brightfield / RGB images the window opens with an empty-state placeholder rather than a crash. You can leave it open while you switch to a fluorescence image — the legend will populate on the next image load.

### First-run mental model

The window shows what QuPath's brightness/contrast dialog calls the *selected* channels — the ones currently contributing to the viewer. Toggle a channel in brightness/contrast and the legend updates immediately. You do not interact with the legend itself except to move, resize, or close it; there is no menu, no settings cog, no in-window control.

### Three ways to open the window

The launch surfaces, with their tooltip text:

- **Toolbar button** (next to brightness/contrast): *Toggle the Channel Names viewer (Ctrl+Shift+C).*
- **Menu item** (`Extensions > Channel Names Viewer...`): *Toggle a small always-visible legend showing currently selected fluorescence channels.*
- **Keyboard shortcut**: `Ctrl+Shift+C` on Linux and Windows, `Cmd+Shift+C` on macOS.

All three surfaces toggle: clicking when the window is showing closes it. Pressing the shortcut a second time has the same effect.

### Reading the legend

Each row in the window is one currently-selected channel. Channel names are drawn in their display colors when the color has enough contrast against the dark window background; otherwise the name is drawn in white. Channel order matches the order in the brightness/contrast dialog.

The contrast check uses WCAG relative luminance (the standard accessibility formula), not perceived brightness. As a result, some bright but low-luminance colors fall into the white-fallback bucket — saturated blues are the common case, since blue contributes the least to perceived luminance even at full saturation. If you picked DAPI as pure blue in brightness/contrast, expect the legend to render that name in white rather than blue. This is intentional, not a bug; the alternative is unreadable blue-on-near-black text. Pick a slightly desaturated or lighter blue in brightness/contrast if you want the channel-color fidelity in the legend.

The window auto-sizes on first show to fit the longest channel name comfortably, then remembers its size for the rest of the session and across QuPath restarts.

</details>

<details>
<summary><strong>Open and close the window</strong></summary>

### Three launch surfaces

The toolbar button, menu item, and keyboard shortcut all do the same thing: toggle the legend window — open it if it is closed, close it if it is open. Hovering each surface shows its tooltip:

- Toolbar button tooltip: *Toggle the Channel Names viewer (Ctrl+Shift+C).* The button sits immediately to the right of QuPath's brightness/contrast button.
- Menu item: **Extensions > Channel Names Viewer...** Tooltip: *Toggle a small always-visible legend showing currently selected fluorescence channels.*
- Keyboard shortcut: `Ctrl+Shift+C` (`Cmd+Shift+C` on macOS). The shortcut is registered globally inside QuPath; it works whether the legend has focus or not.

### Closing

The window can be closed in any of these equivalent ways:

- Press the keyboard shortcut again.
- Click the toolbar button or menu item again (they toggle).
- Double-click anywhere on the body of the window — the body tooltip is *Drag title bar to move. Double-click to close.*
- Press **Esc** while the window has focus.
- Use the native window-close affordance in the title bar.

### What if I open it twice?

Re-opening the window when it is already visible brings it to the front rather than spawning a duplicate. There is only ever one legend window per QuPath session.

### Toolbar button missing on some installs

The toolbar button is best-effort. The extension finds QuPath's brightness/contrast button at install time and inserts itself immediately after. If a future QuPath release reorganizes the toolbar build sequence the button may not appear, in which case the menu item and keyboard shortcut continue to work normally. See the **Troubleshooting** section for diagnostic detail.

</details>

<details>
<summary><strong>Switch images while the window is open</strong></summary>

### Automatic rebinding

You do not need to close and reopen the legend when you switch images. Open a different image in QuPath and the window detaches from the previous image's channel display, attaches to the new image's display, and re-renders. This works whether you switch via **File > Open**, the project pane, or by selecting a different viewer in a multi-viewer split-pane layout.

### What you see for a non-fluorescence image

If the new image is brightfield, RGB, or otherwise has no fluorescence channels, the window does not close. It shows the empty-state placeholder. The headline is always *No fluorescence channels*; the subtitle changes depending on the cause:

- **No image is open**: *(open an image to see its channels)*
- **The active image is RGB / brightfield**: *(this image is RGB; channels do not apply)*
- **The image is fluorescence but you have no channels selected**: *(open Brightness/Contrast and select channels)*

Switching to a fluorescence image, or selecting a channel in brightness/contrast, makes the legend populate immediately — no need to relaunch.

### What "rebinding" means under the hood

The window listens to QuPath's notion of "the active image" rather than to a specific image. Whenever the active image changes, the window's connection to channel-display data is rebuilt. This is transparent to you, but it is the reason the legend keeps working across image switches in v1.0; the original Groovy script does not handle this case and breaks when you change images.

</details>

<details>
<summary><strong>Move and customize position</strong></summary>

### Dragging the window

The legend window has a thin native title bar at the top — drag from there to move it. The title bar tooltip on the body reads *Drag title bar to move. Double-click to close.* The window can be moved anywhere on screen, including onto a second monitor.

### Position and size persistence

Window position **and** size are saved between sessions in v1.0. Move the window where you want it, resize to taste, close it, and the next QuPath start opens it in the same place. There is no separate "save position" command; the values are persisted automatically when the window closes.

If you move the window to a monitor that is later disconnected (for example, an external display you only use at the office), the saved position would be off-screen on the next launch. The extension guards against this by clamping any restored position to the bounds of currently-attached screens; if the saved position is unreachable, the window falls back to centered-on-the-QuPath-main-window placement.

### Multi-monitor

Drag-to-second-monitor works as expected. The window honors the OS's native multi-monitor handling, including different DPI scales per display. If the legend ends up at an unexpected size on a different-DPI monitor, resize once and the new size is saved.

</details>

<details>
<summary><strong>Resize and the text-scales-with-window feature</strong></summary>

### What happens when you resize

Drag any edge or corner of the window. The legend resizes; both the window dimensions and the channel-name text grow or shrink together. There is no separate font-size control — size is bound to window dimensions.

### Why this design

A scientist demoing on a projector wants the legend big enough to read from the back of the room. An analyst at a normal monitor wants it small and out of the way. The same control covers both: pull the corner. No menu to dig through, no preference to set.

The original Groovy script had a fixed font size. Resize-with-text-scaling is the headline polish item this extension adds.

### Bounds

Font size is clamped to a comfortable minimum and a sensible maximum. Below the minimum, channel names start to clip and the empty-state subtitle wraps in ways that look broken; above the maximum, even on a 4K display, the channel rows look comically large. In practice you will not hit either bound during normal use; resizing into either limit has the visible effect of the text holding steady while the window keeps changing size.

### Aspect

The font scales with the smaller of the window's width and height. Stretching the window into a long thin rectangle does not blow up the text — it widens the available room for long channel names while keeping them readable. This avoids a common failure mode where the user shrinks one dimension and the text clips vertically.

### Where the resize handle lives

There is no painted corner-grabber. The window uses standard native window chrome, so you resize by dragging any edge or any corner exactly as you would resize any other window on your operating system. The cursor changes to the standard resize cursor on hover.

</details>

<details>
<summary><strong>Coexistence with the original Groovy script</strong></summary>

### Both can be installed simultaneously

[Sara McArdle's `FluorescentChannelNames.groovy`](https://github.com/saramcardle/Image-Analysis-Scripts/blob/master/QuPath%20Groovy%20Scripts/FluorescentChannelNames.groovy) — originally written by Pete Bankhead at the 2022 QuPath Hackathon — remains a perfectly valid choice. Both the script and this extension can be installed at the same time. They each create their own JavaFX `Stage`; they do not share state, listeners, or window position. Using both at once gives you two windows with the same channel list, which is rarely useful but is harmless if you accidentally do it.

### What the extension adds over the script

- **Discoverability.** The script must be loaded into QuPath's script editor and run; the extension is a toolbar button, a menu item, and a keyboard shortcut.
- **Resize-with-text-scaling.** The script's font size is fixed.
- **Robust image-switch handling.** The script's listener binding is broken when you open a new image; the extension rebinds.
- **Empty-state for RGB images.** The script does not guard against non-fluorescence images and renders an empty pane; the extension renders an explanatory message.
- **Listener cleanup.** Closing the extension's window removes the channel-display listener; closing the script's window leaks one listener per show.
- **Position and size persistence.** The script forgets where it was; the extension remembers.

### When to keep using the script

- You have customized the Groovy version and prefer your customization to the extension's behavior.
- You have it wired into a larger automation that runs the script as part of a workflow.
- You want a fully-transparent window aesthetic that the extension does not provide (the extension uses a thin native title bar; see *What's new in v1.0* in the README).

The extension does not steal `Cmd/Ctrl+Shift+C` from the script — the script does not register a global accelerator at all — so the keymap stays clean even if both are installed.

</details>

<details>
<summary><strong>Settings and preferences</strong></summary>

### v1.0 has no preferences pane

There are no user-tunable settings in v1.0. Defaults are fixed in code:

- **Keyboard shortcut:** `Ctrl+Shift+C` on Linux/Windows, `Cmd+Shift+C` on macOS.
- **Default position on first launch:** centered on the QuPath main window.
- **Default size on first launch:** auto-sized to the longest channel name at the default font size.
- **Minimum window size:** small enough to be unobtrusive, large enough that the empty-state subtitle still renders.
- **Background color, font family, minimum/maximum font size, font-size divisor:** fixed in code; not user-configurable in v1.0.

### What is persisted

Window x, y, width, and height are persisted across QuPath restarts. Persistence happens automatically when the window closes. There is no "save state" command and no "reset to defaults" UI; if you want to reset, close QuPath and the next launch will restore the previously-saved values. To force a true first-show experience, clear QuPath's preferences for this extension via the standard QuPath preferences workflow.

### What is not persisted

The fact that the window was *open* on shutdown is not persisted. Each QuPath start has the window closed; you reopen it as needed. This avoids the "I closed QuPath, came back the next day, and an unwanted window popped up" surprise.

</details>

<details>
<summary><strong>Troubleshooting</strong></summary>

### "Toolbar button is missing"

**What you see.** The menu item and keyboard shortcut work, but no button appears next to brightness/contrast.

**Cause.** The extension looks for QuPath's brightness/contrast button at install time and inserts itself immediately after. If QuPath reorganizes its toolbar in a future release, the lookup heuristic may fail. The extension logs a WARN entry when this happens; the QuPath log is at **Help > Show log**.

**Fix.** Use the menu item (**Extensions > Channel Names Viewer...**) or the keyboard shortcut (`Ctrl+Shift+C`). File an issue with the QuPath version and a copy of the log excerpt; the toolbar lookup heuristic can be updated for the new layout.

### "Window does not update when I toggle channels"

**What you see.** Toggling a channel in brightness/contrast does not change the legend.

**Cause.** The most common cause is that the window was opened before any image was loaded — the channel-display binding has nothing to attach to. A less common cause is a regression in the listener-rebinding logic.

**Fix.** Close and reopen the window after the image is loaded. If the problem persists across multiple image switches, file an issue and include the QuPath log; a leaked listener emits a recognizable WARN line.

### "Text is too small / too large"

**What you see.** Resizing the window does not scale the text past a certain point.

**Cause.** Font size is clamped to documented minimum and maximum bounds. The clamp prevents unreadable text at very small sizes and runaway scaling at very large sizes. This is intentional, not a bug.

**Fix.** Resize the window within the working range. If the maximum is too small for your specific use case (for example, presenting on a 4K display from across a conference room), file an issue with your screen size and DPI; the bounds can be revisited.

### "Window is off-screen on startup"

**What you see.** The window is supposed to be open but is not visible (saved position is on a monitor that is no longer attached, or DPI scaling changed in a way that pushes the window past the screen edge).

**Cause.** The saved position references a screen the OS no longer reports as attached.

**Fix.** This should auto-correct: the extension clamps restored positions to the bounds of currently-attached screens at startup, so the next launch will recenter the window. If for some reason the auto-correct does not work, close and reopen the window with the keyboard shortcut — opening always brings the window to a visible location.

### "Why is my channel showing as white?"

**What you see.** The legend draws a channel name in white even though you picked a non-white display color (a saturated blue or a dark red, for example) in brightness/contrast.

**Cause.** The legend background is near-black (`rgb(13, 13, 13)`), and channel-color text is only drawn in the channel's actual color when that color clears the WCAG AA contrast ratio (4.5:1) against the background. Pure-blue channels in particular have very low WCAG luminance (blue's coefficient is `0.0722`, far smaller than green's `0.7152`), so even fully-saturated `rgb(0, 0, 255)` reads as `2.26:1` contrast — well below 4.5:1 — and the legend falls back to white text. Dark reds, dark purples, and other low-luminance hues fall into the same bucket.

**Fix.** This is intentional. White text is more readable on the dark background than a low-luminance hue. If you want the legend to keep the channel-color fidelity, pick a slightly desaturated or lighter color for that channel in **Image > Brightness/Contrast** — for example a sky-blue rather than a pure blue. The legend will pick up the change immediately. See **Reading the legend** in the *Getting started* section for the full explanation.

### "Empty window shows when I open a new image"

**What you see.** The window is visible but contains a placeholder message instead of channel names — *No fluorescence channels* with one of three subtitles.

**Cause.** The active image is RGB or brightfield, no image is open, or the image is fluorescence but no channels are currently selected. This is intentional behavior.

**Fix.** Open a fluorescence / multiplex image, or select channels in **Brightness/Contrast**. The window updates automatically. The exact subtitle tells you which of the three causes is in play.

### Anything else

For problems not covered here, please open an issue on the [GitHub Issues tracker](https://github.com/uw-loci/qupath-extension-channel-names-viewer/issues) with the QuPath version, the extension version, and a copy of the relevant log excerpt. General QuPath questions are best directed to the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag.

</details>
