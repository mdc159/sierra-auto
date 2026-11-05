#!/bin/sh
# usage: ibuild.sh <label>  -> builds + unit tests in the integrate worktree, log at build-<label>.log
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
use_toolchain
cd "$INTEGRATE_DIR" || exit 2
mkdir -p "$INTEGRATE_LOGS"
LOG="$INTEGRATE_LOGS/build-$1.log"
./gradlew assembleDebug testDebugUnitTest --no-daemon --console=plain > "$LOG" 2>&1
rc=$?
echo "exit=$rc" >> "$LOG"
grep -E "^e: |FAILED|BUILD (SUCCESSFUL|FAILED)|tests completed|> Task :app:testDebugUnitTest|error:|What went wrong" "$LOG" | head -60
exit $rc
