#!/bin/bash
# Checks the weapon decisions behind touch auto-shoot and aim assist: lead and
# lob maths, fire window, primary or secondary per weapon, weapon choice per
# distance, Devastator detonation and the weapon table, the velocity filter,
# shot timing, lob arcs and aim break-out (touch_aim_predict.h), the predictive
# Devastator guide against simulated targets (touch_aim_guide.h), in
# engine/darkplaces/touch_weapons.h. No engine build, no display. See
# docs/CONTROLS.md.
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
CC="${CC:-cc}"
dir="$ROOT/tests/touch-aim"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function \
    -I"$ROOT/engine/darkplaces" -o "$out/aim_test" "$dir/aim_test.c" -lm
"$out/aim_test"

"$CC" -std=c99 -Wall -Wextra -Werror -I"$ROOT/engine/darkplaces" \
    -o "$out/input_test" "$dir/input_test.c"
"$out/input_test"

"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function -I"$ROOT/engine/darkplaces" \
    -o "$out/predict_test" "$dir/predict_test.c" -lm
"$out/predict_test"

"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function -I"$ROOT/engine/darkplaces" \
    -o "$out/guide_test" "$dir/guide_test.c" -lm
"$out/guide_test"
