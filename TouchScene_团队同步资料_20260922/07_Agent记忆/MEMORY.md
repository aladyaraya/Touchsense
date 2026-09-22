# TouchScene canonical project memory

Last updated: 2026-09-22  
Memory version: 1.3.46  
Canonical package entrypoint: `touchscene-agent/AGENTS.md`

## 1. Mission

TouchScene is an accessible photography assistant for blind and low-vision
photographers. It uses an Insta360 camera or a phone camera to interpret the
current frame, then combines speech, planar touch exploration, and haptic
feedback so the photographer can understand the scene and make their own
composition decisions.

The product promise is not "AI takes a good photo for the user." It is:

> Speech helps the photographer understand what is present; touch helps them
> perceive where it is; haptics help them independently compose and capture it.

## 2. Core product decision

Speech and touch solve different problems and should not be positioned as
competing output channels.

| Channel | Best question answered | Information form | Main risk |
|---|---|---|---|
| Speech | What is in the frame and what exceptional problem occurred? | Semantic, summarized, sequential | Occupies hearing, can lag, and can turn the user into an AI instruction follower |
| Planar touch exploration | Where are subjects, boundaries, and empty regions? | Spatial, active, embodied | Requires scanning and training; a single tablet motor is not spatially localized |
| Compact real-time haptics | Which direction should the camera move, and is framing acceptable? | Continuous, low-latency control cue | Too many codes cause confusion; device amplitudes vary |

The preferred interaction state machine is:

1. **Describe** — one short spoken scene summary.
2. **Explore** — freeze a simplified keyframe and let the user scan its tactile
   map on a tablet.
3. **Select** — the user chooses the intended subject or semantic anchor.
4. **Guide** — switch to sparse haptic steering for left/right/up/down,
   subject-loss, and framing quality.
5. **Capture** — confirm a stable frame with a distinctive pulse, optionally
   auto-capture, then speak a short result summary.

Use speech again during guidance only for semantic exceptions such as subject
loss, occlusion, closed eyes, or a major scene change.

The current detailed baseline is documented in
`touchscene-agent/REQUIREMENTS_AND_TECH_ROUTE.md`.

Critical stability rule: live acquisition and analysis may continue, but the
tactile map is frozen while the user explores it. A material camera movement
marks the map stale and requires an explicit refresh; never change the spatial
map silently under the user's finger.

## 3. Important conceptual boundary

A phone or tablet with one built-in vibration motor does **not** create a local
vibration point under the user's finger. The whole device vibrates. In the
current planar-touch concept:

- the touchscreen supplies the finger's absolute coordinate;
- hand movement and proprioception preserve the two-dimensional layout;
- vibration labels the content at the sampled coordinate.

The honest description is **active scanning of a two-dimensional tactile map
with global haptic labels**, not a tactile pixel array or raised relief display.

This interaction is valuable for layout exploration, but it is awkward during
handheld capture because one hand explores the tablet while another handles the
camera. Candidate mitigations are a camera tripod/chest mount, a frozen-frame
exploration phase before shooting, or a finger-worn/stylus actuator.

## 4. Recommended hackathon scope

### Primary demonstration

- Target Android Chrome over HTTPS.
- Accept a photo or camera capture and process it locally with Canvas.
- Reduce the image to a `64 x 48` tactile map.
- Keep four semantic states: background, subject fill, subject edge, keypoint.
- Let a finger scan the map and translate the sampled state into rhythm-based
  vibration.
- Use rhythm more than amplitude because device amplitude control is
  inconsistent.

### Enhanced hardware demonstration

- Browser connects to an ESP32-C3 through Web Bluetooth.
- A small motor mounted in a puck, stylus, or finger contact produces clearer
  feedback than a tablet chassis motor.
- BLE commands carry sequence, mode, coordinate, intensity hint, duration, and
  checksum.
- ESP32 notifies an ACK with the applied coordinate and status.
- Optional SW-420 sensing distinguishes GPIO application from detected physical
  vibration.

### Deliberately out of scope for 48 hours

- Dense electromagnetic pin arrays.
- A true localized tactile tablet surface.
- Custom multi-actuator driver boards.
- Claims of reconstructing a photograph as a physical relief.

## 5. Current implementation

### Web demo

- Deployed URL:
  `https://touchscene-360.aladyaraya.chatgpt.site/touchpuck/`
- Main files:
  - `dist/touchpuck/index.html`
  - `dist/touchpuck/app.mjs`
  - `dist/touchpuck/core.mjs`
  - `dist/touchpuck/styles.css`
- Capabilities:
  - photo/camera-file capture;
  - local grayscale and edge processing;
  - `64 x 48` tactile map;
  - touch coordinate mapping;
  - phone vibration through `navigator.vibrate()`;
  - Web Bluetooth command and notification path;
  - mock self-tests and a downloadable 100-point JSON acceptance report;
  - embedded wiring guide.

The existing demo proves the software interaction and protocol concept. It is
not yet the complete hybrid blind-photography workflow described in section 2,
and it does not prove continuous Insta360 live-video ingestion.

### Insta360 Ace SDK status

The official Ace documentation has now been reviewed. Confirmed capabilities:

- Ace currently provides an Android SDK and shares the V2.x.x SDK with the X
  and GO series; iOS and desktop support are listed as planned.
- `sdk-camera` supports device connection, capture control, camera parameters,
  real-time preview, file management, and firmware upgrade.
- Connections can be created for Wi-Fi, USB, or BLE. Pure BLE does not carry the
  preview stream; BLE can bootstrap a Wi-Fi connection by reading camera hotspot
  credentials.
- `CameraStreamListener.onStreamDataNotify()` exposes encoded preview data. A
  video frame may arrive in multiple fragments with the same timestamp and must
  be reassembled before decoding.
- Preview encoding can be H.264 or H.265 depending on camera model or firmware;
  query it at runtime before configuring `MediaCodec`.
- Capture can be started/stopped and completion paths are delivered through
  `CaptureStatusListener`.
- The documented Android baseline is minSdk 28, compile/target SDK 36, JVM 11,
  and SDK V2.x.x. Maven credentials come from the downloaded SDK demo or the
  Insta360 developer application.

Architecture consequence: the production real-time camera path should be an
Android native app, or a native shell that reuses the existing web UI through a
WebView bridge. A normal public webpage cannot directly import the proprietary
Android Camera SDK. For the shortest reuse path, decode and process sampled
frames natively, then send only the reduced `64 x 48` tactile map and status to
the WebView; do not bridge full video frames through JavaScript.

