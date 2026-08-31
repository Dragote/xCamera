---
name: toolchain-setup
description: Per-machine toolchain xCamera needs that the repo does not carry — JDK, Android SDK, and the gh CLI.
metadata:
  type: project
---

Three things xCamera needs are installed per machine and are not restored by cloning the repo. `scripts/claude-bootstrap.sh` reports which are missing.

- **JDK** — there is no system `java`; the only JDK is Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (currently JBR 25). Android Studio uses it automatically, but `./gradlew` from a terminal fails with "Unable to locate a Java Runtime" unless `JAVA_HOME` points there.
- **Android SDK** — present at `~/Library/Android/sdk` and recorded in the (gitignored) `local.properties`, so Gradle finds it; but `ANDROID_HOME` and `adb` on `PATH` are separate and may be unset.
- **`gh` CLI** — required by the whole issue/PR/Project-board workflow in [[reference-github-project]] and by the `spec-writer` agent. Installed via Homebrew on the `dragote` machine 2026-08-31 and verified against the real board. **Homebrew on Apple Silicon lives at `/opt/homebrew` and is not on `PATH` by default** — without `eval "$(/opt/homebrew/bin/brew shellenv)"` in `~/.zshrc`, both `brew` and `gh` are "command not found" even though they are installed.

Two gotchas worth not rediscovering:

- **`gh`'s git protocol is set per host, and the host setting wins.** `gh config get git_protocol` can report `ssh` while `gh auth status` still says `Git operations protocol: https` — the fix is `gh config set -h github.com git_protocol ssh`. Neither setting rewrites an existing remote; that needs `git remote set-url` separately.
- **Installing anything that needs `sudo` cannot be done through Claude Code's `!` prefix** — it runs without a TTY, so the password prompt fails with "Need sudo access on macOS". Run such installers in a real terminal window. (The `dragote` account *is* an administrator; the non-TTY was the whole problem.)

**Why:** everything under `~/.claude/` and every shell/env setting is lost when moving machines — see [[multi-device-setup]]. Verified 2026-08-31: `./gradlew projects` succeeds with `JAVA_HOME` set to the JBR and fails without it.

On the `dragote` machine both are exported from `~/.zshrc` as of 2026-08-31. **Gotcha:** zsh reads `~/.zshrc` only for *interactive* shells, so a non-interactive `zsh -lc '...'` (or any login-but-not-interactive invocation) still sees them unset — verify with `zsh -ic` before concluding the exports did not take.

**How to apply:** if a Gradle command fails to find Java, or a `gh`-based step in [[reference-github-project]] errors out, check this before assuming the repo is broken. Machine-specific paths belong in the shell profile or `local.properties`, never committed.
