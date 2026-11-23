#!/usr/bin/env bash
# The join card is docked at the BOTTOM on every path that draws one (owner report: 1.2.313-316 docked it at
# the top over the map picture). A source-level guard, since the Android card is drawn by Kotlin (checked at
# run time by JoinCardTest.cardIsDockedAtTheBottomFromTheFirstFrame in CI) and the QuakeC loading screen
# (web and the GTK fallback) cannot be put in its loading state by a headless engine run:
#   - Kotlin: the card's screen is created with JoinFlow.CARD_GRAVITY and every look change sets the same;
#     JoinFlow.CARD_GRAVITY has the BOTTOM bit; no gravity of its own is given to loadingStrip elsewhere.
#   - QuakeC: touch_uib_render.qc moves a "loading" screen's content to the bottom of its region.
#   - Engine fallback: SCR_DrawCurlDownload lays its lines out from the bottom edge (cl_screen.c).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fe=$root/android/app/src/main/kotlin/io/github/dixonsolutions/godnotic/fe
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
chk() { if grep -qE "$2" "$3"; then ok "$1"; else bad "$1"; fi; }
chk "JoinFlow.CARD_GRAVITY is centred at the bottom" 'CARD_GRAVITY: Int = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM' "$fe/JoinFlow.kt"
chk "the card's screen is created with CARD_GRAVITY" 'screen.gravity = JoinFlow.CARD_GRAVITY' "$fe/FrontendHost.kt"
chk "applyLook keeps CARD_GRAVITY" 'screen.gravity = JoinFlow.CARD_GRAVITY' "$fe/FrontendHost.kt"
n=$(grep -c 'screen.gravity = ' "$fe/FrontendHost.kt" || true)
[ "$n" = 2 ] && ok "no other gravity is given to the card's screen ($n assignments)" || bad "expected 2 gravity assignments on the card's screen, found $n"
if grep -nE 'screen\.gravity = Gravity\.(TOP|CENTER_HORIZONTAL|CENTER)\b' "$fe/FrontendHost.kt" | grep -v CARD_GRAVITY; then bad "the card's screen gravity is not top or centre"; else ok "the card's screen is never top or centre gravity"; fi
qc=$root/engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_render.qc
chk "the QuakeC loading screen is docked at the bottom" 'uib_get\(sc, "kind"\) == "loading" && split == ""' "$qc"
chk "... by moving its region-0 items down by the free height" 'float dock = rg_s\[0\]\.y - rg_content\[0\]' "$qc"
chk "the engine's own download lines start at the bottom edge" 'y = vid_conheight\.integer - size \* nDownloads - offset' "$root/engine/darkplaces/cl_screen.c"
# full bleed: the placeholder backdrop is the SCREEN's background; the banner (the map's name) has none of its own,
# so no band of another colour shows behind or below the card (owner's tablet, Hightime minideck_v4r4)
if grep -nE 'banner\.background' "$fe/FrontendHost.kt"; then bad "the banner has its own background (a band behind the card)"; else ok "the map-name banner has no background of its own"; fi
chk "the placeholder state sets the gradient on the whole screen" 'screen\.background = nameBackdrop' "$fe/FrontendHost.kt"
chk "the picture state is the screen's background (full bleed)" 'setShotBackground\(screen, bmp' "$fe/FrontendHost.kt"
exit $fail
