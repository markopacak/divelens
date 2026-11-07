# Design

## Context

See proposal.md (Why) and the delta specs for the behaviour. The current state that shapes the approach:

- **Export check:** `export/Seams.kt` already has the `ExportGate` seam: `check(item)` before each write, and `onExported(item)` only after a successful one. `ExportItemUseCase` turns a refusal into `ItemResult.Refused(message: String)`, and the working set shows it as `ItemStatus.Refused`. `AppGraph` wires `AlwaysAllowExportGate`.
- **Batches:** `BatchExporter` exports strictly one item at a time, so the gate's check and count can't race.
- **Working set:** `WorkingSet.LIMIT = 20` is a constant used by `room`, `isFull` and `addWithinLimit`, and by the start screen's `limit_reached` and `left_out` texts.
- **Settings & help:** `SettingsHelpScreen` builds its Support DiveLens section from fixed entries (Send feedback, Report a bug). It already takes an optional `settings` slot.
- **Preferences:** one preferences file (`divelens`) through the `HelpPrefs.Flags` abstraction, which JVM tests fake.
- **Build:**
  - release signing comes from a gitignored `keystore.properties`; without it the release build is unsigned;
  - `CheckNotice` fails the build when a library on `releaseRuntimeClasspath` has a Maven group that `NOTICE` doesn't list;
  - `LICENSE`/`NOTICE` already carry the GPL section 7 exception for Google Play Billing.
- **Play Billing constraints:**
  - purchases only work for an app uploaded to Play and signed by Play, so debug builds can't buy anything real;
  - purchases must be acknowledged within 3 days or Play refunds them;
  - pending purchases must not be granted;
  - Play keeps a local cache of purchases, which the app queries.

## Goals / Non-Goals

**Goals:**
- **One rule engine:** all licensing logic (the daily counter, the gate, the limit, how purchases map to the licence state) is plain Kotlin, tested on the JVM with a fake clock and a fake store.
- **No Google code in debug:** debug builds contain no Google Play Billing code, and release builds contain no fake store.
- **Thin Play layer:** the Play-specific code is a small adapter that only translates.

**Non-Goals:**
- **Stopping determined cheaters:** the code is public and checks run on the phone (accepted in `config.yaml`). No obfuscation, server checks or Play Integrity.
- **A store abstraction for other app stores.**

## Decisions

### D1. Debug and release pick the store by build-type source sets, not flavours
- `app/src/main` defines a `Store` interface and the product IDs.
- `app/src/release/.../PlayStore.kt` implements `Store` with `billing-ktx`, added as `releaseImplementation`.
- `app/src/debug/.../FakeStore.kt` implements it with a state machine kept in preferences.
- Each source set has its own `Stores.create(context)` with the same signature, so `AppGraph` calls one function and the build type picks the implementation.
- Instrumented tests run on the debug build and drive `FakeStore` directly.

Alternatives considered:
- **Product flavours (`play`/`foss`):** they double every variant and test task for no gain, because the only split we need is buildable-by-anyone versus published.
- **A runtime switch:** it would ship the fake store, and a way to unlock for free, in the published app.

### D2. `Store` interface: translated outcomes only
```kotlin
interface Store {
    suspend fun prices(ids: List<String>): Map<String, String>        // formatted, from Play; empty if unavailable
    suspend fun owned(): Owned?                                        // null = couldn't ask (offline, Play missing)
    suspend fun buy(activity: Activity, id: String): BuyOutcome        // Purchased | Pending | Cancelled | Unavailable | Failed
    val updates: Flow<Owned>                                           // purchases that change while the app runs (pending → paid)
    suspend fun finish(purchase: StorePurchase)                        // acknowledge (full version) or consume (tips)
}
```
`Owned` lists the purchases Play reports, each with a product ID, a state (purchased or pending), an acknowledged flag and a token.

**`PlayStore`:**
- keeps one `BillingClient`, connected lazily with `enableAutoServiceReconnection`, and enables pending purchases for one-time products;
- maps billing response codes to `BuyOutcome`: `USER_CANCELED` → Cancelled; `SERVICE_UNAVAILABLE`, `BILLING_UNAVAILABLE` and connection failures → Unavailable; the rest → Failed;
- `owned()` returns null on any error, never an empty list, so an error is never taken as "doesn't own it" (D3).

