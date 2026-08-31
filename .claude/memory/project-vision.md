---
name: project-vision
description: "xCamera's product goal — a pro capture pipeline behind a playful, tactile, highly customizable interface"
metadata: 
  node_type: memory
  type: project
  originSessionId: 9ab0af68-c96f-4405-9dad-e06dc563ca56
  modified: 2026-07-31T20:17:20.384Z
---

xCamera's product direction is a camera app that pairs a genuinely pro capture pipeline with a playful, tactile, highly customizable 3D interface. Decided 2026-07-31.

**Why:** the user's own idea, positioned as "looks like a toy, works like a serious tool". Not a client requirement — a personal solo project direction.

**Target feature set** (see [[camera-feasibility-android]] for per-feature Android implementation notes):
- SuperRAW-style capture pipeline (raw sensor access before heavy computational processing) + DNG export
- 3D LUT-based color grading with importable custom LUTs
- Full manual controls (exposure, ISO, white balance, focus) with pro readouts (histogram, zebra stripes, focus peaking)
- Fully tactile 3D UI: dynamic lighting/shadows, custom haptics and sound, customizable skins/colorways
- Adjustable-intensity HDR, photo review flow, persisted settings, fast-launch entry point, fully local/private storage

**How to apply:** when scoping the first camera feature, treat this list as the long-term target, not a v1 requirement — pick a small vertical slice (e.g. manual controls + one LUT + basic capture) as the actual first feature, per [[feedback-minimal-infra]]. Architecture for camera code should follow the project's existing module/layer conventions documented in root `CLAUDE.md` (data/domain/presentation/ui/di packages inside a `feature:camera`-style module), not bolt on camera-specific structure.
