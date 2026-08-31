---
name: camera-feasibility-android
description: "Per-feature Android API mapping and platform risks for the target camera feature set"
metadata: 
  node_type: memory
  type: project
  originSessionId: 9ab0af68-c96f-4405-9dad-e06dc563ca56
  modified: 2026-07-31T20:17:33.918Z
---

Conclusions from a 2026-07-31 research pass mapping the target feature set onto Android APIs, done before any camera code existed in xCamera. See [[project-vision]] for the product goal this supports.

**Why:** picking the wrong low-level API (e.g. trying to do RAW capture through CameraX alone, or assuming lock-screen widgets exist on Android) would force a rewrite of the capture layer later — worth fixing the API choices before the first feature lands.

**Feature → Android mapping:**
- RAW/DNG export: `Camera2` `RAW_SENSOR` output + the built-in `DngCreator` class. Requires the device to advertise the `RAW` capability in `CameraCharacteristics` — not universal, reliable mainly on flagships (Pixel, Samsung S/Note). Must runtime-check and hide the feature gracefully rather than assume it's present.
- Manual controls (exposure/ISO/WB/focus): `Camera2` `CaptureRequest` keys (`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `LENS_FOCUS_DISTANCE`, `COLOR_CORRECTION_GAINS` for WB). Requires `MANUAL_SENSOR` capability — most devices 2015+, but still capability-gated.
- **CameraX does not support RAW capture or full manual sensor control** — those need direct `Camera2` (or CameraX's `Camera2Interop`/`Camera2CameraInfo` escape hatches). Use CameraX for lifecycle/lens-switching/basic capture, drop to Camera2 specifically for the pro-mode path.
- Histogram / zebra stripes / focus peaking: pure software overlays computed from YUV preview frames (`ImageAnalysis`/`ImageReader`) — no platform barrier, well-trodden (Manual Camera, Footej Camera prior art).
- 3D LUT color grading: real-time GPU shader (OpenGL ES or AGSL) sampling a 3D LUT texture from a `.cube` file — must be GPU, not a per-pixel Kotlin loop, to stay real-time on preview.
- Adjustable-intensity HDR: Android exposes no tunable OS-level HDR intensity control — `CameraX Extensions` HDR is vendor-implemented and effectively binary. Reproducing "Off/Low/Medium/High" needs a custom multi-exposure bracket capture + own tone-mapping/blend algorithm.
- Custom haptics: `VibrationEffect.Composition` (API 30+) is the richest haptic API available — it sequences primitives (clicks, ticks, rises) instead of firing one flat buzz. But motor quality varies hugely across Android hardware: many devices ship a cheap eccentric-rotating-mass motor rather than a linear resonant actuator, and nuanced compositions simply do not reproduce on them. Don't design haptics that depend on fine tactile fidelity, especially on budget devices.
- Instant launch from lock screen: **Android has no third-party lock-screen widget system at all.** Closest substitutes: a Quick Settings `TileService`, a home-screen widget, or OEM-dependent double-tap-power camera shortcuts (not reliably assignable to third-party apps). This is the one target feature with no platform mechanism behind it, so it needs designing from scratch rather than adapting.

**How to apply:** when implementing camera features, always query device capabilities first and degrade gracefully rather than crash on unsupported hardware. Scope initial device support to flagship-tier (Pixel/Samsung) for RAW and manual-sensor-dependent features. Keep Camera2/CameraX session code thin and push LUT math, tone-mapping, and capability-decision logic into plain testable classes — hardware camera sessions aren't realistically unit-testable, but the surrounding logic is (MockK/Turbine per [[feedback-minimal-infra]] and root `CLAUDE.md` testing conventions).
