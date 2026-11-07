# Tasks

## 1. Project config and dependency

- [x] 1.1 Rename planned change 8 in `openspec/config.yaml` to `add-free-limits-and-purchase (licensing)` (the free-version decisions are already there). Verify: `openspec validate --all --strict` passes and the planned-order line shows the new name.
- [x] 1.2 Add `billing-ktx` (latest stable, recorded in `libs.versions.toml`) as `releaseImplementation`; add the NOTICE entry with a "Maven groups:" line for every group it brings onto `releaseRuntimeClasspath` (D11). Verify: `./gradlew :app:assembleDebug :app:assembleRelease` passes, including `checkNotice`; `./gradlew :app:dependencies --configuration debugRuntimeClasspath` shows no `com.android.billingclient`; the merged release manifest (`app/build/intermediates/merged_manifests/release/`) is inspected and its permissions are listed in this task, with any `INTERNET` reported to the maintainer.
  - Result (2026-11-07): `billing-ktx` 9.1.0. The release runtime classpath gains these groups: `com.android.billingclient`, `com.google.android.gms` (base, basement, tasks, location, places-placereport), `com.google.android.datatransport`, `com.google.firebase` (encoders) and `javax.inject`; NOTICE lists them all. Every billing version since 7.x has the same dependencies. The debug runtime classpath has no `com.android.billingclient`.
  - Merged release manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (as before), plus `com.android.vending.BILLING` (billing) and **`INTERNET`** (from `transport-backend-cct`, the library Play Billing uses to send its own diagnostics to Google). Reported to the maintainer. `<queries>` entries for the Play billing service and its test companion. No location permission.

## 2. Free-version rules (plain Kotlin)

- [x] 2.1 Write `Limits` and `DailyCounter` (D4) on the `Flags` abstraction with an injected `today()`. Verify: JVM tests cover 10 exports then none left; the next day back to 10; a stored day later than today (clock set back) resets; `record()` across midnight counts toward the new day; the values survive a new `DailyCounter` on the same flags.
- [x] 2.2 Replace the free-text refusal with `RefusalReason` in `GateDecision`, `ItemResult` and `ItemStatus` (D5). Verify: `ExportItemUseCaseTest` and the other JVM tests compile and pass with the new type; the summary still lists refused items.
- [x] 2.3 Write `Licence` (D3) with a `Store` interface (D2) in main and a JVM test fake. Verify: JVM tests check that the remembered flag sets the start state; `owned()` returning null changes nothing; a purchased unacknowledged `full_version` unlocks and is acknowledged once; pending sets `pending` without unlocking; a later answer without the purchase revokes; an `updates` event from pending to purchased unlocks; unfinished tips are consumed on refresh.
- [x] 2.4 Write `LicenceGate` (D5) and wire it in `AppGraph` in place of `AlwaysAllowExportGate`. Verify: JVM tests run a 10-item batch through `BatchExporter` with 4 left: the first 4 are exported and 6 are refused with `DailyLimit`; a failed export doesn't count; a batch crossing midnight (fake clock moved mid-batch) exports the remaining items; the full version neither refuses nor counts.

## 3. Working set and start screen

- [x] 3.1 Make the working-set limit a parameter (D6): `WorkingSet` operations take the limit, and `WorkingSession` gets it from the licence as 10 or 20 and exposes `limit`, `free` and `remainingToday` in the UI state. Verify: JVM tests check that 7 + 5 free gives 10 with 2 left out; the full-version scenarios of the import-export spec (15 + 8, 18 + 3 videos, full at 20); 20 items kept when the limit drops to 10, with adding unavailable; existing `WorkingSet` and session tests pass.
- [x] 3.2 Update the start screen: the limit texts use the current limit, with the free-version variant ("the full version holds 20" plus a Full version button); "N of 10 free exports left today" while free with items selected (D6). Verify: instrumented UI tests on the fake store check the free message and button at 10 items, the full-version message at 20, and that the count shows 7 after 3 exports and is absent in the full version.
  - Result: the screen tests set the free or full state directly (the fake store comes in 4.1); "7 left after 3 exports" is checked end to end by the JVM `WorkingSessionLicenceTest`. 19/19 `ImportExportScreenTest` pass on the emulator.
- [x] 3.3 Add the export pre-check and the daily-limit summary (D7). Verify: instrumented tests check that with 0 left, Export opens the daily-limit dialog and starts nothing; with 4 left and 10 items, the summary says 4 exported and 6 not exported because of the daily limit, mentions midnight and shows a Full version button; refused items stay in the working set marked "Not exported".
  - Result: the pre-check and the 4-of-10 batch are checked end to end in the JVM `WorkingSessionLicenceTest`; the dialog and the grouped summary in `ImportExportScreenTest` (21/21 on the emulator). The grid only composes visible cells, so the screen test checks a visible "Not exported" label and the JVM test checks all six refused items stay.

## 4. Purchases and Support entries

