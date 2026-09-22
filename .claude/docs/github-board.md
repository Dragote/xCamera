# GitHub board reference

Read when a board or `gh` call does something unexpected. The everyday flow is already encoded in `/take-issue` and `/ship`, IDs inline — read those rather than reconstructing the steps from here.

**Board** — user-level (not org) Project v2, owner `Dragote`, repo `Dragote/xCamera`:
- project number `1`, node id `PVT_kwHOAeQo9s4BfGuM`
- Status field `PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU` — Backlog `f75ad846`, In progress `47fc9ee4`, In review `df73e18b`, Done `98236657`
- "Item closed → Done" and "Pull request merged → Done" automations are on, so merging closes the loop with no explicit status write.

**Auth:** `gh` must be authenticated as `Dragote` with `project` + `repo` scopes. A GitHub Actions `GITHUB_TOKEN` cannot reach Projects v2 at all, so none of this can move into CI as-is.

**Gotcha:** renaming a branch that already has an open PR (`gh api -X POST repos/.../branches/<b>/rename`) does not carry the PR with it — the PR flips to CLOSED pointing at a dead head ref, and `gh pr reopen` fails. Rename before opening the PR; if it's already open, create a fresh one and comment on the old.

Adding to the backlog outside `/take-issue`: `gh issue create --repo Dragote/xCamera --title "..." --body "..."`, then `gh project item-add 1 --owner Dragote --url <issue-url>`.
