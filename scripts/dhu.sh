#!/usr/bin/env bash
# Starts and drives the Android Auto Desktop Head Unit (DHU) against the phone.
#
#   dhu.sh start            forward the port, check the phone, start the DHU with
#                           its console kept open, wait until Sierra is offered
#   dhu.sh send <command>   send one DHU console command, print what it answered
#   dhu.sh tap <x> <y>      tap a point on the car screen
#   dhu.sh status           say what is running and what the car screen shows
#   dhu.sh stop             quit the DHU that this script started
#
# The DHU exits at once if its stdin is closed, so `start` runs it under a small
# background host that holds its stdin open and forwards commands. If a DHU that
# this script did not start is already running, the script leaves it alone and
# works with it through its window instead: `tap` and shot.sh still work,
# `send` does not.
#
# DHU console commands that matter (from the DHU's own `help`):
#   tap <x> <y>                 touch; (0, 0) is the top-left corner
#   screenshot <file.png>       save the car screen
#   mic play <file.wav>         play a WAV file as the car microphone
#   mic repeat                  play the last WAV again
#   mic begin                   press the voice button
#   keycode back | home         hardware keys
#   day | night                 day/night sensor
#   restrict all | none         driving restrictions
#   sleep <seconds>             pause, for scripted sequences
#   help [command]              list commands, or explain one
#
# A pass on the DHU does not prove the app shows in the truck: Android Auto skips
# its install-source checks for the DHU. See docs/TESTING.md.
#
# Ported from dhu.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

ACTION=start
REST=()
SERIAL=''
TIMEOUT_SEC=60
CONFIG=''
PORT=5277
NO_PHONE=0

usage() {
    cat <<'EOF'
Usage: dhu.sh [start|send|tap|status|stop] [arguments] [options]

  start             forward tcp:5277, start the DHU with a live console and
                    wait for Sierra to be offered
  send <command...> send one DHU console command and print its answer
  tap <x> <y>       tap a point on the car screen
  status            say what is running and what the car screen shows
  stop              quit the DHU that this script started

  --serial SERIAL   adb serial of the phone (default: the only device)
  --timeout SEC     seconds to wait for the session and Sierra being offered
  --config FILE     DHU .ini file (default: 800x480 touch)
  --port PORT       local tcp port for the DHU (default 5277)
  --no-phone        console test with no phone at all
  -h, --help        show this help
EOF
}