- [x] 4.1 Write `FakeStore`, the debug `Stores.create`, the fake purchase sheet overlay and the developer panel in `src/debug`, with an empty `DebugTools` in `src/release` (D1, D10); add the optional `developer` slot to `SettingsHelpScreen`. Verify: the debug app opens the fake sheet from Full version, and Buy unlocks; the panel switches free or full, refunds, completes a pending payment and sets today's count; `unzip -l` on the release APK and a search of its dex for `FakeStore` find nothing.
  - Result: `FakeStore`, the simulated sheet and the developer panel live in `src/debug`; `src/release` has an empty `DebugTools`. `DeveloperPanelTest` 4/4 on the emulator (free/full switch, silent refund, completing a pending payment, today's count). In the release APK, the dex files and `resources.arsc` contain no `FakeStore`, `DeveloperPanel`, `fake_sheet` or `dev_owns_full`; the debug APK contains no `com/android/billingclient`.
- [x] 4.2 Write `PlayStore` and the release `Stores.create` (D2), translating billing results into `BuyOutcome` and `Owned`. Verify: the release build compiles and starts on the phone (unsigned or locally signed, so Play reports billing unavailable): the app runs as a free version, Full version says "not available right now", and nothing crashes.
  - Result: release APK signed locally with the debug key, installed on the emulator (Play Store image, not signed in): the app starts as the free version; Full version and Restore purchase say "Not available right now"; Donate offers the full version; no crash. The billing library logs its own reconnection warnings ("already connecting", result 5) while the first calls run at once; harmless.
- [x] 4.3 Write `PurchaseController` (D8) and the Full version entry in Support DiveLens: the price and what it adds when free; "Full version active" with Restore purchase when full; pending and thank-you states; refresh from `MainActivity.onStart`. Verify: instrumented tests on the fake store cover buy → thanks and unlocked (limit 20, no count); cancel → no change and no error; pay later → "waiting for payment", then completing it in the fake store unlocks; unavailable → message; refund + refresh → free again with the working set kept.
  - Result: `PurchaseFlowTest` 8/8 on the emulator (buy, cancel, pay later then complete, unavailable, refund at the next refresh, restore, plus the two Donate cases of 4.4). The working set kept after a refund is checked in the JVM `WorkingSessionLicenceTest`.
- [x] 4.4 Add Donate (D8): a free user gets the full-version offer and no amounts; a full user gets three amounts from the store, each consumed after paying. Verify: instrumented tests on the fake store check the free dialog offers the full version and shows no amounts; a full user donates `tip_medium` twice in a row and is thanked both times; a donation leaves the licence unchanged.
  - Result: covered by `PurchaseFlowTest` (free user: full-version offer, no amounts; full user: `tip_medium` twice, both thanked, tips consumed, licence unchanged).
- [x] 4.5 Update the texts (D12): `docs/TERMS.md` › Purchases with the free version's limits and the one-time full version; `docs/PRIVACY.md` › Purchases with what is stored on the phone; README status, Play Console product IDs (`full_version`, `tip_small`, `tip_medium`, `tip_large`), and how to test with the fake store and the developer panel. Verify: `LegalTextsTest` passes (bundled texts equal the repository files); the README section names all four product IDs; a grep finds no reference-app names, no secrets and no "no network permission" claim.
  - Result: TERMS gains "Free version and full version"; PRIVACY › Purchases gains the two stored values; README gains a Purchases section with the four product IDs and the fake-store testing; both effective dates are now 7 November 2026; Help › Getting started says 10 items (20 in the full version). Because the release build now has INTERNET (task 1.2), the maintainer approved three more edits on 2026-11-07: Help › Privacy no longer says "sends nothing anywhere" (now "Only purchases go through Google Play"); PRIVACY says no advertising, analytics or crash reporting "of its own", says the Billing library may send Google technical information about purchases, and lists internet access and Play billing under Permissions. `LegalTextsTest` passes; no reference-app names, secrets or "no network permission" claim found.

## 5. Device check

- [x] 5.1 On the Xiaomi 14T with the debug build:
  - **Daily limit:** export 10 items over two batches, then try an 11th; set the count to 6 in the panel and export 10 items.
  - **Batch limit:** add items past 10 in the free version and past 20 in the full version.
  - **Purchases (fake sheet):** buy, cancel, pay later then complete, refund with 20 items selected, Restore purchase.
  - **Donate:** as a free user and as a full user.
  - **Screens:** the messages and Support entries in light and dark mode.

  Verify: results recorded in this task, and the full JVM and instrumented suites pass.
  - Result (2026-11-07): the maintainer ran every item above on the Xiaomi 14T with the debug build; all worked as described. Suites: 161 app JVM tests and 34 `:core:analysis` tests pass; 150 instrumented tests on the phone (149 in the full run, and `theSummaryGroupsItemsStoppedByTheDailyLimit` after making it scroll to the first refused item). On the emulator, `SettingsHelpScreenTest.entriesCallTheirActions` now scrolls past the snackbar.
- [ ] 5.2 Real Google Play purchase check, **blocked until the maintainer has a Play Console account** with `full_version` (1.19 CHF / 1.19 EUR) and the three tips set up, a signed build on the internal testing track, and a licence-tester account. With the build installed from Play:
  - buy the full version with a test card;
  - cancel a purchase;
  - make a slow-payment test purchase and let it complete;
  - refund the full version in Play Console and reopen the app;
  - reinstall and check the purchase is restored;
  - donate once.

  Verify: results recorded in this task. Any difference from the fake store becomes a fix in `PlayStore` before release.

## Workflow follow-up

- Commit and push when asked. Task 5.2 can wait for the Play Console account; archiving with it open is the maintainer's choice.
- Archive after review: create the `licensing` main spec and update `import-export`.
