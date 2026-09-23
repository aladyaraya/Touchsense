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

## Post-r9 develop changes

The develop tree has diverged from the frozen r9 delivery. Nothing below has passed a physical-device gate; each item is labelled with the evidence it does carry.

- Phone-version haptic ported to an `activeValue` state machine in `AndroidHapticRenderer`. Cursor moves that stay on the same value produce no vibration. **Evidence: JVM unit tests + local `assembleDebug` green; on-device behaviour unverified.**
- New `PHOTO_BINARY` debug layer added to the layer cycle. Rec.709 luma, short-edge downscale to 64 with aspect ratio preserved, fixed threshold 63 for `0/1` classification. **Evidence: JVM tests (`photo binary threshold splits luminance around 63`) + local build green; on-device visual alignment unverified.**
- Haptic model reduced to transition-only single taps. `render(value)` and `renderDiscrete(value)` emit exactly one `VibrationEffect.createOneShot(TAP_DURATION_MS = 45L, DEFAULT_AMPLITUDE)` when the current value differs from the previously observed value; identical repeated values are silent, and the first activation from the sentinel `-1` is silent to avoid a spurious touch-down tap. Replaces the previous `SUSTAINED_TIMINGS = longArrayOf(0L, 20L, 160L)` waveform on value `1`. **Evidence: JVM test asserts `TAP_DURATION_MS == 45L`; local build green; perceived cadence on a real motor unverified.**
- New `VoiceCommand.ReturnLive` maps the Chinese phrases `恢复视频流 / 取消冻结 / 解冻 / 解冻画面 / 暂停冻结 / 返回实时 / 恢复实时` to the existing `returnLiveBtn.performClick()` handler. No new UI; the underlying `TactileSession.leaveExploration()` path is unchanged, so the freeze is released and LIVE resumes exactly as if the button had been tapped. **Evidence: JVM parser tests; local build green; on-device speech recognition path unverified.**

Local verification of the above set:

```text
:app:testDebugUnitTest  BUILD SUCCESSFUL
:app:assembleDebug      BUILD SUCCESSFUL
```

What remains unverified after these changes is the same open list from r9: Ace Pro 2 connectivity, physical haptic perception on the target device, TalkBack flow, and any user study. The changes above only shift the software surface; they do not consume any of the physical gates that r9 still owes.

## Web tests

From the repository root:

```sh
node --test tests/touchpuck-core.test.mjs
```

`tests/touchpuck-browser-qa.mjs` requires Playwright Core and a local browser. Open `web/index.html` through an appropriate secure context for Web Bluetooth.