Detailed notes: `touchscene-agent/INSTA360_ACE_SDK.md`.

### Android MVP implementation status

The source-of-truth Android project is now
`actual development/android/TouchScene` (Chinese directory name:
`实际开发/android/TouchScene`). It is based on the supplied SDK Demo 2.1.5 and
retains its real Views/ViewBinding connection, preview, capture, and foreground
service code.

Implemented and desktop-build verified on 2026-09-22:

- encoded preview fragment assembly plus bounded MediaCodec YUV decoding;
- oversized encoded access units now discard the entire timestamp, including
  subsequent fragments, and recover on the next timestamp instead of sending
  a truncated unit to MediaCodec;
- preview teardown now attempts listener unregister, posture unregister,
  pipeline unbind, and SDK stream stop independently, including after start
  and restart failures; a JVM test proves later steps still run if an earlier
  cleanup call or failure logger throws;
- lens/mode SDK switch failures now produce an accessible failure cue and
  continue the appropriate preview restart/reprepare path, while coroutine
  cancellation still propagates; lens-switch busy state is cleared in `finally`;
- independent latest-frame vision and edge workers;
- configurable Gaussian/Canny/hysteresis, morphological close, largest-contour
  fill, Otsu controlled-scene fallback, and a `64 x 48` four-state map;
- immutable freeze/refresh/stale sessions and shared center-fit/letterbox mapping;
- refresh now requires a newer processed map version; without one it retains
  the frozen map and stale state, records `NO_NEW_FRAME`, and asks the user to
  try later;
- map touch release rechecks the final pointer location; lifting in letterbox
  space no longer announces the previous in-map cell;
- map and debug-layer replacements are deferred while a finger explores;
  freeze/refresh controls ask for finger lift instead of swapping the current
  spatial map during contact;
- Android rhythm haptics with deduplication, boundary cooldown, lifecycle stop,
  enable switch, and self-test;
- on-demand and five-second-sampled bundled ML Kit image labeling on the
  original YUV keyframe, merged with matching local contour geometry where
  available and backed by an honest contour-only fallback;
- interrupting Chinese TextToSpeech, SpeechRecognizer whitelist commands, and
  video-motion READY fallback;
- half-duplex speech turns: TTS begins by cancelling any active recognition,
  then resumes only an unfinished push-to-talk turn after the current
  utterance ends; normal spoken command replies do not start background ASR;
- voice photo/record mode switching now returns failure directly instead of
  waiting indefinitely for a target ViewModel mode; capture is dispatched only
  when the requested mode and capture state are ready;
- video recording start is announced only after an active SDK working/timer
  callback, with a one-shot gate; video finish says recording stopped while
  photo finish retains photo-complete feedback;
- capture confirmation speech and haptics only from the SDK finish effect;
- capture terminal callbacks are atomically deduplicated per active operation;
  a failure/cancelled capture cannot later become a false spoken success from
  a late SDK finish callback;
- official four-way posture callbacks rotate the analysis YUV before TouchMap
  creation and mark frozen maps stale on orientation changes; continuous motion
  stability still uses the configurable video-difference fallback because the
  reviewed posture API does not expose angular velocity;
- an accessible preview-page flow for freeze, explore, refresh, describe, voice
  control, capture, and haptic self-test.
- a repeatable emulator end-to-end test now launches the no-camera local demo,
  waits for its processed TouchMap, verifies capture is disabled, and exercises
  freeze, direction exploration, and on-demand description;
- a software-level TalkBack exploration fallback: four native direction buttons
  advance a frozen-map cursor, stop on the first semantic transition, announce
  grid position and cell class, and use finite rather than repeating haptics.
  Actual TalkBack focus order, speech timing, and tactile usability are not yet
  device-verified.

The on-device semantic model is now integrated at build level, while its label
accuracy and latency and all physical Ace/Android/user testing remain
unverified. Detailed status: `实际开发/IMPLEMENTATION_STATUS.md`.

### BLE protocol

- GATT service: `7b100001-6c7d-4c7a-9a31-54bf3f010001`
- Command characteristic ends in `0002`; ACK characteristic ends in `0003`.
- Command: 10 bytes, including magic, sequence, mode, X/Y, intensity, duration,
  and XOR checksum.
- ACK: 9 bytes and echoes sequence, mode, X/Y, and status.
- Only one write is in flight; newer pointer positions are coalesced.
- Browser ACK timeout: 450 ms.
- ESP32 motor safety watchdog: 600 ms.
- ACK status `0`: GPIO applied, no physical-sensor proof.
- ACK status `3`: physical vibration detected.
- ACK status `4`: physical vibration confirmation timed out.

Implementation files:

- `firmware/touchpuck_ble/touchpuck_ble.ino`
- `scripts/touchpuck-commission.ps1`
- `hardware/touchpuck-mobile-bom.csv`
- `dist/touchpuck/wiring.svg`
- `docs/android-haptic-closed-loop.md`

## 6. Evidence ledger

### Verified in software or build tooling

- Seven core Node tests pass for bounded coordinate mapping, command and ACK
  round trips, checksum rejection, fill/edge classification, ACK classification,
  and latency-summary calculation.
- Browser QA has exercised the mock 100-point mapping flow, success and timeout
  UI states, mobile layout, wiring asset loading, and JSON report generation.
- The PWA browser QA was rerun on 2026-09-22 with local Chrome and an isolated
  `playwright-core` install: HTTP 200, no browser errors, 100/100 Mock coordinate
  matches, both Mock sensor-state branches, and no desktop/mobile horizontal
  overflow. Screenshots are in `实际开发/test-reports/browser-qa-20260922/`.
- ESP32 BLE firmware compiled with the physical sensor disabled and enabled
  using Arduino ESP32 core 3.3.11.
- Firmware build observations: approximately 48% flash and 5% RAM.
- The deployed site reached version 8 at the URL above.
- The Android SDK 2.1.5 project passes 66 JVM tests and assembles debug and
  unsigned release APKs after the native
  TouchScene integration.
- Four `TactileMapView` Android instrumentation tests, one no-camera Activity
  flow test, and the SDK Demo's context smoke test ran on an API 35 Google APIs
  emulator: 6/6 pass, covering
  100 View-level taps, letterbox release, direction-button announcements,
  version refresh, multi-pointer suppression, and deferring map replacement
  until the final finger lift. The
  emulator uses ARM native translation for the arm64-only SDK APK. This is not
  physical-screen, TalkBack, motor, or Ace acceptance.
