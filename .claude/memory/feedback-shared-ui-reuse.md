---
name: feedback-shared-ui-reuse
description: "When a Compose UI element/style is needed by more than one module, move it to shared:designsystem rather than duplicating or reaching across feature modules"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 462c417f-7634-4356-b594-ac8aa59aab45
  modified: 2026-08-08T11:44:31.884Z
---

If a Compose UI component, color/gradient/typography token, or Modifier is needed by more than one module, move it into `shared:designsystem` — don't duplicate it, and don't have one feature module reach into another's internal `ui/` package for it.

**Why:** During the camera settings-screen feature (2026-08-08), the user explicitly said that anything needing reuse from the UI moves into the shared design system — twice, once for color/gradient/typography tokens (extracted feature:camera's `CameraChrome` colors into a new `shared:designsystem` chrome object), and again for an actual widget (a two-position toggle used by Flash/Mode, moved wholesale into `shared:designsystem` so the new `feature:settings` module could use the *identical* widget instead of a generic Material3 `Switch`). **Both have since been renamed** — the shared chrome object is now `MinimalChrome.kt` (issue #49 replaced `AppChrome`), and the toggle is now `component/control/Toggle.kt` (`CameraLever` -> `LeverSwitch` in #35, then replaced by `Toggle` in #49). The rule below is what matters; the file names are only examples. This is the concrete, UI-specific instance of CLAUDE.md's general "Duplication vs. abstraction" rule (extract on the second occurrence) — but the user cares enough about visual consistency across screens that this should be treated as a hard rule, not just "wait for the second occurrence to feel it out."

**How to apply:** When building a new feature module that needs to render a control, color, or text style already used elsewhere in the app, check whether the existing implementation lives in another feature module's internal package first. If so, move (not copy) the reusable subset into `shared:designsystem`, leaving only genuinely screen-specific pieces behind (see [[feedback-feature-module-isolation]] for the parallel rule about cross-feature domain contracts and navigation). When moving a widget that has internal helper functions/constants marked `internal`, remember to make them public — cross-module consumers can't see `internal` declarations.
