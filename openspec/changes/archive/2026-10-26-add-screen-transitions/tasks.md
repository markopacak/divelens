# Tasks

## 1. Start screen ↔ Settings & help

- [x] 1.1 Animate `AppScreens` with `AnimatedContent` and the directional slide (D1), and keep each screen's saved state with a `SaveableStateHolder` (D2). Verify: `ImportExportScreenTest` with the test clock paused checks that 100 ms after tapping the gear both screens are composed and after 400 ms only Settings & help is; that the same holds for the back arrow and the system Back; and that the grid, scrolled down before opening Settings, is at the same first visible item after going back. The existing tests (working set and running export kept) still pass. Done: 2 new tests pass on the Xiaomi 14T (2026-10-26), with the 13 existing ones.
- [x] 1.2 Animate the legal pages in `SettingsHelpScreen` with the same slide, and keep the list's scroll position (D2). Verify: `SettingsHelpScreenTest` checks the list is composed mid-transition beside the page, and that after opening the privacy policy from About and pressing Back, the About entries are still on screen without scrolling. The existing 9 tests still pass. Done: 2 new tests pass on the phone (2026-10-26), with the 9 existing ones.

## 2. Grid ↔ viewer

- [x] 2.1 Show the viewer in `AnimatedVisibility` with fade and scale (D3), keeping the last index for the exit and skipping the exit when that item is gone. Verify: `ViewerScreenTest` (or `ImportExportScreenTest`, where the overlay lives) with the test clock paused checks the viewer is partly visible mid-transition when opening and when closing, and gone after 300 ms; that removing the shown item closes the viewer at once without a crash; and that the grid keeps its first visible item after closing. Done: 3 new tests in `ViewerScreenTest` pass on the phone (2026-10-26); the grid check is that the tapped cell is still shown after closing.
- [x] 2.2 Add `transitioning` and `closing` to the viewer (D4): videos inactive while transitioning, Back handler off and touches passed through while closing. Verify: `VideoViewerTest` checks a playing video's state is no longer "playing" one frame after Back, that the thumbnail is shown during the transition, and that playback starts once the opening transition has ended; a test checks that a second Back during closing reaches the start screen's handler. Done: tests pass on the phone (2026-10-26). `VideoViewerTest` now hosts the viewer in the start screen so the transition runs. Touches during closing are taken by a layer and reach neither the viewer nor the grid (design D4, updated).

## 3. Back button in the viewer

- [x] 3.1 Add the "Back" button at the right end of the panel's Reset / Apply to all row (D5). Verify: `ViewerScreenTest` checks the button is in the same row as Reset, to the right of Apply to all and inside the panel's bounds, that tapping it closes the viewer through the same `onClose` and keeps the item's strength; `VideoViewerTest` checks it is shown on a video below the playback bar and stops playback. Done: tests pass on the phone (2026-10-26).

## 4. Device check

- [x] 4.1 On the Xiaomi 14T: open and close Settings & help and each legal page with the arrow, the system Back and the back gesture; open and close photos and a playing video with the Back button and the system Back, and check there is no flicker, no black flash and no sound after closing; check the grid and the Settings list come back where they were scrolled; turn on *Remove animations* (Developer options, or `adb shell settings put global animator_duration_scale 0`) and check every screen switches instantly. Verify: results recorded in this task, and the animation scale set back afterwards. Done: checked by the maintainer on the Xiaomi 14T on 2026-10-26: slides, scroll positions, viewer fade and Back button, video close without sound, and instant switching with animations removed, all as expected; no issues found.

## Workflow follow-up

- Commit and push when asked; archive the change after review, updating the `preview-compare` and `help-and-legal` main specs.
