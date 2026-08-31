---
name: project-vision
description: Read when scoping a camera feature — the long-term target set to slice from, never a v1 requirement
metadata:
  type: project
---

xCamera pairs a genuinely pro capture pipeline with a playful, tactile, highly customizable interface — "looks like a toy, works like a serious tool." A personal solo project, not a client brief, so the direction is the user's to change at will.

**Long-term target feature set** (per-feature Android implementation notes in [[camera-feasibility-android]]):
- SuperRAW-style capture — raw sensor access ahead of heavy computational processing — plus DNG export
- 3D LUT color grading with importable custom LUTs
- Full manual controls (exposure, ISO, white balance, focus) with pro readouts: histogram, zebra stripes, focus peaking
- A tactile interface with custom haptics and sound, and customizable skins/colorways
- Adjustable-intensity HDR, a photo review flow, persisted settings, a fast-launch entry point, fully local storage

**How to apply:** this is the long-term target, never a v1 requirement — scope any new camera work as a small vertical slice from it ([[feedback-minimal-infra]]). Camera code follows the same module and layer conventions as everything else in `CLAUDE.md`; being the flagship feature does not earn it a bespoke structure. The interface half of the vision is expressed today as flat minimal chrome, not the 3D/skeuomorphic reading of "tactile" — see [[project-design-direction]].
