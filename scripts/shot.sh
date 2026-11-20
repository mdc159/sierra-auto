#!/usr/bin/env bash
# Saves a PNG of the car screen shown by the Desktop Head Unit.
#
# Uses the DHU's own `screenshot` console command when the DHU was started by
# dhu.sh. Otherwise, or with --window, it captures the DHU window. The window
# capture may include windows that overlap it.
#
#   scripts/shot.sh                          timestamped name in the runs folder
#   scripts/shot.sh .local/runs/home.png     explicit path beneath .local
#
# Ported from shot.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

OUT=''
WINDOW=0

usage() {
    cat <<'EOF'
Usage: scripts/shot.sh [OUT] [--window]

  OUT        where to save the PNG (default: timestamped name in the runs
             folder); must be beneath the canonical repository's .local
  --window   capture the DHU window even when the console command is available
  -h, --help show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --window) WINDOW=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; sierra_die "Unknown argument: $1" ;;
        *) [[ -z "$OUT" ]] || sierra_die 'Only one output path can be given.'; OUT="$1"; shift ;;
    esac
done

if [[ -z "$OUT" ]]; then
    OUT="$(sierra_runs_dir)/shot-$(date +%Y%m%d-%H%M%S).png"
fi
OUT="$(sierra_resolve_output_file "$OUT")"
rm -f -- "$OUT"

managed=0
sierra_get_managed_dhu && managed=1
how=''

if [[ $managed -eq 1 && $WINDOW -eq 0 ]]; then
    before="$(stat -c %s -- "$MANAGED_LOG")"
    printf 'screenshot %s\n' "$OUT" >> "$MANAGED_COMMANDS"
    deadline=$((SECONDS + 5))
    while [[ $SECONDS -lt $deadline ]]; do
        sleep 0.2
        if [[ -s "$OUT" ]]; then how='DHU screenshot command'; break; fi
    done
    if [[ -z "$how" ]]; then
        said="$(tail -c +$((before + 1)) -- "$MANAGED_LOG" 2>/dev/null | sed -e '/^[[:space:]]*$/d')"
        sierra_note "The DHU screenshot command wrote no file${said:+: $said}. Capturing the window instead."
    fi
fi

if [[ -z "$how" ]]; then
    command -v xdotool >/dev/null 2>&1 || sierra_die 'xdotool is needed to capture the DHU window. Install xdotool (and ImageMagick), or start the DHU with: dhu.sh start'
    command -v import >/dev/null 2>&1 || sierra_die 'ImageMagick (import) is needed to capture the DHU window.'
    pid="${MANAGED_PID:-}"
    if [[ -z "$pid" ]]; then
        pid="$(sierra_get_unmanaged_dhu | head -1)"
    fi
    window=''
    if [[ -n "$pid" ]]; then
        window="$(xdotool search --pid "$pid" --name 'Desktop Head Unit' 2>/dev/null | head -1 || true)"
    fi
    [[ -n "$window" ]] || window="$(xdotool search --name 'Desktop Head Unit' 2>/dev/null | head -1 || true)"
    [[ -n "$window" ]] || sierra_die 'No Desktop Head Unit window is open.'
    import -window "$window" "$OUT" || sierra_die "Could not capture the DHU window ($window)."
    how="window capture of DHU pid ${pid:-?}"
fi

sierra_note "Saved by $how"
printf '%s\n' "$OUT"
