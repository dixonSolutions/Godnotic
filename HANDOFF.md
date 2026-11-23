# HANDOFF: srvctl (Server control: bar, password check, local bypass)

Branch `work/srvctl` (also pushed as `ci/srvctl`), based on origin/main 7317f7d2. Not merged, not released.

## Done and verified
1. **Strange bar.** Cause: `ViewRenderer.screen()` gave every screen's bottom action row a full-width `0xF2050B18` fill; inside a dialog card that is a darker square band. Fix: `DialogChrome.actionBarFill` is transparent for dialog/sheet. Seen gone in the real Android frame (below). GTK draws a flat bottom bar (checked, no change needed); the QC renderer draws the dialog as one panel (no change).
2. **Any password unlocked.** Cause: `ctl.auth` set `ctl.auth = address` without asking the server. Now `UIB_CtlProbe` sends `srcon echo godnotic-rcon-probe`; the engine counts the server's reply (`TouchRcon_Classify`: "denied rcon access" -> _touch_rcon_denied, the echoed probe -> _touch_rcon_ok). Probe echo unlocks, refusal shows "Wrong password", 6 s of silence shows "Server did not answer". Only a verified password is remembered (Android `RconVault`, via app.ctl_result). srcon (challenge based) works on servers at rcon_secure 0, 1 and 2 and needs no synced clock; a stock client's plain `rcon` (rcon_secure 1 = clock based) fails against rcon_secure 2 servers.
3. **Local matches.** `UIB_CtlLocal()` is now `isserver()`: a game this engine hosts opens the controls at once, no password, no rcon (verified on the emulator and in the live test). Android skips the vault lookup when app.local.
4. Found on the emulator and fixed: on a screen with the keyboard up a tall dialog outgrew its card and the Unlock/Close row fell out of it; the body now scrolls and keeps the row (`PageScroll.reservePx`). The verdict line moved above the form so the keyboard cannot hide it. A refused or unanswered password empties the field.
5. Found by the live test and fixed: a server drops a challenge request within 0.5 s of the last one and the engine does not ask again, so remote commands leave through a queue 0.7 s apart.

## Tests (all run on this branch)
- `scripts/test-server-control.sh` -> `tests/rcon/server_control_test.py`: dedicated server with a known test password and a headless client on the UI bridge. 39 checks pass: server rcon_secure 0, 1, 2 with a stock client, plus plain/plain: join, password form, wrong password -> inline "Wrong password" and stays locked, right password unlocks, a command sent from the dialog reaches the server (bots join); a frozen server -> "Server did not answer" after about 4.4 s and the right password works again once it thaws; a local match -> controls at once, no form, no Forget, no rcon sent.
- `tests/joinfail/joinfail_test.c` (TouchRcon_Classify): ok. `scripts/test-uibridge.sh`: all passed; with `--engine` the engine parts pass and the fixture-drift step lists 17 DRIFT lines, all settings*/mod-page/seq-setting (not my area; not checked against main); control*/pause fixtures validate. New fixtures: control-wrong, control-timeout, control-checking.
- `cargo test --offline -p godnotic-loader-core`: ok. `make sv-release` and `xonotic_compile_engine_only`: built. `scripts/build-web.sh --engine-only` (XT_WEB_DEPS set): built (5.4M wasm).
- Kotlin unit tests (gradle, offline): DialogChromeTest (5), RconVaultTest (7), UiBridgeTest (25) pass. CI on ci/srvctl: Android and Tests green (for 8f8d2f9f; re-check the last commit).

## Screenshots (1920x1200, all in /var/home/ratnode2/.cache/godnotic-work/srvctl-shots)
Android emulator (real adb taps, debug APK built locally, own dedicated server via 10.0.2.2 and a real public server):
- empty dialog, no bar behind Unlock: `emu-h1-empty.png` (first build), `t2-empty.png` (final)
- typed password, Unlock above the keyboard: `r3-typed.png`
- wrong password with the inline error, field emptied, Unlock visible: `t4-wrong.png`
- right password unlocked, Close visible: `t5-unlocked.png`; reopened with the remembered password, unlocked at once: `t6-reopen.png`
- local match, no dialog, controls at once: `u3-local-control.png`, `emu-c-local-control.png`
- real public server (Xonotic Australia, trespass), no rcon password: `r2-empty.png`, `r5-wrong-real.png` (refusal came back in under 1.5 s, no hang)
- before the fix, Unlock pushed out of the card: `emu-h4-wrong.png`
GTK renders of the fixtures (flat footer): `gtk-control-locked.png`, `gtk-control-wrong.png`, `gtk-control-timeout.png`, `gtk-control-checking.png`, `gtk-control.png`.

## NOT verified
- The real tablet (never touched). The QC fallback renderer's dialog (no screenshot; the verdict line is the same node).
- "Checking the password..." frame on a device: the local server answers faster than a screenshot (fixture and GTK render only).
- Others' chat not leaking into the field: a server-console `say` was sent while typing and the field showed only typed characters, but I did not re-count characters; the field is a plain Android EditText that takes only IME input.
- A public server with an rcon password I do not have to test "Unlock succeeds": not possible; wrong password on a real server, own server right password were tested.

## Nearby problems seen (not mine, not fixed)
- Every emulator start auto-downloads the 39.5 MB release APK (Godnotic-live-1.2.323) into no_backup/updates, then relaunch shows "Install unknown apps" / "Installing update..." forever (Cancel does nothing). Delete the apk and updates.xml to get past it. Metered-link cost per session.
- Multiplayer list shows 2-4 servers for 20+ s while the header says 235; Home's card showed 10 immediately.
- After Leave match, a strip of the old HUD (clock, health bar) stays drawn above Home. Pause overlay: HUD elements show through ("Pause" title overlaps the health bar).
- Server info dialog's picture is a blank rectangle until loaded; a duplicate QC "Allow servers to store your name" prompt shows behind the native one.
- Welcome dialog's Continue moves when the keyboard opens (first tap after typing missed).
- GTK dialog sizing leaves about 170 px of empty space above the button on the password form (heuristic height).

## Round 2 (coordinator's follow-ups), verified on the emulator with real taps
- **Keyboard after Unlock**: hidden when the password is accepted (focus cleared, IME hidden); the dialog gets its room back and the whole Apply row shows: `w4-unlocked.png`. Dialog bodies (and pages) fade at the scroll edge instead of cutting a button in half: `w5-scrolled.png` (the Forget password row fades under Close).
- **Debug builds do not self-update**: `AppUpdater.isEnabled` defaults check_on_launch to false for FLAG_DEBUGGABLE builds when the player never chose (an explicit pref wins; release unchanged). Unit test `AppUpdateStateTest.aDebuggableBuildDoesNotCheckForUpdatesUnlessAsked`. Emulator run: after `emu-shot.sh noupdate` and a full run (Home, Singleplayer, Server control) there is no `no_backup/updates` folder, so no 40 MB download. (The debug default alone was not tested separately from the `noupdate` helper; both write check_on_launch=false.)
- Frames: /var/home/ratnode2/.cache/godnotic-work/srvctl-shots/w2-empty.png, w3-wrong.png, w4-unlocked.png, w5-scrolled.png.
