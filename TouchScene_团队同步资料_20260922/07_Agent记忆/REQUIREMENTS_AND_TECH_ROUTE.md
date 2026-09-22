# TouchScene requirements and technical route

Version: 1.0  
Updated: 2026-09-22  
Status: baseline proposal for implementation and review

## 1. Product statement

TouchScene converts the live view from an Insta360 Ace camera into two
complementary non-visual outputs for blind and low-vision photographers:

1. a stable two-dimensional map that can be explored by touching an Android
   screen and feeling haptic labels;
2. a spoken description of the original scene.

The first release helps a user understand a subject's rough shape, position,
and boundary. It does not claim to reproduce the full visual detail of a photo
or create localized tactile pixels on the screen.

## 2. Corrected data flow

The Camera SDK transports the camera preview to Android. Image processing occurs
in the Android application, not inside the camera:

```text
Insta360 Ace camera
  -> Camera SDK over Wi-Fi or USB
  -> H.264/H.265 encoded PreviewStreamFrame fragments
  -> aggregate fragments with the same timestamp
  -> Android MediaCodec decode
  -> YUV/RGB frame
  -> denoise / grayscale / segmentation or threshold / morphology
  -> binary subject mask B(x,y)
  -> boundary map E(x,y)
  -> 64 x 48 tactile map
  -> screen touch coordinate lookup
  -> Android vibration effect

Original decoded frame
  -> scene-description service
  -> description text
  -> Android TextToSpeech
```

Pure BLE cannot carry the Ace preview stream. BLE may discover/wake the camera
and bootstrap its Wi-Fi credentials; live preview must use Wi-Fi or USB.

## 3. Interaction model

### 3.1 Live mode

- The application continuously receives and analyzes sampled camera frames.
- It shows the live image and tracks whether the camera or scene is stable.
- It does not continuously speak every analysis result.
- A user can request a scene description or freeze the current tactile frame.

### 3.2 Explore mode

- Entering explore mode freezes one processed frame and its tactile map.
- The map must remain unchanged while a finger is touching it.
- The user scans the screen to learn subject position, extent, and boundary.
- A deliberate refresh action replaces the map with a newer frame.
- If the camera moves materially after the freeze, the app announces that the
  tactile map is stale and asks the user to refresh; it must not silently move
  the map under the finger.

This distinction is mandatory: camera acquisition may be real time, but a
spatial map must be stable during tactile exploration.

### 3.3 Description mode

- The user explicitly requests "describe scene", or the application offers a
  new description after a major scene change.
- The description should start with subject, relative position, and framing
  risk, then optionally mention background context.
- Speech must be interruptible and must not block touch exploration.
- Repeated descriptions should be rate-limited and deduplicated.

## 4. Binary and boundary representation

Define the simplified subject mask:

- `B(x,y) = 0`: background or ignored region;
- `B(x,y) = 1`: selected subject region.

Do not derive the edge by testing for an exact threshold value. Create a
separate boundary map, for example with a morphological gradient:

```text
E = dilate(B, kernel) XOR erode(B, kernel)
```

The boundary should be thickened to approximately 2-4 cells on the `64 x 48`
map so it can be found reliably with a finger. Rendering priority is:

```text
boundary > subject fill > background
```

### MVP processing path

For controlled, high-contrast scenes:

1. downscale;
2. grayscale;
3. median or bilateral denoise;
4. Otsu or adaptive threshold;
5. morphological opening/closing;
6. remove small connected components;
7. keep the selected or largest 1-3 components;
8. create fill and boundary maps;
9. resize to `64 x 48` with nearest-neighbor semantics.

### Robust processing path

Global binary thresholding is not sufficient for ordinary photography because
textures, shadows, and lighting changes create many false regions. The preferred
product route is:

1. detect or segment a semantic subject;
2. let the user or scene-description system select the intended subject;
3. simplify that subject mask;
4. compute fill and boundary from the mask.

Thresholding remains a hackathon fallback and a useful controlled demonstration,
not the final general-scene algorithm.

## 5. Haptic vocabulary

Baseline meaning requested by the product team:

| Sampled state | Output | Engineering behavior |
|---|---|---|
| Background `0` | No vibration | Cancel any active waveform immediately |
| Subject `1` | Sustained feedback | Prefer a low-duty repeating waveform rather than an unlimited full-strength buzz |
| Boundary `E=1` | Distinct point/tick feedback | Play a crisp tick or double-tick when entering/crossing the boundary |

Recommended fallback waveform vocabulary:

- background: silence;
- subject fill: `20 ms on / 160 ms off`, repeating while the finger remains in
  the subject;
- boundary entry: `35 ms on / 55 ms off / 35 ms on` once;
- freeze/refresh confirmation: one predefined Android confirmation effect;
- error or stale map: a separate reject pattern plus speech.

The exact values are starting parameters for user testing, not validated
perceptual thresholds.

### Trigger rules

- Do not restart vibration on every `ACTION_MOVE` event.
- Cache the previous grid cell and semantic state.
- Trigger the edge pattern only when entering an edge cell or when the binary
  state changes from `0 -> 1` or `1 -> 0`.
- Use a short cooldown to prevent noisy repeated ticks when the finger jitters
  near the edge.
- Stop all vibration on `ACTION_UP`, `ACTION_CANCEL`, screen exit, or app pause.