- The no-camera local-demo page was operated on that emulator: demo frame
  loaded, map v1 froze, a direction button announced a subject grid cell,
  on-demand description completed with an explicitly marked `local-contour`
  fallback, and a metadata-only JSONL session trace persisted after exit.
  A post-fix repeat confirmed that refresh with no newer frame logs
  `NO_NEW_FRAME` and keeps v1 rather than reporting a false refresh.
- The supplied `实际开发/资料/Android-SDK-2.1.5.zip` is 172,748,267 bytes;
  SHA-256 is `6651295FE89C05C4C919D885F41379FF235379E63CB711893AB1716089FD7F99`.
  Its official demo APK is present in the archive, but has not been run on the
  target device.
- The latest Android `lintDebug` report contains zero errors and 101 warnings;
  five missing English debug-layer translations were fixed after inspection.
- The old `TouchScene_Android_Ace_SDK_20260922.zip` is a raw-frame-era
  delivery, not the current MVP. The current source plus Debug and unsigned
  Release APKs were independently rebuilt and archived as
  `实际开发/交付包/TouchScene_Android_MVP_20260922_r2.zip` (SHA-256
  `27CEE784F74F58879F8C96745A31AB9C1A093673F2FCEECD0A440EBBBD91796B`).
  The archive contains 214 files without local credentials or build caches;
  its copied source passes 29 JVM tests. This is not a signed or device-tested
  release. The r2 archive remains a historical snapshot. Current 31-test
  sources and the stream-safety fixes were independently rebuilt and archived
  in `实际开发/交付包/TouchScene_Android_MVP_20260922_r3.zip` (SHA-256
  `7C3E4E32FCD54F9A0021CFB447B96C97383550907D68CDD7F9E7B0DECAD11EBD`).
  Its 216 entries exclude local configuration and build caches. Neither ZIP
  is a signed or device-tested release. The live source has since gained a
  37-test preview-switch, capture-terminal, and refresh-safety changes, so r3
  is a historical snapshot. The r4 archive was independently rebuilt
  from a 200-file source copy with zero hash differences, then passed 37 JVM
  tests, 5 API 35 emulator instrumentation tests, and Lint (0 errors, 101
  warnings). `实际开发/交付包/TouchScene_Android_MVP_20260922_r4.zip` has 221
  entries, excludes local credentials and build caches, and has SHA-256
  `566300FF044C8F0AB7F4E9E422E321B98CA8103BF8EA3E086DC9422F85A42D90`.
  It is not a signed or physical-device-tested release and predates the voice
  no-camera guard. The r5 archive was independently rebuilt from the latest
  200-file source copy with zero path/hash differences; 37 JVM and 5 API 35
  emulator tests, Debug/unsigned Release/instrumentation APKs, and Lint (0
  errors, 101 warnings) pass. `实际开发/交付包/TouchScene_Android_MVP_20260922_r5.zip`
  is 325,778,416 bytes with 221 individually hash-verified entries and SHA-256
  `E44D059835E4A617B7166D562C6BCA0463144CB666497938036E47CE718F28D1`.
  It excludes local credentials, keystores, and build caches; it is not signed
  or physically validated. The live source now includes subsequent voice,
  recording-feedback, and performance-trace changes with 49 JVM tests, so r5
  is a historical snapshot.
  A non-overwriting r6 snapshot now contains the later 66-test source. Its
  214 `app/src` files match the live source by relative path and SHA-256, and
  the independent copy passed 66 JVM tests, 6 API 35 emulator tests, three
  APK builds, and Lint 0 errors/101 warnings. The 235-entry ZIP was checked
  entry-by-entry against its delivery directory, with zero mismatches and no
  build caches, real local configuration, or keystores. The ZIP is
  `实际开发/交付包/TouchScene_Android_MVP_20260922_r6.zip`, 325,812,265 bytes,
  SHA-256 `A8E2BC470BDF936E1864C6F6584DB2AC41F76D57BA451F9AF0E75EB669BCB2B0`.
  Its Release APK remains unsigned and no physical Gate is closed by this
  archive.
  A newer r7 snapshot now includes the concave-subject keypoint fix and the
  physical-Gate evidence collector. Its independent source copy has 214
  `app/src` files matching the live source by relative path and SHA-256,
  passed 67 JVM and 6 API 35 emulator tests, Debug/unsigned Release/test APK
  builds, and Lint 0 errors/101 warnings. The 236-entry ZIP was hash-checked
  entry-by-entry against the delivery directory with zero mismatches; it is
  325,817,075 bytes with SHA-256
  `5F4893516B74BA0C65DB4EF2347E81CA3777E95BDF106F1AB84C3917A2D0FC2D`.
  No signing or physical Gate is implied.
  The r8 snapshot adds safe handling of executor rejection during preview
  shutdown. Its independent 214-file source copy matches the live source by
  path and SHA-256 and passed 68 JVM tests, 6 API 35 emulator tests, three
  APK builds, and Lint 0 errors/101 warnings. Its 236-entry ZIP is
  325,818,518 bytes with every entry hash-verified against the delivery
  directory and zero mismatches; SHA-256
  `0891B563619C9CF06D9C149633FB82A3E0AFD18E27EFC3641EBB70AFBF65A83B`.
  The Release APK is unsigned and no physical Gate is closed.
  The newer r9 snapshot incorporates the detailed-plan boundary waveform
  and an itemized acceptance audit. Its independent 214-file source copy
  matches the live source by path and SHA-256, and passed 69 JVM tests,
  6 API 35 emulator tests, three APK builds, and Lint 0 errors/101 warnings.
  The 237-entry ZIP is 325,821,960 bytes with zero entry/hash mismatches;
  SHA-256
  `B8388A7D14B029CE4AE3551BF67AA688604546324A6E6819F7DE5821B7F63404`.
  It is unsigned and does not close hardware or user Gates.

### Simulated or mocked only

- Reported mock RTT around 27-31 ms.
- Mock physical-success and physical-timeout UI states.
- Browser-level 100/100 coordinate acceptance without real BLE hardware.

These figures must never be presented as real device performance.

### Still unverified on physical hardware

- Real Android vibration behavior across phone models.
- TalkBack end-to-end flow and direction-button focus/speech/haptic behavior on
  a real Android device.
