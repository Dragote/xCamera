---
name: feedback-minimal-infra
description: "User prefers minimal upfront scaffolding — add shared infrastructure only when there's a concrete current need"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9ab0af68-c96f-4405-9dad-e06dc563ca56
  modified: 2026-07-31T20:17:40.595Z
---

Don't build speculative/premature infrastructure "for later." Add shared modules, abstractions, or scaffolding only when a real, current feature needs them.

**Why:** confirmed twice during the initial architecture setup (2026-07-31). First, when asked how many `shared:*` modules to start with, the user explicitly chose the minimal set (`shared:common` + `shared:designsystem` only) over the full "gentleman's set" (network/database/navigation/testing modules), planning to split things out only once a second feature actually needs them. Second, when `shared:common`'s placeholder `AppDatabase` (an empty `@Database` reserved "for future cross-cutting tables") turned out to be invalid Room config, the fix — deleting it entirely rather than working around it — was accepted without pushback.

**How to apply:** default to the smallest working slice. When a task could reasonably ship as "just enough for the current feature" or "generalized/reusable now," prefer the former. If you catch yourself creating an empty/unused class, module, or config "for future use," that's a signal to stop and either implement it for real or skip it. This applies to camera feature work too — see [[project-vision]]'s note to ship a small vertical slice first, not the full reference feature list at once.
