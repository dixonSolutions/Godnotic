# work/chatev: chat events, tappable icons, session log, virtual scroll, remote typing

## Done and verified (offscreen 1920x1200 client against a real dedicated server)
- Player events as lines: `<name> joined the server` / `started playing` / `joined the <team> team` /
  `moved to spectators` / `left the server (reason)` / `is now known as <new>`, each with its own
  crisp icon and verb colour; chat lines keep a speech icon. Classified from the game's chat-box lines
  (`touch_chatlog_logic.h`); a duplicate server line ("left the game" then "disconnected") is dropped.
  A scoreboard rename the server did not announce is logged after 1.5 s.
- Feed (replaces the stock chat lines over play): backdrop, shadow, text >= 12 dp, wraps, no clipping.
- Icons are drawn as runs of device pixels at exactly their pixel size (no scaled bitmaps, no textures).
- Every button has a >= 44 dp hit target (log, clear, copy, download, share, close, chips, "latest").
  Tap a name or kind icon (feed or log) for Mention / Copy name / Copy line.
- Log panel: whole session (1000 lines, kept until cleared, across maps, after the match ends),
  times, filter chips All/Chat/Events, virtual list (only rows in view are laid out), mouse wheel,
  PageUp/PageDown, touch drag with fling. Opens from the log icon, a tap on the feed, the pause menu
  ("Chat log" button, dialog_touch_gamemenu.qc) or `touch_chatlog_open`. Escape/Back closes it.
- Remote typing leak (coordinator report): the engine drew "<names> are typing" at the chat area
  position, i.e. on top of the player's input line / the QuakeC sheet's field, while the QuakeC sheet
  drew the same line itself. Nothing from other players was ever written into the input buffer; the
  indicator was drawn over it. Now `TouchMention_TypingY` (touch_mention_logic.h) puts it above the
  input (below when no room) and draws nothing while the QuakeC sheet is open (`_touch_chat_sheet`).
  Test cvar `_touch_typing_sim Bob` simulates a remote typer.
- Merged origin/main: chatpolish's (e0200dce) log box/icons/scroll in touch_chatlog.c and its logic
  header are replaced by this work (same goals, one implementation); its @ picker, sheet scrolling and
  tests in touch_mention*/touch_chat.qc are kept. Its "icons only with lines" rule is kept (toolbar hidden on an empty log).

## Tests
- `bash scripts/test-chat-mention.sh` (pure C: events, feed, retention, scroll, window, hit targets, icons, typing placement): ok
- `BASEDIR=<.suite-base> ENGINE=build/bin/xonotic SIZE=1920x1200 OUT=<dir> scripts/test-chat-events.sh`: 29 checks, all ok
  (join/play/spec/rename/leave from a second real client in order, tap hit-testing and 44 px targets,
  log open/popup/Mention, wheel via Key_Event, drag distance, rest-then-lift no fling, flick coasts and stops,
  filters, retention after the feed faded, clear, remote typing with an empty and a non-empty input).
- test-notify-lines.sh: all checks passed; test-touch-hudmode.sh: ok; test-touch-hudmode-online.sh: SKIP (needs STOCKBASE).
- QC (csprogs.dat, menu.dat) and darkplaces-sdl build clean.

## Screenshots (1920x1200, looked at each)
build/chatev-shots/events-feed.png (feed + toolbar), events-log.png (log), events-popup.png (name actions),
events-scrolled.png (long log, scrollbar, jump-to-latest, my input "@BlueBird" with Bob typing),
events-filtered.png, events-after.png (log after the feed faded). Logs: build/chatev-shots/*.log.
Defects found and fixed from these: textured icons rendered as notexture (now drawn as pixel runs);
the console KEYBOARD tab covered the log's close button (panel starts below it).

## Android emulator (real `adb shell input tap`, touch HUD visible, debug APK built locally)
Frames (JPG, 1400 px): `build/chatev-shots/emu1/` (first session) and `build/chatev-shots/emu2/` (second).
Local match (host dedicated server + bots, emulator 5580/5584), per icon, first tap each:
- log icon (feed strip) opens the log: emu1/t-log-open.jpg, emu2/k-log.jpg. PASS
- copy: Android clipboard preview with the log text: emu1/t-copy.jpg, emu2/f-copy.jpg. PASS
- download: toast "Saved godnotic-chat-...txt to Downloads": emu1/t-download.jpg, emu2/f-download.jpg. PASS
- share: real Android share sheet with the log text: emu1/t-share.jpg, emu2/f-share.jpg. PASS
- clear: log shows "No messages or events yet", strip hides: emu1/t-clear.jpg. PASS
- name/icon of a row: Mention / Copy name / Copy line popup: emu1/t-name.jpg. PASS (Copy name tapped: emu1/t-copyname.jpg)
- close X (after the panel moved below the HUD's top row): emu2/k-close.jpg. PASS
- tap outside the panel closes it: emu1/t-outside.jpg. PASS
REAL public server (95.217.62.206:26000 afterslime, "[EU] futa.tips", stock map, live bots and a real player list; joined from the app via +connect):
- emu2/p2.jpg live match with the strip above the feed lines (real events "Player#5 started playing", "[BOT]Death left the server").
- log opens on the first tap (emu2/q-log.jpg), copy (q-copy.jpg), download (q-download.jpg), share (q-share.jpg: share sheet with the real server's lines). PASS
Defects found on the emulator and fixed: the log's close X was under the HUD's info button (panel now starts below the HUD stats/top row, H*0.21); the panel was narrower than needed (the console-button avoidance also fired when the button was above the panel; now only on a real overlap).
Cause of the owner's "icons do nothing": fingers were handled by the QuakeC layer from a rect it read back from the engine; now the engine takes a finger on the strip, the feed's names and the open log itself, in `TouchVote_Finger` before any CSQC (works for the stock CSQC too).

## NOT verified
- Moving/aiming with the other thumb while tapping (adb input is single touch), and that nothing underneath fires: the claimed finger never reaches the control table (by construction), not observed with two fingers.
- Tablet hardware and portrait phone on a device; the emulator was 1920x1200 landscape only (desktop frames cover 720x1280 and 960x540).
- The re-fix of the panel position (H*0.21, width) is checked on desktop only, not re-run on the emulator.
- Pins: `components.json` was not updated (component-push.sh is the coordinator's); engine/ differs from the pins.
