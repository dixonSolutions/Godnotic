#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The server list's sort is the player's and is remembered: through the engine as
# a native front end's backend and on the QuakeC renderer (tests/mpsort/mpsort_test.py),
# then on the GTK front end (scripts/test-gtk-mpsort.sh, skipped without its runtime).
#
#   BASEDIR=<Godnotic basedir> [ENGINE=build/bin/xonotic] [LIBS=...] scripts/test-mpsort.sh
#
# Exit 77: skipped (no engine or data).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
BASEDIR="${BASEDIR:-${UIB_BASEDIR:-}}"
if [ ! -x "$ENGINE" ] || [ ! -d "${BASEDIR:-/nonexistent}/data" ]; then
	echo "SKIP: set ENGINE and BASEDIR"
	exit 77
fi
UIB_ENGINE="$ENGINE" UIB_BASEDIR="$BASEDIR" UIB_LIBDIR="${LIBS:-}" python3 "$ROOT/tests/mpsort/mpsort_test.py"
rc=$?
ENGINE="$ENGINE" bash "$ROOT/scripts/test-gtk-mpsort.sh"
g=$?
[ $g = 77 ] && g=0
[ $rc = 0 ] && [ $g = 0 ]