if [[ $# -gt 0 ]]; then
    case "$1" in
        start|send|tap|status|stop) ACTION="$1"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; sierra_die "Unknown action: $1" ;;
    esac
fi
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="${2:?}"; shift 2 ;;
        --timeout) TIMEOUT_SEC="${2:?}"; shift 2 ;;
        --config) CONFIG="${2:?}"; shift 2 ;;
        --port) PORT="${2:?}"; shift 2 ;;
        --no-phone) NO_PHONE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; while [[ $# -gt 0 ]]; do REST+=("$1"); shift; done ;;
        *) REST+=("$1"); shift ;;
    esac
done

if [[ $NO_PHONE -eq 1 ]]; then
    SERIAL=''
else
    SERIAL="$(sierra_phone_serial "$SERIAL")"
fi

send_dhu_command() {
    # send_dhu_command STATE_COMMANDS STATE_LOG COMMAND [WAIT_MS]
    local commands="$1" log="$2" command="$3" wait_ms="${4:-800}"
    local before answer
    before="$(stat -c %s -- "$log")"
    printf '%s\n' "$command" >> "$commands"
    sleep "$((wait_ms / 1000)).$(printf '%03d' $((wait_ms % 1000)))"
    answer="$(tail -c +$((before + 1)) -- "$log" 2>/dev/null | sed -e '/^[[:space:]]*$/d')"
    [[ -z "$answer" ]] || printf '%s\n' "$answer"
}

head_unit_server_listening() {
    # ss works for the shell user on Android 16; netstat is the fallback.
    sierra_invoke_adb -a "$1" shell "ss -ltn 2>/dev/null; netstat -ltn 2>/dev/null" \
        | grep -E "[:.]${DHU_PORT}[[:space:]]" | grep -q LISTEN
}

show_head_unit_server_help() {
    local serial="$1" locked=0
    sierra_phone_locked "$serial" || locked=1
    printf '\n'
    sierra_warn_note "The phone's head unit server is not listening on port $DHU_PORT."
    sierra_warn_note 'On the phone:'
    if [[ $locked -eq 0 ]]; then sierra_warn_note '  0. Unlock the phone.'; fi
    sierra_warn_note '  1. Open Settings > Connected devices > Android Auto.'
    sierra_warn_note '     (This script has just asked the phone to open that screen.)'
    sierra_warn_note '  2. Tap the three-dot menu at the top right.'
    sierra_warn_note "  3. Tap 'Start head unit server'. If the item says 'Stop head unit server',"
    sierra_warn_note "     tap it, open the menu again and tap 'Start head unit server'."
    sierra_warn_note "     If the menu has no such item, scroll to the bottom of the settings,"
    sierra_warn_note "     tap 'Version' ten times to turn on developer mode, and try again."
    sierra_warn_note 'Then run this script again.'
    sierra_invoke_adb -a "$serial" shell am start -a "$GEARHEAD_PACKAGE.SETTINGS" >/dev/null || true
}

wait_sierra_offered() {
    local serial="$1" timeout_sec="$2" deadline
    deadline=$((SECONDS + timeout_sec))
    sierra_get_car_state "$serial"
    while [[ "$CAR_CONNECTED" != true && $SECONDS -lt $deadline ]]; do
        sleep 2
        sierra_get_car_state "$serial"
    done
    if [[ "$CAR_CONNECTED" != true ]]; then
        sierra_die "Android Auto on the phone did not reach CONNECTED within $timeout_sec s. If the DHU window says 'Waiting for phone', the head unit server is stuck: on the phone, Android Auto settings > three-dot menu > Stop head unit server, then Start head unit server, and run this again."
    fi
    sierra_note "Session connected. Head unit: $CAR_INFO"

    if ! sierra_get_install_record "$serial"; then
        sierra_die 'Sierra is not installed on the phone. Run scripts/install-phone.sh first.'
    fi
    local services
    services="$(sierra_invoke_adb -a "$serial" shell cmd package query-services --brief -a androidx.car.app.CarAppService)" || true
    if ! grep -qF "$SIERRA_PACKAGE/" <<< "$services"; then
        sierra_die 'Sierra is installed but exposes no androidx.car.app.CarAppService, so Android Auto has nothing to offer.'
    fi
    if [[ "$CAR_SIERRA_DENIED_COUNT" -gt 0 ]]; then
        printf '\033[31m%s\033[0m\n' "$CAR_SIERRA_DENIED_LINES"
        sierra_die 'Android Auto denied Sierra (lines above are from its validator).'
    fi
    sierra_note 'Sierra is offered: car app service present, no validator denial.'
    if [[ "$CAR_INFO" == *'Desktop Head Unit'* ]]; then
        sierra_note 'Note: Android Auto skips install-source checks on the DHU. This does not show the truck will offer it.'
    fi
}

# Window access to a DHU this script did not start (no console). Best effort on
# Linux: xdotool for taps, ImageMagick import for capture.
dhu_window_id() {
    command -v xdotool >/dev/null 2>&1 || sierra_die 'xdotool is needed to reach the DHU window. Install xdotool, or stop that DHU and run: dhu.sh start'
    local id
    id="$(xdotool search --name 'Desktop Head Unit' 2>/dev/null | head -1 || true)"
    [[ -n "$id" ]] || sierra_die 'No Desktop Head Unit window is open.'
    printf '%s\n' "$id"
}

case "$ACTION" in
    start)
        managed=0
        sierra_get_managed_dhu && managed=1
        unmanaged=''
        if [[ $NO_PHONE -eq 0 ]]; then unmanaged="$(sierra_get_unmanaged_dhu)"; fi
        if [[ $managed -eq 1 ]]; then
            sierra_step "A DHU started by this script is already running (pid $MANAGED_PID)"
        elif [[ -n "$unmanaged" ]]; then
            sierra_step "A DHU that this script did not start is already running (pid $(head -1 <<< "$unmanaged"))"
            sierra_note 'Leaving it alone and using it through its window: tap and shot.sh work, send does not.'
            sierra_note 'For a console session, close that DHU window yourself and run this again.'
        else
            dhu="$(sierra_dhu)"
            if [[ $NO_PHONE -eq 0 ]]; then
                sierra_step "Forwarding tcp:$DHU_PORT to the phone"
                sierra_invoke_adb "$SERIAL" forward "tcp:$PORT" "tcp:$DHU_PORT" >/dev/null
                if ! head_unit_server_listening "$SERIAL"; then
                    show_head_unit_server_help "$SERIAL"
                    exit 2
                fi
                sierra_note "The phone is listening on $DHU_PORT."
            fi
            sierra_step 'Starting the Desktop Head Unit'
            state_dir="$(sierra_state_dir)"
            rm -f "$state_dir/state.json" "$state_dir/commands.txt" "$state_dir/dhu.log"
            host_args=("$SIERRA_SCRIPT_DIR/_dhu-host.sh" --dhu "$dhu" --state-dir "$state_dir" --port "$PORT")
            if [[ -n "$CONFIG" ]]; then
                host_args+=(--config "$(realpath -- "$CONFIG")")
            fi
            nohup setsid bash "${host_args[@]}" >/dev/null 2>&1 </dev/null &
            managed=0
            deadline=$((SECONDS + 15))
            while [[ $managed -eq 0 && $SECONDS -lt $deadline ]]; do
                sleep 0.3
                sierra_get_managed_dhu && managed=1
            done
            if [[ $managed -eq 0 ]]; then
                log="$state_dir/dhu.log"
                tail='(no output)'
                [[ -f "$log" ]] && tail="$(tail -n 12 -- "$log")"
                sierra_die "The DHU did not stay up. Its last output:
