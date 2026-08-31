---
name: multi-device-setup
description: The user works on xCamera from several Macs; Claude's memory is committed to the repo and symlinked per machine.
metadata:
  type: project
---

The user develops xCamera from more than one Mac. Known checkouts: `/Users/emil/AndroidStudioProjects/xCamera` and `/Users/dragote/StudioProjects/xCamera` (set up 2026-08-31).

Because `~/.claude/projects/<slug>/` derives its slug from the checkout's absolute path, that path differs on every machine, and everything under `~/.claude/` is lost when moving to new hardware. Memory therefore lives in the repo at `.claude/memory/`, and `scripts/claude-bootstrap.sh` symlinks the machine-local slug path at it.

**Why:** on the 2026-08-31 move to the `dragote` machine, all prior memory was lost — the repo-tracked context (`CLAUDE.md`, `.claude/agents/`, `docs/features/`) survived intact while nothing under `~/.claude/` did.

**How to apply:** anything that must stay consistent across the user's machines belongs in the repo, not in `~/.claude/`. Run `scripts/claude-bootstrap.sh` once per new machine. See [[toolchain-setup]].
