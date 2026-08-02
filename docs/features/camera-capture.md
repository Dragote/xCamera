# Camera capture

**Purpose:** The core capture pipeline for `feature:camera` — an Android take on iOS's (Not Boring) Camera app. Skeuomorphic viewfinder UI (levers, dials) driving a CameraX/Camera2-backed capture flow.

**Current state:**
- CameraX for lifecycle/preview/capture; `Camera2Interop`/`Camera2CameraControl` for physical-lens enumeration and manual sensor control (`CameraController` in `data/`).
- Lens switching (`CameraLens`), flash mode (`FlashMode`).
- Manual ISO control via a repurposed ISO dial, gated on `CameraCharacteristics` `MANUAL_SENSOR` capability (`manualIsoSupported` in `CameraUiState`) — hidden/disabled on devices that don't report it.
- Manual shutter speed control alongside manual ISO, same manual-mode toggle; `ManualControlTarget` selects which of the two the shared exposure dial currently drives.
- Manual ISO/shutter ladders (`isoStops`/`shutterStops`) are the standard stop ladder filtered to the selected lens's actual supported range, not a fixed list.
- Viewfinder grid overlay, off by default.
- Capture writes to `MediaStore` (`ContentValues`); no success toast on capture (removed deliberately, see issue #3).
- No manual white balance, no RAW/DNG, no LUT grading yet.

**Key decisions:**
- Camera2 escape hatches (not pure CameraX) for manual ISO/shutter — CameraX alone has no manual sensor control. See `camera-feasibility-android` project memory for the full per-feature API mapping.
- Manual-mode pending values (`pendingManualIso`, `pendingManualShutterNs`) are cached in `CameraController` so they survive a rebind (e.g. switching lenses mid manual-mode).
- Capability-gated per lens, not per device — `manualIsoSupported` is evaluated against the *selected* lens's `CameraCharacteristics`, since front/back/tele lenses can differ.

**Open questions:**
- Manual white balance / manual focus scope not yet defined.
- RAW/DNG capture not yet scoped (tracked at feasibility level only, no issue yet).