### D3. Licence state: a remembered flag that only a successful answer from Play changes
`Licence` (in main, app scope) holds `state: StateFlow<LicenceState>`, where `LicenceState(full: Boolean, pending: Boolean)`. The flag `full_version` is kept in the preferences file.
- **At start:** the state comes from the remembered flag, so the app works offline.
- **`refresh()`:** runs at process start (in `AppGraph`) and from `MainActivity.onStart`, and calls `store.owned()`.
  - `null` (couldn't ask): no change.
  - A purchased `full_version`: full = true. If it isn't acknowledged yet, it is acknowledged now. Retrying at every refresh covers a failed acknowledge within Play's 3 days.
  - A pending one: pending = true, full unchanged.
  - Neither: full = false. This is how a refund or chargeback arrives.
  - Unfinished tip purchases are consumed, so a crash between paying and consuming doesn't block the next donation.
- **`store.updates`:** goes through the same mapping, so a pending payment that completes while the app is open unlocks it.

Alternative considered: never revoking. A refunded unlock would stay forever, which contradicts the spec, and the maintainer chose explicit behaviour for refunds.

### D4. Daily counter: local day, stored as epoch day + count
`DailyCounter(flags, today: () -> LocalDate = { LocalDate.now() })` stores `exports_day` (epoch day) and `exports_count`.
- **Reset:** a stored day different from today, earlier or later, means the count is 0. Setting the clock back or forward resets it (accepted).
- **`remaining()`** is `10 - count` for today.
- **`record()`** increments it, resetting first if the day changed.
- **Clock:** `today()` is read at every call, so a batch running past midnight uses the new day for its remaining items (spec "Partial export at the daily limit").
- **Constants:** `FREE_DAILY_EXPORTS = 10`, `FREE_WORKING_SET = 10` and `FULL_WORKING_SET = 20` live in one `Limits` object.

### D5. The gate: `LicenceGate(licence, counter)` replaces `AlwaysAllowExportGate`
- `check`: full → Allowed; `remaining() > 0` → Allowed; otherwise `Refused(RefusalReason.DailyLimit)`.
- `onExported`: `record()`, only while free. Exports made in the full version don't count, so a refunded user starts the day with whatever is left.
- **Refusal type:** `GateDecision.Refused`, `ItemResult.Refused` and `ItemStatus.Refused` carry a `RefusalReason` enum instead of a free-text `message`, so the screen can show localised text and recognise the daily limit. The gate is their only producer.
- **Tests:** `AlwaysAllowExportGate` stays for the tests that check export without licensing.

### D6. Working-set limit from the licence
- **`WorkingSet`:** it no longer owns the limit. `room(limit)`, `isFull(limit)` (true when `size >= limit`) and `addWithinLimit(items, limit)` take it as a parameter.
- **`WorkingSession`:**
  - it gets `limit: StateFlow<Int>` (10 or 20, mapped from the licence state) and exposes it in `ImportExportUiState`, with `free: Boolean` and `remainingToday: Int?`;
  - `remainingToday` is null in the full version and refreshed after each batch and on `refresh()`;
  - the existing `leftOut` message takes the current limit, and the free variant adds the full-version line and button.
- **Lowered limit:** items above the new limit are kept, because nothing ever trims the set (spec "Limit drops below the working set").

### D7. Export pre-check and the daily-limit summary
- **Nothing left:** `WorkingSession.export()` checks first. In the free version with `remaining() == 0`, it sets `dailyLimitNotice = true` and starts no batch. The screen shows a dialog with the explanation and a Full version button.
- **Otherwise:** the batch runs normally, and the gate refuses items once the count reaches 10.
- **Summary dialog:** refused items with `DailyLimit` are grouped into one paragraph instead of one line per item: "6 items weren't exported: the free version exports 10 items a day. The limit resets at midnight." It is followed by a Full version button. Other failures keep their per-item lines.
- **Remaining count:** shown above the action row on the start screen, only when free and items are selected.

### D8. Purchases from the UI: `PurchaseController` and an Activity per call
- **`PurchaseController`** (app scope, in `AppGraph`) exposes:
  - `prices` (loaded on start and on refresh);
  - `busy`;
  - a one-shot `messages` flow: thanks, pending, unavailable or failed, shown as snackbars or dialogs;
  - `buyFullVersion(activity)` and `donate(activity, tier)`.
- **Activity:** the composables get it through `LocalActivity` (activity-compose 1.13), because `launchBillingFlow` needs one. The controller never keeps a reference to it.
- **Entry points:**
  - the Full version entry and the Donate flow in Settings & help;
  - the Full version buttons in the limit messages (D6, D7);
  - in the full version the Full version entry shows "Full version active · Restore purchase", and Restore calls `licence.refresh()` and reports the result.
- **Donate:**
  - for a free user it's a dialog: the full version supports DiveLens, donations are possible after it, with Buy and Not now buttons;
  - for a full user it's a dialog with three amounts from `prices` and a Donate button for each;
  - a tip purchase is consumed (`finish`) right after `Purchased` and grants nothing.

### D9. Product IDs and prices live in Play Console
- **IDs:** `full_version` (one-time, non-consumable); `tip_small`, `tip_medium` and `tip_large` (consumable). They're constants in main, documented in the README for the Play Console setup.
- **Prices:** none in code. The UI shows Play's formatted prices, and an entry whose price couldn't be loaded shows its label without a price; tapping it reports "not available right now".
- The full version is 1.19 CHF / 1.19 EUR (`config.yaml`). The tip amounts are the maintainer's choice in Play Console (suggested 2, 5 and 10).

### D10. Fake store and developer panel (debug source set only)
- **`FakeStore`:**
  - keeps its owned purchases in preferences;
  - `buy()` puts a request in `sheet: StateFlow<FakeSheet?>` and suspends until it's answered. A debug-only overlay composable renders it as a dialog standing in for Play's sheet, with Buy, Pay later, Cancel and Fail;
  - instrumented tests answer it directly: `FakeStore.answerNext(outcome)`.
- **Developer panel:** an entry in Settings & help, plugged into a new optional `developer` slot like `settings`. It can:
  - switch free or full;
  - refund (remove the purchase, then `refresh()`);
  - complete a pending payment;
  - set today's count to 0, 7 or 10.
- **Release:** `src/release` provides an empty `DebugTools` with the same API (no overlay, no panel).

### D11. Dependency, manifest and notices
- **Library:** `com.android.billingclient:billing-ktx`, the latest stable version at implementation time (8.x when this was written), recorded in `libs.versions.toml`.
- **NOTICE:** it gains an entry for the Billing Library under the existing section 7 exception, with "Maven groups:" lines for every group it brings onto `releaseRuntimeClasspath` (at least `com.android.billingclient`; `CheckNotice` names any other).
- **Manifest:** the merged release manifest is checked. The `com.android.vending.BILLING` permission and a `<queries>` entry for the Play Store are expected. Any `INTERNET` permission is reported to the maintainer. The privacy texts make no claim about permissions, so they stay true either way.
- **Shrinking:** R8 keeps what billing needs through its own consumer rules. A release build is checked by launching it.

### D12. Texts
- **Terms:** `docs/TERMS.md` › Purchases gains the free version's limits (10 exports a day, 10 items at a time) and the one-time full version.
- **Privacy:** `docs/PRIVACY.md` › Purchases gains one sentence: the app stores only whether the full version is active and the day's export count, on the phone.
- **Strings:** all new text goes in `strings.xml`, with "DiveLens" taken from the existing name resource.

## Risks / Trade-offs

- [No Play Console account yet, so the real billing path can't be tried] → Everything above the `Store` adapter runs on the fake store and is tested now. `PlayStore` stays a thin translation. The last task is a manual checklist on the internal testing track with a licence tester (buy, cancel, pending through Play's test cards, refund in Play Console, reinstall), done once the maintainer has the account.
- [Clock changes or reinstalls reset the free count] → Accepted in `config.yaml`. The counter is only a soft limit.
- [A refunded user who never goes online keeps the full version] → Accepted. Revocation happens at the next successful refresh.
- [Play's policy on "donations"] → Play allows tips to the developer as in-app products. Donations to charities are not allowed, and DiveLens collects none. If review objects to the word, the label becomes "Tip" with no code change beyond strings.
- [The billing library adds an unexpected permission] → D11 checks the merged release manifest and reports it before release.
- [Pending purchase completes while the app is in the background and the process dies] → The next start's `refresh()` sees it as purchased and acknowledges it (D3).
- [A release build made by a contributor without Play signing] → Play reports billing unavailable, and the app stays a working free version.

## Migration Plan

- Existing installs are only the maintainer's test phones. After the update they start in the free version with today's count at 0. A working set above 10 is kept (D6).
- **Rollback:** wiring `AlwaysAllowExportGate` and a fixed limit of 20 back in `AppGraph` restores the old behaviour. The stored flags are ignored.

## Open Questions

- The tip amounts (suggested 2, 5 and 10 in CHF and EUR) are set in Play Console and can be decided any time before release. The app shows whatever Play returns.
- The exact `billing-ktx` version is chosen at implementation time (D11).
