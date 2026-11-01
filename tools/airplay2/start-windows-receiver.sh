#!/bin/sh
# Run inside the dedicated WSL distro after setup-windows.ps1.
set -eu
BASE=/opt/jbl-airplay
[ "$(id -u)" = 0 ] || { echo 'Run as root inside the dedicated distro.'; exit 1; }
[ -S /mnt/wslg/PulseServer ] || { echo 'WSLg audio socket is unavailable.'; exit 1; }
export PULSE_SERVER=unix:/mnt/wslg/PulseServer
pactl info >/dev/null
service dbus start
service avahi-daemon start
mkdir -p "$BASE/run"
chmod 700 "$BASE/run"

launch() {
    name=$1
    shift
    if [ -f "$BASE/run/$name.pid" ]; then
        pid=$(cat "$BASE/run/$name.pid")
        case "$pid" in ''|*[!0-9]*|0|1) echo 'Invalid PID file.'; exit 1;; esac
        if kill -0 "$pid" 2>/dev/null; then
            [ "$(readlink "/proc/$pid/exe")" = "$BASE/bin/$name" ] || { echo 'PID belongs to another process.'; exit 1; }
            return
        fi
    fi
    nohup "$@" </dev/null >"$BASE/run/$name.log" 2>&1 &
    echo "$!" >"$BASE/run/$name.pid"
    sleep 1
    kill -0 "$(cat "$BASE/run/$name.pid")"
}

launch nqptp "$BASE/bin/nqptp"
launch shairport-sync "$BASE/bin/shairport-sync" -c "$BASE/etc/shairport-sync.conf"
echo 'Receiver started. Test iPhone discovery, sound and multiroom synchronization.'
