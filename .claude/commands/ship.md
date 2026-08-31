---
description: Turn work already sitting in the tree into an open pull request — filing the issue and branching if that hasn't happened yet
argument-hint: [issue-number]
allowed-tools: Bash(./gradlew *), Bash(gh *), Bash(git *), Read, Grep, Glob
---

Take whatever is currently done — uncommitted edits, commits on a branch, or both — through to an open PR on `Dragote/xCamera`. Most of the time none of the ceremony has happened yet, so start by checking what actually exists rather than assuming.

**Catch up on whatever is missing**

1. **Issue.** Use `$ARGUMENTS` if given, else the number in the branch name. If there is neither, read `git status`/`git diff` to see what the work actually is and file it now: pick the one type label (root `CLAUDE.md`, "Issue type labels" — harness, docs and agent-config work is `documentation`, not `tech`), write Problem / Requirements / Non-goals, `gh issue create --repo Dragote/xCamera --label <type> ...`, then `gh issue edit <N> --add-assignee @me`.
2. **Branch.** If `git branch --show-current` is `main`, move the work off it. `git fetch origin && git checkout -b <label>/<N>-<slug> origin/main` carries uncommitted changes across. If commits have already landed on local `main`, keep them with `git branch <label>/<N>-<slug> && git reset --hard origin/main && git checkout <label>/<N>-<slug>`.
3. **Board → In progress**, if it was never moved:
   ```bash
   ITEM_ID=$(gh project item-add 1 --owner Dragote --url https://github.com/Dragote/xCamera/issues/<N> --format json --jq '.id')
   gh project item-edit --project-id PVT_kwHOAeQo9s4BfGuM --id "$ITEM_ID" \
     --field-id PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU --single-select-option-id 47fc9ee4
   ```
4. **Commits.** Anything uncommitted becomes `#<N>: Message` commits — leading `#`, colon, capitalized imperative subject.

**Verify**

5. `./gradlew test 2>&1 | tail -5` — the whole suite must be green. Never open a PR over a red suite.
6. `./gradlew :app:installDebug 2>&1 | tail -5`; if no device is attached, `assembleDebug` instead and say so in the PR. Verification is exactly these two commands — don't launch, click through, or screenshot the app (memory `feedback-verification`).

**PR**

7. Rebase even when it looks unnecessary: `git fetch origin && git rebase origin/main`. Skipping it is how two branches cut from the same older `main` and merged out of order produce crossing lines in the graph instead of a straight one.
8. `git push -u origin HEAD` (`--force-with-lease` after a rebase), then `gh pr create --title "<issue title>" --body "Closes #<N>"`, the body saying what changed and why.
9. Board → **In review** (`--single-select-option-id df73e18b`, same two calls as step 3).

**Merge — only for a `documentation` issue**

A `tech`, `feature` or `bug` issue stops above: report the PR and let the user verify on-device. For `documentation`:

10. Rebase onto `origin/main` once more, since main may have moved while the PR was open: `git fetch origin && git rebase origin/main && git push --force-with-lease`.
11. Merge with an explicit subject, never GitHub's default `Merge pull request #N from Dragote/<branch>`:
    ```bash
    gh pr merge <N> --merge --subject "Merge <branch-name>" --delete-branch
    ```
    GitHub has no repo setting for this — the "default commit message" dropdown only affects squash merges — so the flag is required every time.
12. Board automations move the item to Done on merge; no explicit status write needed.
