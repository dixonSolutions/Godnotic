#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Multiplayer opens in the next frame with rows, and its list stays put while a
# refresh streams in (tests/mpfast/mpfast_test.py: the engine as a native front
# end's backend, offscreen, against fake servers on loopback).
#
#   BASEDIR=<Godnotic basedir> [ENGINE=build/bin/xonotic] [LIBS=...] [BUDGET_MS=30] scripts/test-mpfast.sh
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
UIB_ENGINE="$ENGINE" UIB_BASEDIR="$BASEDIR" UIB_LIBDIR="${LIBS:-}" exec python3 "$ROOT/tests/mpfast/mpfast_test.py"
