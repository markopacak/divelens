# Proposal

## Why

Moving between screens is instant today: the Settings & help screen, its legal pages and the full-screen viewer replace what was there in a single frame. On the phone this doesn't feel like moving to another page and back, so it's harder to tell where you are and how you got there. The viewer can also only be closed with the system Back gesture, which isn't obvious to everyone and is far from the thumb on a tall phone.

## What Changes

- **Start screen ↔ Settings & help:** a short horizontal slide. Settings & help comes in from the right while the start screen moves a little to the left and fades; going back reverses it. The legal pages inside Settings & help (notices, privacy policy, terms) open and close with the same slide. Going back returns to the grid and to the Settings & help list where they were scrolled; today both jump back to the top.
- **Grid ↔ viewer:** opening an item fades the viewer in while it grows slightly to full size; closing reverses it, back to the grid at its scroll position. A playing video stops as soon as closing starts.
- Every way of leaving a screen (system Back, back gesture, on-screen buttons) runs the same transition. Transitions follow the system's *Remove animations* setting: with animations off, screens switch instantly as today.
- **Back button in the viewer:** a "Back" text button at the right end of the Reset / Apply to all row in the bottom panel. It closes the viewer exactly like the system Back.

Not in this change: the photo growing out of its thumbnail and shrinking back into it (a shared-element transition, harder with the GPU-drawn viewer and when the user swiped to another item; a possible later change); a back preview that follows the finger during the back gesture (predictive back).

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `preview-compare`: adds the Viewer transition and Back button in the viewer requirements.
- `help-and-legal`: adds the Page transitions and Scroll position kept when going back requirements.

## Impact

- **Code:** `ui/AppScreens.kt` animates the switch between the two screens (Compose `AnimatedContent`); `ImportExportScreen` keeps the viewer composed while it animates out (`AnimatedVisibility`); `ViewerScreen` gets the Back button in its adjustment panel and stops video playback when closing starts; `SettingsHelpScreen` animates its legal pages.
- **Resources:** no new string ("Back" exists).
- **Tests:** instrumented tests that leave or open screens wait for the transition to finish; new tests for the Back button and for the video stopping when closing starts.
- **Dependencies and permissions:** none. The animation APIs are part of Compose, already in the app.
- **Privacy:** unaffected.
