#!/usr/bin/env bash
# Run one probe under the studio54 launcher:
#   ./run.sh p02_stt.py [args]
# The launcher injects the gateway and ElevenLabs keys as environment variables;
# nothing here reads or prints a key.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
launcher="$HOME/.config/studio54/run"
[[ -x "$launcher" ]] || { echo "studio54 launcher not found: $launcher" >&2; exit 1; }
script="${1?usage: ./run.sh pNN_xxx.py [args]}"
shift
exec "$launcher" "${S54_AGENT:-omarchy}" -- "$here/.venv/bin/python" "$here/$script" "$@"
