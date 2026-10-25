# Tasks

## 1. Repository and release gate

- [x] 1.1 Scan the whole git history for secrets and personal data (keys, tokens, passwords, keystores, `local.properties`, `trainingset/` media, GPS data, home paths) and record the result in design.md Context. Verify: the scan commands and findings are written down (done once on 2026-10-22: clean apart from machine-generated author emails); re-run the scan right before the first GitHub mirror push and confirm nothing new appears. Done: re-run on 2026-10-22 on the 15 rewritten commits, clean (design Context).
- [x] 1.2 Maintainer decision on the older author emails (D9): either rewrite them with `git filter-repo --mailmap` and force-push to GitLab, or keep them. Verify: `git log --all --format='%ae' | sort -u` shows the chosen result, and the decision is noted in this task. Decided 2026-10-22: rewrite to info@markopacak.com (and RefApp names), force-pushed to GitLab; `git log --all --format='%ae'` shows only info@markopacak.com.
- [x] 1.3 Add `docs/PRIVACY.md` and `docs/TERMS.md` with the content in D8, both with an effective date and dev.markopacak@gmail.com. Link both from README. Verify: the files render on the GitLab web view and the maintainer has reviewed the wording. Wording reviewed and approved by the maintainer on 2026-10-24; rendering checked on 2026-10-25 on the public GitHub mirror (the GitLab repository is private and no GitLab CLI is installed): both files show headings, lists and bold with no stray `#` or `**`.

## 2. Legal texts in the app

- [x] 2.1 Complete `NOTICE` with every shipped library and add the `checkNotice` Gradle task (D5), run before every build, that fails when a shipped Maven group is not covered. Verify: `./gradlew :app:checkNotice` passes, and fails naming `com.squareup.okio` when its `Maven groups:` line is removed (checked 2026-10-22).
- [x] 2.2 Add the `copy<Variant>LegalTexts` Gradle task that copies LICENSE, NOTICE, docs/PRIVACY.md and docs/TERMS.md into a generated `res/raw` dir before `preBuild` (D4), plus a JVM test that compares the generated files byte for byte with the repository files. Verify: `./gradlew :app:assembleDebug` succeeds from a clean checkout, and the test passes.
- [x] 2.3 Add the mini Markdown renderer for headings, paragraphs, bullets and bold, with JVM tests on a sample covering each element and on unknown syntax shown as plain text. Verify: the tests pass, and PRIVACY.md and TERMS.md render without stray `#` or `**` in a Compose preview. Done: JVM tests pass; on the phone (2026-10-24) `SettingsHelpScreenTest` opens each legal page and finds no `**` or leading `# ` in any text node, which replaces the Compose preview check.

## 3. Settings & help screen

- [x] 3.1 Add `Links` (feedback email, repository, issues) and `FeedbackMessage.body(...)` with a JVM test that the body has exactly the app version, Android version and phone model, and the subject is "DiveLens feedback". Verify: the tests pass.
- [x] 3.2 Add `SettingsHelpScreen` with the Support, Help and About sections (D2): an empty Settings section is not shown; Help topics fold and unfold, all folded on open; About shows "DiveLens <version>", the developer line, licence next to Source code, and entries for notices, privacy policy and terms that open full-screen text pages with back. Add the help texts as string resources. Verify: a Compose test covers section order, no Settings heading, fold and unfold, version text, and opening and closing each legal page. Done: 9 tests pass on the Xiaomi 14T (2026-10-24).
- [x] 3.3 Wire the links with the fallback dialog (D3): Send feedback, Report a bug, Source code. Verify: `HelpIntentsTest` (instrumented, with the intent starter replaced, so no Espresso-Intents dependency) checks the `ACTION_SENDTO mailto:` intent (address, subject, body, no `EXTRA_STREAM`), both `ACTION_VIEW` URLs and `false` without a matching app; `SettingsHelpScreenTest` checks the dialog shows the address. Done: `HelpIntentsTest` (4) and the dialog test pass on the phone (2026-10-24).
- [x] 3.4 Add the gear icon to the start screen's top bar and the screen switch in `MainActivity` (D1), with back returning to the start screen. Verify: `ImportExportScreenTest` checks the gear opens the screen, and that back returns with the working set unchanged and a running export (`BatchUi.Running`) still shown. Done: passes on the phone (2026-10-24).

## 4. Gesture guidance in the viewer

- [x] 4.1 Add `HelpPrefs` (tips still to show as a `StateFlow`, mark done, reset) on the existing `divelens` preferences (D6), with a test that a dismissed tip stays dismissed for a new `HelpPrefs` instance and that reset brings all tips back. Verify: the tests pass.
- [x] 4.2 Add the tip overlay to photo pages: hold tip, then zoom tip, appearing 400 ms after the page settles, dismissed by the gesture or any touch, never consuming events (D6). Verify: `ViewerScreenTest` checks the order, that holding with the tip visible still reports "original", that a double-tap dismisses the zoom tip, and that neither tip returns for a new `HelpPrefs` on the same stored flags (as after a restart). Done: passes on the phone (2026-10-24).
- [x] 4.3 Add the hold tip to video pages while playback continues. Verify: `VideoViewerTest` checks the tip appears with the state "playing", that a hold dismisses it, and that it doesn't return. Done: passes on the phone (2026-10-24).
- [x] 4.4 Add the help icon and the gesture sheet (D7) with photo and video content. Verify: `ViewerScreenTest` checks the sheet text per page type and that the icon's bounds don't overlap the strength panel or playback bar; `VideoViewerTest` checks a paused video stays paused at the same position after the sheet closes. Done: passes on the phone (2026-10-24). The tests close the sheet with a swipe down, because the phone blocks injected Back keys; Back on the sheet is checked by hand in 5.1.
- [x] 4.5 Wire Show tips again in Help to `HelpPrefs.resetTips()`. Verify: `SettingsHelpScreenTest` checks the entry calls its action, and `ViewerScreenTest` checks `resetTips()` brings the hold tip back in an open viewer. Done: passes on the phone (2026-10-24).

## 5. Device check

- [x] 5.1 On the Xiaomi 14T: open Settings & help during an export; close the gesture sheet with the Back key (the viewer stays open); send feedback (the draft opens in the email app with the right details and no attachment); open Report a bug and Source code (once the GitHub mirror is live; before that, check the browser opens the URL); read each legal page in flight mode; see each tip once on a fresh install; check the help icon is reachable and clear of the status bar. Verify: the results are recorded in this task with any issues found. Done: checked by the maintainer on the Xiaomi 14T on 2026-10-24, all as expected, no issues found.

## Workflow follow-up

- Switch on the GitLab → GitHub push mirror after 1.1 and 1.2 are done (it doesn't depend on the rest of this change).
- Archive the change after review, and add the `help-and-legal` main spec.
- Change 7 (licensing) adds the Donate entry under Support and updates `docs/PRIVACY.md` for Play Billing.
