#!/usr/bin/env bash
# Checks that a checkout is ready to work in: the local toolchain, and the repo's
# context files.
#
# The context check runs from a SessionStart hook (--hook) as well as by hand. It
# catches the kind of rot no compiler sees — a doc listed in an index that no longer
# exists, or a doc naming a `Symbol` that was renamed out of the sources. It prints
# nothing when clean.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

HOOK_MODE=false
[ "${1:-}" = "--hook" ] && HOOK_MODE=true

# Checks the repo's docs against the rules in CLAUDE.md: every doc indexed and every
# index line backed by a file, feature docs inside their word budget, and no doc naming
# a symbol the sources no longer have. Prints one line per problem, nothing when clean.
check_context() {
  local docs="$REPO_ROOT/.claude/docs" idx="$REPO_ROOT/.claude/docs/README.md"
  local features="$REPO_ROOT/.claude/docs/features" problems=0
  local f base rel

  # Top-level index <-> the docs it covers, both directions.
  for f in "$docs"/*.md "$docs"/project/*.md; do
    [ -e "$f" ] || continue
    base="$(basename "$f")"
    [ "$base" = "README.md" ] && continue
    rel="${f#$docs/}"
    grep -q "($rel)" "$idx" || { echo "doc '$rel' has no line in docs/README.md"; problems=$((problems+1)); }
  done
  for rel in $(grep -o '(\(project/\)\?[a-z0-9-]*\.md)' "$idx" | tr -d '()'); do
    [ -f "$docs/$rel" ] || { echo "docs/README.md lists '$rel', which does not exist"; problems=$((problems+1)); }
  done

  # Feature docs: indexed in their own README, and short enough to still be rationale.
  for f in "$features"/*.md; do
    [ -e "$f" ] || continue
    base="$(basename "$f")"
    [ "$base" = "README.md" ] && continue
    grep -q "($base)" "$features/README.md" || { echo "feature doc '$base' has no line in its README"; problems=$((problems+1)); }
    local words; words=$(wc -w < "$f" | tr -d " ")
    [ "$words" -gt 1500 ] && { echo "feature doc '$base' is $words words — past the point where it is probably describing code, not explaining it"; problems=$((problems+1)); }
  done

  # Referential check: a doc naming a `Symbol` that no longer exists in the sources is
  # stale in a way no structural check sees. ALL-CAPS tokens are platform constants;
  # EXTERNAL_SYMBOLS are deliberate mentions of APIs this project does not use.
  local EXTERNAL_SYMBOLS="Camera2Interop Camera2CameraControl Camera2CameraInfo ImageCapture PixelCopy Preview"
  if [ -d "$features" ]; then
    for sym in $(grep -oh '`[A-Z][A-Za-z0-9]*' "$features"/*.md 2>/dev/null | tr -d '`' | sort -u); do
      printf '%s' "$sym" | grep -q '^[A-Z0-9_]*$' && continue
      case " $EXTERNAL_SYMBOLS " in *" $sym "*) continue ;; esac
      grep -rqw "$sym" --include='*.kt' "$REPO_ROOT/feature" "$REPO_ROOT/shared" "$REPO_ROOT/app" 2>/dev/null && continue
      # A doc may name a file whose declarations are named differently (DialText.kt holds no
      # `DialText` function), so a matching filename counts as the symbol existing.
      find "$REPO_ROOT/feature" "$REPO_ROOT/shared" "$REPO_ROOT/app" -name "$sym.kt" -not -path '*/build/*' 2>/dev/null | grep -q . \
        || { echo "feature docs name '$sym', which no longer exists in the sources"; problems=$((problems+1)); }
    done
  fi

  return $problems
}

if $HOOK_MODE; then
  # Stay silent when everything is consistent; speak up only when something is off.
  issues="$(check_context 2>/dev/null || true)"
  if [ -n "$issues" ]; then
    msg="Context files need attention: $(printf '%s' "$issues" | tr '\n' '|' | sed 's/|/; /g; s/; $//')."
    printf '{"systemMessage":"%s"}\n' "$(printf '%s' "$msg" | sed 's/"/\\"/g')"
  fi
  exit 0
fi

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
warn() { printf '  \033[33mwarn\033[0m  %s\n' "$1"; }

echo "Repo:   $REPO_ROOT"
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
  ok "docs indexes are in sync and feature docs are current"
else
  printf '%s\n' "$issues" | while IFS= read -r line; do warn "$line"; done
fi
