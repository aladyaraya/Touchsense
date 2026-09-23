# Post-r9 Handoff — TouchScene Android (develop)

Date: 2026-09-22
Base delivery: `02_Android_MVP/TouchScene_Android_MVP_20260922_r9/` (frozen snapshot)
Editable tree: `Touchsense/android/`
Target: Insta360 Ace Pro 2 + Android app `com.insta360.kmpsdk.demo`

This handoff covers only the develop-branch changes made after the r9 delivery. r9 itself remains the last snapshot with a full verification matrix; every item below is labelled with the evidence that actually exists.

## 1. Evidence discipline

Three separate buckets. Do not merge them in status reports.

- **verified (local)** — reproducible on this machine: JVM unit tests, `./gradlew :app:testDebugUnitTest :app:assembleDebug` green.
- **simulated** — behaviour proven by unit test or emulator only. Does not imply the same behaviour on hardware.
- **unverified** — no Ace Pro 2, real motor, real microphone, or user study evidence.

r9's open gates (real Ace connectivity, physical haptic perception, TalkBack, user study) remain open. The changes below do not consume any of them.

## 2. Changes since r9

### 2.1 Phone-version haptic port — activeValue state machine

`AndroidHapticRenderer` now holds a single `activeValue` (initially `-1`). Repeated identical values are silent; the renderer only reacts when the value crosses.

- File: `Touchsense/android/app/src/main/java/com/insta360/kmpsdk/demo/touchscene/AndroidHapticRenderer.kt`
- Public surface unchanged: `render(value)`, `renderDiscrete(value)`, `playSuccess()`, `playError()`, `selfTest()`, `cancel()`, `setEnabled(value)`.
- Evidence: JVM regression covering the state machine + local `assembleDebug` green. **On-device motor cadence unverified.**

### 2.2 PHOTO_BINARY debug layer

A new debug layer parallel to `TOUCH_MAP` / `GRAY` / `CANNY` etc. Binarizes the raw YUV frame directly, without going through the tactile pipeline.

- Rec.709 luma: `Y = floor((2126R + 7152G + 722B) / 10000)`, computed straight from the Y plane.
- Short-edge downscale to 64 preserving aspect ratio (`PhotoBinaryProcessor.SHORT_EDGE`).
- Fixed threshold **63**: `Y < 63 → 0`, `Y >= 63 → 1`. Mirrors the phone-version rule so black/white classification is byte-identical between the two apps.
- Evidence: `photo binary threshold splits luminance around 63` JVM test + local build. **On-device visual alignment unverified.**

### 2.3 Transition-only 45 ms haptic taps

Previously value `1` triggered a sustained `SUSTAINED_TIMINGS = longArrayOf(0L, 20L, 160L)` waveform. Now every transition — `0 → 1` and `1 → 0` — fires a single `VibrationEffect.createOneShot(45L, DEFAULT_AMPLITUDE)` and nothing else.

- Constant: `AndroidHapticRenderer.TAP_DURATION_MS = 45L`.
- First activation (`activeValue == -1 → v`) stays silent to avoid a touch-down tap.
- `renderDiscrete()` follows the same rule so cursor-key exploration matches finger exploration.
- Evidence: JVM test asserts the constant; local build green. **Perceived cadence on a real motor unverified.**

### 2.4 `VoiceCommand.ReturnLive` — voice unfreeze

New whitelist entry that ends an EXPLORING session by voice, mirroring the existing "返回实时" button.

- Files:
  - `Touchsense/android/app/src/main/java/com/insta360/kmpsdk/demo/touchscene/VoiceCommand.kt`
  - `Touchsense/android/app/src/main/java/com/insta360/kmpsdk/demo/ui/capture/PreviewFragment.kt`
- Phrases mapped to `ReturnLive`: `恢复视频流 / 取消冻结 / 解冻 / 解冻画面 / 暂停冻结 / 返回实时 / 恢复实时`.
- Dispatch is a single line: `VoiceCommand.ReturnLive -> binding.returnLiveBtn.performClick()`. No UI additions; `TactileSession.leaveExploration()` is unchanged, so the freeze release, snapshot invalidation, and LIVE recovery all follow the button path.
- Evidence: JVM parser tests + local build. **On-device speech recognition path unverified.**

## 3. Local verification

Run from `Touchsense/android/`:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Last run this session: `BUILD SUCCESSFUL`. Release APK, Lint, and emulator instrumentation were not re-run for this delta — r9's numbers still stand for those.

## 4. Standing constraints (unchanged)

- Credentials (keystore, `local.properties`, Insta360 Maven credentials, AI key) never enter the shared handoff folder or Git.
- AI endpoint/key are injected via Gradle properties `touchscene.ai.endpoint` and `touchscene.ai.key` only.
- Cellular AI requests must use per-connection binding via `network.openConnection`; never `bindProcessToNetwork` (steals the camera Wi-Fi route).
- Insta360 SDK 2.1.5 is vendor material under license.
- Evidence discipline: verified / simulated / unverified stay strictly separated. Simulated results must not be promoted to physical-device-verified.

## 5. What to do next on hardware

The develop tree is ready for another physical pass. On an Ace Pro 2 paired to a phone with the develop APK:

1. Freeze a scene, verify a single 45 ms tap at each `0↔1` boundary during finger sweep, silence while dwelling.
2. Cycle to `PHOTO_BINARY` and confirm the black/white split matches the phone-version reference on the same scene.
3. With the freeze active, say each of the seven `ReturnLive` phrases; verify each one unfreezes and re-arms READY exactly as the "返回实时" button does.
4. Record the trace JSONL from `getExternalFilesDir("touchscene-traces")` for the session.

None of the above is verified today.
