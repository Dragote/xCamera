---
name: feedback-lean-build-output
description: "Don't dump full ./gradlew output into context when just confirming build/tests pass"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 659964b7-8599-446a-b4f0-b1b4e70c98fb
  modified: 2026-08-02T08:51:42.010Z
---

When running `./gradlew build`/`test`/`assembleDebug` etc. just to confirm green, pipe to `tail -N` or grep for `BUILD SUCCESSFUL`/`BUILD FAILED` instead of reading the full log. Only pull the untruncated output when a build actually fails and the stack trace/compiler error is needed to fix it.

**Why:** user flagged that a subagent (`android-clean-architect`, implementing issue #8's Clean Architecture fix) burned ~134k tokens on a task that was mostly "does it build, do tests pass" — full Gradle output (task graph, deprecation warnings, KSP/Hilt noise) is a major contributor to that on every build/test invocation.

**How to apply:** applies to any agent or inline work running Gradle in this repo. Already baked into `.claude/agents/android-clean-architect.md` and `.claude/agents/camera-engineer.md` under "Verifying builds/tests — keep it cheap" — if a new agent is added that runs builds, give it the same instruction rather than assuming it's obvious.
