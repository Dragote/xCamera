# Docs index

Reference material loaded on demand, one topic per file. Nothing here repeats what the code or `CLAUDE.md` already says — a doc holds what reading the source would not have told you.

**Project direction** — `project/`
- [Product vision](project/vision.md) — scoping a camera feature: the long-term target list to slice from, never a v1 requirement
- [Camera feasibility on Android](project/camera-feasibility-android.md) — touching capture, sensor control, or the image pipeline: which Android API can actually do it and what it demands of the device
- [Design direction](project/design-direction.md) — changing how anything looks: flat "minimal chrome" is the shipped identity, and these directions are already ruled out

**Process**
- [GitHub board reference](github-board.md) — a board or `gh` call misbehaves: IDs, auth scopes, and the branch-rename trap

**Features** — `features/`, one file per feature, indexed in [features/README.md](features/README.md). Maintained by the `spec-writer` agent.
