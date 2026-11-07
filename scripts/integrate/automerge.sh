#!/usr/bin/env bash
# Token-free merge loop for mdc159/sierra-auto. Runs until 23:50 local time.
# Merges a PR when: its workflow checks are finished with none failed, the
# `build` job passed, GitHub says it merges cleanly, the last commit is at
# least 12 minutes old, and either it is not a draft or its author has posted
# the closing comment the lane rules ask for (it mentions "unverified").
# When a PR conflicts with main it asks the session, once per head commit, to
# merge origin/main. When main's build is red it stops merging and says so.
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
R=$SIERRA_REPO
LOG=$SIERRA_WT/automerge-log.md
STATE=$SIERRA_WT/automerge-state
mkdir -p "$STATE"
log() { echo "$(date +%H:%M) $*" | tee -a "$LOG"; }
log "automerge loop started"

Q='[ (.isDraft|tostring), .mergeable, .headRefOid, .commits[-1].committedDate,
  ([.statusCheckRollup[] | select((.workflowName // "") != "") | select(.status != "COMPLETED")] | length | tostring),
  ([.statusCheckRollup[] | select((.workflowName // "") != "") | select(.conclusion == "FAILURE" or .conclusion == "CANCELLED" or .conclusion == "TIMED_OUT")] | length | tostring),
  ([.statusCheckRollup[] | select(.name == "build" and .conclusion == "SUCCESS")] | length | tostring),
  ([.comments[] | select((.body | test("unverified"; "i")) and ((.body | length) > 400))] | length | tostring),
  .title ] | @tsv'

while [ "$(date +%H%M)" -lt 2350 ]; do
  MAIN=$(gh run list -R $R --branch main --event push -L 8 --json conclusion,status,workflowName \
    -q '[.[] | select(.workflowName == "build" and .status == "completed")][0].conclusion' 2>/dev/null)
  if [ "$MAIN" = "failure" ]; then
    [ -f "$STATE/main-red" ] || { log "MAIN BUILD IS RED: merges on hold until it is green again"; touch "$STATE/main-red"; }
    sleep 120; continue
  fi
  rm -f "$STATE/main-red"

  MERGED=0
  for N in $(gh pr list -R $R --state open -L 50 --json number -q '.[].number' 2>/dev/null); do
    LINE=$(gh pr view "$N" -R $R --json isDraft,mergeable,headRefOid,commits,statusCheckRollup,comments,title -q "$Q" 2>/dev/null) || continue
    IFS=$'\t' read -r DRAFT MERGEABLE SHA LAST PENDING FAILED BUILDOK CLOSING TITLE <<< "$LINE"
    AGE=$(( ( $(date +%s) - $(date -d "$LAST" +%s 2>/dev/null || echo 0) ) / 60 ))

    if [ "$MERGEABLE" = "CONFLICTING" ]; then
      if [ ! -f "$STATE/conflict-$N-$SHA" ]; then
        gh pr comment "$N" -R $R --body "Coordinator (automatic): main has moved and this branch now conflicts with it. Merge origin/main into this branch, keep every lane's additions in the shared files (AndroidManifest.xml, MainActivity.kt, app/build.gradle.kts, the tool registry), push, and get the build green again. It will be merged automatically once it is green and quiet." >/dev/null 2>&1
        touch "$STATE/conflict-$N-$SHA"
        log "#$N conflicts with main; asked its session to merge main ($TITLE)"
      fi
      continue
    fi
    [ "$MERGEABLE" = "MERGEABLE" ] || continue
    [ "$PENDING" = "0" ] || continue
    [ "$FAILED" = "0" ] || continue
    [ "$BUILDOK" != "0" ] || continue
    [ "$AGE" -ge 12 ] || continue
    if [ "$DRAFT" = "true" ] && [ "$CLOSING" = "0" ]; then continue; fi

    [ "$DRAFT" = "true" ] && gh pr ready "$N" -R $R >/dev/null 2>&1
    if gh pr merge "$N" -R $R --merge >/dev/null 2>&1; then
      log "merged #$N ($TITLE) at ${SHA:0:7}, last commit ${AGE} min old"
      MERGED=1
      break   # let main build and the other PRs' merge state settle
    else
      log "#$N looked ready but the merge command failed ($TITLE)"
    fi
  done
  [ "$MERGED" = "1" ] && sleep 240 || sleep 120
done
log "automerge loop finished (23:50)"
