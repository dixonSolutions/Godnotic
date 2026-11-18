#!/bin/sh
# Guard both menus against exposing obsolete aim-pull controls.
set -eu
cd "$(dirname "$0")/.."
classic=engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/dialog_settings_touch.qc
modern=engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_settings.qc
esp=engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/dialog_touch_esp.qc
! grep -Eq 'cl_touch_aimassist_maxrate|never snaps' "$classic"
! grep -q 'cl_touch_practice_instant' "$esp"
grep -q 'makeXonoticCheckBoxEx_T(0.000001, 0, "cl_touch_aimassist_strength"' "$classic"
grep -q 'cvar("cl_touch_aimassist_strength") > 0' "$modern"
grep -q 'case "vm:touch.snap"' "$modern"
grep -q 'stop if it dies or disappears' "$classic"
printf '%s\n' 'touch aim settings: OK'
