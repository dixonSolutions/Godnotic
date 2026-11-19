#!/bin/sh
# Build the pinned native libdatachannel and its pinned git submodules.
set -eu
commit=4e4f4892dccb2a57fe3a490d0c9d958de4244e74
cache=${GODNOTIC_DATACHANNEL_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/godnotic/libdatachannel-$commit}
src=$cache/src
prefix=$cache/prefix
if [ -f "$prefix/.godnotic-complete" ]; then
    exit 0
fi
mkdir -p "$cache"
if [ ! -d "$src/.git" ]; then
    git clone --no-checkout https://github.com/paullouisageneau/libdatachannel.git "$src"
fi
git -C "$src" checkout --detach "$commit"
test "$(git -C "$src" rev-parse HEAD)" = "$commit"
git -C "$src" submodule update --init --recursive
cmake -S "$src" -B "$cache/build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF -DBUILD_SHARED_DEPS_LIBS=OFF \
    -DNO_MEDIA=ON -DNO_WEBSOCKET=OFF -DNO_EXAMPLES=ON -DNO_TESTS=ON
cmake --build "$cache/build" --parallel "${JOBS:-4}"
cmake --install "$cache/build"
touch "$prefix/.godnotic-complete"
