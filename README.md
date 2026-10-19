# DiveLens

Android app that fixes underwater photos and videos (blue-green cast, missing red, flat contrast) on the device. Photos and videos never leave your device.

"DiveLens" is a working name.

## Status

Early development. Today the app imports photos and videos through the system photo picker (up to 20 items per batch) and exports colour-corrected copies to the gallery (Pictures/DiveLens): photos at full resolution with their date, camera and orientation metadata, videos as MP4 at their original resolution, frame rate and orientation, with the audio unchanged and the creation date kept. Exports never contain location. The correction is automatic: it rebuilds red from green and blue and stretches green and blue contrast, with parameters estimated per photo, and runs on the GPU (with a CPU fallback for photos). For videos the parameters are sampled every second and smoothed over time, so the colour follows depth without flicker. The grid shows corrected thumbnails with the date taken and, for videos, the duration. A full-screen viewer zooms photos to real pixels, plays videos with the correction applied live, shows the original while you press and hold, and has a strength slider per item (with "Apply to all") that the thumbnails, playback and export follow. The export runs in the background with a progress notification. Further sliders come later.

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
