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

## NOT verified
- Android / the emulator: Android builds only in CI; no APK of this branch was available locally and
  downloading one is off limits on the metered link. The typing and event screenshots on the emulator are therefore NOT taken.
- Tablet (touch), portrait phone, real finger input: only the forwarded pointer path
  (`touch_chatlog_ptr`) was driven, not a real touchscreen. QC compiles; the forwarding from touch_chat.qc is untested live.
- Stock-CSQC public servers: the finger forwarding lives in the port's QuakeC, so a finger cannot yet scroll/tap the log there (mouse and wheel work).
- Native Kotlin/GTK pause menus have no "Chat log" button (only the QuakeC game menu).
- Pins: `components.json` was not updated (component-push.sh is the coordinator's); engine/ differs from the pins.

CI: ci/chatev pushed for the Android compile (head commit carries no skip marker).
