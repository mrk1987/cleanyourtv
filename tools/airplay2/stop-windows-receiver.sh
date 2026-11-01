#!/bin/sh
set -eu
BASE=/opt/jbl-airplay
for name in shairport-sync nqptp; do
    if [ ! -f "$BASE/run/$name.pid" ]; then continue; fi
    pid=$(cat "$BASE/run/$name.pid")
    case "$pid" in ''|*[!0-9]*|0|1) echo 'Invalid PID file; preserved.'; exit 1;; esac
    if ! kill -0 "$pid" 2>/dev/null; then continue; fi
    [ "$(readlink "/proc/$pid/exe")" = "$BASE/bin/$name" ] || { echo 'PID belongs to another process; preserved.'; exit 1; }
    kill -TERM "$pid"
    i=0
    while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 12 ]; do sleep 1; i=$((i+1)); done
    if kill -0 "$pid" 2>/dev/null; then
        [ "$(readlink "/proc/$pid/exe")" = "$BASE/bin/$name" ] || exit 1
        kill -KILL "$pid"
    fi
done
echo 'Owned receiver processes stopped; the WSL distro was not shut down.'
