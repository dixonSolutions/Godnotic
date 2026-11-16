# Backlog

What was asked for, its state, and how it will be proven. One commit per item, reviewed and released.
Updated by hand; if an item is not here, it is not being tracked.

## Done (shipped, tested, released)

| Item | Where | Released |
|---|---|---|
| Cursor returns with the mouse in touch mode | vid_touchdetect.c | 1.2.214 |
| Move stick fixed in place and larger; top bar never overlaps | touch_hud.c, touch_topbar.h | 1.2.217 |
| Spectator bar opt-in; MENU carries follow; aim-pull dead band | touch_spectate, touch_hudmode | 1.2.218 |
| Auto boost: pure decision function + 42 checks (not wired yet) | touch_boost.h | 1.2.219 |
| Music starts when its pack arrives late | touch_fs_mount.h | 1.2.220-226 |
| Dynamic cursor: no pointer until a mouse is used | vid_touchdetect.c | 1.2.226 |
| Escape opens the native menu and stays open | EscapeKeyRoute (Android) | 1.2.226 |
| Description rewritten (fork, assets, Lua, native-first, all four stores) | README, metainfo, credits | 1.2.226+ |
| CI caches keyed on sources, not every commit | workflows | - |
| Settings rules and settings map (with search spec) | docs/SETTINGS_RULES.md, docs/SETTINGS_MAP.md | - |
| Death prompt says tap the control, not a key | scoreboard.qc | next |
| AndySU verifies before staging; deltas off by default | AndySU work/update-verify | releasing |

## In progress

| Item | Acceptance |
|---|---|
| Server list: selected row fills the address bar | a selection writes host:port into the field; editing still works |
| Server list: sort, direction, chips and selection survive a refresh | test that rebuilds the model and asserts the state is unchanged |
| Join dialog: one action height, shared edges, background matches its button | geometry test that fails before the fix |
| Join dialog: server name, gametype, players/max, spectators, map, time or limit | fields populated from client data, never empty |

## Queued, in order

| Item | Notes and acceptance |
|---|---|
| AndySU pin bump in Godnotic | live build fetches andysu-host-template-0.1.135.zip by literal version; bump after the AndySU tag exists |
| Portrait layout | fails 96 checks at 1080x2340; top bar needs a portrait branch (stack or narrow clock band) |
| Spectator controls | Play button with a real disabled state and a reason; next/prev always available while spectating; player-free toggle instead of the follow-only FREE; teammates-only scope, default in team games |
| Scoreboard in native UI | port the table, sorting and menu interactions; conflicts with portrait, do one then the other |
| Settings search | build against docs/SETTINGS_MAP.md section 4: index every setting with category path and help, jump to the section, close on outside tap or X |
| Leave match: one tap | engine action is already single-step (TOUCHACT_LEAVE = ENABLED); the confirm is in the UI layer, needs the dialog wording |
| OSK: system keyboard first, fall back to ours | needs SDL_IsScreenKeyboardShown for a real 'did it appear' answer, not a blind timer |
| Map load, reconnect, vote responsiveness | one benchmark family: extend scripts/bench-load.sh to reconnect and vote before optimising |
| Server filter glitches beyond the two in progress | needs a reproduction |
| Auto boost: wire the decision to a control and a setting, default off | cvar, HUD trigger, settings row, test |
| Asset profile on Android | confirm slim/ASTC is chosen; phone packs are the cheap 554 MB win |

## Rules used

- One deliverable per commit; a test that fails before and passes after, or the item is not done.
- Merge order by component movement: never push a component over newer work, pin after pushing.
- Announce each release with its version, what it carries and the exact asset to test.
