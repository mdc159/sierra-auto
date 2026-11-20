#!/usr/bin/env bash
# End-to-end smoke test on the real phone: install, connect the DHU, open
# Sierra, and save screenshots of Home, Places and Talk with the log.
#
# Runs install-phone.sh, dhu.sh start, opens Sierra on the car screen, then
# captures Home, Places and Talk. Everything goes to a timestamped folder under
# the runs folder (canonical repository .local/runs):
#
#   home.png, places.png, talk.png   car screen
#   launcher.png                     the Android Auto app grid, if it was used
#   car.log                          filtered logcat for the run
#   android-auto-dump.txt            Android Auto's own state dump
#   smoke.log                        output of the scripts it ran
#   summary.txt                      what passed and what did not
#
# The script checks what it can without reading the pictures: the session is
# connected, Android Auto reports Sierra in the foreground, and the template
# host logs a new template from the app after each tap. Look at the PNGs for
# the rest.
#
# Tap points come from scripts/screens.json.
#
#   scripts/smoke.sh
#   scripts/smoke.sh --skip-install
#   scripts/smoke.sh --apk /path/sierra-auto.apk --mic-wav /audio/question.wav
#
# Ported from smoke.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

TAG=''
APK=''
SKIP_INSTALL=0
MIC_WAV=''
MIC_SECONDS=12
POINTS="$SIERRA_SCRIPT_DIR/screens.json"
SETTLE_SEC=3
SERIAL=''

usage() {
    cat <<'EOF'
Usage: scripts/smoke.sh [options]

  --tag TAG          install this release tag (default: latest)
  --apk APK          install this local APK
  --skip-install     test what is on the phone now
  --mic-wav WAV      play this WAV into the car microphone on the Talk screen;
                     needs a DHU started by dhu.sh
  --mic-seconds SEC  how long to wait after --mic-wav (default 12)
  --points FILE      tap points file (default scripts/screens.json)
  --settle-sec SEC   seconds to wait after each tap (default 3)
  --serial SERIAL    adb serial of the phone (default: the only device)
  -h, --help         show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) TAG="${2:?}"; shift 2 ;;
        --apk) APK="${2:?}"; shift 2 ;;
        --skip-install) SKIP_INSTALL=1; shift ;;
        --mic-wav) MIC_WAV="${2:?}"; shift 2 ;;
        --mic-seconds) MIC_SECONDS="${2:?}"; shift 2 ;;
        --points) POINTS="${2:?}"; shift 2 ;;
        --settle-sec) SETTLE_SEC="${2:?}"; shift 2 ;;
        --serial) SERIAL="${2:?}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; sierra_die "Unknown argument: $1" ;;
    esac
done

SERIAL="$(sierra_phone_serial "$SERIAL")"
out="$(sierra_runs_dir)/smoke-$(date +%Y%m%d-%H%M%S)"
mkdir -p -- "$out"
smoke_log="$out/smoke.log"
results=()
FAILED=0

add_result() {
    # add_result NAME STATUS [NOTE]
    local name="$1" status="$2" note="${3:-}"
    local line
    line="$(printf '%-5s %s' "$status" "$name")"
    [[ -n "$note" ]] && line="$line: $note"
    results+=("$line")
    case "$status" in
        PASS) printf '%s\n' "$line" ;;
        FAIL) printf '\033[31m%s\033[0m\n' "$line"; FAILED=1 ;;
        *) printf '%s\n' "$line" ;;
    esac
}

# Runs one of the sibling scripts in its own process, so its exit code is
# reliable, appends its output to smoke.log and shows it indented (run_script
# keeps it only in smoke.log).
# shellcheck disable=SC2329  # invoked in the finalize trap below
run_script() {
    local name="$1"
    shift
    local rc=0
    bash "$SIERRA_SCRIPT_DIR/$name" "$@" >>"$smoke_log" 2>&1 || rc=$?
    return "$rc"
}

run_script_shown() {
    local name="$1"
    shift
    local rc=0
    bash "$SIERRA_SCRIPT_DIR/$name" "$@" 2>&1 | tee -a -- "$smoke_log" | sed 's/^/    /' || rc=$?
    return "$rc"
}

get_phone_time() {
    sierra_invoke_adb "$SERIAL" shell "date '+%m-%d %H:%M:%S.000'" | head -1
}

# invoke_tap POINT
TAP_X='' TAP_Y=''
invoke_tap() {
    local got
    got="$(sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*\[[[:space:]]*\([0-9][0-9]*\)[[:space:]]*,[[:space:]]*\([0-9][0-9]*\)[[:space:]]*\].*/\1 \2/p' "$POINTS")"
    [[ -n "$got" ]] || sierra_die "screens.json has no point named '$1'."
    TAP_X="${got% *}"; TAP_Y="${got#* }"
    run_script_shown dhu.sh tap "$TAP_X" "$TAP_Y" --serial "$SERIAL"
    sleep "$SETTLE_SEC"
}

save_shot() {
    local path="$out/$1.png"
    if run_script_shown shot.sh "$path"; then
        if [[ -s "$path" ]]; then add_result "$1.png" PASS; else add_result "$1.png" FAIL 'no screenshot; see smoke.log'; fi
    else
        add_result "$1.png" FAIL 'no screenshot; see smoke.log'
    fi
}

# Template types the host received from the app since a phone time, read from
# the template host's log lines.
get_new_templates() {
    sierra_invoke_adb -a "$SERIAL" logcat -d -T "$1" -s 'CarApp.H.Tem:I' \
        | grep -oE 'Host received new template \(type: ([[:alnum:]_.]*[.])?[[:alnum:]_]+\)' \
        | sed -e 's/^.*type: //' -e 's/^\([[:alnum:]_.]*\)\.\([[:alnum:]_]*\)$/\2/' || true
}

