#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# Supply a Xonotic data directory containing pk3s and compiled progs.dat, and a
# Playwright installation resolvable by Node (or set NODE_PATH).
: "${XONOTIC_BASEDIR:?Set XONOTIC_BASEDIR to a base directory containing data/ and compiled game assets}"
: "${BRIDGE_DEDICATED:=$root/engine/darkplaces/darkplaces-dedicated}"
export XONOTIC_BASEDIR BRIDGE_DEDICATED
exec node "$root/tests/bridge/e2e.js"
