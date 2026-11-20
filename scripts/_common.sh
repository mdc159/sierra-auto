#!/usr/bin/env bash
# Shared helpers for the device test scripts. Source it; do not run it.
#
# Paths come from the environment so nothing machine-specific is hard-coded:
#   SIERRA_TOOLCHAIN  folder holding android-sdk (legacy layout support)
#   SIERRA_SERIAL     adb serial of the phone (default: the only device attached)
#   SIERRA_PACKAGE    package the device scripts target (default com.mdc159.sierra,
#                     the Play/release app). Debug builds install beside it as
#                     the bench app: SIERRA_PACKAGE=com.mdc159.sierra.blitz
#   SIERRA_RUNS       where screenshots, logs and built APKs go
#                     (default <canonical repo>/.local/runs)
#   SIERRA_STATE      where the DHU session state lives
#                     (default <canonical repo>/.local/state/dhu)
# Output overrides must remain beneath the canonical repository's .local folder.
#
# Toolchain: sources <canonical repo>/.local/omarchy-toolchain/env.sh when
# present; otherwise defaults to $HOME/.local/share/android-sdk and the mise
# temurin-17 JDK. Ported from _common.ps1 (git history keeps the original).
#
# The uppercase globals set here (SIERRA_*, RECORD_*, MANAGED_*, CAR_*) are the
# interface that sourcing scripts read, so unused-in-this-file is expected.
# shellcheck disable=SC2034

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_workspace.sh"

SIERRA_PACKAGE="${SIERRA_PACKAGE:-com.mdc159.sierra}"
if [[ ! "$SIERRA_PACKAGE" =~ ^com\.mdc159\.sierra(\.[a-z][a-z0-9_]*)*$ ]]; then
    printf 'SIERRA_PACKAGE must be com.mdc159.sierra or a suffixed variant of it, not %s\n' "$SIERRA_PACKAGE" >&2
    exit 2
fi
# Class names keep the Kotlin namespace whatever the package id.
SIERRA_SERVICE="$SIERRA_PACKAGE/com.mdc159.sierra.car.SierraCarAppService"
# Regex for exactly this package: the release and bench ids share a prefix, so
# a match must not continue into a longer package name.
SIERRA_PACKAGE_RE="${SIERRA_PACKAGE//./[.]}([^A-Za-z0-9_.]|\$)"
GEARHEAD_PACKAGE='com.google.android.projection.gearhead'
GEARHEAD_LAUNCHER_SERVICE='GhAppLauncherService'
PLAY_INSTALLER='com.android.vending'
DHU_PORT=5277

sierra_step() { printf '\033[36m==> %s\033[0m\n' "$*"; }
sierra_note() { printf '    %s\n' "$*"; }
sierra_warn_note() { printf '\033[33m    %s\033[0m\n' "$*"; }

_sierra_setup_toolchain() {
    local root env_sh
    root="$(sierra_root)"
    env_sh="$root/.local/omarchy-toolchain/env.sh"
    if [[ -f "$env_sh" ]]; then
        # shellcheck source=/dev/null
        source "$env_sh"
        return
    fi
    # No local toolchain bundle: fall back to this machine's user-scoped
    # defaults (mise temurin-17, official SDK location).
    if command -v mise >/dev/null 2>&1 && mise where java@temurin-17 >/dev/null 2>&1; then
        JAVA_HOME="${JAVA_HOME:-$(mise where java@temurin-17)}"
        export JAVA_HOME
    elif [[ -d "$HOME/.local/share/mise/installs/java/temurin-17.0.21+9" ]]; then
        JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.21+9}"
        export JAVA_HOME
    fi
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
}
_sierra_setup_toolchain

sierra_android_sdk() {
    local -a candidates=()
    local c
    [[ -n "${SIERRA_TOOLCHAIN:-}" ]] && candidates+=("$SIERRA_TOOLCHAIN/android-sdk")
    [[ -n "${ANDROID_HOME:-}" ]] && candidates+=("$ANDROID_HOME")
    [[ -n "${ANDROID_SDK_ROOT:-}" ]] && candidates+=("$ANDROID_SDK_ROOT")
    candidates+=("$HOME/.local/share/android-sdk")
    for c in "${candidates[@]}"; do
        if [[ -d "$c/platform-tools" ]]; then
            printf '%s\n' "$c"
            return 0
        fi
    done
    sierra_die 'Android SDK not found. Set SIERRA_TOOLCHAIN (folder with android-sdk inside) or ANDROID_HOME.'
}

