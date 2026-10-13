# DiveLens

Android app that fixes underwater photos (blue-green cast, missing red, flat contrast) on the device. Photos and videos never leave your device.

"DiveLens" is a working name.

## Status

Early development. Today the app imports photos through the system photo picker and exports full-resolution copies to the gallery (Pictures/DiveLens), keeping date, camera and orientation metadata. Colour correction is not implemented yet.

Planned work is tracked as [OpenSpec](https://github.com/Fission-AI/OpenSpec) changes in `openspec/changes/`.

## Building

Requirements: Android Studio (or JDK 17+ and the Android SDK). Min SDK 29 (Android 10).

```sh
./gradlew :app:assembleDebug            # debug APK, no setup needed
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device or emulator
```

If Gradle can't find Java on macOS, point it at Android Studio's bundled JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

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
