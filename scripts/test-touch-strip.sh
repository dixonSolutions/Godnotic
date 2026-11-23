#!/bin/bash
# Hit-tests the touch weapon strip: the NADE row against the HOOK entry, the
# weapon list holding the hook, and old hook layouts (touch_strip.h).
# No engine build, no display. See docs/CONTROLS.md.
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
CC="${CC:-cc}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function \
    -I"$ROOT/engine/darkplaces" -o "$out/strip_test" "$ROOT/tests/touch-strip/strip_test.c" -lm
"$out/strip_test" || exit 1

# The layout side of the move: the HOOK button is gone from the layout editor,
# the HUD and the settings, and an old saved layout (touch_hook_x/_y/_size/_visible)
# still loads (those cvars stay registered, ignored).
Q="$ROOT/engine/data/xonotic-data.pk3dir"
fail=0
absent() { ! grep -q "$@"; }
check() { if ! "$@"; then echo "FAIL: $*"; fail=1; fi; }
check grep -q 'touch_hook_x' "$Q/touch_defaults.cfg"
check grep -q 'Touch_RegisterCvar("touch_hook_visible"' "$Q/qcsrc/client/touch_init.qc"
check grep -q 'case TOUCH_WIDGET_HOOK: return false;' "$Q/qcsrc/client/touch_customize.qc"
check absent 'rect_circle("HOOK"' "$ROOT/engine/darkplaces/touch_hud.c"
check absent 'draw_circle_widget("touch_hook_x"' "$ROOT/engine/darkplaces/touch_hud.c"
check absent TouchHook_ButtonShown "$ROOT"/engine/darkplaces/touch_hud.c "$ROOT"/engine/darkplaces/touch_hook.c "$ROOT"/engine/darkplaces/touch_hook.h
check absent 'autocvar_touch_hook_x, autocvar_touch_hook_y' "$Q/qcsrc/client/touch_draw.qc" "$Q/qcsrc/client/touch_input.qc"
check absent '"touch_hook_visible", "1"' "$Q/qcsrc/menu/xonotic/dialog_settings_touch.qc"
check grep -q 'TOUCH_WEAP_ACT_DEAD' "$Q/qcsrc/client/touch_input.qc"
check grep -q 'Touch_Weapons_SetHook' "$Q/qcsrc/client/touch_hud.qc"
[ "$fail" = 0 ] && echo "layout checks ok"
exit "$fail"