- Real Web Bluetooth RTT and packet-loss behavior.
- Actual ESP32, MOSFET, motor, and SW-420 closed-loop actuation.
- Physical confirmation rate of at least 99/100.
- Ten-minute thermal and power stability.
- Closed-eye shape recognition by representative participants.
- Continuous Insta360 camera SDK/live-stream integration on the exact Ace model
  and firmware. The official API capability is confirmed; implementation and
  device behavior are not.
- Real blind or low-vision user evaluation of the hybrid interaction.

## 7. Acceptance targets for future physical testing

| Test | Target |
|---|---|
| Coordinate and mode mapping | 100/100 correct ACKs |
| Physical actuation with sensor | At least 99/100 non-background commands return status `3` |
| BLE latency | Mean RTT below 80 ms; P95 below 150 ms |
| Disconnect safety | Motor stops within 600 ms |
| Thermal stability | Ten minutes without unsafe heating or power instability |
| Basic tactile learning study | Closed-eye recognition target at least 80%, reported with participant count and protocol |

Targets are not results.

## 8. Product research memory

- Sensory-substitution design should transmit information needed for the task,
  not all available pixels. Excess detail creates attentional overload.
- Speech is effective for scene category, identity, expressions, occlusion,
  exposure, and concise corrective instructions.
- Touch is useful for boundaries, relative position, extent, empty space, and
  user-controlled spatial exploration.
- Haptics preserve the auditory channel, which blind users may rely on for
  environmental awareness.
- Training, onset of blindness, residual vision, tactile sensitivity, and prior
  spatial experience can materially change performance. User testing must
  segment or at least record these variables.
- A relevant market precedent is Google Pixel Guided Frame, which combines scene
  description, verbal cues, vibration, and automatic capture rather than relying
  on a single channel.

Research references used in the product reasoning:

- Sensory-substitution principles and overload:
  `https://pmc.ncbi.nlm.nih.gov/articles/PMC5044782/`
- Auditory and tactile processing after visual deprivation:
  `https://pubmed.ncbi.nlm.nih.gov/22612281/`
- Cognitive-map formation from auditory and haptic information:
  `https://pubmed.ncbi.nlm.nih.gov/35902045/`
- Google Guided Frame:
  `https://support.google.com/accessibility/android/answer/14110054?hl=en`
- Chrome Web Bluetooth constraints:
  `https://developer.chrome.com/docs/capabilities/bluetooth`
- Android haptic API behavior:
  `https://developer.android.com/develop/ui/views/haptics/haptics-apis`
- Insta360 Ace Android SDK overview:
  `https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/guide/`
- Insta360 Ace Camera SDK integration guide:
  `https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/android/camera-integration/`

## 9. Open product questions

1. Is the primary capture setup handheld, tripod-mounted, chest-mounted, or
   operated through a remote tablet? This affects whether planar exploration is
   physically compatible with shooting.
2. Is the first target population congenitally blind users, late-blind users,
   low-vision users, or a mixed group?
3. Which photography task is primary: portraits, selfies, objects, street
   scenes, or documentation? Each requires different semantic anchors.
4. Which exact Insta360 Ace model, firmware, SDK package, and Maven credentials
   are available? The API family is confirmed, but model restrictions must be
   checked through the downloaded SDK and runtime support queries.
5. Should the first study compare speech-only, touch-only, and hybrid modes on
   task time, framing error, workload, confidence, and sense of authorship?
6. Is a finger-worn actuator acceptable, or must all feedback come from the
   tablet chassis?

## 10. Recommended next work

1. Turn the current demo into the five-state hybrid workflow in section 2.
2. Add a repeatable speech-only / touch-only / hybrid experiment mode.
3. Define portrait-framing metrics: subject centroid error, headroom, scale,
   clipping, capture time, correction count, and user confidence.
4. Connect one real Android device and one ESP32-C3 setup; save the 100-point
   report and thermal observations.
5. Interview or test with blind and low-vision participants before locking the
   tactile vocabulary.
6. Obtain the Insta360 SDK package/credentials and verify the exact Ace model;
   implement a native Wi-Fi preview spike before promising real-time camera
   performance.

## 11. Decision log

- **2026-09-17:** Dense pin arrays were rejected as the 48-hour primary build;
  a single moving tactile point/TouchPuck was preferred for implementation risk.
- **2026-09-18:** BLE protocol gained explicit physical-feedback states so a
  command ACK could not be misrepresented as motor actuation.
- **2026-09-21:** The active product framing changed from generic
  visual-to-tactile conversion to an accessible photography assistant.
- **2026-09-21:** Speech and touch were defined as complementary: semantics by
  speech, spatial exploration by touch, and continuous framing control by sparse
  haptics.
- **2026-09-21:** A single-motor tablet was explicitly classified as active
  planar scanning with global vibration, not a localized tactile surface.
- **2026-09-22:** Official Ace SDK documentation confirmed Android V2.x.x
  real-time preview and raw encoded stream callbacks. The main camera path was
  changed from web-only to Android native or native + WebView; PWA remains the
  fallback demonstration.
- **2026-09-22:** Requirements baseline defined `0` as silent background, `1` as
  subject feedback, and a separately computed thick boundary as a discrete
  crossing pulse. Tactile exploration uses a frozen processed frame; scene
  description is on demand and runs outside the per-frame haptic loop.
- **2026-09-22:** The native SDK Demo project gained the planned Canny TouchMap,
  frozen exploration, Android haptics/TTS/ASR, video stability fallback, and
  capture-confirmation integration. An on-device ML Kit image labeler is used
  on demand; local contour description remains its failure fallback. The Canny
  processor now rejects open contours as filled subjects, preserves empty
  scenes, and derives a boundary even after Otsu fallback. Debug layers are
  selectable in the preview UI and stay paired with a frozen TouchMap version.
  A new fixture test found and corrected foreground/background inversion in
  Otsu component scoring. READY cues are suppressed during frozen exploration,
  and on-demand description now reads the frozen source frame while exploring.
- **2026-09-22:** Scene descriptions gained nullable structured subject,
  position, size, clipping/occlusion, background, and lighting fields. Requests
  now have explicit cancellation and a three-second coordinator timeout;
  repeated requests for the same frame share one inference, while newer frames
  cancel older work. JVM tests cover timeout, cancellation, deduplication, and
  Edge-lane independence. Page pause cancels work and suppresses queued speech.
  Device-level latency remains unverified.
- **2026-09-22:** Canny now preserves full-frame aspect ratio within its
  analysis bounds, and the 64×48 map leaves letterbox cells silent. Wide and
  portrait fixtures plus 100 center-fit coordinate calculations pass in JVM
  tests. The SDK's `windowCropInfo` is documented as player/panorama rendering
  data; raw-frame-to-player crop parity is still an explicit physical Gate.
