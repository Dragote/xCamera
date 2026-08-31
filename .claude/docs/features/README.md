# Feature docs index

One file per feature under `.claude/docs/features/`, kept short (~40 lines) so an agent only loads what it needs instead of one giant spec. Maintained by the `spec-writer` agent — see `.claude/agents/spec-writer.md` for the template and update rules.

- [Camera capture](camera-capture.md) — Camera2 capture pipeline: lens switching, flash, manual ISO/shutter/focus, viewfinder grid, zebra clipping overlay, opt-in RAW/DNG capture
- [LUT color grading](lut-color-grading.md) — real-time 3D LUT color grading (importable `.cube` files) applied to live preview and captured JPEGs
- [Settings](settings.md) — persisted camera preferences (grid/histogram/horizon/focus-peaking/RAW toggles) and the imported-LUT library backing `feature:camera`'s Settings screen
- [Camera diagnostics](camera-diagnostics.md) — per-lens hardware characteristics and RAW/manual-ISO/manual-focus support, reached from Settings; backed by `shared:diagnostics`, the app's sole source of camera-capability-determination logic
