#!/bin/sh
# Polls every 60 s. Exits (printing why) when an open PR looks ready, or main's latest build run failed,
# or after $1 seconds (default 1500). "Ready" = build check SUCCESS on the head and
# (not draft, or an owner-account comment newer than the last commit, or no commit for 20 minutes).
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
R=$SIERRA_REPO
SKIP="${2:-}"   # space-separated PR numbers to ignore
end=$(( $(date +%s) + ${1:-1500} ))
while [ "$(date +%s)" -lt "$end" ]; do
  main=$(gh run list -R $R --workflow build --branch main --limit 5 --json number,status,conclusion,headSha --jq '[.[]|select(.status=="completed" and .conclusion!="cancelled")][0]|"\(.number) \(.conclusion) \(.headSha[0:7])"' 2>/dev/null)
  case "$main" in *failure*|*timed_out*|*startup_failure*) echo "MAIN RED: build #$main"; exit 0;; esac
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  cutoff=$(date -u -d '20 minutes ago' +%Y-%m-%dT%H:%M:%SZ)
  out=$(gh pr list -R $R --json number,title,isDraft,statusCheckRollup,commits,comments --jq '.[] | {n:.number, t:.title[0:50], d:.isDraft, b:([.statusCheckRollup[]|select(.name=="build")|.conclusion]|last), lc:(.commits[-1].committedDate), cm:([.comments[]|select(.author.login=="mdc159")|.createdAt]|max)} | select(.b=="SUCCESS") | select((.d|not) or (.cm!=null and .cm>.lc) or (.lc<"'"$cutoff"'")) | "\(.n) draft=\(.d) lastcommit=\(.lc) lastcomment=\(.cm) \(.t)"' 2>/dev/null)
  for s in $SKIP; do out=$(echo "$out" | grep -v "^$s " ); done
  if [ -n "$out" ]; then echo "READY CANDIDATES at $now:"; echo "$out"; echo "main: $main"; exit 0; fi
  sleep 60
done
echo "watch timed out with nothing ready; main: $main"
gh pr list -R $R --json number,title,isDraft --jq '.[]|"\(.number) draft=\(.isDraft) \(.title[0:60])"'
