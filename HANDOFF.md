# Join card handoff (work/join3)

One join card, the map's own picture, the map's music, a leaner load. Detailed report with exact
commands and numbers: `~/Projects/.coord/join3-report.md`.

## What changed

- **Picture identity** (`touch_joinpreview.c`, `touch_joinname.c`): the map of a join is only ever the
  fresh `getinfo` answer of that join, the serverinfo, or the precache list's world model. A pending
  `connect` made while still connected (a second server, the menu's Join on the server list) no longer
  reads the old server's `cl.model_name[1]` (that was the "wrong/previous map's picture" at the start of
  the second join). The server-list row's map is not used (it is stale after a vote). No generic or
  blurred stand-in exists any more: no picture is a black card with the progress, never another map's.
- **Levelshot before the pack** (`touch_curlshot.h`, `touch_zipshot.h`, `touch_zipinflate.h`): once the
  download of a map pack has started, the zip's central directory and the `maps/<map>.jpg|png` member are
  range-read from the same URL (bounded, CRC-checked, two requests at most, never delays the pack) and
  shown while the rest downloads (`scripts/test-touch-join-shot-fetch.sh`).
- **Music** (`touch_frontend.c`, `touch_music_cd.h`, `cd_shared.c`): the menu theme is stopped when a
  join starts (`TouchMusic_BeginJoin`) and can not be started during a join or game
  (`TouchMusic_BlockTrack`); the map's `cdtrack` from `maps/<map>.mapinfo` starts as soon as that file is
  mounted (`TouchMusic_EnsureMap`), also at every map change.
- **Stale menu** (`touch_joinfail.c: TouchJoinFail_CloseStaleMenu`): the old map's Welcome/team dialog (and
  the menu background) stayed up over the loading world at every map change and every new join. The menu
  is now closed at the edge where a join starts (a `connect`, or the signon dropping back to 1), also from
  the picture's draw because a blocking load runs no host frame. Dialogs of the new map open later by
  themselves.
- **Android card** (`FrontendHost.kt`, `JoinFlow.kt`): the card is built from the first busy snapshot (no
  300 ms settle), `holdCard` keeps it through the idle/`menu` gaps of a join (before a failure notice,
  during a reconnect), a failure turns the card itself into the error with Retry / Back (no menu, no second
  dialog), a lost connection raises the card from the first `reconnect` app snapshot, Cancel/Back end it at
  once. Pure rules are unit-tested in `UiBridgeTest` (replay of the engine's sequences).
- **Load**: `touch_preload_defer` defaults to 1 (speculative seed/replay work runs in budgeted menu frames
  after the first frame, not under the loading screen).

## Tests

`scripts/test-touch-join-sequence.sh` (picture identity per map over a map change, a second join to another
map and a rejoin after a lost connection; join-card screen sequence), `scripts/test-touch-join-music.sh`
(music state sequence, remote and local), `scripts/test-touch-join-shot-fetch.sh`,
`scripts/test-touch-join-picture.sh`, `scripts/test-touch-joinpreview.sh`, `scripts/test-join-world.sh`,
`scripts/test-joinfail.sh`, `scripts/test-touch-warm-spawn.sh`, Android `:app:testDebugUnitTest`.
Env for the engine tests: `ENGINE=engine/darkplaces/darkplaces-sdl BASEDIR=<dir with data/> SDL_VIDEODRIVER=offscreen`.

## Not verified

Nothing was run on a tablet or emulator: the Android card behaviour (error on the card, reconnect card,
grace timer) is covered by unit tests of the pure rules and by the engine-side sequence only. Map music
from a map pack that is still downloading (its `.mapinfo` is inside the pack) was not run live. The
precache time of a real 400+ model server was not reproducible on this host.
