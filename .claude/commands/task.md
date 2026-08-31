---
description: Do a piece of work end to end — file the issue, branch, implement, test, open the PR, and merge it when the label says to
argument-hint: <what needs doing>
allowed-tools: Bash(./gradlew *), Bash(gh *), Bash(git *), Read, Write, Edit, Glob, Grep
---

Take `$ARGUMENTS` from a sentence to an open pull request, without checking back at each step. Use this when the work is known to be a task up front; when a change is already sitting in the tree from ordinary conversation, `/ship` picks it up from there instead.

1. **File and start it** — everything `/take-issue` describes, given this description rather than a number: pick the one type label (root `CLAUDE.md`, "Issue type labels"), write the issue as Problem / Requirements / Non-goals, create it, assign it, move the board to In progress, and cut `<label>/<N>-<slug>` from current `origin/main`.
2. **Implement it.** Commit as you go, following `CLAUDE.md`'s "Commit conventions": one coherent step each, ordered so every commit builds and tests green on its own, with mechanical moves kept apart from meaningful change. Don't let a whole task land as a single commit.
3. **Ship it** — everything `/ship` describes: full suite green, debug build, rebase onto `origin/main`, push, PR body saying what changed and why, board to In review. Then merge if and only if the issue is `documentation`; anything that changes code that runs stops at the open PR for on-device verification.

Report at the end, not at each step: the issue, the PR, whether it merged, and anything you had to decide on your own along the way.

Stop early and say so — rather than shipping something half-right — only if the work turns out to need a decision that is genuinely the user's: a product choice, a trade-off with no clear default, or scope that grew well past what the sentence implied.
