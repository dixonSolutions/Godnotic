# Spectator controls, input-device-aware controls, observe/respawn state (work/spec)

All five items are committed on `work/spec` (merged with origin/main, which had nothing new). Details and the
exact test commands: `~/Projects/.coord/spec-report.md`.

- Spectator state: the engine HUD now reads the CSQC's `spectatee_status` (server clientdata) instead of trusting
  the scoreboard frags (`TouchUI_SpectateeStatus`, `TouchSpec_StateFromCsqc`). `touch_hud_state` prints it.
- Hints: `Touch_Spec_*Hint` (bundled CSQC) and `TouchHUD_HideStockKeyPanel` (public-server CSQC) never name a keyboard
  key on a touch device without a keyboard.
- Devices: `vid_keyboard_detected` hides the move stick, `vid_mouse_confirmed` hides look area, FIRE and ALT, in both HUDs
  (touch_hud.c, touch_input.qc, touch_draw.qc, touch_spectate.qc); a listed mouse now counts as present at once
  (`VID_TouchMouseConfirmed`).
- Aim assist and auto-shoot: `TouchAim_EnabledForInput(mouse_present, cl_accessibility_desktop)`; `cl_touch_aim_input` shows it.

Tests: `scripts/test-touch-input-devices.sh` (needs BASEDIR/STOCKBASE for the live parts), `scripts/test-touch-hudmode-online.sh dm ctf`
(fakes a stale scoreboard; fails without the spectatee fix), `test-touch-spectate.sh`, `test-touch-aim.sh`.

Not verified: a real tablet; automatic hot-plug (no /dev/uinput here: the fake device list is re-read with
`vid_touchscreen_rescan`); the bundled-CSQC path only has source guards for the hint rotation; `components.json` pins
were not updated (scripts/component-push.sh is the integrator's). A keyboard-only touch device on a public server
still sees the stock "MOUSE1" wording in the stock hints.
