---
name: feedback-session-economy
description: Read when starting a task, weighing a subagent, or noticing a session has run long — how to keep the work cheap
metadata:
  type: feedback
---

Work in short, focused sessions and keep the context shallow. One task per session: `/take-issue`, implement, `/ship`, then clear before moving to something unrelated. Compact inside a task that genuinely has to run long.

Prefer inline work to a subagent. Spawn one only when the work is genuinely deep or runs in parallel with something else — each starts by loading `CLAUDE.md` plus its own definition, roughly 8k tokens before it reads a single line of code, so a three-file edit is cheaper done directly.

Offer Sonnet for routine implementation against an already-specced issue, and keep Opus for architecture and design calls where the judgement is the product.

Keep output tight: write a file once rather than iterating in it out loud, and answer at the length the question actually needs.

**Why:** measured on a real session rather than assumed. Average context reached ~163k tokens per request, with 72% of the session's cost incurred above 150k. Context grows monotonically, so every turn pays for the whole conversation preceding it — that dominated everything else by an order of magnitude, including the size of the always-loaded files, which accounted for about 1%. Output volume was the second driver; the model's own long prose and repeated in-place file edits are a real line item, not a rounding error.

**How to apply:** treat session length as the first thing to manage, not the last. When a conversation has ranged over several unrelated topics, that is the signal to clear, not a reason to keep going because the context is warm. Deep exploratory sessions are legitimate — they are how this harness got built — but they are an investment to make deliberately, not the default shape of feature work.
