#!/system/bin/sh
# Recreate only our AAudio sink after Android returns from standby.
# No wake locks, boot hooks, system clock changes or stock service restarts.
set -eu
ulimit -c 0
ROOT=${AIRPLAY_RUNTIME_ROOT:-/data/local/tmp/airplay2-root}
PULSE=${AIRPLAY_PULSE_ROOT:-/data/local/tmp/airplay-pulse}
BB=${AIRPLAY_BUSYBOX:-/data/adb/magisk/busybox}
DUMPSYS=${AIRPLAY_DUMPSYS:-/system/bin/dumpsys}
CONTROL=${AIRPLAY_CONTROL:-/data/local/tmp/airplay2-control.sh}
[ "$(id -u)" = 0 ] || { echo 'Wake recovery requires root.'; exit 1; }
PULSE_LIBRARIES="$PULSE/lib:$PULSE/lib/pulseaudio"
# Android's dumpsys must use platform libraries, not Termux's C++ runtime.
unset LD_LIBRARY_PATH
export PULSE_SERVER="unix:$ROOT/run/pulse/native"
export PULSE_COOKIE="$ROOT/run/pulse/cookie"
trap 'exit 0' TERM INT
log() { echo "$(date '+%m-%d %H:%M:%S') $*"; }

last_wake=
was_awake=1
pending=
attempts=0
while :; do
    if ! snapshot=$("$BB" timeout 3 "$DUMPSYS" power 2>/dev/null); then
        sleep 5
        continue
    fi
    state=$(printf '%s\n' "$snapshot" | "$BB" sed -n 's/^[[:space:]]*mWakefulness=\([A-Za-z]*\).*/\1/p')
    wake=$(printf '%s\n' "$snapshot" | "$BB" sed -n 's/^[[:space:]]*mLastWakeTime=\([0-9][0-9]*\).*/\1/p')
    case "$wake" in ''|*[!0-9]*) sleep 5; continue;; esac
    case "$state" in Awake|Asleep|Dozing|Dreaming) :;; *) sleep 5; continue;; esac
    if [ -z "$last_wake" ]; then
        last_wake=$wake
        log "Wake watcher baseline: state=$state wake=$wake."
    fi
    if [ "$state" = Asleep ] || [ "$state" = Dozing ]; then
        was_awake=0
        pending=
        attempts=0
    elif [ "$state" = Awake ] && { [ "$wake" != "$last_wake" ] || [ "$was_awake" = 0 ]; }; then
        if [ "$pending" != "$wake" ]; then
            # Require a second awake snapshot so Android audio can settle.
            pending=$wake
            attempts=0
        else
            attempts=$((attempts + 1))
            log "Wake $wake: recreating the test AAudio stream (attempt $attempts)."
            # SIGKILL on timeout: pactl answers SIGTERM and SIGINT with exit status 0, which would read as
            # a recreated stream when PulseAudio is frozen and never answered.
            if "$BB" timeout -s KILL 8 env LD_LIBRARY_PATH="$PULSE_LIBRARIES" "$PULSE/bin/pactl" suspend-sink android_airplay 1 &&
               sleep 1 &&
               "$BB" timeout -s KILL 8 env LD_LIBRARY_PATH="$PULSE_LIBRARIES" "$PULSE/bin/pactl" suspend-sink android_airplay 0; then
                log "Wake $wake: test AAudio stream recreated."
                last_wake=$wake
                was_awake=1
                pending=
                attempts=0
            elif [ "$attempts" -ge 3 ]; then
                last_wake=$wake
                was_awake=1
                pending=
                # PulseAudio can deadlock inside the sink while its stream is recreated: the process stays
                # alive but its socket refuses every client (seen 2026-11-05, silent for five days). Only a
                # restart of the runtime clears that. Its own session, because the restart stops this watcher.
                if [ -f "$CONTROL" ]; then
                    log "Wake $wake: recovery failed after three attempts; restarting the test runtime."
                    setsid sh "$CONTROL" recover </dev/null >>"$ROOT/run/recover.log" 2>&1 &
                else
                    log "Wake $wake: recovery failed after three attempts; inspect pulse.log."
                fi
            fi
        fi
    fi
    sleep 5
done
