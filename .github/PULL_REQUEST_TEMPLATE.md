## What changed

A short description of the change. One or two sentences is fine for small fixes; expand for larger work.

## Why

Why is this change needed? Link the issue this PR closes (if any) with `Closes #N`.

## How to verify

Steps a reviewer can take to confirm the change behaves as described. For UI changes, screenshots before and after are welcome.

## Checklist

- [ ] Code compiles (`./gradlew shadowJar`).
- [ ] Unit tests pass (`./gradlew test`).
- [ ] New logic in `core` is covered by tests; UI-only changes do not require tests.
- [ ] ASCII-clean in `LOGGER.*` calls, exception messages, internal strings, and paths (`grep -rn '[^\x00-\x7F]' src/main/java` returns nothing new). Unicode is permitted in user-visible JavaFX labels and in Markdown documentation. Channel names themselves are user-supplied and may be non-ASCII; the UI must render them, but they must not be concatenated into log lines without sanitization.
- [ ] Uses `qupath.fx.dialogs.Dialogs`, never the deprecated `qupath.lib.gui.dialogs.Dialogs`.
- [ ] User-facing changes are reflected in `docs/user-guide.md`; architecture changes are reflected in `docs/developer-guide.md`.
- [ ] If the keyboard accelerator or toolbar-button-injection logic changed, the change is noted in the README "What's new" section for the next release.

## Notes for reviewers

Anything you want a reviewer to look at carefully, design decisions you weighed, or known follow-ups deferred to a later PR.
