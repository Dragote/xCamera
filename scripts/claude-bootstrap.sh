#!/usr/bin/env bash
# Wires a checkout into Claude Code on this machine, and keeps it wired.
#
# Claude's persistent memory lives outside the repo, under
# ~/.claude/projects/<slug>/memory, where <slug> is the checkout's absolute path
# with "/" replaced by "-". That slug differs on every machine whose username or
# checkout location differs, so the memory directory cannot be committed at a
# fixed location — instead the repo owns .claude/memory/ and this script points
# the machine-local slug path at it via a symlink.
#
# Without that symlink the harness silently creates a plain directory there and
# every memory written to it is invisible to git — so this runs from a
# SessionStart hook (--hook) as well as by hand, to guarantee the link exists
# before anything is written. Safe to re-run: it converts an existing directory
# rather than clobbering it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLUG="$(printf '%s' "$REPO_ROOT" | tr '/' '-')"
LINK="$HOME/.claude/projects/$SLUG/memory"
TARGET="$REPO_ROOT/.claude/memory"

HOOK_MODE=false
[ "${1:-}" = "--hook" ] && HOOK_MODE=true

# Points $LINK at $TARGET. Echoes a description iff it had to change something.
link_memory() {
  mkdir -p "$TARGET" "$(dirname "$LINK")"
  if [ -L "$LINK" ]; then
    [ "$(readlink "$LINK")" = "$TARGET" ] && return 0
    rm "$LINK" && ln -s "$TARGET" "$LINK"
    echo "repointed the memory symlink at the repo"
  elif [ -d "$LINK" ]; then
    # A real directory: rescue anything already written into it, then replace it.
    if [ -n "$(ls -A "$LINK")" ]; then
      cp -Rn "$LINK"/. "$TARGET"/ 2>/dev/null || true
      rm -rf "$LINK" && ln -s "$TARGET" "$LINK"
      echo "found memory files in an unlinked directory, moved them into .claude/memory/ and linked it"
    else
      rm -rf "$LINK" && ln -s "$TARGET" "$LINK"
      echo "created the memory symlink"
    fi
  else
    ln -s "$TARGET" "$LINK"
    echo "created the memory symlink"
  fi
}

if $HOOK_MODE; then
  # Stay silent when already correct; speak up only when something was repaired.
  changed="$(link_memory 2>/dev/null || true)"
  [ -n "$changed" ] && printf '{"systemMessage":"Claude memory: %s (run scripts/claude-bootstrap.sh for the full check)."}\n' "$changed"
  exit 0
fi

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
warn() { printf '  \033[33mwarn\033[0m  %s\n' "$1"; }

echo "Repo:   $REPO_ROOT"
echo "Slug:   $SLUG"
echo
echo "Memory:"
changed="$(link_memory)"
[ -n "$changed" ] && ok "$changed" || ok "already linked -> $TARGET"

echo
echo "Toolchain:"

JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -x "$JBR/bin/java" ]; then
  if [ "${JAVA_HOME:-}" = "$JBR" ]; then
    ok "JAVA_HOME -> Android Studio JBR"
  else
    warn "JAVA_HOME unset or elsewhere; add to your shell profile:"
    printf '        export JAVA_HOME="%s"\n' "$JBR"
  fi
else
  warn "Android Studio JBR not found at $JBR"
fi

SDK="$HOME/Library/Android/sdk"
if [ -d "$SDK" ]; then
  if [ "${ANDROID_HOME:-}" = "$SDK" ]; then
    ok "ANDROID_HOME -> $SDK"
  else
    warn "ANDROID_HOME unset; add to your shell profile:"
    printf '        export ANDROID_HOME="%s"\n' "$SDK"
    printf '        export PATH="$ANDROID_HOME/platform-tools:$PATH"\n'
  fi
else
  warn "Android SDK not found at $SDK"
fi

command -v gh >/dev/null 2>&1 \
  && ok "gh installed" \
  || warn "gh missing (the spec-writer agent needs it): brew install gh && gh auth login"
