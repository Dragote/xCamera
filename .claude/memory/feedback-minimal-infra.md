---
name: feedback-minimal-infra
description: Read when deciding whether to add a module, abstraction, or config — the answer is the smallest working slice
metadata:
  type: feedback
---

Add shared modules, abstractions, config, or scaffolding only when a real, current feature needs them. Never "for later."

**Why:** chosen twice during the initial architecture setup. Asked how many `shared:*` modules to start with, the user took the minimal pair (`shared:common` + `shared:designsystem`) over a full network/database/navigation/testing set, planning to split further only once a second feature demanded it. Separately, a placeholder empty `@Database` reserved for future cross-cutting tables turned out to be invalid Room config, and deleting it outright — rather than working around it — was accepted without pushback.

**How to apply:** when a task could ship either as "just enough for the current feature" or as "generalized now", take the former. Catching yourself creating an empty or unused class, module, or config is the signal to stop and either implement it for real or drop it. This governs camera work too: ship a narrow vertical slice, not [[project-vision]]'s full target list. The counterweight is CLAUDE.md's "Duplication vs. abstraction" rule — once a second consumer genuinely exists, extract without waiting.
