# TouchScene source

This `develop` branch contains the editable TouchScene source tree. The Android MVP is based on the Insta360 Android SDK Demo 2.1.5 and targets the Ace Pro 2. The Web/ESP32 prototype is kept separately as an interaction and hardware fallback.

## Layout

- `android/`: Android Gradle project, application, resources, and JVM/instrumentation tests. Open this directory in Android Studio.
- `web/`: browser/PWA prototype.
- `firmware/`: ESP32-C3 BLE actuator firmware.
- `tests/`: Web tests and the Android evidence collector's fake-ADB test.
- `scripts/`: device commissioning and evidence collection scripts.

The Android source comes from the r9 delivery snapshot dated 2026-09-22. Built APKs, SDK archives, handoff ZIPs, and historical planning documents are not part of this branch's current file tree. The original handoff remains on `main`.

## Android setup

Use JDK 17 and Android SDK 35. Copy `android/local.properties.example` to `android/local.properties`, set your local SDK path, and obtain Insta360 Maven access from the authorized developer account if dependencies are not already cached. Keep real credentials out of Git.

From `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

With an Android test device or compatible emulator available:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The r9 delivery was previously verified with 69 JVM tests and 6 API 35 emulator tests. Ace camera connection, physical haptics, TalkBack, and user testing still need device evidence.

## Web tests

From the repository root:

```sh
node --test tests/touchpuck-core.test.mjs
```

`tests/touchpuck-browser-qa.mjs` requires Playwright Core and a local browser. Open `web/index.html` through an appropriate secure context for Web Bluetooth.
