---
description: Ship the current branch — test, rebase, open the PR, move the board, and merge with the project's commit subject
argument-hint: [issue-number]
allowed-tools: Bash(./gradlew *), Bash(gh *), Bash(git *), Read, Grep, Glob
---

Ship the current branch of `Dragote/xCamera`. The issue number is `$ARGUMENTS` if given, otherwise read it from the branch name (`<label>/<N>-<slug>`).

**Before pushing**

1. `./gradlew test 2>&1 | tail -5` — the whole suite must be green. Never open a PR over a red suite.
2. `./gradlew :app:installDebug 2>&1 | tail -5`. Verification is exactly these two commands — don't launch, click through, or screenshot the app (memory `feedback-verification`).
3. Rebase even when it looks unnecessary: `git fetch origin && git rebase origin/main`. Skipping it is how two branches cut from the same older `main` and merged out of order produce crossing lines in the graph instead of a straight one.

**PR**

4. `git push -u origin HEAD` (add `--force-with-lease` after a rebase), then `gh pr create --title "<issue title>" --body "Closes #<N>"`.
5. Move the board to **In review**:
   ```bash
   ITEM_ID=$(gh project item-add 1 --owner Dragote --url https://github.com/Dragote/xCamera/issues/<N> --format json --jq '.id')
   gh project item-edit --project-id PVT_kwHOAeQo9s4BfGuM --id "$ITEM_ID" \
     --field-id PVTSSF_lAHOAeQo9s4BfGuMzhZcBhU --single-select-option-id df73e18b
   ```

Stop here and report. Steps 6-8 run only when the user asks to merge.

**Merge**

6. Rebase onto `origin/main` once more — main may have moved while the PR was open: `git fetch origin && git rebase origin/main && git push --force-with-lease`.
7. Merge with an explicit subject, never GitHub's default `Merge pull request #N from Dragote/<branch>`:
   ```bash
   gh pr merge <N> --merge --subject "Merge <branch-name>" --delete-branch
   ```
   GitHub has no repo setting for this — the "default commit message" dropdown only affects squash merges — so the flag is required on every merge.
8. The board's automations move the item to Done on merge; no explicit status write is needed.
