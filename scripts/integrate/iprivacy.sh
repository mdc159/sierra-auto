#!/bin/sh
# Pre-merge privacy check on the integrate worktree HEAD: named *.ts.net hosts and tailnet (100.64/10) addresses.
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
cd "$INTEGRATE_DIR" || exit 2
out=$(git grep -I -n -E "[A-Za-z0-9][A-Za-z0-9-]*\.[A-Za-z0-9-]+\.ts\.net|//[A-Za-z0-9-]+\.ts\.net|(^|[^0-9.])100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+" HEAD -- . ':!gradle/' | grep -v -E "<[a-z-]+>\.ts\.net|<[a-z-]+>\.<[a-z-]+>\.ts\.net|example\.ts\.net|your-[a-z-]*\.ts\.net|tailnet-name\.ts\.net|tail1234\.ts\.net|\.tailnet\.ts\.net|//gw\.ts\.net|[a-z-]*\.example\.ts\.net")
# SIERRA_PRIVATE_PATTERNS: an extended regex of the real tailnet name and addresses to refuse.
# It is kept out of this public repo on purpose; set it in the environment before running.
if [ -n "${SIERRA_PRIVATE_PATTERNS:-}" ]; then
  real=$(git grep -I -n -i -E "$SIERRA_PRIVATE_PATTERNS" HEAD -- . | cut -c1-160)
else
  echo "note: SIERRA_PRIVATE_PATTERNS is not set, so the check for the real tailnet name is skipped"
  real=""
fi
if [ -n "$real" ]; then echo "REAL TAILNET NAME/ADDRESS PRESENT:"; echo "$real"; exit 1; fi
if [ -n "$out" ]; then echo "PRIVACY HITS:"; echo "$out" | cut -c1-200; exit 1; else echo "privacy check: clean"; fi
