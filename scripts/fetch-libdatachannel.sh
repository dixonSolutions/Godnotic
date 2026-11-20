#!/bin/sh
# Fetch the pinned libdatachannel sources and the submodules the relay builds
# (libjuice, usrsctp, plog, json) as shallow single-commit fetches, into <dir>.
# libsrtp is left out: the relay is built with NO_MEDIA. About 3 MB over the
# network instead of a full clone with history. Used by build-libdatachannel.sh
# (Linux) and android-deps.sh (Android).
set -eu
dir=${1:?usage: fetch-libdatachannel.sh <dir>}
commit=4e4f4892dccb2a57fe3a490d0c9d958de4244e74
url=https://github.com/paullouisageneau/libdatachannel.git

fetch_commit() { # <dir> <url> <sha>
    if [ ! -d "$1/.git" ]; then
        mkdir -p "$1"
        git -C "$1" init -q
        git -C "$1" remote add origin "$2"
    fi
    if [ "$(git -C "$1" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$3" ]; then
        git -C "$1" fetch -q --depth 1 origin "$3"
        git -C "$1" checkout -q --detach FETCH_HEAD
    fi
    test "$(git -C "$1" rev-parse HEAD)" = "$3"
}

fetch_commit "$dir" "$url" "$commit"
for m in libjuice usrsctp plog json; do
    sha=$(git -C "$dir" ls-tree HEAD "deps/$m" | awk '{print $3}')
    murl=$(git config -f "$dir/.gitmodules" "submodule.deps/$m.url")
    test -n "$sha" && test -n "$murl"
    fetch_commit "$dir/deps/$m" "$murl" "$sha"
done
