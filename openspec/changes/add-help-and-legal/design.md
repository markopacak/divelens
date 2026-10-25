# Design

## Context

See proposal.md (Why) and specs/help-and-legal/spec.md for the behaviour. Current state that shapes the approach:

- `MainActivity` sets one composable, `ImportExportScreen`, which shows the viewer as an overlay over the grid. There is no navigation library. App-wide state lives in `AppGraph` (`DiveLensApp`) and `WorkingSession`, so it survives a screen switch.
- The start screen has a Material 3 `TopAppBar` with only the title. The viewer has no top bar. It is a black full-screen pager with the strength panel and, on videos, the playback bar at the bottom.
- Gestures in the viewer: `Modifier.holdToCompare` observes on `PointerEventPass.Initial` without consuming, then Telephoto handles zoom and double-tap. On videos, `ignorePinch` and the hold box wrap the surface (add-video-processing).
- One SharedPreferences file `divelens` already exists (`asked_notifications` in `MainActivity`).
- `versionName = "1.0"` and `buildConfig` is not enabled.
- `NOTICE` lists only Telephoto and Media3, but the APK also ships AndroidX (core, activity, lifecycle, exifinterface), Compose (ui, material3), Kotlin stdlib, kotlinx-coroutines and Coil 3. All are Apache-2.0.
- Git history was scanned on 2026-10-22 before planning: no keys, tokens, passwords, keystores, `local.properties` or `trainingset/` media were ever committed. `storePassword=...` and `keyPassword=...` occur only as README placeholders. The only location in test media is the synthetic fixture's Eiffel Tower coordinate. Commits used machine-generated author emails (user name plus the Mac's host name), because git on the maintainer's Mac had no email set, and the text named the two reference apps.
- Done on 2026-10-22, before the public GitHub mirror (D9): the history was rewritten so that every commit is by `Marko Pacak <info@markopacak.com>` (set in the repository's git config for new commits) and the reference apps are called RefApp and RefApp2, also in commit messages and in the local `trainingset/` tags. The rewritten history was force-pushed to GitLab and mirrored to https://github.com/markopacak/divelens. The scan was re-run on the 15 commits afterwards: one author email, no secret patterns, no sensitive files, no home paths, no reference-app names.

## Goals / Non-Goals

**Goals:**
- Guidance that is found without being looked for (one-time tips) and is available on demand ((?) sheet, Help).
- Everything works offline and needs no new permission or library.
- One source for each legal text, shown in the app and published in the repository.

**Non-Goals:**
- A navigation library, deep links or a settings framework (DataStore, Preference library). One screen switch and one SharedPreferences file are enough.
- Translations. Texts are English string resources, so translating later needs no code change.
- Animated tutorials or illustrations. Tips are text with a simple icon.

## Decisions

### D1. Screen switch in `MainActivity`, not a navigation library
`MainActivity` keeps `var screen by rememberSaveable { mutableStateOf(Screen.Start) }` and shows `ImportExportScreen` or `SettingsHelpScreen`. A `BackHandler` in `SettingsHelpScreen` returns to Start. The working set and export are owned by `WorkingSession` in `AppGraph`, so switching screens does not touch them. A legal text (licence, privacy, terms) opens as a full-screen sub-page inside `SettingsHelpScreen` with its own back handling.
*Alternative:* Navigation Compose. It adds a dependency and a route model for two screens; this can be revisited if a third top-level screen appears.

### D2. Section layout
`SettingsHelpScreen` is one `LazyColumn`:
- **Settings**: rendered only when `settings` is not empty. Today the list is empty; the watermark switch will add the first item.
- **Support DiveLens**: Send feedback, Report a bug. The Donate entry is added by change 7.
- **Help**: one foldable row per topic. Fold state is plain `remember` state, so it starts folded every time the screen opens, as the spec says. The last row is "Show tips again", an action rather than a topic.
- **About**: name and version, developer, licence plus Source code, Open-source notices, Privacy policy, Terms of use.

The version comes from `packageManager.getPackageInfo(packageName, 0).versionName`, which avoids turning on `buildConfig`.

### D3. Links through system intents with a visible fallback
- Feedback: `Intent(ACTION_SENDTO, "mailto:".toUri())` with `EXTRA_EMAIL`, `EXTRA_SUBJECT` and `EXTRA_TEXT`. `ACTION_SENDTO` with `mailto:` only matches email apps, and it carries no attachment. The body is built by a pure function `FeedbackMessage.body(appVersion, sdkInt, release, manufacturer, model)`, so a JVM test can check it holds nothing else.
- Web links: `Intent(ACTION_VIEW, url.toUri())`.
- Both are started in `try { startActivity } catch (ActivityNotFoundException)`. On failure a dialog shows the address in `SelectionContainer`, so it can be copied. Android 11+ package visibility needs no `<queries>` entry for this, because `startActivity` resolves implicit intents without visibility. Only `resolveActivity` would need one, and we don't call it.
- Addresses live in one Kotlin object `Links` (feedback email, repository, issues), like `Branding`, so a later repository move is a one-line change.

### D4. Texts: string resources for help, raw resources for legal documents
- Help topics and the gesture sheet are string resources (short, translatable).
- Long legal texts are files: the GPL-3.0 text (`LICENSE`), `NOTICE`, `docs/PRIVACY.md`, `docs/TERMS.md`. A Gradle task per variant (`copyDebugLegalTexts`, `copyReleaseLegalTexts`) copies them into a generated `res/raw` directory registered with `variant.sources.res.addGeneratedSourceDirectory` (`legal_license`, `legal_notice`, `legal_privacy`, `legal_terms`). The repository files stay the single source, and the spec's "same content" requirement holds by construction.
- The in-app viewer shows them as scrollable text. For the two Markdown files, a tiny renderer handles headings (`#`, `##`), paragraphs, bullet lists and bold, which is all those files use. Other Markdown is shown as plain text.
*Alternative:* a Markdown library. Most are not worth a dependency for two short files.
*Alternative:* copying the texts by hand into `res/raw`. They would drift from the repository versions.

### D5. NOTICE lists every shipped library, checked by the build
NOTICE gets one entry per library family (AndroidX including Compose and Media3; JetBrains Kotlin, coroutines and Compose runtime; Coil; Okio; Guava and its annotations; Accompanist; JSpecify; Poko; Checker Framework qualifiers; Telephoto), each with its copyright holder, licence and a `Maven groups:` line. A Gradle task `checkNotice` in `app/build.gradle.kts` walks the resolved `releaseRuntimeClasspath`, collects every Maven group the app ships, and fails when no `Maven groups:` pattern covers one (a pattern ending in `.*` covers the groups below it). `preBuild` depends on it, so a new library that pulls in an unlisted group stops the build until NOTICE is updated.
*Alternative:* a JVM test against a hand-kept list of groups (the first plan). It cannot notice a library that is missing from both the list and NOTICE, which the classpath walk does.
*Alternative:* Google's `oss-licenses` plugin. Its runtime library is part of Play services, so I'd rather not depend on it for this.

### D6. One-time tips
- A small `HelpPrefs` class wraps the existing `divelens` SharedPreferences with the keys `tip_photo_hold`, `tip_photo_zoom` and `tip_video_hold`, plus `resetTips()`. It exposes a `StateFlow<Set<Tip>>` of tips still to show, so the viewer recomposes when Show tips again is used.
- The viewer draws the current tip as an overlay in the upper third: a rounded surface with an icon and one line, no scrim, so the photo stays visible. The overlay has **no** pointer handling. A single observer on the pager, `Modifier.onEveryTouchEnd`, watches `PointerEventPass.Initial` without consuming anything and marks the visible tip done when the last finger of any gesture lifts: after a hold, a double-tap, a pinch, a swipe or a plain tap. Hold and double-tap are touches, so "the gesture" and "any touch" need no separate detectors (an earlier plan read `holdToCompare` and Telephoto's zoom state for them). Because nothing in the overlay or the observer consumes events, the spec's "a tip does not block the gesture" holds by construction.
- Order on photos: hold first, then zoom. On videos only hold. A tip appears 400 ms after the page settles, so it doesn't flash while swiping past.

### D7. Gesture help icon and sheet
A 48 dp `IconButton` with the outlined help icon, top end, inside `statusBarsPadding()`, drawn as a sibling of the pager like the strength panel, so its touches never reach hold or zoom. It opens a Material 3 `ModalBottomSheet` with the gestures for the current page type. Opening the sheet doesn't pause or resume a video. The player keeps its own state, and the sheet only covers it.

### D8. Privacy policy and terms content
- `docs/PRIVACY.md`: the app processes media on the device only; no accounts; no analytics, ads or crash reporting; exports drop location; feedback is an email the user sends themselves, so only what they write is received; Google Play (billing, from change 7) is governed by Google's privacy policy; contact address; effective date.
- `docs/TERMS.md`: the app is provided under GPL-3.0 "as is", without warranty; the user is responsible for the media they process; purchases follow Google Play's terms (refunds through Play); contact address; effective date.
These are plain, honest texts written for this app, not legal advice. The maintainer reviews them before release.

### D9. Making the repository public
The history scan above is the release gate for the GitHub mirror. The author email in older commits isn't a secret, but it will be public once mirrored. Rewriting it (for example with `git filter-repo --mailmap`) changes every commit hash and needs a force-push to GitLab, so it must happen **before** the first mirror push or not at all. The maintainer chose to rewrite (task 1.2). The first mirror push had briefly carried the old history; the GitHub repository was deleted and recreated, so the old commits no longer resolve there.

## Risks / Trade-offs

- [The email app is missing or misconfigured on some phones] → The fallback dialog shows the address to copy (D3).
- [The repository moves again (for example only GitHub)] → All addresses live in `Links` and the docs. One change updates the app.
- [Tips annoy repeat users] → Each tip shows once, disappears on any touch and needs no extra tap.
- [Privacy policy wording out of date after billing lands] → Change 7 has to update `docs/PRIVACY.md`, and its tasks will say so. The copy task keeps the app in step.
- [Rewriting history after the mirror is on would break the GitHub copy] → D9 makes it a one-time choice before the first push.
- [A raw-resource copy task adds build complexity] → It's a few lines of Gradle with the task wired to `preBuild`. A JVM test reads the generated resource dir and compares bytes to the repository files.

## Migration Plan

No data to migrate. New SharedPreferences keys default to "not shown yet", so existing installs see the tips once after updating. Rollback is removing the screen; nothing else depends on it.