open_screen() {
    local name="$1" point="$2" since templates
    since="$(get_phone_time)"
    invoke_tap "$point"
    templates="$(get_new_templates "$since")"
    if [[ -n "$templates" ]]; then
        add_result "$name screen" PASS "after the tap the host received $(printf '%s' "$templates" | tr '\n' ',' | sed 's/,$//')"
    else
        add_result "$name screen" FAIL "no new template after the tap; check the $point point in screens.json"
    fi
}

started_at="$(get_phone_time)"
sierra_step "Smoke run in $out"

# shellcheck disable=SC2329  # wired to the EXIT trap
finalize() {
    local car_log="$out/car.log" bad=0
    # Logs, whatever happened.
    run_script car-logs.sh --dump --since "$started_at" --out "$car_log" --serial "$SERIAL" || true
    run_script car-logs.sh --host-dump --out "$out/android-auto-dump.txt" --serial "$SERIAL" || true
    if [[ -f "$car_log" ]]; then
        bad="$(grep -cE 'FATAL EXCEPTION|CarApp\.H\.Tem: Error' "$car_log" || true)"
    fi
    if [[ "$bad" -eq 0 ]]; then
        add_result 'no crash or host error in car.log' PASS
    else
        add_result 'no crash or host error in car.log' FAIL "$bad matching lines"
    fi
    {
        printf 'Sierra smoke run %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
        printf 'Phone %s\n' "$SERIAL"
        printf '\n'
        printf '%s\n' "${results[@]}"
    } > "$out/summary.txt"
    printf '\nOutput folder: %s\n' "$out"
}
trap finalize EXIT

# A failing step records its result and stops the flow early.
stop_flow() {
    add_result "$1" FAIL "${2:-}"
    exit 1
}

# 1. Install.
if [[ $SKIP_INSTALL -eq 1 ]]; then
    add_result 'install' SKIP 'testing what is on the phone'
else
    install_args=(--serial "$SERIAL")
    [[ -n "$APK" ]] && install_args+=(--apk "$APK")
    [[ -z "$APK" && -n "$TAG" ]] && install_args+=(--tag "$TAG")
    if run_script_shown install-phone.sh "${install_args[@]}"; then
        add_result 'install' PASS
    else
        stop_flow 'install' 'see smoke.log'
    fi
fi
if sierra_get_install_record "$SERIAL"; then
    if [[ "$RECORD_INSTALLER" == "$PLAY_INSTALLER" ]]; then
        add_result 'installed' PASS "$RECORD_VERSION_NAME (code $RECORD_VERSION_CODE), installer $RECORD_INSTALLER"
    else
        add_result 'installed' FAIL "$RECORD_VERSION_NAME (code $RECORD_VERSION_CODE), installer $RECORD_INSTALLER"
    fi
else
    stop_flow 'installed' 'Sierra is not on the phone'
fi

# 2. DHU session.
if run_script_shown dhu.sh start --serial "$SERIAL"; then
    add_result 'dhu session, Sierra offered' PASS
else
    stop_flow 'dhu session, Sierra offered' 'see smoke.log'
fi

# 3. Bring Sierra to the front.
opened=0
for _ in 1 2 3 4; do
    [[ $opened -eq 1 ]] && break
    sierra_get_car_state "$SERIAL"
    if [[ "$CAR_FOREGROUND" == "$SIERRA_PACKAGE" && "$CAR_LAUNCHER_SHOWN" != true ]]; then
        opened=1
        break
    fi
    if [[ "$CAR_LAUNCHER_SHOWN" == true ]]; then
        save_shot 'launcher'
        invoke_tap 'sierraIcon'
    else
        invoke_tap 'launcherButton'
    fi
done
if [[ $opened -ne 1 ]]; then
    save_shot 'not-opened'
    sierra_get_car_state "$SERIAL"
    stop_flow 'open Sierra' "Android Auto shows '${CAR_FOREGROUND}'. Look at launcher.png and set sierraIcon in screens.json to where the Sierra icon is."
fi
add_result 'open Sierra' PASS "Android Auto reports $SIERRA_PACKAGE in the foreground"

# Leave any sub-screen. On Home the header has no back arrow and the tap does nothing.
invoke_tap 'back'
invoke_tap 'back'

# 4. Screens.
save_shot 'home'

open_screen 'places' 'places'
save_shot 'places'
invoke_tap 'back'

open_screen 'talk' 'talk'
if [[ -n "$MIC_WAV" ]]; then
    managed=0
    sierra_get_managed_dhu && managed=1
    if [[ $managed -eq 1 ]]; then
        wav="$(realpath -- "$MIC_WAV")"
        run_script_shown dhu.sh send mic play "$wav" --serial "$SERIAL" || true
        sleep "$MIC_SECONDS"
        add_result 'mic play' PASS "sent $wav; read talk.png for the result"
    else
        add_result 'mic play' SKIP "the DHU was not started by dhu.sh, so there is no console to send mic commands to"
    fi
fi
save_shot 'talk'
invoke_tap 'back'

sierra_get_car_state "$SERIAL"
if [[ "$CAR_CONNECTED" == true && "$CAR_FOREGROUND" == "$SIERRA_PACKAGE" ]]; then
    add_result 'still running' PASS "connected=$CAR_CONNECTED, foreground=$CAR_FOREGROUND"
else
    add_result 'still running' FAIL "connected=$CAR_CONNECTED, foreground=$CAR_FOREGROUND"
fi

exit "$FAILED"
