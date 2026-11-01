#!/bin/sh
# Debian 13 x86_64 build host. Only generated, redistributable open-source files.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE=${1:-/tmp/airplay-philips-build}
SPS_REV=0a34fbda83724cc06266aadcf7c88acc8fef4e7d
NQPTP_REV=ab8fb67483411640fd7f1e193889482f2421c37c
[ "$(id -u)" = 0 ] || { echo 'Build dependencies require root on the isolated build host.'; exit 1; }
export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture armhf
apt-get update
apt-get install -y --no-install-recommends build-essential gcc-arm-linux-gnueabihf \
    g++-arm-linux-gnueabihf git ca-certificates autoconf automake libtool pkg-config \
    libpopt-dev:armhf libconfig-dev:armhf libssl-dev:armhf libpulse-dev:armhf \
    libavahi-client-dev:armhf libplist-dev:armhf libsodium-dev:armhf uuid-dev:armhf \
    libgcrypt20-dev:armhf libavutil-dev:armhf libavcodec-dev:armhf libavformat-dev:armhf \
    libplist-utils xxd python3
mkdir -p "$BASE"
checkout() {
    name=$1
    revision=$2
    if [ ! -d "$BASE/$name/.git" ]; then
        git clone --no-checkout "https://github.com/mikebrady/$name.git" "$BASE/$name"
        git -C "$BASE/$name" checkout --detach "$revision"
    fi
    [ -z "$(git -C "$BASE/$name" status --porcelain)" ] || { echo 'Existing source edits preserved.'; exit 1; }
    git -C "$BASE/$name" fetch origin "$revision"
    git -C "$BASE/$name" checkout --detach "$revision"
    [ "$(git -C "$BASE/$name" rev-parse HEAD)" = "$revision" ]
}
checkout nqptp "$NQPTP_REV"
checkout shairport-sync "$SPS_REV"
cd "$BASE/nqptp"
autoreconf -fi
./configure --host=arm-linux-gnueabihf --prefix=/opt/airplay
make -j2
cd "$BASE/shairport-sync"
autoreconf -fi
PKG_CONFIG_LIBDIR=/usr/lib/arm-linux-gnueabihf/pkgconfig:/usr/share/pkgconfig \
    CXX=arm-linux-gnueabihf-g++ ./configure --host=arm-linux-gnueabihf --prefix=/opt/airplay \
    --with-airplay-2 --with-pulseaudio --with-avahi --with-ssl=openssl
make plists/get_info_response.h
make -j2
mkdir -p "$BASE/daemon-debs"
cd "$BASE/daemon-debs"
apt-get download avahi-daemon:armhf libavahi-core7:armhf libdaemon0:armhf dbus-daemon:armhf \
    libcap-ng0:armhf libapparmor1:armhf libaudit1:armhf
python3 "$HERE/package_arm_runtime.py" "$BASE" "$BASE/receiver-arm-runtime.tar"
echo "ARM runtime assembled at $BASE/receiver-arm-runtime.tar; device acceptance testing is required."