sierra_adb() {
    local adb
    adb="$(sierra_android_sdk)/platform-tools/adb"
    [[ -x "$adb" ]] || sierra_die "adb not found at $adb"
    printf '%s\n' "$adb"
}

# The Desktop Head Unit launcher: the repo-local wrapper when present (it
# provides the bundled runtime libraries), else the SDK's binary.
sierra_dhu() {
    local root run dhu
    root="$(sierra_root)"
    run="$root/.local/omarchy-toolchain/run-dhu.sh"
    if [[ -x "$run" ]]; then
        printf '%s\n' "$run"
        return 0
    fi
    dhu="$(sierra_android_sdk)/extras/google/auto/desktop-head-unit"
    if [[ ! -x "$dhu" ]]; then
        sierra_die "Desktop Head Unit not found at $dhu. Install it with: sdkmanager \"extras;google;auto\""
    fi
    printf '%s\n' "$dhu"
}

sierra_phone_serial() {
    local serial="${1:-}"
    if [[ -n "$serial" ]]; then printf '%s\n' "$serial"; return 0; fi
    if [[ -n "${SIERRA_SERIAL:-}" ]]; then printf '%s\n' "$SIERRA_SERIAL"; return 0; fi
    local lines ready
    lines="$("$(sierra_adb)" devices | tail -n +2 | grep -E '\S' || true)"
    ready="$(printf '%s\n' "$lines" | grep -E '^(\S+)[[:space:]]+device$' | awk '{print $1}' || true)"
    local count
    count="$(printf '%s\n' "$ready" | grep -c . || true)"
    if [[ "$count" -eq 1 ]]; then
        printf '%s\n' "$ready"
        return 0
    fi
    if [[ "$count" -eq 0 ]]; then
        sierra_die "No phone is attached and authorised for adb. adb devices says: [$lines]. Plug the phone in, unlock it and accept the USB debugging prompt."
    fi
    sierra_die "More than one device is attached ($(printf '%s' "$ready" | tr '\n' ' ')). Pass --serial or set SIERRA_SERIAL."
}

# Runs adb against the phone and prints its combined output. Pass the adb
# arguments after the serial. Returns adb's exit code; with -a as the first
# argument failures are returned instead of fatal (caller checks $?).
sierra_invoke_adb() {
    local allow=0
    if [[ "${1:-}" == "-a" ]]; then allow=1; shift; fi
    local serial="$1"
    shift
    local out rc=0
    out="$("$(sierra_adb)" -s "$serial" "$@" 2>&1)" || rc=$?
    printf '%s\n' "$out"
    if [[ $rc -ne 0 && $allow -eq 0 ]]; then
        sierra_die "adb $* failed ($rc): $out"
    fi
    return "$rc"
}

# Version and install-source record of Sierra on the phone. Sets
# RECORD_VERSION_NAME, RECORD_VERSION_CODE, RECORD_INSTALLER and
# RECORD_INITIATING; returns 1 when it is not installed.
sierra_get_install_record() {
    local serial="$1" text
    text="$(sierra_invoke_adb -a "$serial" shell dumpsys package "$SIERRA_PACKAGE")" || true
    if [[ "$text" != *'versionName='* ]]; then return 1; fi
    RECORD_VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName=//p' <<< "$text" | head -1 | awk '{print $1}')"
    RECORD_VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode=//p' <<< "$text" | head -1 | awk '{print $1}')"
    RECORD_INSTALLER="$(sed -n 's/^[[:space:]]*installerPackageName=//p' <<< "$text" | head -1 | awk '{print $1}')"
    RECORD_INITIATING="$(sed -n 's/^[[:space:]]*initiatingPackageName=//p' <<< "$text" | head -1 | awk '{print $1}')"
    return 0
}

sierra_runs_dir() { sierra_local_directory runs "${SIERRA_RUNS:-}"; }
sierra_state_dir() { sierra_local_directory state/dhu "${SIERRA_STATE:-}"; }

_sierra_state_field() {
    local file="$1" key="$2"
    sed -n 's/.*"'"$key"'"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\).*/\1/p' "$file" | head -1
}

# Process start time (field 22 of /proc/<pid>/stat, in clock ticks), used to
# detect recycled pids.
_sierra_proc_ticks() {
    local stat rest
    stat="$(cat "/proc/$1/stat" 2>/dev/null)" || return 1
    rest="${stat##*) }"
    awk '{print $20}' <<< "$rest"
}

