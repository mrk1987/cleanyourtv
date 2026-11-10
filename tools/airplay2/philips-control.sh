#!/system/bin/sh
# Temporary, owner-operated test receiver. No boot hooks or Android service changes.
set -eu
ulimit -c 0
ROOT=/data/local/tmp/airplay2-root
PULSE=/data/local/tmp/airplay-pulse
SELF=/data/local/tmp/airplay2-control.sh
WATCHER=/data/local/tmp/airplay2-wake-watch.sh
BB=/data/adb/magisk/busybox
[ -x "$BB" ] || { echo "Magisk BusyBox is required."; exit 1; }
[ "$(id -u)" = 0 ] || { echo 'Run through su -c.'; exit 1; }
umask 077

alive() {
    [ -f "$ROOT/run/$1.pid" ] || return 1
    pid=$(cat "$ROOT/run/$1.pid")
    case "$pid" in ''|*[!0-9]*|0|1) return 1;; esac
    target=$(readlink "/proc/$pid/exe" 2>/dev/null) || return 1
    case "$1" in
        watcher)
            case "$target" in /system/bin/sh|/system/bin/mksh) :;; *) return 1;; esac
            "$BB" tr '\000' '\n' <"/proc/$pid/cmdline" | "$BB" grep -Fxq "$WATCHER" || return 1
            kill -0 "$pid" 2>/dev/null
            return
            ;;
        pulse) expected=$PULSE/bin/pulseaudio;;
        dbus) expected=$ROOT/usr/bin/dbus-daemon;;
        avahi) expected=$ROOT/usr/sbin/avahi-daemon;;
        nqptp) expected=$ROOT/usr/bin/nqptp;;
        receiver) expected=$ROOT/usr/bin/shairport-sync;;
        *) return 1;;
    esac
    [ "$target" = "$expected" ] && kill -0 "$pid" 2>/dev/null
}

launch() {
    name=$1
    shift
    if alive "$name"; then return; fi
    setsid "$@" </dev/null >"$ROOT/run/$name.log" 2>&1 &
    echo "$!" >"$ROOT/run/$name.pid"
    sleep 1
    alive "$name" || { echo "$name did not start; inspect $ROOT/run/$name.log"; return 1; }
}

case "${1:-status}" in
start-watcher)
    alive pulse || { echo 'Start the audio runtime first.'; exit 1; }
    [ -f "$WATCHER" ] || { echo 'Wake watcher is not installed.'; exit 1; }
    launch watcher /system/bin/sh "$WATCHER"
    echo 'Wake recovery watcher running.'
    ;;
restart-receiver)
    # Preserve the Android sink and timing/discovery services during calibration.
    alive pulse && alive dbus && alive avahi && alive nqptp || {
        echo 'Start the complete test runtime first.'; exit 1;
    }
    if alive receiver; then
        pid=$(cat "$ROOT/run/receiver.pid")
        kill -TERM "$pid"
        i=0
        while alive receiver && [ "$i" -lt 12 ]; do sleep 1; i=$((i+1)); done
        if alive receiver; then kill -KILL "$pid"; sleep 1; fi
        alive receiver && { echo 'Receiver could not be stopped.'; exit 1; }
    fi
    launch receiver chroot "$ROOT" /usr/bin/airplay-launch /usr/bin/shairport-sync -c /etc/airplay/shairport-sync.conf
    echo 'Test receiver restarted; reconnect the phone if necessary.'
    ;;
start)
    chmod 700 "$ROOT"
    mkdir -p "$ROOT/run/pulse" "$ROOT/run/dbus" "$ROOT/run/avahi-daemon" "$ROOT/dev/shm" "$ROOT/proc"
    chmod 700 "$ROOT/run" "$ROOT/run/pulse"
    if [ ! -s "$ROOT/run/pulse/cookie" ]; then
        dd if=/dev/urandom of="$ROOT/run/pulse/cookie" bs=256 count=1 2>/dev/null
        chmod 600 "$ROOT/run/pulse/cookie"
    fi
    if [ ! -s "$ROOT/etc/machine-id" ]; then
        tr -d '-' </proc/sys/kernel/random/uuid >"$ROOT/etc/machine-id"
        cp "$ROOT/etc/machine-id" "$ROOT/var/lib/dbus/machine-id"
    fi
    for node in null zero random urandom; do
        if ! grep -q " $ROOT/dev/$node " /proc/mounts; then
            touch "$ROOT/dev/$node"
            "$BB" mount -o bind "/dev/$node" "$ROOT/dev/$node"
        fi
    done
    if ! grep -q " $ROOT/proc " /proc/mounts; then "$BB" mount -o bind /proc "$ROOT/proc"; fi
    cat >"$ROOT/etc/airplay/android.pa" <<EOF
