# help-and-legal Specification

## Purpose

Helps people use DiveLens and know who makes it: a Settings & help screen with support links, foldable help, the licence, source code, privacy policy and terms, and in-app guidance for the viewer's gestures.

## Requirements

### Requirement: Settings and help screen
The start screen SHALL show a settings icon in its top bar that opens the Settings & help screen at any time, including while an export runs. The screen SHALL show its sections in this order: Settings (only when at least one setting exists), Support DiveLens, Help, About. Back SHALL return to the start screen with the working set and any running export unchanged.

#### Scenario: Open and leave
- **WHEN** the user has photos selected, taps the settings icon and then presses back
- **THEN** the Settings & help screen opens, and back returns to the start screen with the same photos selected

#### Scenario: No empty settings section
- **WHEN** the app has no settings yet
- **THEN** the screen starts with the Support DiveLens section and shows no empty Settings heading

#### Scenario: Export keeps running
- **WHEN** an export is running and the user opens the Settings & help screen
- **THEN** the export continues, and its progress is still shown on the start screen when the user goes back

### Requirement: Send feedback by email
Send feedback SHALL open the phone's email app with a new message to dev.markopacak@gmail.com, the subject "DiveLens feedback", and a body with the app version, Android version and phone model, which the user can edit before sending. The app SHALL NOT attach photos, videos or any other file, and SHALL NOT send anything itself.

#### Scenario: Write feedback
- **WHEN** the user taps Send feedback
- **THEN** the email app opens a draft to dev.markopacak@gmail.com showing the app version, Android version and phone model, with no attachment

#### Scenario: No email app
- **WHEN** no app on the phone can send email
- **THEN** DiveLens shows the address dev.markopacak@gmail.com so the user can write from another device

### Requirement: Report a bug and see the source code
Report a bug SHALL open https://github.com/markopacak/divelens/issues and Source code SHALL open https://github.com/markopacak/divelens, each in the phone's browser.

#### Scenario: Open the issue page
- **WHEN** the user taps Report a bug
- **THEN** the browser opens https://github.com/markopacak/divelens/issues

#### Scenario: No browser
- **WHEN** no app on the phone can open a web link
- **THEN** DiveLens shows the address as text the user can select and copy

### Requirement: Foldable help
The Help section SHALL show these topics as foldable sections, all folded when the screen opens: Getting started, Gestures, Correction strength, Videos, Privacy. Tapping a topic SHALL unfold or fold it. The help texts SHALL be shown without a network connection.

#### Scenario: Read a topic offline
- **WHEN** the phone is in flight mode and the user taps Gestures
- **THEN** the Gestures topic unfolds and explains hold to compare, double-tap and pinch to zoom, and swiping between items

#### Scenario: Privacy topic
- **WHEN** the user unfolds Privacy
- **THEN** the text says "Photos and videos never leave your device." and that exports do not keep the location

### Requirement: About and legal texts
The About section SHALL show the app name and version, "Developed by Marko Pacak" with dev.markopacak@gmail.com, the licence (GPL-3.0 with the Google Play libraries exception) next to the Source code link, and entries that open the open-source notices, the privacy policy and the terms of use. The open-source notices SHALL show DiveLens's licence text and list every third-party library the app ships with its licence. All these texts SHALL be shown without a network connection.

#### Scenario: Read the privacy policy offline
- **WHEN** the phone is offline and the user taps Privacy policy
- **THEN** the full privacy policy is shown inside the app

#### Scenario: Version shown
- **WHEN** the user opens About in a build with version 1.0
- **THEN** the screen shows "DiveLens 1.0"

#### Scenario: Notices list the shipped libraries
- **WHEN** the user opens the open-source notices
- **THEN** they show the GPL-3.0 text with the additional permission, and each library the app ships with its licence

### Requirement: Public privacy policy and terms
The privacy policy and terms of use SHALL also be published in the public repository, with the same content as the in-app texts, so the store listing can link to a public address.

#### Scenario: Same text in app and repository
- **WHEN** the privacy policy in the repository is compared with the one shown in the app
- **THEN** the two have the same content

### Requirement: One-time gesture tips
The first time the viewer shows a photo, it SHALL show a tip "Hold to see the original", and after that tip a tip "Double-tap to zoom". The first time it shows a video, it SHALL show "Hold to see the original" while the video keeps playing. A tip SHALL go away when the user taps it or does the gesture. A tip that went away SHALL NOT be shown again, also after the app restarts.

#### Scenario: Tips on the first photo
- **WHEN** the user opens the viewer on a photo for the first time, holds to compare, then lets go
- **THEN** the hold tip goes away and the double-tap tip appears

#### Scenario: Not shown again
- **WHEN** the user has dismissed both photo tips and opens the viewer again, also after restarting the app
- **THEN** no photo tip appears

#### Scenario: Tip on the first video
- **WHEN** the user opens a video in the viewer for the first time
- **THEN** the hold tip appears and the video plays on behind it

#### Scenario: A tip does not block the gesture
- **WHEN** a tip is shown and the user holds on the photo
- **THEN** the original is shown, exactly as without the tip

### Requirement: Show tips again
Help SHALL offer Show tips again. After it, the viewer SHALL show the one-time tips again as if for the first time.

#### Scenario: Reset the tips
- **WHEN** the user taps Show tips again and then opens a photo in the viewer
- **THEN** the "Hold to see the original" tip appears

### Requirement: Gesture help in the viewer
The viewer SHALL show a help icon in a top corner, which opens a sheet explaining the gestures for the item shown: for photos hold to compare, double-tap and pinch to zoom, and swipe; for videos hold to compare, play and pause, scrub, and swipe. Closing the sheet SHALL return to the viewer unchanged. The help icon SHALL NOT cover the playback bar or the strength panel.

#### Scenario: Gestures for a photo
- **WHEN** the user taps the help icon on a photo
- **THEN** a sheet explains hold to compare, double-tap and pinch to zoom, and swiping to the next item

#### Scenario: Video keeps its state
- **WHEN** a video is paused, the user opens the gesture sheet and closes it
- **THEN** the video is still paused at the same position

### Requirement: Page transitions
Opening the Settings & help screen SHALL slide it in from the right while the start screen moves a little to the left and fades, and going back SHALL reverse it. The legal pages opened from the screen SHALL open and close with the same slide. Each transition SHALL take at most 300 ms and SHALL run the same way for the system Back, the back gesture and the on-screen back arrow. When the system setting to remove animations is on, the screens SHALL switch without a transition.

#### Scenario: Slide in and out
- **WHEN** the user taps the settings icon, and later taps the back arrow
- **THEN** the Settings & help screen slides in from the right, and slides out to the right, revealing the start screen

#### Scenario: Legal page slides
- **WHEN** the user opens the privacy policy from About and presses the system Back
- **THEN** the page slides in from the right and slides out again, showing the Settings & help screen

#### Scenario: Animations removed
- **WHEN** the system setting to remove animations is on and the user opens Settings & help
- **THEN** the screen appears without a transition

### Requirement: Scroll position kept when going back
Going back from the Settings & help screen SHALL show the start screen's grid at the scroll position it had, and going back from a legal page SHALL show the Settings & help screen at the scroll position it had.

#### Scenario: Grid where it was
- **WHEN** the user scrolls the grid down, opens Settings & help and goes back
- **THEN** the grid shows the same items as before opening Settings & help

#### Scenario: Settings list where it was
- **WHEN** the user scrolls down to About, opens the privacy policy and goes back
- **THEN** the About entries are still on screen without scrolling