_sierra_is_dhu_process() {
    local pid="$1" exe
    [[ -d "/proc/$pid" ]] || return 1
    exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    [[ "$(basename -- "${exe:-}")" == 'desktop-head-unit' ]]
}

# The DHU session that dhu.sh started, if its processes are still alive.
# Sets MANAGED_PID, MANAGED_LOG, MANAGED_COMMANDS and MANAGED_HOST_PID;
# returns 1 when there is none.
sierra_get_managed_dhu() {
    local state_dir file pid ticks
    state_dir="$(sierra_state_dir)"
    file="$state_dir/state.json"
    [[ -f "$file" ]] || return 1
    pid="$(_sierra_state_field "$file" dhuPid)"
    ticks="$(_sierra_state_field "$file" dhuStartTicks)"
    MANAGED_LOG="$(_sierra_state_field "$file" log)"
    MANAGED_COMMANDS="$(_sierra_state_field "$file" commands)"
    MANAGED_HOST_PID="$(_sierra_state_field "$file" hostPid)"
    [[ -n "$pid" && -n "$ticks" ]] || return 1
    _sierra_is_dhu_process "$pid" || return 1
    # A recycled pid would have a different start time.
    [[ "$(_sierra_proc_ticks "$pid")" == "$ticks" ]] || return 1
    MANAGED_PID="$pid"
    return 0
}

# Any DHU process on this machine that dhu.sh did not start, one pid per line.
sierra_get_unmanaged_dhu() {
    local pid
    for pid in $(pgrep -f '^([[:alnum:]/._-]*/)?desktop-head-unit([[:space:]]|$)' 2>/dev/null || true); do
        if [[ "${MANAGED_PID:-}" != "$pid" ]] && _sierra_is_dhu_process "$pid"; then
            printf '%s\n' "$pid"
        fi
    done
}

