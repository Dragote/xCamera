---
name: write-memory
description: How xCamera's committed project memory works and what belongs in it. Load before saving, editing, or deleting anything under .claude/memory/, and before answering questions about how this project's memory is stored or why it is in the repo.
---

# Writing project memory

Memory for this project is **committed to the repo** at `.claude/memory/`: `MEMORY.md` is the always-loaded index, and each memory is a single file beside it. Write there exactly as if writing to the usual `~/.claude/projects/<slug>/memory/` path — on a bootstrapped machine that path *is* this directory, via a symlink.

## What earns a memory

Three questions; a memory has to survive all three:

1. **Can this be derived by reading the repo?** If yes it is a convention, and conventions go in `CLAUDE.md`.
2. **Does something already check it at runtime?** If yes it goes in that thing. `scripts/claude-bootstrap.sh` checks the local toolchain, so no memory records installed tools, paths, or versions — a fresh check beats a remembered claim about how some machine was configured once.
3. **Would any action differ if I didn't know it?** If no, it is trivia. Write nothing.

What survives is decisions and preferences that leave no trace in the repo: a direction that was **rejected**, a trade-off the user chose between two defensible options, a preference about how work should be done. Rejected designs are the clearest case — code never records what was tried and thrown away.

A repeatable procedure is not a memory either. It goes in `.claude/commands/` if the user triggers it by name, or `.claude/skills/` if the situation triggers it.

## Both selectors are triggers, not topics

A memory only helps if it fires at the right moment, and two things decide that:

- the file's `description:` — how it gets pulled in when relevant
- its line in `MEMORY.md` — the only part loaded every session

Write both as *when to read this*, never *what this is about*. `description:` opens with "Read when …" or "Read before …". A memory whose index line is a topic label ("Verification", "Minimal infra") is invisible in practice, because nothing in it says when it applies.

## Format

```markdown
---
name: <kebab-case slug, matching the filename>
description: Read when <trigger> — <what it settles>
metadata:
  type: user | feedback | project | reference
---

<The rule, present tense, first.>

**Why:** <what makes this the decision — only what a reader needs to trust it>

**How to apply:** <what to do differently, concretely>
```

Link related memories as `[[their-name-slug]]`.

**No history.** The same rule as `CLAUDE.md`'s comment conventions: state the current rule, never how it got there. No "used to be X", no "renamed since", no correction-upon-correction, no dated narration of an investigation. If a memory has to annotate its own staleness, the stale part should have been deleted instead.

## Index

`MEMORY.md` is grouped by the moment each memory applies — deciding what to build → writing the code → finishing a change. Every memory file needs exactly one index line, under the group where its trigger fires, and every index line needs a file. Keep it in sync in the same change.

## Mechanics

- **The symlink is self-healing.** A `SessionStart` hook in `.claude/settings.json` runs `scripts/claude-bootstrap.sh --hook` every session. It is silent when the link is correct; when it isn't — a fresh machine, or a plain directory the harness created — it links it, first rescuing any memory files already written there, and says so. That is what stops a new machine from accumulating memories git never sees.
- **Why the repo:** `~/.claude/projects/<slug>/` derives its slug from the checkout's absolute path, so it differs per machine, and nothing under `~/.claude/` survives new hardware. Repo-tracked context does. Anything that must stay consistent across machines belongs in the repo.
- **Memory is versioned, so it follows the branch.** Uncommitted memory is untracked and survives `git checkout`. Once committed on a feature branch it lives on that branch until it merges. Expected, not a bug — commit memory alongside the work it came from and it reaches `main` with the PR.
- `.claude/memory/MEMORY.md` is `merge=union` in `.gitattributes`, so two machines adding index lines don't conflict. A memory added on both sides shows up as a duplicate line — delete it when you see it.
