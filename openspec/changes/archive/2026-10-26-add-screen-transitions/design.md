# Design

## Context

See proposal.md (Why) and the two delta specs for the behaviour. Current state that shapes the approach:

- `ui/AppScreens.kt` switches between `Screen.Start` and `Screen.SettingsHelp` with a plain `when`. The screen that is left leaves the composition, and its `rememberSaveable` state goes with it, including the start screen's `LazyVerticalGrid` scroll position (its default `rememberLazyGridState`).
- `SettingsHelpScreen` shows a legal page by returning early (`if (doc != null) { LegalPage(...); return }`), so the `LazyColumn` and its scroll position are dropped while a page is open.
- The viewer is an overlay inside `ImportExportScreen`, shown while `state.viewing != null` (from `WorkingSession`). `closeViewer()`, removing the item and clearing the set all set `viewing = null`, and the viewer disappears in the same frame. The grid underneath stays composed and keeps its scroll position.
- `ViewerScreen` registers `BackHandler(onBack = onClose)`. Videos play only while `VideoPage(active = true)`, on a `SurfaceView` in an `AndroidView`; with `active = false` the player is released and the page shows the video's thumbnail as a Compose `Image` (add-video-processing D10).
- Photos are drawn by Compose (Telephoto and the GPU-rendered bitmaps), so they follow `graphicsLayer` alpha and scale.
- The adjustment panel's last row holds Reset and Apply to all, left-aligned. The string `back` ("Back") exists.
- Instrumented tests run with the Compose test clock in auto-advance mode, so `waitForIdle()` and the finders already wait for running animations.

## Goals / Non-Goals

**Goals:**
- Transitions that make the page structure visible, at most 300 ms, never blocking a touch for longer.
- No change to the session state model: `viewing` stays the single source of whether the viewer is open.

**Non-Goals:**
- A navigation library. Two screens and one overlay don't need one.
- Shared-element and predictive-back transitions (see proposal).
- Transitions for dialogs, sheets and snackbars, which already animate in Material 3.

## Decisions

### D1. `AnimatedContent` in `AppScreens`, with a direction
`AppScreens` wraps the `when` in `AnimatedContent(targetState = screen)`. Moving to `SettingsHelp` uses `slideInHorizontally { it } + fadeIn` for the incoming screen and `slideOutHorizontally { -it / 4 } + fadeOut` for the start screen; going back uses the mirror image, chosen from the order of the `Screen` entries. Duration 300 ms with Material's standard easing (`FastOutSlowInEasing`).

Alternatives: Navigation Compose (adds a library and a nav graph for two screens); `Crossfade` (no direction, so it doesn't read as going forward and back, which is the point of the change).

### D2. `SaveableStateHolder` keeps each screen's state
`AppScreens` wraps each screen in `rememberSaveableStateHolder().SaveableStateProvider(screen.name)`. A screen that is left keeps its saved state, so the grid comes back at its scroll position. The same is done in `SettingsHelpScreen` for its list and the legal page: the early return becomes an `AnimatedContent(targetState = doc)` with the same slide as D1, and the list sits in a `SaveableStateProvider`, so it comes back where it was scrolled.

Alternative: hoisting the grid and list states into the session. That keeps scroll state in a place meant for the working set and export, and needs every scroll state passed down by hand.

### D3. The viewer animates out from its last index
`ImportExportScreen` shows the viewer in `AnimatedVisibility(visible = viewing != null)`, with `fadeIn + scaleIn(initialScale = 0.92f)` and the reverse on exit, 250 ms. During the exit `viewing` is already `null`, so the content uses the last non-null index kept in a `remember`ed variable. If that index is no longer in the working set (the item was removed, or the set cleared), the exit is skipped and the viewer disappears in one frame, as today.

Alternative: a separate "closing" state in `WorkingSession`. That spreads a UI animation into the session, which tests and the export don't care about.

### D4. Videos show their thumbnail during a transition; closing lets Back and touches through
The viewer gets a `transitioning: Boolean`, true while `AnimatedVisibility` is entering or exiting (`transition.currentState != transition.targetState`), and `closing`, true while exiting. A `SurfaceView` is a separate window surface that doesn't reliably follow the alpha and scale of the Compose layer around it, so:
- every `VideoPage` gets `active = false` while `transitioning`. Opening on a video fades in its thumbnail and starts the player when the transition ends, adding at most 250 ms before the first frame, which the player's own preparation mostly hides. Closing releases the player at the start of the transition, so no sound plays during it, and fades out the thumbnail.
- while `closing`, the viewer's `BackHandler` is disabled, so a second Back goes to the start screen as it would without a transition, and a transparent layer on top takes the touches, so the closing viewer doesn't react to them. They don't reach the grid either: Compose gives a touch to the topmost element that handles it, and letting it fall through to a sibling underneath would need a custom pointer node, which isn't worth it for 250 ms.

Alternative: switch the video to a `TextureView`, which does follow the layer. It costs some battery and a frame of latency during the whole playback, for a 250 ms effect.

### D5. The Back button in the adjustment panel
The panel's last row gets `Spacer(Modifier.weight(1f))` and a `TextButton` "Back" (tag `viewer_back`) that calls the same `onClose` as the `BackHandler`. Placing it in the panel, not over the photo, keeps it out of the hold, zoom and swipe gestures, like Reset and Apply to all. On videos it is in the same panel, below the playback bar.

### D6. Removed animations
Compose's animation specs scale with the system animator duration setting, and a scale of 0 ends them at once. Nothing extra is needed; the device check covers it.

## Risks / Trade-offs

- [Opening on a video shows the thumbnail, then the first corrected frame, which can differ slightly in colour] → The same switch already happens when swiping to a video; accepted.
- [Both screens are composed during the 300 ms slide, so the start screen's thumbnails keep decoding] → Short and bounded; the Settings & help screen is light.
- [Existing instrumented tests assert a node is gone right after Back] → The auto-advancing test clock waits for the transition; tests that check a single frame are adjusted where needed.
- [A tap during the slide reaches the screen that is leaving] → `AnimatedContent` doesn't block it; at 300 ms this is accepted, as in most Android apps.
