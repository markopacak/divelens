# DiveLens

Android app that fixes underwater photos and videos (blue-green cast, missing red, flat contrast) on the device. Photos and videos never leave your device.

## Status

Early development. Today the app imports photos and videos through the system photo picker (up to 10 items per batch in the free version, 20 in the full version) and exports colour-corrected copies to the gallery (Pictures/DiveLens): photos at full resolution with their date, camera and orientation metadata, videos as MP4 at their original resolution, frame rate and orientation, with the audio unchanged and the creation date kept. Exports never contain location. The correction is automatic: it rebuilds red from green and blue and stretches green and blue contrast, with parameters estimated per photo, and runs on the GPU (with a CPU fallback for photos). For videos the parameters are sampled every second and smoothed over time, so the colour follows depth without flicker. The grid shows corrected thumbnails with the date taken and, for videos, the duration. A full-screen viewer zooms photos to real pixels, plays videos with the correction applied live, shows the original while you press and hold, and has a strength slider per item (with "Apply to all") that the thumbnails, playback and export follow. The export runs in the background with a progress notification. Exports carry a small diveLENS watermark in the bottom-right corner, on by default; anyone can turn it off in Settings & help. The app icon, colours and watermark come from [`docs/brand/`](docs/brand/BRAND.md). The free version exports 10 items a day; the full version is a one-time Google Play purchase that removes the daily limit and holds 20 items (see [Purchases](#purchases)). Further sliders come later.

Planned work is tracked as [OpenSpec](https://github.com/Fission-AI/OpenSpec) changes in `openspec/changes/`.

## Building

Requirements: Android Studio (or JDK 17+ and the Android SDK). Min SDK 29 (Android 10).

```sh
./gradlew :app:assembleDebug            # debug APK, no setup needed
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device or emulator
```

`:core:analysis` holds the correction model and runs as plain JVM tests (`./gradlew :core:analysis:test`).

If Gradle can't find Java on macOS, point it at Android Studio's bundled JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Reference check (maintainers)

The model's coefficients are fitted to before/after pairs from RefApp by the scripts in [`tools/reference/`](tools/reference/README.md). That media is personal and never committed. With it in a local, gitignored `trainingset/` folder:

```sh
python3 -m venv .venv && .venv/bin/pip install -r tools/reference/requirements.txt
.venv/bin/python tools/reference/prepare.py    # normalised pairs in trainingset/reference/
.venv/bin/python tools/reference/fit.py --loso # refit; copy the coefficients into V1Model
./gradlew :core:analysis:test                  # ReferenceSetTest checks the quality limits
```

Without `trainingset/reference/`, `ReferenceSetTest` is skipped, so contributors and CI need nothing extra.

## Purchases

The free version's limits and the full version live in `app/src/main/java/com/markopacak/divelens/licensing/`. Debug and release builds use different stores:

- **Debug builds** use a fake store with no Google code and no account, so anyone can build and try purchases. Tapping **Full version** or a donation opens a "Simulated Google Play" dialog: Buy, Pay later, Fail or Cancel. **Settings & help › Developer (debug build)** switches between free and full, refunds (silently, noticed at the next return to the app), completes a pending payment, turns the store off to simulate Google Play unreachable, and sets today's export count to 0, 7 or 10.
- **Release builds** use Google Play Billing (`billing-ktx`, release only). Purchases only work for a build installed from Google Play, at least on the internal testing track, with a licence tester. A locally built release says "not available right now" and runs as the free version.

Play Console products (prices are set in Play Console, never in code):

| Product ID | Type | Purpose |
|---|---|---|
| `full_version` | One-time, non-consumable | The full version (1.19 CHF / 1.19 EUR) |
| `tip_small` | One-time, consumed by the app | Donation |
| `tip_medium` | One-time, consumed by the app | Donation |
| `tip_large` | One-time, consumed by the app | Donation |

## Release signing

Release builds are signed only when a `keystore.properties` file exists in the project root. It is gitignored; never commit it or the keystore. Without it, `./gradlew :app:bundleRelease` produces an unsigned bundle and debug builds are unaffected.

```properties
# keystore.properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` may also be relative to the project root, but keep the keystore outside the repository.

## License

GPL-3.0-only, with an additional permission for the Google Play Billing and Play services libraries. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The app's [privacy policy](docs/PRIVACY.md) and [terms of use](docs/TERMS.md) are kept in `docs/`. The build copies them into the app, so the two versions are always the same.
