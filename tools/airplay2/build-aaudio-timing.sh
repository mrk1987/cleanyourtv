#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to an installed Android NDK}"
OUT=${1:?Pass an ignored output directory}
mkdir -p "$OUT"
cc -std=c11 -Wall -Wextra -Werror "$HERE/aaudio_delay_check.c" -o "$OUT/aaudio-delay-check"
"$OUT/aaudio-delay-check"
python3 "$HERE/test_aaudio_timing.py"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang" \
    -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -Wl,-z,defs \
    "$HERE/aaudio_timing.c" -laaudio -ldl -o "$OUT/libairplay-aaudio-timing.so"
if [ -n "${2:-}" ]; then
    "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang" \
        -std=c11 -O2 -Wall -Wextra -Werror -I "$2" \
        "$HERE/clock_status.c" -o "$OUT/airplay-clock-status"
fi
