---
name: feedback-merge-commit-format
description: "Use a short \"Merge <branch>\" subject for PR merge commits in xCamera, not GitHub's default \"Merge pull request #N from Dragote/<branch>\""
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 4339ef89-b65f-4181-85a8-19899332acf0
  modified: 2026-08-02T14:52:05.737Z
---

When merging a PR in this repo (`Dragote/xCamera`) via `gh pr merge`, always pass a custom subject:

```bash
gh pr merge <N> --merge --subject "Merge <branch-name>" --delete-branch
```

instead of accepting GitHub's default merge commit title (`Merge pull request #N from Dragote/<branch-name>`).

**Why:** User asked (2026-08-02) to drop the "pull request #N from Dragote/" boilerplate from merge commit titles going forward — just the branch name. GitHub has no repo-setting for this (the "default commit message" dropdown in repo settings only affects squash merges, not merge commits), so it has to be passed per-merge via `--subject`.

**How to apply:** Every future `gh pr merge --merge` in this repo needs `--subject "Merge <branch-name>"` explicitly. Does not apply retroactively — already-merged commits on `main` (e.g. PR #14's "Merge pull request #14 from Dragote/tech/13-split-iso-shutter-dial") are left as-is, not rewritten.
