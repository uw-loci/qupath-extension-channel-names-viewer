# QuPath Extension: Channel Names Viewer

A small always-visible legend window for [QuPath](https://qupath.github.io/) that lists the currently-selected fluorescence channels, color-coded by display color, and updates live as you toggle channels in QuPath's brightness/contrast dialog. The window resizes freely and the channel-name text scales with the window so you can shrink the legend out of the way on a laptop or blow it up for a presentation.

This extension packages [Sara McArdle's `FluorescentChannelNames.groovy`](https://github.com/saramcardle/Image-Analysis-Scripts/blob/master/QuPath%20Groovy%20Scripts/FluorescentChannelNames.groovy) (originally written by Pete Bankhead at the 2022 QuPath Hackathon) as a real extension with a toolbar button, menu item, and keyboard accelerator, plus polish around image switching, RGB-image handling, and listener cleanup.

---

## Requirements

- QuPath 0.6.0 or later
- JDK 21 (only required if you build from source)

---

## Installation

For v1.0 the extension is distributed as a single jar:

1. Download `qupath-extension-channel-names-viewer-{version}-all.jar` from the [Releases page](https://github.com/uw-loci/qupath-extension-channel-names-viewer/releases).
2. Drag the jar onto a running QuPath window.
3. When QuPath asks whether to copy the jar into your extensions folder, accept.
4. **Restart QuPath.** This step is required — QuPath copies the jar but does not load new extensions on the fly, so the toolbar button, menu entry, and keyboard shortcut will not appear until QuPath is fully restarted.

After restart you will see the new toolbar button next to QuPath's brightness/contrast button, a new menu entry under **Extensions > Channel Names Viewer...**, and the keyboard shortcut `Ctrl+Shift+C` (`Cmd+Shift+C` on macOS) ready to open the legend.

---

## Quick start

1. Open a multiplex / fluorescence image in QuPath.
2. Open the legend with any of the three launch surfaces: click the toolbar button (labeled `Ch`, with a small triangle in the bottom-right corner indicating an extra menu) next to brightness/contrast, choose **Extensions > Channel Names Viewer...**, or press **Ctrl+Shift+C** (`Cmd+Shift+C` on macOS).
3. The legend lists the currently-selected channels, each name drawn in its display color.
4. **Move:** drag the body. **Resize:** drag any edge or corner (the cursor changes within ~8 px of an edge). **Close:** double-click the body, press the shortcut again, or press Esc. **Settings:** right-click the body or the toolbar button for a menu with background opacity and a lock-font-size toggle.

---

## Key concepts

- **Selected channels.** The window mirrors what brightness/contrast calls *selected*. Toggle a channel there and the legend updates immediately.
- **Color coding.** Channel names are drawn in their display colors. A WCAG luminance check switches very dark channels to white so they stay readable on the dark window background.
- **Resize-with-text.** No font-size control by default. Drag any edge or corner — text scales with the window. If you want a fixed size (e.g. matched screenshots across different channel counts), use **Lock font size** in the right-click menu.
- **Right-click for settings.** The window has no chrome and no controls bar. Right-click the toolbar button (without opening the window), or right-click the window body, to access background opacity, lock-font, and reset-opacity.
- **Image switching.** Open a different image and the legend rebinds automatically. RGB / brightfield images render an empty-state placeholder rather than a crash.

---

## Coexistence with the original Groovy script

This extension does not replace [Sara McArdle's `FluorescentChannelNames.groovy`](https://github.com/saramcardle/Image-Analysis-Scripts/blob/master/QuPath%20Groovy%20Scripts/FluorescentChannelNames.groovy). Both can be installed at once — they create independent JavaFX windows and do not conflict. Keep using the script if you have customized it or wired it into automation; otherwise the extension adds discoverability (toolbar / menu / shortcut), resize-with-text scaling, clean rebinding on image switch, an RGB empty state, listener cleanup, persisted position/size/opacity/lock-state, and a right-click settings menu. Sara's script does not register a global accelerator at all, so `Cmd/Ctrl+Shift+C` is exclusive to the extension. Full discussion in the [user guide](docs/user-guide.md).

---

## What's new

**v1.0.4** — Real translucent background (the rgba slider was being layered over an opaque pane in v1.0.3, so it only darkened the gray rather than letting the desktop show through). Edge and corner resize on all eight sides; the corner grip indicator was removed.

**v1.0.3** — Switched the window to `StageStyle.TRANSPARENT` with rounded corners (matching the original Groovy script aesthetic). Added a right-click context menu (background opacity slider, lock-font-size toggle, reset opacity) accessible from the window body and the toolbar button. Background opacity persists across sessions.

**v1.0.2** — Smart default geometry on open: window auto-sizes to the longest current channel name at QuPath's *Location text font size* preference. Saved geometry is restored only when *Lock font size* is checked.

**v1.0.1** — Toolbar button injection lookup uses the `ActionTools` action property (was looking up the wrong key). Keyboard accelerator routes through `QuPathGUI.setAccelerator` so it fires globally. Font size is now driven by window height (not width); added the *Lock font size* toggle.

**v1.0.0** — First release: toolbar button, menu item, accelerator, channel-color rendering with WCAG fallback, image-switch rebinding, RGB empty state, persisted position/size.

Toolbar button placement remains best-effort; if QuPath reorganizes its toolbar in a future release the menu item and keyboard shortcut continue to work.

---

## Links

- [User guide](docs/user-guide.md) — three ways to launch, image-switching behavior, the resize-with-text rationale, troubleshooting
- [Developer guide](docs/developer-guide.md) — architecture, listener lifecycle, building from source
- [GitHub Issues](https://github.com/uw-loci/qupath-extension-channel-names-viewer/issues) — bug reports and feature requests
- [image.sc forum](https://forum.image.sc/) — discussion and support; tag `#qupath` and mention `@Mike_Nelson`

---

## License

Apache License 2.0. Copyright 2026 Regents of the University of Wisconsin-Madison. See [LICENSE](LICENSE).

**Author:** Mike Nelson — University of Wisconsin-Madison
**Version:** 1.0.5
