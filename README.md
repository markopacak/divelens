# DiveLens

Android app that fixes underwater photos (blue-green cast, missing red, flat contrast) on the device. Photos and videos never leave your device.

"DiveLens" is a working name.

## Status

Early development. Today the app imports photos through the system photo picker and exports colour-corrected, full-resolution copies to the gallery (Pictures/DiveLens), keeping date, camera and orientation metadata. The correction is automatic: it rebuilds red from green and blue and stretches green and blue contrast, with parameters estimated per photo, and runs on the GPU (with a CPU fallback). The grid shows corrected thumbnails; a full-screen viewer zooms to real pixels, shows the original while you press and hold, and has a strength slider per photo (with "Apply to all") that the thumbnails and the export follow. Further sliders and video come later.

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
