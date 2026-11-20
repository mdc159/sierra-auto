#!/usr/bin/env bash
# Shows the phone's log filtered to Sierra and to Android Auto's car-app tags.
#
# Keeps lines from Sierra's own process, lines that name the package, crashes,
# and these Android Auto tags: CAR.VALIDATOR (package accepted or DENIED, with
# a reason), GH.AppNotifier, GH.ConnLoggerV2 (session connect and disconnect),
# CarApp.* and HostConfig (the template host binding to the app).
#
# Follows new lines until Ctrl+C by default. --dump prints what is in the
# buffer and exits. --since starts either mode at a given time.
#
# Android Auto writes very little to logcat unless its tags are raised.
# --verbose-tags raises them for this boot; --reset-tags puts them back.
# --host-dump saves Android Auto's own state dump instead, which holds the
# last 500 lines of its internal log and the validator state, needs no tag
# changes and works with the phone locked.
#
#   scripts/car-logs.sh
#   scripts/car-logs.sh --dump --since '11-04 18:00:00.000' --out .local/runs/car.log
#   scripts/car-logs.sh --host-dump --out .local/runs/android-auto-dump.txt
#
# Ported from car-logs.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

DUMP=0
SINCE=''
OUT=''
HOST_DUMP=0
VERBOSE_TAGS=0
RESET_TAGS=0
SERIAL=''

usage() {
    cat <<'EOF'
Usage: scripts/car-logs.sh [options]

  --dump           print what is in the log buffer and exit (default: follow)
  --since TIME     logcat -T time, as printed by the log itself:
                   'MM-DD HH:MM:SS.mmm' (phone clock)
  --out FILE       write the filtered lines to this file beneath .local
  --host-dump      save Android Auto's own state dump instead of logcat
  --verbose-tags   raise Android Auto's log tags to VERBOSE for this boot
  --reset-tags     put those tags back to their default level
  --serial SERIAL  adb serial of the phone (default: the only device)
  -h, --help       show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dump) DUMP=1; shift ;;
        --since) SINCE="${2:?}"; shift 2 ;;
        --out) OUT="${2:?}"; shift 2 ;;
        --host-dump) HOST_DUMP=1; shift ;;
        --verbose-tags) VERBOSE_TAGS=1; shift ;;
        --reset-tags) RESET_TAGS=1; shift ;;
        --serial) SERIAL="${2:?}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; sierra_die "Unknown argument: $1" ;;
    esac
done

[[ -n "$OUT" ]] && OUT="$(sierra_resolve_output_file "$OUT")"
SERIAL="$(sierra_phone_serial "$SERIAL")"

tags=('CAR.VALIDATOR' 'GH.AppNotifier' 'GH.ConnLoggerV2')

if [[ $VERBOSE_TAGS -eq 1 || $RESET_TAGS -eq 1 ]]; then
    level=''
    [[ $RESET_TAGS -eq 0 ]] && level='VERBOSE'
    for tag in "${tags[@]}"; do
        sierra_invoke_adb "$SERIAL" shell setprop "log.tag.$tag" "'$level'" >/dev/null
    done
    if [[ $RESET_TAGS -eq 1 ]]; then
        sierra_note "Reset: ${tags[*]}"
    else
        sierra_note "Raised to VERBOSE: ${tags[*]}"
    fi
    if [[ $RESET_TAGS -eq 1 && $DUMP -eq 0 && $HOST_DUMP -eq 0 ]]; then
        exit 0
    fi
fi

if [[ $HOST_DUMP -eq 1 ]]; then
    host_lines="$(sierra_invoke_adb -a "$SERIAL" shell dumpsys activity service "$GEARHEAD_PACKAGE")" || true
    if [[ -z "$OUT" ]]; then
        OUT="$(sierra_runs_dir)/android-auto-dump-$(date +%Y%m%d-%H%M%S).txt"
    fi
    OUT="$(sierra_resolve_output_file "$OUT")"
    printf '%s\n' "$host_lines" > "$OUT"
    sierra_get_car_state "$SERIAL"
    sierra_note "Connected: $CAR_CONNECTED. On screen: ${CAR_FOREGROUND:-unknown}. Unknown sources: ${CAR_UNKNOWN_SOURCES:-unknown}."
    validator_lines="$(printf '%s\n' "$host_lines" | grep 'CAR\.VALIDATOR' | grep -cE "$SIERRA_PACKAGE_RE" || true)"
    sierra_note "Validator lines naming Sierra: $validator_lines"
    sierra_note 'The full dump lists other apps on the phone. Do not post it whole.'
    printf '%s\n' "$OUT"
    exit 0
fi

# Lines from Sierra's process are kept whatever their tag. The pid changes when
# the app restarts, so it is re-read from the 'Start proc' line inside the
# filter itself.
sierra_pid="$(sierra_invoke_adb -a "$SERIAL" shell pidof "$SIERRA_PACKAGE" | head -1)" || true
sierra_pid="$(grep -oE '^[0-9]+' <<< "$sierra_pid" || true)"

logcat=(logcat -v threadtime)
if [[ $DUMP -eq 1 ]]; then
    logcat+=(-d)
fi
if [[ -n "$SINCE" ]]; then
    logcat+=(-T "$SINCE")
elif [[ $DUMP -eq 0 ]]; then
    # When following, start at the newest line instead of replaying the buffer.
    logcat+=(-T 1)
fi

filter() {
    awk -v spid="$sierra_pid" -v pkg="$SIERRA_PACKAGE_RE" -v startproc="^Start proc [0-9]+:${SIERRA_PACKAGE//./[.]}/" '
        function drop_line(l) {
            return l ~ /Package DENIED; (Uses for [[:alnum:]_]+ not defined|package is disabled)/ \
                || l ~ /ActivityThread: Package \[/ \
                || l ~ /[[:space:]](adbd|artd)[[:space:]]*:/
        }
        function keep_line(l) {
            return l ~ /CAR\.VALIDATOR|GH\.AppNotifier|GH\.ConnLoggerV2|CarApp\.|HostConfig|AndroidRuntime|FATAL EXCEPTION/ || l ~ pkg
        }
        {
            if ($0 ~ startproc) {
                split($0, a, " ")
                split(a[3], b, ":")
                spid = b[1]
            }
            if (drop_line($0)) next
            if (keep_line($0)) { print; next }
            if (spid != "" && $0 ~ ("^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+" spid "([[:space:]]|$)")) print
        }
    '
}

adb_bin="$(sierra_adb)"
if [[ -n "$OUT" ]]; then
    if [[ $DUMP -eq 1 ]]; then
        "$adb_bin" -s "$SERIAL" "${logcat[@]}" 2>&1 | filter > "$OUT"
    else
        "$adb_bin" -s "$SERIAL" "${logcat[@]}" 2>&1 | filter | tee -- "$OUT"
    fi
    printf '%s\n' "$OUT"
else
    "$adb_bin" -s "$SERIAL" "${logcat[@]}" 2>&1 | filter
fi