- **2026-09-22:** A bounded, metadata-only session trace now records preview,
  decoder, map, description, capture, error-code, and fallback milestones. It
  is flushed as JSONL on pause/exit; capture errors now produce an accessible
  failure cue. A 90-second main/fallback demo and physical Gate runbook were
  drafted, not performed. File persistence and full Ace flow remain unverified
  until a device is connected.
- **2026-09-22:** Static accessibility review found that map focus and a
  generic description alone did not expose individual tactile cells to
  TalkBack. Four native direction buttons now offer frozen-map exploration
  with coordinate/class announcements and finite confirmation pulses. The
  software path builds and has two navigator tests; physical TalkBack
  acceptance remains open.
- **2026-09-22:** The attached SDK ZIP was identified and hashed without
  modifying the original. A connected person-silhouette software fixture was
  added to the existing circle/rectangle processor tests; 29 JVM tests pass.
  This does not establish performance or robustness on real portrait scenes.
- **2026-09-22:** The existing delivery ZIP was found to predate the tactile
  MVP. A separate r2 package was built from current sources and verified by an
  independent offline Gradle build, 29 passing JVM tests, ZIP entry inspection,
  and APK hashes. The old package remains untouched; signed release and all
  Ace/TalkBack physical Gates remain open.
- **2026-09-22:** Stream aggregation review found an oversized timestamp could
  be reset and then emitted as a corrupt tail fragment, with a single oversized
  fragment bypassing the nominal buffer limit. The assembler now discards all
  fragments for that timestamp and resumes with a new timestamp. 30 JVM tests,
  Debug/unsigned Release builds, and Lint (0 errors, 101 warnings) pass. The
  previous r2 archive remains a historical 29-test snapshot, not this source.
- **2026-09-22:** Preview teardown review found grouped SDK cleanup would skip
  subsequent release calls after one exception, and restart-start failure lacked
  full cleanup. Shared best-effort teardown now covers normal stop, initial
  start failure, switch stop, and restart failure. An exception-isolation JVM
  test passes; 31 JVM tests, Debug/unsigned Release builds, and Lint (0 errors,
  101 warnings) pass. The current source was independently rebuilt from an r3
  delivery copy, but actual Ace resource release remains a physical Gate.
- **2026-09-22:** Lens/mode switch error review found an SDK exception could
  leave a previously stopped stream unrestarted and the lens-switch busy overlay
  stuck. Ordinary failures now report once and proceed to recovery, cancellation
  remains unswallowed, and the busy overlay clears in `finally`. 33 JVM tests,
  Debug/unsigned Release, and Lint (0 errors, 101 warnings) pass. Physical SDK
  fault injection and a refreshed delivery ZIP remain open.
- **2026-09-22:** Capture callback review found finish/error were not mutually
  exclusive in app state: a late finish could overwrite a prior error and emit
  a false success cue. An atomic terminal gate now accepts only the first
  terminal callback for an active capture and is invalidated on cancellation or
  listener detach. Three JVM tests cover ordering and concurrent claims; 36
  tests, Debug/unsigned Release, and Lint (0 errors, 101 warnings) pass. SDK
  callback behavior and rapid consecutive captures still need hardware testing.
- **2026-09-22:** Android View test preparation found an out-of-map finger
  release could announce the last valid cell. The release now remaps its own
  coordinate and resets to generic map guidance outside the image. A 100-point
  View-level instrumentation test and direction-control test compile, but no
  emulator or device exists to run them. 36 JVM tests and 7 PWA core Node tests
  pass; browser QA did not run because Playwright configuration is absent.
- **2026-09-22:** Restored an isolated Playwright Core + local Chrome browser
  QA environment and reran the PWA script successfully. The downloadable Mock
  report showed 100/100 coordinate matches, desktop/mobile layouts had no
  horizontal overflow, and both simulated physical-success and timeout UI
  states passed with zero browser errors. Mock RTT is not physical latency;
  Android instrumentation and hardware Gates remain open.
- **2026-09-22:** Installed a Google APIs API 35 emulator with ARM native
  translation and ran the Android instrumentation suite: 4/4 passed, including
  100 View-level coordinate/announcement taps. A newly found multi-finger
  freeze/refresh hazard is now guarded; View map/debug updates wait until
  finger lift, and buttons request lift during contact. 36 JVM tests, Debug
  and unsigned Release builds, and Lint (0 errors, 101 warnings) pass. This
  closes the emulator View Gate only; physical Ace, TalkBack, and haptics Gates
  remain open.
- **2026-09-22:** Ran the no-camera Android fallback as an actual emulator UI
  flow and verified freeze, direction exploration, local-contour description,
  and JSONL trace persistence. The run exposed false refresh success when no
  newer frame existed. Refresh now requires a strictly newer processed map and
  preserves stale state otherwise; a JVM regression and emulator Logcat/UI
  retest pass. Current checks: 37 JVM, 4 instrumentation, Debug/unsigned
  Release, and Lint with 0 errors/101 warnings. TTS perception and all physical
  Ace/haptic Gates remain open.
- **2026-09-22:** Added a repeatable Android Activity-level no-camera fallback
  test, including processed map availability, frozen exploration, direction
  cursor, description completion, local-demo identity, and disabled capture.
  The API 35 emulator suite passed 5/5 on two consecutive runs; 37 JVM tests,
  Debug/unsigned Release builds, and Lint (0 errors, 101 warnings) remain green.
  This strengthens fallback software evidence, not physical haptic or Ace proof.
- **2026-09-22:** Created a non-overwriting r4 delivery ZIP from the current
  source. Its 200 `app/src` files match the main project by path and SHA-256;
  the copied project independently passed 37 JVM tests, 5 emulator tests,
  Debug/unsigned Release and instrumentation APK builds, and Lint with 0
  errors/101 warnings. ZIP audit found 221 expected entries and no build cache,
  `local.properties`, or keystore. Signed release and all hardware Gates remain
  open.
- **2026-09-22:** Audited the six-command voice whitelist against the MVP
  plan. The capture button was disabled in no-camera local demo, but voice
  photo/start/stop could still enter camera mode switching. Voice capture now
  rejects local-demo or disconnected state before switching, with visible and
  spoken camera-required feedback. An Activity-level emulator regression
  exercises all three commands; 37 JVM and 5 API 35 instrumentation tests,
  Debug/unsigned Release builds, and Lint (0 errors, 101 warnings) pass.
  The r4 ZIP predates this fix and is now a historical snapshot; physical
  Ace, ASR/TTS listening, TalkBack, and haptic Gates remain open.
