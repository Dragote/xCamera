---
name: feedback-no-claude-attribution
description: User does not want Claude/Claude Code attribution in commit messages or PR descriptions for this project
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 659964b7-8599-446a-b4f0-b1b4e70c98fb
  modified: 2026-08-02T08:44:12.637Z
---

Do not add `Co-Authored-By: Claude ...` trailers to git commits, and do not add "🤖 Generated with Claude Code" (or similar) lines to PR descriptions in this repo.

**Why:** caught live on issue #8's fix — the user flagged that the Claude co-author trailer had appeared in a commit again after it went out with the standard trailer. Amended the commit (`git commit --amend`, then `git push --force-with-lease` since it was already pushed) and stripped the same line from the PR body.

**How to apply:** when following the default git-commit instructions elsewhere in this environment that say to end commit messages with a Claude co-author trailer, skip that step for this repo. Same for PR bodies created via `gh pr create`/`gh pr edit` — no Claude/Claude Code self-attribution line.