# Android Auto's own view of the session, read from its service dump. This
# works with the phone locked and needs no log settings. Sets CAR_CONNECTED,
# CAR_FOREGROUND, CAR_LAUNCHER_SHOWN, CAR_UNKNOWN_SOURCES, CAR_INFO,
# CAR_SIERRA_DENIED_COUNT and CAR_SIERRA_DENIED_LINES.
sierra_get_car_state() {
    local serial="$1" line key value
    CAR_CONNECTED=false
    CAR_FOREGROUND=''
    CAR_LAUNCHER_SHOWN=false
    CAR_UNKNOWN_SOURCES=''
    CAR_INFO=''
    CAR_SIERRA_DENIED_COUNT=0
    CAR_SIERRA_DENIED_LINES=''
    local answers
    answers="$(sierra_invoke_adb -a "$serial" shell dumpsys activity service "$GEARHEAD_PACKAGE")" || true
    # Each Android Auto process dumps its own connection history; the session
    # is up when one of them ends in CONNECTED.
    while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        key="${line%%=*}"
        value="${line#*=}"
        case "$key" in
            connected) CAR_CONNECTED="$value" ;;
            foreground) CAR_FOREGROUND="$value" ;;
            launcherShown) CAR_LAUNCHER_SHOWN=true ;;
            unknownSources) CAR_UNKNOWN_SOURCES="$value" ;;
            carInfo) CAR_INFO="$value" ;;
            deniedCount) CAR_SIERRA_DENIED_COUNT="$value" ;;
            deniedLine)
                CAR_SIERRA_DENIED_LINES+="$value"$'\n'
                ;;
        esac
    done < <(awk -v pkg="$SIERRA_PACKAGE_RE" '
        BEGIN { connected = "false"; fg = ""; launcher = ""; unk = ""; info = ""; denied = 0 }
        /Dumping history of connection state for Car Module \(human readable timestamps\):/ {
            inhist = 1; seen = 0; last = ""; next
        }
        inhist && /^[[:space:]]*$/ {
            if (seen && last == "CONNECTED") connected = "true"
            inhist = 0; next
        }
        inhist {
            if (match($0, /:[A-Za-z0-9_]+[[:space:]]*$/)) {
                last = substr($0, RSTART + 1, RLENGTH - 1)
                sub(/[[:space:]]+$/, "", last)
                seen = 1
            }
            next
        }
        fg == "" && /primaryRegionAppPackageName:[[:space:]]*[A-Za-z0-9._]+/ {
            fg = $0
            sub(/^.*primaryRegionAppPackageName:[[:space:]]*/, "", fg)
            next
        }
        /unknownSourcesEnabled=/ {
            unk = $0
            sub(/^.*unknownSourcesEnabled:/, "", unk)
            sub(/[^A-Za-z0-9_].*$/, "", unk)
            next
        }
        /^[[:space:]]*CarInfo:/ {
            info = $0
            sub(/^[[:space:]]*CarInfo:[[:space:]]*/, "", info)
            sub(/[[:space:]]+$/, "", info)
            next
        }
        /Current app ProjectionApp\(appKey=AppKey\(componentName=ComponentInfo\{[^}]*GhAppLauncherService\}/ { launcher = "true" }
        /Package DENIED/ && $0 ~ pkg { denied++; printf "deniedLine=%s\n", $0 }
        END {
            if (seen && last == "CONNECTED") connected = "true"
            printf "connected=%s\n", connected
            printf "foreground=%s\n", fg
            printf "launcherShown=%s\n", launcher == "" ? "false" : launcher
            printf "unknownSources=%s\n", unk
            printf "carInfo=%s\n", info
            printf "deniedCount=%d\n", denied
        }
    ' <<< "$answers")
}

sierra_phone_locked() {
    sierra_invoke_adb -a "$1" shell dumpsys window | grep -q 'isKeyguardShowing=true'
}

# True while Play Protect is showing a dialog on the phone. An install waits
# behind it until someone answers.
sierra_play_protect_prompt() {
    sierra_invoke_adb -a "$1" shell dumpsys activity activities | grep -q 'PlayProtectDialogsActivity'
}

# adb install with a time limit. A plain `adb install` blocks for ever when
# Play Protect asks a question on the phone, which nobody sees if the phone is
# locked. Prints adb's output text; dies on timeout.
sierra_invoke_adb_install() {
    local serial="$1" timeout_sec="$2"
    shift 2
    local adb
    adb="$(sierra_adb)"
    local out_file err_file started_at pid deadline=$SECONDS told=0
    out_file="$(mktemp)"
    err_file="$(mktemp)"
    started_at="$(sierra_invoke_adb -a "$serial" shell "date '+%m-%d %H:%M:%S.000'" | head -1)"
    "$adb" -s "$serial" install "$@" >"$out_file" 2>"$err_file" &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        sleep 5
        if [[ $told -eq 0 ]] && sierra_play_protect_prompt "$serial"; then
            told=1
            printf '\n'
            sierra_warn_note 'Play Protect is asking a question on the phone and the install is waiting for the answer.'
            if sierra_phone_locked "$serial"; then
                sierra_warn_note 'The phone is locked. Unlock it to see the dialog.'
            fi
            sierra_warn_note "Answer the dialog on the phone. Waiting up to $timeout_sec s in total."
        fi
        if (( SECONDS - deadline >= timeout_sec )); then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            local why
            if [[ $told -eq 1 ]]; then why="Play Protect's dialog on the phone was not answered"; else why='the phone did not finish the install'; fi
            # The session is still waiting on the phone. Cancel it so it cannot
            # install by surprise later. Its id is only in the system log.
            local commits ids answer cancelled=0 after
            commits="$(sierra_invoke_adb -a "$serial" logcat -d -T "$started_at" -s PackageInstallerSession:I)" || true
            ids="$(printf '%s\n' "$commits" | grep -oE 'START COMMIT SESSION: id\{[0-9]+\}' | grep -oE '[0-9]+' | tail -1 || true)"
            if [[ -n "$ids" ]]; then
                answer="$(sierra_invoke_adb -a "$serial" shell pm install-abandon "$ids")" || true
                if grep -q 'Success' <<< "$answer"; then cancelled=1; fi
            fi
            if [[ $cancelled -eq 1 ]]; then
                after='The waiting install was cancelled, so nothing was changed. Answer or dismiss the dialog on the phone, then run this again.'
            else
                after='The install may still be waiting on the phone and can complete once the dialog is answered; run this again afterwards to check.'
            fi
            rm -f "$out_file" "$err_file"
            sierra_die "adb install gave up after $timeout_sec s: $why. $after"
        fi
    done
    local rc=0
    wait "$pid" || rc=$?
    cat "$out_file" "$err_file" | sed -e '/^[[:space:]]*$/d'
    rm -f "$out_file" "$err_file"
    return "$rc"
}
