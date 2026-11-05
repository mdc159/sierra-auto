#!/bin/sh
# usage: ifull.sh <label>  -> assembleDebug + unit tests + lintDebug in the integrate worktree; prints a short verdict
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
use_toolchain
cd "$INTEGRATE_DIR" || exit 2
mkdir -p "$INTEGRATE_LOGS"
LOG="$INTEGRATE_LOGS/full-$1.log"
./gradlew assembleDebug testDebugUnitTest lintDebug --continue --no-daemon --console=plain > "$LOG" 2>&1
rc=$?
echo "exit=$rc" >> "$LOG"
grep -E "^e: |FAILED|BUILD (SUCCESSFUL|FAILED)|tests completed|What went wrong|Lint found|: Error:" "$LOG" | cut -c1-300 | head -50
python - <<'PY'
import re,glob
for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml'):
    s=open(f,encoding='utf-8').read()
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<failure message="([^"]{0,400})',s):
        print('TESTFAIL',f.split('TEST-')[-1], m.group(1),'=>',m.group(2)[:400])
PY
exit $rc
