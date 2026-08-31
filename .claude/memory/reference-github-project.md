---
name: reference-github-project
description: Read when creating, labelling, or tracking an issue — board IDs and what each type label actually covers
metadata:
  type: reference
---

The issue → branch → PR → board flow is implemented as slash commands: **`/take-issue <N>`** and **`/ship`**. Read those rather than reconstructing the steps.

**Board** — user-level (not org) Project v2, owner `Dragote`, repo `Dragote/xCamera`:
- project number `1`, node id `PVT_kwHOAeQo9s4BfGuM`
- Status field `PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU` — Backlog `f75ad846`, In progress `47fc9ee4`, In review `df73e18b`, Done `98236657`
- "Item closed → Done" and "Pull request merged → Done" automations are on, so merging closes the loop with no explicit status write.

**Issues** are flat — no epics, the user's explicit preference ([[feedback-minimal-infra]]). Each carries exactly one type label; `CLAUDE.md`'s "Issue type labels" section defines them and is the only place that does.

Adding to the backlog: `gh issue create --repo Dragote/xCamera --title "..." --body "..."`, then `gh project item-add 1 --owner Dragote --url <issue-url>`.

`gh` must be authenticated as `Dragote` with `project` + `repo` scopes — a GitHub Actions `GITHUB_TOKEN` cannot reach Projects v2.

**Gotcha:** renaming a branch that already has an open PR (`gh api -X POST repos/.../branches/<b>/rename`) does not carry the PR with it — the PR flips to CLOSED pointing at a dead head ref, and `gh pr reopen` fails. Rename before opening the PR; if it's already open, create a fresh one and comment on the old.