$tail"
            fi
            sierra_note "DHU pid $MANAGED_PID; output in $MANAGED_LOG"
        fi
        if [[ $NO_PHONE -eq 0 ]]; then
            sierra_step 'Waiting for the session and for Sierra to be offered'
            wait_sierra_offered "$SERIAL" "$TIMEOUT_SEC"
        fi
        ;;

    send)
        if [[ ${#REST[@]} -eq 0 ]]; then
            sierra_die 'Give a DHU console command, for example: dhu.sh send help'
        fi
        managed=0
        sierra_get_managed_dhu && managed=1
        if [[ $managed -eq 0 ]]; then
            if [[ -n "$(sierra_get_unmanaged_dhu)" ]]; then
                sierra_die "The running DHU was not started by this script, so it has no console to send to. Use 'dhu.sh tap' and shot.sh, or close that DHU and run 'dhu.sh start'."
            fi
            sierra_die "No DHU is running. Run 'dhu.sh start' first."
        fi
        command="${REST[*]}"
        send_dhu_command "$MANAGED_COMMANDS" "$MANAGED_LOG" "$command"
        ;;

    tap)
        if [[ ${#REST[@]} -ne 2 ]]; then
            sierra_die 'Usage: dhu.sh tap <x> <y>'
        fi
        x="${REST[0]}" y="${REST[1]}"
        managed=0
        sierra_get_managed_dhu && managed=1
        if [[ $managed -eq 1 ]]; then
            send_dhu_command "$MANAGED_COMMANDS" "$MANAGED_LOG" "tap $x $y" 300
        else
            window="$(dhu_window_id)"
            xdotool mousemove --window "$window" "$x" "$y" click 1
            sierra_note "Tapped $x,$y through the window of DHU pid $(xdotool getwindowpid "$window" 2>/dev/null || echo '?')."
        fi
        ;;

    status)
        managed=0
        sierra_get_managed_dhu && managed=1
        unmanaged="$(sierra_get_unmanaged_dhu)"
        if [[ $managed -eq 1 ]]; then
            printf 'DHU            : running, started by this script (pid %s), console available\n' "$MANAGED_PID"
        elif [[ -n "$unmanaged" ]]; then
            printf 'DHU            : running, not started by this script (pid %s), window access only\n' "$(head -1 <<< "$unmanaged")"
        else
            printf 'DHU            : not running\n'
        fi
        if [[ $NO_PHONE -eq 0 ]]; then
            if sierra_get_install_record "$SERIAL"; then
                printf 'Sierra         : %s (code %s), installer %s\n' "$RECORD_VERSION_NAME" "$RECORD_VERSION_CODE" "$RECORD_INSTALLER"
            else
                printf 'Sierra         : not installed\n'
            fi
            if head_unit_server_listening "$SERIAL"; then
                printf 'Head unit server: listening on %s\n' "$DHU_PORT"
            else
                printf 'Head unit server: not listening\n'
            fi
            if sierra_phone_locked "$SERIAL"; then printf 'Phone          : locked\n'; else printf 'Phone          : unlocked\n'; fi
            sierra_get_car_state "$SERIAL"
            if [[ "$CAR_CONNECTED" == true ]]; then printf 'Session        : connected\n'; else printf 'Session        : not connected\n'; fi
            on_screen='unknown'
            if [[ "$CAR_LAUNCHER_SHOWN" == true ]]; then on_screen='app launcher'
            elif [[ -n "$CAR_FOREGROUND" ]]; then on_screen="$CAR_FOREGROUND"; fi
            printf 'On screen      : %s\n' "$on_screen"
            printf 'Unknown sources: %s\n' "$CAR_UNKNOWN_SOURCES"
        fi
        ;;

    stop)
        managed=0
        sierra_get_managed_dhu && managed=1
        if [[ $managed -eq 0 ]]; then
            if [[ -n "$(sierra_get_unmanaged_dhu)" ]]; then
                sierra_note 'A DHU is running but this script did not start it, so it is left alone.'
            else
                sierra_note 'No DHU is running.'
            fi
            exit 0
        fi
        sierra_step "Quitting the DHU (pid $MANAGED_PID)"
        printf 'quit\n' >> "$MANAGED_COMMANDS"
        deadline=$((SECONDS + 5))
        while kill -0 "$MANAGED_PID" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
            sleep 0.1
        done
        if kill -0 "$MANAGED_PID" 2>/dev/null; then
            sierra_note 'It did not quit by itself; ending the process.'
            kill "$MANAGED_PID" 2>/dev/null || true
            wait "$MANAGED_PID" 2>/dev/null || true
        fi
        sleep 0.5
        if kill -0 "$MANAGED_HOST_PID" 2>/dev/null; then
            deadline=$((SECONDS + 3))
            while kill -0 "$MANAGED_HOST_PID" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
                sleep 0.1
            done
        fi
        sierra_note 'Stopped.'
        ;;
esac
