---
description: Take GitHub issue #N into work — assign it, move the board to In progress, create the branch
argument-hint: <issue-number>
allowed-tools: Bash(gh *), Bash(git *), Read, Grep, Glob
---

Take issue #$1 into work in `Dragote/xCamera`.

1. Read it: `gh issue view $1 --json number,title,body,labels`. Note its single type label (`feature` / `bug` / `tech` / `documentation`) — the branch prefix comes from it, it is not always `feature`.
2. Assign it: `gh issue edit $1 --add-assignee @me`. `gh` runs under the user's own token; there is no bot account.
3. Move the board to **In progress**:
   ```bash
   ITEM_ID=$(gh project item-add 1 --owner Dragote --url https://github.com/Dragote/xCamera/issues/$1 --format json --jq '.id')
   gh project item-edit --project-id PVT_kwHOAeQo9s4BfGuM --id "$ITEM_ID" \
     --field-id PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU --single-select-option-id 47fc9ee4
   ```
   `item-add` is idempotent — it returns the existing item id if the issue is already on the board.
4. Branch from current `origin/main`: `git fetch origin && git checkout -b <label>/$1-<slug> origin/main`, e.g. `tech/12-camera-repository-di`.

Settle the branch name now: renaming a branch that already has an open PR breaks the PR (see memory `reference-github-project`).

Then implement the issue. Commits are `#$1: Message` — leading `#`, colon, capitalized imperative subject. `/ship` takes it from there.
