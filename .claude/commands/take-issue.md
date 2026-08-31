---
description: Take work into progress — file the issue if it doesn't exist yet, assign it, move the board, and cut the branch
argument-hint: <issue-number> | <description of the work>
allowed-tools: Bash(gh *), Bash(git *), Read, Grep, Glob
---

Start work on `$ARGUMENTS` in `Dragote/xCamera`. The argument is either an issue number or a description of work that has no issue yet.

**If it's a description, file the issue first.** Pick the one type label that fits (root `CLAUDE.md`, "Issue type labels" — harness, docs and agent-config work is `documentation`, not `tech`), then:

```bash
gh issue create --repo Dragote/xCamera --label <type> --title "..." --body "..."
```

Body follows Problem / Requirements / Non-goals, with Technical notes only when there is a real constraint worth flagging. Draft it inline — invoking the `spec-writer` agent costs ~8k tokens before it reads anything, so it earns its place only when the scope is genuinely ambiguous and needs requirements teased out, not for a change you already understand.

**Then, for the issue number `<N>`:**

1. Read it if you didn't just write it: `gh issue view <N> --json number,title,body,labels`. The branch prefix is its type label.
2. `gh issue edit <N> --add-assignee @me` — `gh` runs under the user's own token, there is no bot account.
3. Move the board to **In progress**:
   ```bash
   ITEM_ID=$(gh project item-add 1 --owner Dragote --url https://github.com/Dragote/xCamera/issues/<N> --format json --jq '.id')
   gh project item-edit --project-id PVT_kwHOAeQo9s4BfGuM --id "$ITEM_ID" \
     --field-id PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU --single-select-option-id 47fc9ee4
   ```
   `item-add` is idempotent — it returns the existing item id if the issue is already on the board.
4. Branch from current `origin/main`: `git fetch origin && git checkout -b <label>/<N>-<slug> origin/main`, e.g. `tech/12-camera-repository-di`.

Settle the branch name now: renaming a branch that already has an open PR breaks the PR (memory `reference-github-project`).

Then implement, committing as you go per `CLAUDE.md`'s "Commit conventions" — one coherent step per commit, each building and testing green on its own. `/ship` finishes.