.fail
load-module module-native-protocol-unix socket=$ROOT/run/pulse/native auth-anonymous=0 auth-cookie=$ROOT/run/pulse/cookie
load-module module-aaudio-sink sink_name=android_airplay rate=48000 latency=50 pm=0
set-default-sink android_airplay
EOF
    AUDIO_PRELOAD=
    AUDIO_MODE=observe
    if [ -f "$ROOT/etc/airplay/aaudio-timing-mode" ]; then
        AUDIO_MODE=$(cat "$ROOT/etc/airplay/aaudio-timing-mode")
    fi
    case "$AUDIO_MODE" in observe|feedback|disabled) :;; *) echo 'Invalid AAudio timing mode.'; exit 1;; esac
    if [ "$AUDIO_MODE" = feedback ] && [ ! -f "$PULSE/lib/libairplay-aaudio-timing.so" ]; then
        echo 'AAudio feedback was requested but its bridge is missing.'; exit 1
    fi
    if [ -f "$PULSE/lib/libairplay-aaudio-timing.so" ] && [ "$AUDIO_MODE" != disabled ]; then
        env LD_LIBRARY_PATH="$PULSE/lib:$PULSE/lib/pulseaudio" "$PULSE/bin/pulseaudio" --version | grep -Eq '^pulseaudio 17[.]0(-dirty)?$' || {
            echo 'AAudio timing bridge requires the pinned PulseAudio 17.0 runtime.'; exit 1;
        }
        AUDIO_PRELOAD=$PULSE/lib/libairplay-aaudio-timing.so
    fi
    launch pulse env LD_LIBRARY_PATH="$PULSE/lib:$PULSE/lib/pulseaudio" LD_PRELOAD="$AUDIO_PRELOAD" \
        AIRPLAY_AAUDIO_TIMING_MODE="$AUDIO_MODE" HOME="$ROOT/root" TMPDIR="$ROOT/tmp" \
        PULSE_RUNTIME_PATH="$ROOT/run/pulse" PULSE_STATE_PATH="$ROOT/run/pulse" \
        "$PULSE/bin/pulseaudio" -n -F "$ROOT/etc/airplay/android.pa" \
        -p "$PULSE/lib/pulseaudio/modules" --daemonize=no --use-pid-file=no \
        --exit-idle-time=-1 --disable-shm=yes --log-target=stderr --log-level=info
    launch dbus chroot "$ROOT" /usr/bin/airplay-launch /usr/bin/dbus-daemon --nofork --config-file=/etc/airplay/dbus.conf
    launch avahi chroot "$ROOT" /usr/bin/airplay-launch /usr/sbin/avahi-daemon --no-drop-root --no-chroot --no-rlimits \
        --debug -f /etc/avahi/avahi-daemon.conf
    i=0
    while ! grep -q 'Server startup complete' "$ROOT/run/avahi.log"; do
        alive avahi || { echo 'Discovery service exited; inspect avahi.log.'; exit 1; }
        [ "$i" -lt 12 ] || { echo 'Discovery service did not become ready.'; exit 1; }
        sleep 1
        i=$((i+1))
    done
    launch nqptp chroot "$ROOT" /usr/bin/airplay-launch /usr/bin/nqptp -v
    launch receiver chroot "$ROOT" /usr/bin/airplay-launch /usr/bin/shairport-sync -c /etc/airplay/shairport-sync.conf
    if ! printf 'OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n' | "$BB" nc -w 2 127.0.0.1 7100 | "$BB" head -n 1 | grep -q '200 OK'; then
        echo 'Receiver did not answer the RTSP readiness probe.'
        exit 1
    fi
    if [ -f "$WATCHER" ]; then launch watcher /system/bin/sh "$WATCHER"; fi
    sh "$SELF" status
    ;;
stop)
    for name in watcher receiver nqptp avahi dbus pulse; do
        if alive "$name"; then
            pid=$(cat "$ROOT/run/$name.pid")
            kill -TERM "$pid"
            i=0
            while alive "$name" && [ "$i" -lt 12 ]; do sleep 1; i=$((i+1)); done
            if alive "$name"; then
                echo "$name did not exit after SIGTERM; stopping the verified test process."
                kill -KILL "$pid"
                sleep 1
                if alive "$name"; then echo "$name could not be stopped"; exit 1; fi
            fi
        fi
    done
    # Mounts are confined to this owned runtime; leave files for inspection/retry.
    for path in proc dev/urandom dev/random dev/zero dev/null; do
        if grep -q " $ROOT/$path " /proc/mounts; then "$BB" umount "$ROOT/$path"; fi
    done
    echo 'Test receiver stopped.'
    ;;
recover)
    # For a PulseAudio that is alive but answers no client (deadlocked in the sink). Keeps the end of the
    # logs that a start would truncate, then restarts the whole runtime. Calibration files are not touched.
    echo "$(date '+%m-%d %H:%M:%S') recovering the test runtime."
    for name in pulse receiver; do
        if [ -f "$ROOT/run/$name.log" ]; then
            "$BB" tail -c 262144 "$ROOT/run/$name.log" >"$ROOT/run/$name-before-recovery.log"
        fi
    done
    sh "$SELF" stop
    sh "$SELF" start
    ;;
status)
    for name in pulse dbus avahi nqptp receiver watcher; do
        if ! alive "$name"; then
            echo "$name: stopped"
        elif [ "$name" = pulse ] && ! "$BB" timeout -s KILL 5 env LD_LIBRARY_PATH="$PULSE/lib:$PULSE/lib/pulseaudio" \
                PULSE_SERVER="unix:$ROOT/run/pulse/native" PULSE_COOKIE="$ROOT/run/pulse/cookie" \
                "$PULSE/bin/pactl" info >/dev/null 2>&1; then
            echo "pulse: running but not answering (hung); use: recover"
        else
            echo "$name: running"
        fi
    done
    ;;
*) echo "Usage: $SELF start|status|stop|recover|restart-receiver|start-watcher"; exit 2;;
esac
