#!/bin/sh
# Build a pinned receiver inside the dedicated Windows WSL distro.
set -eu
BASE=/opt/jbl-airplay
SPS_REV=0a34fbda83724cc06266aadcf7c88acc8fef4e7d
NQPTP_REV=ab8fb67483411640fd7f1e193889482f2421c37c
[ "$(id -u)" = 0 ] || { echo 'Run as root inside the dedicated distro.'; exit 1; }
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends build-essential git ca-certificates autoconf automake \
    libtool pkg-config libpopt-dev libconfig-dev libpulse-dev pulseaudio-utils avahi-daemon \
    dbus libavahi-client-dev libssl-dev libplist-dev libplist-utils libsodium-dev uuid-dev \
    libgcrypt20-dev xxd libavutil-dev libavcodec-dev libavformat-dev
mkdir -p "$BASE/src" "$BASE/run" "$BASE/etc"
chmod 700 "$BASE/run"

checkout() {
    name=$1
    url=$2
    revision=$3
    if [ ! -d "$BASE/src/$name/.git" ]; then
        git clone --no-checkout "$url" "$BASE/src/$name"
        git -C "$BASE/src/$name" checkout --detach "$revision"
    fi
    if [ -n "$(git -C "$BASE/src/$name" status --porcelain)" ]; then
        echo "Existing edits preserved in $BASE/src/$name."
        exit 1
    fi
    git -C "$BASE/src/$name" fetch origin "$revision"
    git -C "$BASE/src/$name" checkout --detach "$revision"
    [ "$(git -C "$BASE/src/$name" rev-parse HEAD)" = "$revision" ]
}

checkout nqptp https://github.com/mikebrady/nqptp.git "$NQPTP_REV"
checkout shairport-sync https://github.com/mikebrady/shairport-sync.git "$SPS_REV"
cd "$BASE/src/nqptp"
autoreconf -fi
./configure --prefix="$BASE"
make -j2
make install
cd "$BASE/src/shairport-sync"
autoreconf -fi
./configure --prefix="$BASE" --with-airplay-2 --with-pulseaudio --with-avahi --with-ssl=openssl
make plists/get_info_response.h
make -j2
make install
"$BASE/bin/shairport-sync" -V
"$BASE/bin/nqptp" -V
echo 'Build complete. Playback and synchronization still require device testing.'
