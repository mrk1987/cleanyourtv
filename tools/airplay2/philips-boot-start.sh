#!/system/bin/sh
# Start the AirPlay 2 test runtime after boot. Install as /data/adb/service.d/90-airplay2.sh (Magisk,
# late_start). The owner asked for this on 2026-11-10; before that the runtime had no boot hook.
# Remove the file to go back to starting it by hand. Nothing here changes Android services or settings.
PATH=/sbin:/system/bin:/system/xbin
export PATH
CONTROL=/data/local/tmp/airplay2-control.sh
LOG=/data/local/tmp/airplay2-boot.log

(
    echo "$(date '+%m-%d %H:%M:%S') boot hook started."
    [ -f "$CONTROL" ] || { echo 'Control script is missing; nothing started.'; exit 0; }

    # Android up, then an address on Wi-Fi: discovery and the clock service bind to it.
    i=0
    while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 120 ]; do sleep 2; i=$((i + 1)); done
    i=0
    while ! ip -4 addr show wlan0 2>/dev/null | grep -q 'inet ' && [ "$i" -lt 60 ]; do sleep 2; i=$((i + 1)); done
    # The audio server answers a little after boot_completed; the launcher and Watch TV start here too.
    sleep 30

    n=1
    while [ "$n" -le 3 ]; do
        echo "$(date '+%m-%d %H:%M:%S') start attempt $n."
        if sh "$CONTROL" start; then
            echo "$(date '+%m-%d %H:%M:%S') runtime started."
            exit 0
        fi
        # A failed start can leave some services up; clear them before the next try.
        sh "$CONTROL" stop
        sleep 20
        n=$((n + 1))
    done
    echo "$(date '+%m-%d %H:%M:%S') runtime did not start after three attempts."
) >"$LOG" 2>&1 </dev/null &
