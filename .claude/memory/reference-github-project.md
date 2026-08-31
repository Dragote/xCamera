---
name: reference-github-project
description: "GitHub Projects v2 board \"xCamera\" — IDs and workflow for backlog/status automation via gh CLI"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 390884e1-aa7b-43c1-a590-a297bb9bcbf8
  modified: 2026-08-02T08:27:15.955Z
---

GitHub Project v2 board for this repo:
- Owner: `Dragote` (user-level project, not org)
- Project number: `1`, project node id: `PVT_kwHOAeQo9s4BfGuM`
- Repo: `Dragote/xCamera`
- `gh` CLI must be installed and authenticated as `Dragote` with scopes including `project` and `repo` — required because the default `GITHUB_TOKEN` in Actions lacks Projects v2 access; for local/interactive sessions the user's own `gh auth login` token already has it. Installed per machine, not carried by the repo — see [[toolchain-setup]].

Status field: `PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU`, options:
| Status | option-id |
|---|---|
| Backlog | `f75ad846` |
| In progress | `47fc9ee4` |
| In review | `df73e18b` |
| Done | `98236657` |

No epics — flat issues only, user's explicit preference (see [[feedback-minimal-infra]]).

Issue type labels (repo `Dragote/xCamera`, managed via `gh label`): `feature` (new/changed business logic — renamed from GitHub's default `enhancement`), `bug`, `tech` (refactors/architecture/infra, no user-visible behavior change), `documentation`. Every issue created by the `spec-writer` agent (`.claude/agents/spec-writer.md`) gets exactly one of these via `--label`.

## Workflow: "take issue #N into work" (interactive, run by Claude Code in-session, no GitHub Actions needed)

1. `gh issue edit <N> --add-assignee @me` (assigns to the user, since `gh` runs under their own token — no bot account)
2. Get/create the project item for the issue, then move Status → "In progress":
   ```bash
   ITEM_ID=$(gh project item-add 1 --owner Dragote --url https://github.com/Dragote/xCamera/issues/<N> --format json --jq '.id')
   gh project item-edit --project-id PVT_kwHOAeQo9s4BfGuM --id $ITEM_ID \
     --field-id PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU --single-select-option-id 47fc9ee4
   ```
   (`item-add` is idempotent if already on the board — returns existing item id.)
3. Branch naming: `<type>/<N>-<slug>`, where `<type>` is the issue's type label (`feature`, `bug`, `tech`, `documentation` — see label table above), e.g. `tech/12-camera-repository-di`, `bug/9-flash-not-resetting`. Superseded the earlier always-`feature/`-prefix convention as of 2026-08-02 — the prefix now tracks the actual label, not just "feature" for everything. Implement the issue on this branch.
   Commit message format: `#<N>: Message` (e.g. `#1: Disable viewfinder grid by default`) — number with a leading `#` plus colon. Changed 2026-08-02 from the earlier plain `<N>: Message` (no `#`) at the user's request. Body/trailer can still carry `Closes #<N>` for GitHub auto-linking.
4. Before `git push`/opening the PR: run `./gradlew test` (full unit test suite, all modules — project is small enough that this is cheap; revisit and scope to touched modules if it gets slow) and make sure it's green. Then rebase the branch onto the current `origin/main` (`git fetch origin && git rebase origin/main`) even if it looks unnecessary — otherwise two branches both cut from an older main and merged out of order produce a "diamond" in the graph (crossing lines) instead of a straight line. Then `gh pr create --body "Closes #<N>" ...`.
5. Move Status → "In review" (`--single-select-option-id df73e18b`).
6. Right before merging: rebase onto `origin/main` again (`git fetch origin && git rebase origin/main && git push --force-with-lease`) in case main moved while the PR was open for review — same reason as step 4. Do this even if nothing looks like it changed.
7. Merging the PR / closing the issue does NOT auto-move to Done by default — Project's built-in workflow automations (Settings → Workflows on the project) should be turned on by the user for "Item closed → Done" and "Pull request merged → Done" to cover that step for free; otherwise set Done explicitly. (User has already enabled both on the xCamera project as of 2026-08-01.)

## Backlog population
`gh issue create --repo Dragote/xCamera --title "..." --body "..."` then `gh project item-add 1 --owner Dragote --url <issue-url>`, Status defaults to whatever the project's default view sets (usually "Backlog" if a workflow auto-adds new items, otherwise set explicitly with the same `item-edit` pattern above).

## Gotcha: renaming a branch with an open PR

`gh api -X POST repos/<owner>/<repo>/branches/<branch>/rename -f new_name=...` renames the branch but does **not** reliably carry an open PR over — observed 2026-08-02: PR stayed pointing at the old (now-nonexistent) branch name and flipped to CLOSED, `gh pr reopen` fails with "Could not open the pull request" since the head ref is gone. Fix is to open a fresh PR from the renamed branch (same title/body) and leave a comment on the old one pointing to the replacement. **Prefer renaming the branch before opening a PR from it**, not after — e.g. if a `bug/`-labeled issue gets relabeled `tech`/`feature`/etc. mid-work, rename the branch first, then create the PR.
