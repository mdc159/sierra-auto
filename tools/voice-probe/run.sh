#!/usr/bin/env bash
# Run one probe under the studio54 launcher (Git Bash on Windows):
#   ./run.sh p02_stt.py [args]
# The launcher injects the gateway and ElevenLabs keys as environment variables;
# nothing here reads or prints a key.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
py="$(cygpath -w "$here/.venv/Scripts/python.exe")"
script="$(cygpath -w "$here/$1")"; shift
launcher="$(cygpath -w "$HOME/.config/studio54/run.cmd")"
cmd //c "$launcher" "${S54_AGENT:-9530}" -- "$py" "$script" "$@"