- **2026-09-22:** Refreshed delivery as non-overwriting r5. A copied 200-file
  source tree had zero path/hash differences from the live Android project and
  independently passed 37 JVM tests, 5 emulator instrumentation tests,
  Debug/unsigned Release and test APK builds, and Lint (0 errors, 101 warnings).
  The 221-entry ZIP was audited entry-by-entry against source hashes, excludes
  secrets and caches, and has SHA-256 E44D059835E4A617B7166D562C6BCA0463144CB666497938036E47CE718F28D1.
  Signed release and physical Ace/TalkBack/haptic Gates remain open.
- **2026-09-22:** Closed a software-level gap in the technical plan's TTS/ASR
  echo rule. TTS now signals before audio starts, cancels active recognition,
  and resumes only the interrupted push-to-talk turn after the active utterance
  ends. A completed command's spoken reply cannot reopen recognition; pause
  and destroy clear pending resume. Three new JVM tests pass (40 total), the
  existing 5 API 35 instrumentation tests still pass, Debug/unsigned Release
  builds pass, and Lint has 0 errors/101 warnings. Actual acoustic echo
  suppression and ASR callback timing remain physical-device Gates. r5 predates
  this code and is a historical snapshot.
- **2026-09-22:** Voice capture review found that failed PHOTO/VIDEO SDK mode
  switching was followed by an unbounded `StateFlow.first` wait for the mode
  that might never arrive. Preview switch helpers now propagate Boolean
  success/failure to the voice caller, which dispatches capture only after the
  target mode is ready and the capture state allows it. Three JVM tests cover
  rejection, not-ready, and exactly-once dispatch; current results are 43 JVM,
  5 API 35 instrumentation, Debug/unsigned Release, and Lint 0 errors/101
  warnings. Physical Ace mode-switch fault injection remains unverified; r5
  predates this fix.
- **2026-09-22:** Filled the MVP plan's distinct video start/stop feedback.
  Video start now waits for SDK working or recording-time evidence and passes
  a one-shot gate; video finish says recording stopped, while photo finish
  still says capture complete. Pending start cues are invalidated by failed,
  cancelled, stopped, or detached operations. Three gate tests pass (46 JVM
  total); 5 API 35 instrumentation tests, Debug/unsigned Release builds, and
  Lint 0 errors/101 warnings pass. Actual Ace callback ordering, spoken audio,
  and haptic perception remain hardware Gates; r5 predates this change.
- **2026-09-22:** Checked the detailed MVP plan's scope: first-release Guide
  means stable/READY only; left/right subject guidance, centering, and
  composition scoring are explicitly deferred. Added a bounded 64-sample
  performance window at the Android preview boundary. A pause-time metadata
  trace reports map FPS plus P95 decoded-frame receipt-to-processing and
  receipt-to-visible-map latency, marking local-demo sessions separately.
  Three new JVM metric tests pass (49 total); Debug build and Lint (0 errors,
  101 warnings) pass. This is measurement infrastructure, not an Ace-device
  throughput or touch-to-motor latency result. The r5 ZIP predates it.
- **2026-09-22:** Extended the no-camera Android Activity test to verify the
  performance trace actually reaches the persisted JSONL with a local-demo
  marker and nonzero map sample count. Current source passes 49 JVM tests,
  5/5 API 35 Google APIs emulator instrumentation tests, Debug and unsigned
  Release APK builds, and Lint 0 errors/101 warnings. The emulator proves
  trace integration only; it does not establish Ace FPS or motor latency.
- **2026-09-22:** Haptic transition review found that an edge tick suppressed
  by cooldown could leave the prior subject's repeating fill waveform running
  while the finger was already on a boundary. The gate now issues CANCEL on
  that transition, while remaining silent for further moves within the same
  boundary. Its JVM regression passes; current source has 49/49 JVM tests,
  Debug/unsigned Release builds, and Lint 0 errors/101 warnings. Perceptual
  boundary distinction and real motor behavior still require device testing.
- **2026-09-22:** Multi-pointer exploration now cancels the current haptic
  waveform and suppresses further coordinate sampling for that contact gesture.
  It retains the frozen map until the last finger lifts, then applies any
  pending map without announcing an ambiguous cell. A new API 35 View test
  covers second-finger down, first-finger up, remaining-finger move, and final
  release. Current source passes 49 JVM and 6/6 emulator instrumentation
  tests, Debug/unsigned Release builds, and Lint 0 errors/101 warnings.
  Physical touch and motor response remain unverified.
- **2026-09-22:** Implemented the MVP plan's no-hardware Fake sources: a
  demo-only YUV camera fixture generates rectangle, circle, irregular/person,
  and empty scenes for the real Canny/TouchMap chain; test-only motion,
  fixed-phrase vision, and edge drivers exercise those branches. The local
  demo now uses the shared irregular fixture. Fixed a description-timeout
  race found during parallel build: timeout now claims the request before
  cancelling the worker, so an interruption-aware describer cannot deliver
  a late success. The timeout test checks the callback on the test thread.
  Current source passes 52/52 JVM and 6/6 API 35 emulator tests,
  Debug/unsigned Release builds, and Lint 0 errors/101 warnings. Release DEX
  inspection finds the demo camera fixture but none of the three test-only
  fake drivers. This is offline software evidence, not Ace or model accuracy.
- **2026-09-22:** Completed same-frame description result deduplication.
  Concurrent calls were already coalesced, but a second request after the
  first completed still reran ML Kit. Successful results are now cached by
  source-frame timestamp and TouchMap version, so repeated queries on a
  frozen frame return immediately; a new frame is reanalyzed and failures
  remain retryable. Two JVM tests cover cache reuse, frame change, and failure
  retry. Current source passes 54/54 JVM and 6/6 API 35 emulator tests,
  Debug/unsigned Release builds, and Lint 0 errors/101 warnings. This reduces
  inference duplication but does not claim a measured on-device ML Kit latency
  or an automatic scene-change speech policy.
