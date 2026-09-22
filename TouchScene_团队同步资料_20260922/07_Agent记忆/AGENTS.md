# TouchScene agent instructions

This folder is a self-contained memory and handoff package for **TouchScene: an
accessible photography assistant for blind and low-vision photographers**.

## Required read order

1. Read [`MEMORY.md`](MEMORY.md) completely.
2. Read [`state.json`](state.json) when structured status is useful.
3. Read [`REQUIREMENTS_AND_TECH_ROUTE.md`](REQUIREMENTS_AND_TECH_ROUTE.md) before
   changing product behavior or architecture.
4. For camera integration, read [`INSTA360_ACE_SDK.md`](INSTA360_ACE_SDK.md).
5. Open only the implementation documents linked from the memory file that are
   relevant to the current task.

## Current direction

- Do not frame speech and touch as substitutes. Use speech for scene semantics,
  planar touch for spatial exploration, and compact haptics for continuous
  framing corrections.
- The preferred flow is: **describe -> explore -> select subject -> guide
  framing -> confirm capture**.
- The browser/ESP32 prototype is evidence for the interaction and transport
  chain; it is not proof of a localized tactile tablet or a tested Insta360
  live-stream integration.
- A tablet or phone with one vibration motor provides global vibration. Spatial
  information comes from finger position and proprioception while scanning.
- For a 48-hour hackathon, prioritize an Android Chrome/PWA fallback plus one
  ESP32-C3 haptic actuator. Do not expand to a dense actuator array unless the
  user explicitly changes scope.

## Evidence rules

- Keep `verified`, `simulated`, and `unverified` claims separate.
- Never report browser mocks, protocol ACKs, or GPIO application as physical
  motor confirmation.
- Only status code `3` from the optional vibration sensor represents physical
  actuation in the existing BLE protocol.
- Do not claim real-device latency, tactile recognition accuracy, or thermal
  safety until measured with actual hardware and participants.
- Do not generalize one interaction model to all blind users. Distinguish
  congenital blindness, late blindness, and low vision where it affects design.

## Repository hygiene

- Preserve unrelated work and the existing dirty worktree.
- Historical StoryBean, FrameFit, writing-robot, magnetic-board, and dense pin
  array materials are not the active TouchScene direction.
- Use `apply_patch` for manual file edits.
- After a material decision or test, update both `MEMORY.md` and `state.json`.
  Preserve superseded decisions in the decision log.

## Verification commands

Run from the repository root:

```powershell
node --test tests/touchpuck-core.test.mjs
node tests/touchpuck-browser-qa.mjs
```

Firmware commissioning requires a real USB serial device:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/touchpuck-commission.ps1
```

Add `-EnableSensor` only when the SW-420 is connected and calibrated.