Android haptic APIs and actuator capabilities differ by device. Use rhythm as
the primary semantic dimension and amplitude only as an enhancement after
checking support.

## 6. Touch-to-image coordinate mapping

The visible processed image and tactile map must use the same crop, rotation,
and aspect ratio. Account for letterboxing:

```text
u = clamp((touchX - contentLeft) / contentWidth, 0, 0.999...)
v = clamp((touchY - contentTop)  / contentHeight, 0, 0.999...)
gridX = floor(u * 64)
gridY = floor(v * 48)
```

Touches outside the displayed image content are background. Apply Ace posture
rotation and any preview crop before generating and displaying the tactile map.
The mapping must be tested at all four corners, the center, and letterbox areas.

## 7. Scene-description route

Separate description generation from speech synthesis:

```text
frame -> SceneDescriber -> Chinese text -> Android TextToSpeech
```

- `SceneDescriber` may be a cloud multimodal model or an on-device model.
- Android `TextToSpeech` only reads text; it does not understand the image.
- For the hackathon, description should run on demand from a compressed keyframe,
  not on every live frame.
- The response format should be constrained to: primary subject, position,
  approximate size, clipping/occlusion, and short background context.
- Speech output should use queue replacement for a new explicit request rather
  than accumulating stale descriptions.

Important network risk: connecting the phone to the camera hotspot can affect
Internet routing. If the description model is cloud-based, test cellular routing
while the SDK remains connected to camera Wi-Fi. Provide a local or precomputed
fallback for the stage demonstration.

## 8. Android architecture

Suggested modules:

| Module | Responsibility |
|---|---|
| `CameraGateway` | Insta360 SDK initialization, Wi-Fi/USB connection, preview lifecycle, capture status |
| `PreviewDecoder` | Timestamp fragment aggregation, codec detection, MediaCodec decode, bounded buffers |
| `FrameSampler` | Drops excess frames and emits only the latest frame at the analysis rate |
| `TactileProcessor` | Denoise, mask creation, morphology, edge extraction, `64 x 48` map |
| `TactileSession` | Freezes/refreshes the map and tracks stale-map state |
| `TouchMapper` | Maps screen coordinates to the exact displayed map |
| `HapticRenderer` | Background/fill/edge state machine and device-capability fallbacks |
| `SceneDescriber` | Produces structured Chinese scene descriptions |
| `SpeechRenderer` | Android TextToSpeech lifecycle, interruption, and rate limiting |

Use latest-frame backpressure: if processing is busy, discard old preview frames
instead of building a queue. The SDK callback thread must never perform decoding,
segmentation, network access, or speech directly.

## 9. Functional requirements

- `FR-01`: connect to a supported Ace camera and report accessible connection
  status.
- `FR-02`: open and close the preview without leaking SDK or decoder resources.
- `FR-03`: generate a stable binary subject mask and separate boundary map.
- `FR-04`: freeze a tactile frame before exploration.
- `FR-05`: map every valid screen touch to exactly one `64 x 48` cell.
- `FR-06`: render background, subject, and boundary with distinguishable haptic
  behavior.
- `FR-07`: stop haptics immediately when contact or the session ends.
- `FR-08`: produce an on-demand Chinese description of the original frame.
- `FR-09`: speak descriptions through Android TextToSpeech and allow interruption.
- `FR-10`: capture a photo through the SDK and confirm only after the SDK reports
  capture completion.
- `FR-11`: preserve the existing phone-camera/PWA path as a no-SDK fallback.

## 10. Non-functional targets

These are acceptance targets, not current results:

- tactile-map generation at least 5 updates per second in live mode;
- touch-to-haptic response below 80 ms locally;
- no map mutation while a finger is exploring;
- no unbounded stream-frame queue;
- ten minutes of preview without crash or unsafe heating;
- four corners and center map correctly in 100/100 repeated trials;
- background, fill, and edge achieve at least 80% recognition in a documented
  closed-eye pilot before formal blind-user testing;
- camera disconnect, app pause, and touch cancellation always stop vibration.

## 11. 48-hour build order

1. Run the official Ace Android demo and prove Wi-Fi preview on the exact camera.
2. Build a native screen with SDK preview and capture control.
3. Add decoded-frame sampling and a controlled-scene threshold pipeline.
4. Add freeze/refresh and verify coordinate mapping visually.
5. Add background/fill/edge haptics and lifecycle safety.
6. Add on-demand scene description and TextToSpeech.
7. Add semantic segmentation only if the threshold pipeline is already stable.
8. Test with fixed demo scenes, then run a closed-eye pilot and record results.

## 12. Open decisions

- Exact Ace model and firmware.
- Whether the app UI is fully native or reuses the current WebView interface.
- Portrait, object, selfie, or general-scene first use case.
- Subject-selection method: automatic primary subject, spoken list, or touch
  selection.
- Cloud or on-device scene-description model.
- Whether the tablet's built-in motor is sufficient for the stage demo or an
  ESP32/finger actuator is required.

## 13. References

- Insta360 Ace Camera SDK integration:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/camera-integration/
- Insta360 Ace Android environment:
  https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/environment/
- Android haptics:
  https://developer.android.com/develop/ui/views/haptics
- Android TextToSpeech:
  https://developer.android.com/reference/android/speech/tts/TextToSpeech
