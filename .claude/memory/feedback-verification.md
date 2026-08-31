---
name: feedback-verification
description: Read when a change is written and it is time to verify — what to run, what never to run, and how to keep the Gradle log out of context
metadata:
  type: feedback
---

After any change in this repo the finish line is exactly two commands, then stop:

1. `./gradlew test` — the full unit suite (the project is small enough; scope to touched modules only if it gets slow)
2. `./gradlew :app:installDebug`

Report both results and hand over. Do not relaunch the app, click through it, exercise the new behavior, capture the device screen, or compare anything against a design reference. **Screenshot/snapshot testing (Roborazzi, Paparazzi) is out of scope for this project** — don't introduce it and don't offer it as a way around this rule. Visual correctness is judged by the user, on a real device, and by nobody else.

Run Gradle so the log stays out of context: pipe to `tail -5` or grep for `BUILD SUCCESSFUL`/`BUILD FAILED`. Pull the untruncated output only when a build actually fails and the compiler error is needed to fix it.

**Why:** the user asked for this directly — "no checks, I'll verify everything myself, only run tests and install the build on the phone" — and wants to take over the moment the APK lands. The lean-output half has its own cost behind it: a subagent once burned ~134k tokens on a task that was mostly "does it build", nearly all of it Gradle task-graph and KSP/Hilt noise.

**How to apply:** applies to inline work and to every subagent. `android-clean-architect` and `camera-engineer` already carry the lean-output instruction in their agent files — give it to any new agent that runs Gradle rather than assuming it's obvious. `/ship` encodes the two-command finish line.