- **2026-09-22:** Corrected READY to fire once per stable episode instead of
  repeating every two seconds while the camera remains still. Detected motion
  rearms it; a new stable episode must still satisfy the 600 ms stability
  duration and 2000 ms cue cooldown. One new JVM regression covers movement
  followed by stability before cooldown expiry, and the existing stability
  test now covers prolonged stillness and rearming. Current source passes
  55/55 JVM tests, 6/6 API 35 emulator tests, Debug/unsigned Release builds,
  and Lint 0 errors/101 warnings. Ace motion thresholds, READY speech and
  vibration perception still require physical-device verification.
- **2026-09-22:** Implemented the plan's automatic vision path. The independent
  vision lane now schedules on-device description analysis at most once per
  five seconds while the map is LIVE; it does not wait for edge processing.
  Only a matched same-frame TouchMap is included. A scene-signature gate
  announces an initial useful result and meaningful subject/position/size/
  clipping/lighting changes after a five-second speech cooldown, never an
  unavailable-frame placeholder or unchanged scene. User-requested analysis
  preempts automatic inference; automatic speech is suppressed during active
  recognition, other TTS, a pending manual query, frozen exploration, or a
  paused view. Three new JVM tests cover the announcement gate, periodic
  independent analysis, and manual priority. Current source passes 58/58 JVM
  tests, 6/6 API 35 emulator tests, Debug/unsigned Release builds, and Lint
  0 errors/101 warnings. The emulator does not verify acoustic behavior,
  real-camera scene-change quality, or on-device ML Kit latency.
- **2026-09-22:** Completed the technical plan's immediate recent-description
  path for active queries without treating old scenes as current. In LIVE mode,
  a recent automatic description is returned synchronously only when its
  analyzed frame is at most five seconds old, the video stability state is
  STABLE, no compared-frame motion has occurred since that analysis, and no
  newer offered frame is waiting ahead of the paired tactile snapshot. Otherwise
  the requested frame is analyzed; in frozen exploration, the frozen source
  still takes precedence over any live result. Distinguishing initial MOVING
  state from measured motion prevented false cache invalidation. Pause clears
  the recent result, and delayed automatic callbacks are rejected after
  motion/expiration. Four new JVM tests cover immediate reuse, age expiration,
  movement invalidation, frozen-frame isolation, and a newer offered frame
  arriving before the edge lane. Current source passes 62/62 JVM tests, 6/6
  API 35 emulator tests, Debug/unsigned Release builds, and Lint 0 errors/
  101 warnings. The five-second freshness rule is a software policy, not a
  measured Ace-device model latency or real-world description-accuracy result.
- **2026-09-22:** Closed two remaining software gaps in the MVP interaction.
  ML Kit labels are now filtered through a conservative Chinese vocabulary
  before selecting the top three; unknown English labels cannot be read aloud
  as mixed-language semantics, and unsupported high-confidence labels no
  longer hide later recognized Chinese-mapped labels. Unmapped results retain
  the honest contour/no-description fallback; vocabulary coverage and actual
  model accuracy still need target-device evaluation. The preview page also
  gained an explicit Return to live control. Frozen exploration can now return
  to the latest live map, disable tactile scanning, and rearm READY without
  bypassing its two-second cooldown; Freeze is disabled during exploration so
  it cannot bypass Refresh's newer-map requirement. Two vocabulary and two
  state-machine JVM tests plus an expanded no-camera Activity round-trip test
  pass. Current source passes 66/66 JVM tests, 6/6 API 35 emulator tests,
  Debug/unsigned Release builds, and Lint 0 errors/101 warnings. Physical
  TalkBack focus, speech, haptic perception, and Ace behavior remain open.
- **2026-09-22:** Produced the non-overwriting r6 delivery snapshot after
  confirming r5 predates multiple MVP changes. Copied only selected source,
  build configuration, documents, and APK outputs; 214 source files matched
  the main project by path and SHA-256. The copy independently passed 66/66
  JVM and 6/6 emulator tests, Debug/unsigned Release/instrumentation APK
  builds, and Lint 0 errors/101 warnings. Three APKs were hash-matched to
  build outputs. The final 235-entry, 325,812,265-byte ZIP was verified
  entry-by-entry (zero mismatches), excluding caches, credentials, and keys;
  SHA-256 A8E2BC470BDF936E1864C6F6584DB2AC41F76D57BA451F9AF0E75EB669BCB2B0.
  Signing, Ace/TalkBack/haptic device tests, and user studies remain open.
- **2026-09-22:** Added `scripts/collect_touchscene_android_gate.ps1` and
  linked it from the Ace Pro 2 Gate runbook. The read-only collector requires
  an online physical ADB device, rejects emulator serials and reported QEMU,
  refuses ambiguous multi-device selection or evidence-directory overwrite,
  and records device properties, optional APK SHA-256, TouchSceneTrace log
  lines, battery/thermal snapshots, and the latest externally readable JSONL
  trace. Its manifest explicitly leaves every Gate unassessed. PowerShell
  parsing passed with zero errors; the actual no-device branch correctly
  exited 1 before writing evidence. `adb devices` showed no phone. The
  physical-device collection path, Ace integration, TalkBack, haptics, and
  user Gates remain unverified; r6 predates this tooling addition.
- **2026-09-22:** Tested the physical-Gate collector against an online API 35
  emulator. It exited 1 and created no evidence directory, as required; the
  emulator was then stopped. Android code review found that the TouchMap
  keypoint used a mean subject coordinate that could lie in the empty center
  of a concave subject. It now snaps to the nearest actual fill cell, with a
  U-shaped subject regression and empty-map case. The current source passed
  67/67 JVM tests with zero failures using the established `X:` path and
  nonincremental Kotlin compilation. This is software evidence only; r6
  predates the fix, and Ace, physical haptics, and user Gates remain open.
- **2026-09-22:** Rechecked the repaired source with 67/67 JVM tests, Debug,
  unsigned Release, and instrumentation APK builds, Lint 0 errors/101
  warnings, and 6/6 API 35 emulator tests. Created a non-overwriting r7
  delivery copy; its 214 `app/src` files match live source SHA-256, and it
  independently passed the same software suite. All three delivered APKs
  match independent build outputs by hash. The 236-entry ZIP was verified
  entry-by-entry with zero mismatches and no caches, real local configuration,
  or keystores; ZIP SHA-256
  5F4893516B74BA0C65DB4EF2347E81CA3777E95BDF106F1AB84C3917A2D0FC2D.
  r6 remains historical. Physical Ace, TalkBack, haptic, thermal, and user
  acceptance are still unverified.
