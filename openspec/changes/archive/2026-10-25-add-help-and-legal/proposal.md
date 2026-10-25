# Proposal

## Why

The gestures that make DiveLens useful (hold to see the original, double-tap to zoom, pinch, swipe) are not discoverable: during the add-strength-slider check on a phone, nobody found them unaided. The app also has no place for help, contact, the licence, the source code, a privacy policy or terms, and Google Play needs a privacy policy before the first release. This change adds them before billing (change 7), so the purchase flow can link to the same terms and the donate button has a home.

## What Changes

- A **Settings & help** screen, opened from a gear icon in the start screen's top bar. Back returns to the start screen. Sections, top to bottom:
  - **Settings**: none yet. The section appears with the first setting (the watermark switch, planned after a logo exists). The time-zone setting discussed on 2026-10-20 is left out.
  - **Support DiveLens**: *Send feedback* opens the phone's email app with a message to `dev.markopacak@gmail.com`, pre-filled with the app version, Android version and phone model, which the user sees and can edit before sending. No photos or videos are attached. *Report a bug* opens `https://github.com/markopacak/divelens/issues` in the browser. *Donate* comes in change 7 (Play Billing tips).
  - **Help**: foldable sections, all folded at first: Getting started, Gestures, Correction strength, Videos, Privacy, and *Show tips again*.
  - **About**: app name and version; "Developed by Marko Pacak" with `dev.markopacak@gmail.com`; licence GPL-3.0 with a *Source code* link to `https://github.com/markopacak/divelens`; Open-source notices (DiveLens's licence and the libraries the app ships); Privacy policy; Terms of use.
- **One-time gesture tips** in the viewer: the first time a photo is shown, a light overlay shows "Hold to see the original", then "Double-tap to zoom". The first time a video is shown, it shows "Hold to see the original" while the video plays. A tip goes away when the user taps it or does the gesture, and it is not shown again.
- A **(?) icon** in the viewer's top corner opens a sheet that explains the gestures, at any time.
- All texts ship inside the app and work offline. The privacy policy and terms are also kept as Markdown files in the repository, so Google Play can link to their public GitHub address.
- A release step: before the GitHub mirror is first switched on, scan the whole git history for secrets and personal data. The scan was already run on 2026-10-22 (see design).

Not in this change: a first-launch tutorial with several screens (the one-time tips and the Getting started section replace it); an "accept the terms" screen (no accounts; Google Play's own terms cover purchases); the donate button (change 7); the watermark switch (after the logo).

## Capabilities

### New Capabilities
- `help-and-legal`: the Settings & help screen (support links, foldable help, about and legal texts) and the in-app gesture guidance (one-time tips and the (?) sheet).

### Modified Capabilities
None. The viewer and start screen gain an entry point and an overlay, but none of their existing requirements change.

## Impact

- **Code:** new `ui/help/` screens (Settings & help, gesture tips, (?) sheet); a small `HelpPrefs` store in SharedPreferences for the tips already shown; a gear icon in `ImportExportScreen`'s top bar; the viewer gets the (?) icon and the tip overlay; navigation in `MainActivity` (a simple state switch, no navigation library).
- **Resources:** new strings for the help texts; `res/raw/` copies of the GPL-3.0 text, NOTICE, privacy policy and terms.
- **Repository:** `docs/PRIVACY.md` and `docs/TERMS.md`; NOTICE completed with every library the app ships (AndroidX, Compose, Kotlin, coroutines, Coil and the ones already listed).
- **Permissions and dependencies:** none. Email and browser links use system intents, which need no permission. No new library.
- **Privacy:** the public claim "Photos and videos never leave your device." stays true. Feedback is plain email that the user writes and sends from their own email app.
