# namekb: Player page name keypad, live name/model, fullscreen model viewer

Branch `work/namekb` (and `ci/namekb` for the Android CI build). Status: NOT fully verified on Android; see "Not verified".

## Done and verified (commands and results)
- `scripts/test-uibridge.sh` -> `test-uibridge: all passed` (new `settings-player.json` fixture = the real page; schema regenerated with `field.keypad`, `image.viewer/model/skin`).
- `cd loader && cargo test --offline -p godnotic-loader-core` -> 20 passed + 13 passed (incl. 3 `name_markup` tests); `cargo build -p godnotic-loader` OK.
- QC: `xonotic_compile_qc_only` -> `qc rc=0` (menu.dat built).
- Android: `gradle testDebugUnitTest --tests '*NameColourTest' --tests '*ModelViewerTest' --tests '*UiBridgeTest'` + `assembleDebug` -> BUILD SUCCESSFUL (44 tests).
- Engine: `make sv-release` rc=0; `scripts/build-web.sh --engine-only` rc=0 (5.4 MB wasm).
- GTK screenshot (1920x1200, fixture): `~/.cache/godnotic-work/namekb-shots/gtk-player-dark.jpg`, crop `gtk-player-crop.jpg`: coloured name preview, full colour keypad, no letter on the six `^xRGB` swatches, no Profile/Colours tabs.
- Android emulator run 6 (before the keypad fix): Player page has no colour section; Character with the fullscreen icon; frame `~/.cache/godnotic-work/namekb-emu/frames/player.jpg` (may be overwritten by later runs).

## What changed
1. Name colour section (sliders, preview, "N of 64") removed from QC, Kotlin (ColourPicker.kt deleted) and GTK.
2. Name keypad (Colours: ^0-^9 + six ^xRGB; Faces; Symbols incl. game-font U+E0xx glyphs, from the stock CHARMAP). Android: `NameField.kt` (coloured field spans, keypad grid, keyboard toggle remembered in SharedPreferences "fe-ui"). GTK: keypad + coloured preview line (`name_markup`), toggle remembered in `~/.config/godnotic/name-keypad`. QC-drawn fallback: no keypad (typed codes show in colour via the engine's text drawing). Keypad groups are pushed one by one (a QC temp string is 4095 bytes).
3. Live: no `apply` hint on name/shirt/pants; the engine already sends name/playermodel/playerskin/color live. The name is held 0.8 s after the last key (`UIB_PlayerTick`) so the server sees one rename, not one per key. Servers may force a character (`sv_defaultcharacter`) or limit renames; the caption says so.
4. Fullscreen model viewer (Android only): `Iqm.kt`, `OrbitCamera.kt`, `ModelAnim.kt`, `ModelViewer.kt` (GLES2); engine ref `pk3raw:` (models/ .iqm/.skin/.txt/.framegroups, 8 MiB). Only iqm; textures from `.skin` lines only (no shirt/pants tint or normal layers).
5. Settings page while the system keyboard is up: header/search/tab bar hidden so the focused field has room (`ViewRenderer.imeChanged`).

## Not verified (could not be run to completion)
- Emulator run 7 (`~/.cache/godnotic-work/namekb-emu/s1.sh` via `emu-slot.sh`) was still queued behind other workers; none of these has been seen on Android after the keypad fix: keypad + toggle, keys acting on first tap, show/hide remembered, Faces/Symbols, coloured name field, keyboard-up layout, fullscreen viewer (X, Back, zoom, rotate, Play), live name/model on 72.205.209.239:26000 (getstatus), single name send after 0.8 s. If the viewer is not clean on a real run, hide its button (`ViewRenderer.image`, `viewer: "model"` block).
- `s1.sh` records results in `namekb-emu/results.txt` and frames in `namekb-emu/frames` (png + jpg <= 1600 px).
- Real tablet not touched. QC fallback keypad and a GTK viewer were not built.

## Known issues
- Run 6 showed (pre-fix) the keypad missing because of the QC string limit; fixed in 5c87d469 but not yet seen.
- The Android 'Previous/Next model' and Upload layout were not re-reviewed.
