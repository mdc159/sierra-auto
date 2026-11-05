#!/bin/sh
# usage: ilog.sh "message"  -> appends "HH:MM PST  message" to the integrator log
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
echo "$(TZ=PST8 date '+%H:%M') PST  $*" >> "$INTEGRATE_LOG"
