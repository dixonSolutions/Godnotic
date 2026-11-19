#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Controls follow the input devices that are present, live (touch only, keyboard only, mouse only, both):
# the pure decision, both HUDs' policy, a fake device list rescanned in one engine, and a live match whose
# HUD rectangles change with the list. The live parts need
#   BASEDIR=<Godnotic basedir>  STOCKBASE=<basedir with upstream Xonotic data only>  (+ bwrap)
# and exit 77 (skipped) without them. /dev/uinput is not needed: the list is re-read on request.
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
python3 "$ROOT/tests/touch-detection/check.py"
python3 "$ROOT/tests/touch-detection/qc_policy_check.py" "$ROOT"
rc=0
if command -v bwrap > /dev/null; then
	python3 "$ROOT/tests/touch-detection/hotplug_live.py" || rc=$?
	python3 "$ROOT/tests/touch-detection/match_live.py" || rc=$?
else
	echo "SKIP: bwrap is missing, the live hot-plug checks did not run"; rc=77
fi
exit $rc
