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

# Checks the repo's context files against the rules in CLAUDE.md / the write-memory
# skill: index and files in sync both ways, wiki-links resolve, slugs match filenames,
# feature docs inside their word budget. Prints one line per problem, nothing when clean.
check_context() {
  local mem="$REPO_ROOT/.claude/memory" idx="$REPO_ROOT/.claude/memory/MEMORY.md"
  local docs="$REPO_ROOT/.claude/docs/features" problems=0
  local f base

  for f in "$mem"/*.md; do
    base="$(basename "$f")"
    [ "$base" = "MEMORY.md" ] && continue
    grep -q "($base)" "$idx" || { echo "memory '$base' has no line in MEMORY.md"; problems=$((problems+1)); }
    grep -q "^name: ${base%.md}$" "$f" || { echo "memory '$base' has a name: that doesn't match its filename"; problems=$((problems+1)); }
    grep -q "^description: Read " "$f" || { echo "memory '$base' description is not a trigger (must start 'Read when/before')"; problems=$((problems+1)); }
  done

  for base in $(grep -o '([a-z0-9-]*\.md)' "$idx" | tr -d '()'); do
    [ -f "$mem/$base" ] || { echo "MEMORY.md lists '$base', which does not exist"; problems=$((problems+1)); }
  done

  for base in $(grep -oh '\[\[[a-z0-9-]*\]\]' "$mem"/*.md | tr -d '[]' | sort -u); do
    [ -f "$mem/$base.md" ] || { echo "dangling wiki-link [[$base]] in memory"; problems=$((problems+1)); }
  done

  # Referential check: a doc naming a `Symbol` that no longer exists in the sources is
  # stale in a way no structural check sees. ALL-CAPS tokens are platform constants;
  # EXTERNAL_SYMBOLS are deliberate mentions of APIs this project does not use.
  local EXTERNAL_SYMBOLS="Camera2Interop Camera2CameraControl Camera2CameraInfo ImageCapture PixelCopy Preview"
  if [ -d "$docs" ]; then
    for sym in $(grep -oh '`[A-Z][A-Za-z0-9]*' "$docs"/*.md 2>/dev/null | tr -d '`' | sort -u); do
      printf '%s' "$sym" | grep -q '^[A-Z0-9_]*$' && continue
      case " $EXTERNAL_SYMBOLS " in *" $sym "*) continue ;; esac
      grep -rqw "$sym" --include='*.kt' "$REPO_ROOT/feature" "$REPO_ROOT/shared" "$REPO_ROOT/app" 2>/dev/null && continue
      # A doc may name a file whose declarations are named differently (DialText.kt holds no
      # `DialText` function), so a matching filename counts as the symbol existing.
      find "$REPO_ROOT/feature" "$REPO_ROOT/shared" "$REPO_ROOT/app" -name "$sym.kt" -not -path '*/build/*' 2>/dev/null | grep -q . \
        || { echo "feature docs name '$sym', which no longer exists in the sources"; problems=$((problems+1)); }
    done
  fi

  if [ -d "$docs" ]; then
    for f in "$docs"/*.md; do
      base="$(basename "$f")"
      [ "$base" = "README.md" ] && continue
      grep -q "($base)" "$docs/README.md" || { echo "feature doc '$base' has no line in its README"; problems=$((problems+1)); }
      local words; words=$(wc -w < "$f" | tr -d " ")
      [ "$words" -gt 1500 ] && { echo "feature doc '$base' is $words words — past the point where it is probably describing code, not explaining it"; problems=$((problems+1)); }
    done
  fi

  return $problems
}

if $HOOK_MODE; then
  # Stay silent when already correct; speak up only when something was repaired.
  changed="$(link_memory 2>/dev/null || true)"
  issues="$(check_context 2>/dev/null || true)"
  msg=""
  [ -n "$changed" ] && msg="Claude memory: $changed."
  [ -n "$issues" ] && msg="$msg Context files need attention: $(printf '%s' "$issues" | tr '\n' '|' | sed 's/|/; /g; s/; $//')."
  if [ -n "$msg" ]; then
    printf '{"systemMessage":"%s"}\n' "$(printf '%s' "${msg# }" | sed 's/"/\\"/g')"
  fi
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
  || warn "gh missing (/take-issue, /ship and the spec-writer agent need it): brew install gh && gh auth login"

echo
echo "Context files:"
if issues="$(check_context)"; then
  ok "memory index, wiki-links and feature docs are consistent"
else
  printf '%s\n' "$issues" | while IFS= read -r line; do warn "$line"; done
fi
