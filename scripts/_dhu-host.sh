#!/usr/bin/env bash
# Background host for one Desktop Head Unit session. dhu.sh starts it; do not
# run it by hand.
#
# The DHU quits at once when its stdin is closed, so this process owns the DHU
# and keeps a pipe to its stdin open. Lines appended to commands.txt in the
# state folder are forwarded to the DHU console. The DHU's output goes to
# dhu.log. Ported from _dhu-host.ps1 (git history keeps the original).
set -euo pipefail

DHU=''
STATE_DIR=''
PORT=5277
CONFIG=''

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dhu) DHU="${2:?}"; shift 2 ;;
        --state-dir) STATE_DIR="${2:?}"; shift 2 ;;
        --port) PORT="${2:?}"; shift 2 ;;
        --config) CONFIG="${2:?}"; shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done
[[ -n "$DHU" && -n "$STATE_DIR" ]] || { printf 'Both --dhu and --state-dir are required.\n' >&2; exit 2; }

commands="$STATE_DIR/commands.txt"
log="$STATE_DIR/dhu.log"
state_file="$STATE_DIR/state.json"
fifo="$STATE_DIR/console.fifo"
: > "$commands"
: > "$log"
rm -f "$fifo"
mkfifo "$fifo"

dhu_pid=''
forwarder_pid=''
dhu_rc=0

# /proc/<pid>/stat field 22 (starttime, clock ticks): changes if a pid is recycled.
proc_ticks() {
    local stat rest
    stat="$(cat "/proc/$1/stat" 2>/dev/null)" || return 1
    rest="${stat##*) }"
    awk '{print $20}' <<< "$rest"
}

cleanup() {
    if [[ -n "$dhu_pid" ]] && kill -0 "$dhu_pid" 2>/dev/null; then
        printf 'quit\n' >&3 2>/dev/null || true
        local _
        for _ in $(seq 1 30); do
            kill -0 "$dhu_pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$dhu_pid" 2>/dev/null; then
            kill "$dhu_pid" 2>/dev/null || true
        fi
        wait "$dhu_pid" 2>/dev/null || true
    fi
    [[ -n "$forwarder_pid" ]] && kill "$forwarder_pid" 2>/dev/null || true
    printf '[host] DHU exited with code %d\n' "$dhu_rc" >> "$log"
    rm -f "$state_file" "$fifo"
}
trap cleanup EXIT
trap 'exit 1' TERM INT

# Hold both ends of the fifo so the DHU's stdin never reaches EOF and
# `dhu.sh send` appends can never block on a missing reader.
exec 3<>"$fifo"

dhu_args=()
if [[ "$PORT" != 5277 ]]; then dhu_args+=("--adb=$PORT"); fi
if [[ -n "$CONFIG" ]]; then dhu_args+=("--config=$CONFIG"); fi

if [[ "$DHU" == *run-dhu.sh ]]; then
    ( cd -- "$(dirname -- "$DHU")" && exec bash "$DHU" "${dhu_args[@]}" ) <&3 >>"$log" 2>&1 &
else
    ( cd -- "$(dirname -- "$DHU")" && exec "$DHU" "${dhu_args[@]}" ) <&3 >>"$log" 2>&1 &
fi
dhu_pid=$!

tail -n +1 -f "$commands" >&3 &
forwarder_pid=$!

ticks=''
for _ in $(seq 1 50); do
    ticks="$(proc_ticks "$dhu_pid")" && [[ -n "$ticks" ]] && break
    sleep 0.1
done

json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    printf '%s' "$s"
}

printf '{"dhuPid":"%s","dhuStartTicks":"%s","hostPid":"%s","port":"%s","log":"%s","commands":"%s"}\n' \
    "$dhu_pid" "$ticks" "$$" "$PORT" "$(json_escape "$log")" "$(json_escape "$commands")" \
    > "$state_file"

wait "$dhu_pid" || dhu_rc=$?