- **2026-09-22:** Reviewed the independent latest-frame dispatcher against
  preview shutdown. Its `execute()` could reject a frame if `close()` shut
  down an executor between `offer()` and scheduling, allowing an exception to
  escape toward the camera callback. The dispatcher now catches scheduling
  rejection, clears the lane's running flag and queued frame, and leaves the
  other lane independent. A deterministic regression injects a stopped vision
  executor and verifies Edge still receives the frame. Current live source
  passed 68/68 JVM tests and built Debug; Release, Lint, and emulator were not
  rerun for this edit. The r7 ZIP predates it, and actual Ace disconnect
  behavior remains unverified.
- **2026-09-22:** Completed the missing software checks for the dispatcher
  shutdown fix: live source passed 68/68 JVM tests, Debug/unsigned Release/
  instrumentation APK builds, Lint 0 errors/101 warnings, and 6/6 API 35
  emulator tests. A non-overwriting r8 delivery copy independently passed
  the same suite; 214 `app/src` files matched the live source by relative
  path and SHA-256, and all three delivered APKs matched the copy's build
  outputs. The 236-entry ZIP had zero entry/hash mismatches and excluded
  caches, real local config, and keystores; SHA-256
  0891B563619C9CF06D9C149633FB82A3E0AFD18E27EFC3641EBB70AFBF65A83B.
  r7 is historical; physical Ace disconnect and all other hardware/user Gates
  remain unverified.
- **2026-09-22:** Audited the user-supplied architecture image, the early
  technical route, and the detailed MVP plan against the Android haptics.
  The early route suggests an edge tick/double-tick, but the image and detailed
  plan sections 20/23/39 require a long boundary pulse and a double keypoint
  pulse; plan section 0.7 prioritizes the diagram for product semantics.
  Android now uses one configurable 150 ms boundary pulse while retaining the
  20/160 ms repeating fill and 50/50/50 ms keypoint double pulse. A JVM
  waveform regression passed in the 69/69 suite and Debug built. A
  requirement-by-requirement acceptance audit was added at
  `实际开发/test-reports/mvp-acceptance-audit-20260922.md`. Release, Lint,
  emulator, and independent delivery were not rerun for this edit; r8 is a
  historical snapshot. Real tactile distinguishability, Ace, TalkBack,
  performance, and user Gates remain open.
- **2026-09-22:** Completed the full software regression for the boundary
  waveform change: live source passed 69/69 JVM tests, Debug/unsigned
  Release/instrumentation APK builds, Lint 0 errors/101 warnings, and 6/6
  API 35 emulator tests. Created a non-overwriting r9 delivery copy with 214
  `app/src` files identical to live source by relative path and SHA-256; the
  copy independently passed the same suite. Delivered APKs hash-match the
  copy's build outputs. The 237-entry ZIP was verified entry-by-entry with
  zero mismatches and excluded caches, real local config, and keystores;
  SHA-256 B8388A7D14B029CE4AE3551BF67AA688604546324A6E6819F7DE5821B7F63404.
  r8 is historical. Actual Ace, Android motor, TalkBack, thermal, and human
  acceptance remain unverified.
- **2026-09-22:** Added a clearly marked fake-ADB fixture and repeatable
  collector self-test in `scripts/tests/`. The simulated online-device path
  passed assertions for device properties, supplied-file SHA-256, filtering
  only `TouchSceneTrace` lines, latest JSONL pull, and a manifest that still
  says `gate_result=not_assessed_by_collector`. Both PowerShell scripts parsed
  with zero errors, the self-test exited 0, and its temporary directory was
  cleaned. The real collector code was unchanged; r9 predates the test files.
  This is simulated tooling evidence only, not physical ADB/Ace/haptic proof.
- **2026-09-22:** Revalidated the acceptance matrix after r9 and corrected
  its stale delivery row: the boundary waveform fix is in the independently
  verified r9 ZIP, while signing and all physical/user evidence remain open.
  The r9 archive was not overwritten; its enclosed matrix is the earlier
  packaging snapshot, and the main repository's audit is current. The r9 ZIP
  SHA-256 was rechecked unchanged at
  B8388A7D14B029CE4AE3551BF67AA688604546324A6E6819F7DE5821B7F63404.
  ADB still listed no physical device.
- **2026-09-22:** Prepared a focused, non-overwriting physical-connectivity
  handoff at `实际开发/实体连接性测试资料包_20260922/` and its ZIP. It contains the
  original SDK 2.1.5 Demo APK, r9 TouchScene Debug APK, evidence collector,
  full Gate/audit references, a connectivity record template, and a verified
  seven-file SHA-256 manifest. ZIP has eight entries, each hash-matched to the
  directory; SHA-256 `3011E36AF71FB67ABEC7056A72CF3E6E4B95C1EE7D8CD66084C2023210818C16`.
  Read-only APK inspection found both apps use `com.insta360.kmpsdk.demo` but
  different signing certificates. The handoff requires a Demo-first test and
  explicitly warns that switching requires an intentional uninstall that
  clears app data. The user will perform actual connectivity tests; no real
  Ace/Android Gate is marked passed by packaging or inspection.
- **2026-09-22:** Added a current-state summary at
  `实际开发/TouchScene_当前实现与待办总结_20260922.md`. It reconciles the two
  requirements baselines, r9 software evidence, and the physical acceptance
  audit into implemented/software-verified, implemented-but-device-unverified,
  test-driven follow-up, and explicitly out-of-v1.1 scope. No new runtime
  code or physical tests were performed; the latest verified Android build
  remains r9, with 69 JVM and 6 emulator tests, unsigned Release, and all
  Ace/Android motor/TalkBack/user Gates still open.
- **2026-09-22:** Assembled a team-sync snapshot at
  `实际开发/TouchScene_团队同步资料_20260922/` containing the current r9 Android
  source/APK archive, focused physical-connectivity package, original supplied
  SDK archive, current requirements/plan/status/audit/runbook, architecture
  image, early TouchScene DOCX/reference material clearly separated as
  historical, Web/PWA and ESP32 prototype files, validation reports/scripts,
  and this portable Agent memory. The README distinguishes vendor material,
  verified software, simulated evidence, and unrun physical Gates. Historical
  r2-r8 releases and unrelated StoryBean/FrameFit materials are intentionally
  excluded. Packaging is not a new functional or device test.

## 12. Repository caveats

The repository also contains StoryBean, FrameFit, a writing-robot route, a
magnetic drawing-board route, and older dense tactile hardware concepts. They
are historical or separate experiments. Do not infer the active TouchScene
direction from the root `README.md` alone; start with this memory and then inspect
the relevant code.
