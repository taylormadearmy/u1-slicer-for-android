#!/usr/bin/env bash
# Install tracked git hooks into this clone's .git/hooks/.
#
# Run once per clone. Re-run after pulling new hook scripts.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
GIT_COMMON_DIR="$(git rev-parse --git-common-dir)"
HOOKS_SOURCE="$REPO_ROOT/scripts/git-hooks"
HOOKS_TARGET="$GIT_COMMON_DIR/hooks"

if [ ! -d "$HOOKS_SOURCE" ]; then
  echo "scripts/git-hooks/ not found — nothing to install." >&2
  exit 1
fi

mkdir -p "$HOOKS_TARGET"

installed=0
for hook in "$HOOKS_SOURCE"/*; do
  [ -f "$hook" ] || continue
  name="$(basename "$hook")"
  target="$HOOKS_TARGET/$name"
  cp "$hook" "$target"
  chmod +x "$target"
  echo "installed: $name -> $target"
  installed=$((installed + 1))
done

if [ "$installed" -eq 0 ]; then
  echo "scripts/git-hooks/ contained no hook files." >&2
  exit 1
fi

echo ""
echo "$installed hook(s) installed. These run automatically on relevant git events."
