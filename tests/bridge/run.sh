#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
commit=4e4f4892dccb2a57fe3a490d0c9d958de4244e74
cache=${GODNOTIC_DATACHANNEL_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/godnotic/libdatachannel-$commit}
"$root/scripts/build-libdatachannel.sh"
prefix=$cache/prefix
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT HUP INT TERM
${CC:-cc} -std=c11 -Wall -Wextra -Werror -DRTC_STATIC -DRTC_ENABLE_MEDIA=0 \
    -I"$prefix/include" -I"$root/engine/darkplaces" \
    "$root/tests/bridge/loopback_test.c" "$root/engine/darkplaces/touch_bridge.c" \
    "$prefix/lib/libdatachannel.a" "$prefix/lib/libjuice.a" "$prefix/lib/libusrsctp.a" \
    -lstdc++ -lssl -lcrypto -lpthread -lm -o "$out/loopback_test"
"$out/loopback_test"
