# Insta360 Ace SDK integration note

Reviewed: 2026-09-22  
Source: official Insta360 developer documentation  
Status: API capability verified; SDK package and physical camera integration not
yet verified in this repository.

## Decision

Use the Ace Android Camera SDK for the real-time competition build. Keep the
existing PWA as a fallback and interaction prototype, not as the direct Ace SDK
host.

The shortest credible architecture is:

```text
Ace camera
  -> BLE discovery / Wi-Fi credential bootstrap
  -> camera Wi-Fi connection
  -> Camera SDK encoded preview callback
  -> timestamp-fragment aggregation
  -> MediaCodec H.264/H.265 decode
  -> sampled low-resolution CV processing
  -> 64 x 48 tactile map
  -> native haptics or map/status bridge to existing WebView UI
  -> optional ESP32 BLE actuator
```

Do not send full decoded video frames through the WebView JavaScript bridge.
Process frames natively and bridge only the compact tactile map, scene summary,
and state changes.

## Officially confirmed capabilities

| Area | Confirmed behavior | Product use |
|---|---|---|
| Platform | Ace has Android support; iOS and desktop are listed as planned | Build the real camera prototype on Android |
| SDK generation | V2.x.x is current and shared by X, Ace, and GO | Do not use V1 examples |
| Camera connection | `CameraDevice` supports Wi-Fi, USB, and BLE instances | Prefer Wi-Fi for a mobile demo; USB is a fallback experiment |
| BLE | Can scan/connect, wake the camera, and read Wi-Fi credentials | Use BLE for onboarding, not video |
| Preview | Wi-Fi supports real-time preview; pure BLE does not | A web-BLE-only camera path is invalid |
| Stream callback | `onStreamDataNotify(PreviewStreamFrame)` exposes encoded stream fragments | Enables custom decoding, frame sampling, and CV |
| Fragmentation | Same-timestamp fragments must be concatenated into a complete encoded frame | Required before feeding `MediaCodec` |
| Codec | Preview may be H.264 or H.265; query at runtime | Configure AVC or HEVC decoder dynamically |
| Capture | `startCapture()`, `stopCapture()`, and capture status callbacks are exposed | Allow haptic-guided auto-capture and reliable completion feedback |
| Files | Wi-Fi supports media list and HTTP-based download | Optional post-capture quality check |
| Status | Battery, storage, temperature, and disconnect listeners are exposed | Convert critical failures to concise accessible alerts |

## Documented Android baseline

- Android Studio Ladybug 2024.2.1 or later
- JDK/JVM target 11 or later
- Gradle 8.11.1 or later
- Android Gradle Plugin 8.7.3
- Kotlin 2.3.20
- compileSdk / targetSdk 36
- minSdk 28 (Android 9)
- Camera artifact: `com.arashivision.sdk:sdk-camera:2.x.x`
- Media artifact: `com.arashivision.sdk:sdk-media:2.x.x`

The Maven repository requires credentials supplied in the downloaded SDK demo
or through the Insta360 developer application. Do not commit credentials.

## Recommended 48-hour implementation slice

### P0: prove the camera loop

1. Obtain the official SDK demo, Maven credentials, exact SDK version, Ace
   model, and current camera firmware.
2. Start a minimal Kotlin Android project using the documented SDK baseline.
3. Initialize `InstaCameraSDK` in `Application.onCreate()`.
4. Connect over Wi-Fi. If necessary, use BLE only to read the hotspot SSID and
   password, bind the Android process to the resulting network, then reconnect
   through a Wi-Fi `CameraDevice`.
5. Start preview and show the SDK-provided `InstaCapturePlayerView` first. This
   separates camera connectivity from the CV pipeline.
6. Add the raw stream callback, aggregate fragments by timestamp, query codec,
   and decode with `MediaCodec`.
7. Sample frames at an initial target of 5-10 analysis updates per second; do
   not run segmentation on every camera frame.

### P1: connect the tactile loop

1. Downscale the sampled frame before inference.
2. Produce only background, subject fill, edge, and keypoint classes.
3. Generate a `64 x 48` byte map.
4. If reusing the web UI, send that map through a narrow WebView bridge.
5. Trigger Android `VibratorManager` patterns or send the existing ESP32 BLE
   command packet.
6. Keep image analysis and haptics asynchronous so slow CV never blocks camera
   stream callbacks.

### Stop-loss order

1. If raw decode blocks progress, retain `InstaCapturePlayerView` and analyze
   periodic captured stills.
2. If Ace connection remains unstable, demonstrate the full interaction with
   the phone camera/PWA and show the verified Ace integration boundary.
3. Do not spend the final testing block debugging dense tactile hardware.

## Risks requiring real tests

- Exact Ace model support and feature restrictions are not enumerated on the
  overview page; check the downloaded SDK, `getSupportCameraType()`, camera
  firmware, and runtime supported parameters.
- Documentation exposes encoded stream data, not ready-to-use RGB tensors.
  Decoding and extracting inference frames still require an Android pipeline.
- Binding the process to the camera Wi-Fi changes routing for the whole app and
  must be undone on disconnect. Cloud AI may lose Internet connectivity while
  bound to the camera hotspot; prefer on-device CV for the live loop.
- H.264/H.265 variation, resolution, bitrate, thermals, and latency must be
  measured on the actual camera and phone.
- The current repository does not contain the proprietary SDK artifact or
  credentials, so compilation against the Ace SDK is not yet possible here.

## Acceptance gates

- Camera connects and reconnects without restarting the app.
- Preview opens and the first frame is reported.
- Ten minutes of preview without unbounded fragment-buffer growth.
- Analysis updates at least 5 times per second on the target phone.
- End-to-end frame-to-haptic P95 is measured and reported, not inferred.
- Capture completion is announced only after `onCaptureFinish`.
- Network binding is always released after disconnect.

## Official references

- Ace SDK series overview:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/
- Android SDK overview:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/guide/
- Camera SDK integration guide:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/camera-integration/
- Camera SDK API:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/camera-api/
- Android environment:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/environment/
