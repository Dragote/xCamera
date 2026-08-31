---
name: feedback-build-verify
description: "After code changes: run unit tests and install the debug build on device — do not do any further verification (no launching/clicking through, no screenshots, no manual UI testing), the user tests everything himself from there"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 6ce9241b-61cb-4517-8ed3-66ded68c8083
  modified: 2026-08-06T16:40:14.818Z
---

After making changes in this repo, the verification step is exactly two things: run the unit test suite, and install the debug build on the phone (`./gradlew :app:installDebug`). Stop there — do not relaunch and click through the app, do not manually exercise the new feature, do not take screenshots or do pixel-perfect visual comparisons against a design/mock. The user takes it from there and tests everything himself once it's installed.

**Why:** Tightened 2026-08-06 (was previously "build and launch to confirm no crash") — the user explicitly said "no checks, I'll verify everything myself, only run tests and install the build on the phone." He wants to take over testing immediately after install, not have Claude explore/verify the app's behavior first.

**How to apply:** For any change in this repo (UI or otherwise), the finish line is: tests green + `installDebug` succeeded. Report that and stop — don't add manual app interaction as an extra verification pass, even a brief one, unless the user explicitly asks for it again in a given task.
