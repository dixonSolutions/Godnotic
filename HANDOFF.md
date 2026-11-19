# Multiplayer list handoff (work/mplist)

Done on this branch (see `~/Projects/.coord/mplist-report.md` for commands and numbers):

- Engine: the shared server cache is loaded on the first bridge request / first frame, not on the first refresh
  (`TouchSrvList_EnsureCache`); cached rows are not stale while refreshing; `touch_uib_src.c` remembers the row order
  per (sort, direction, filter) so Home's preview and the full list do not reorder each other.
- QC front end: Home's Multiplayer card lists three live rows plus the top map; opening Multiplayer neither resets
  nor reloads the list; Home refreshes every 30 s, Multiplayer every 10 s. The selection card is picture, title, one
  facts line, player list and a primary Join.
- Android: whole cached source requested in 32-row windows, details card with primary Join, no stale dimming,
  error state no longer covers cached rows. GTK has the same selection/whole-source handling (not compiled here).
- `tests/srvlist/measure_open.py` times first rows after opening Multiplayer.

Open points: the GTK crate could not be compiled on this host (no GTK4/libadwaita dev packages); emulator
screenshots were black (see report); `components.json` pins were not updated (component-push is for the
integrator): the darkplaces and data components both changed.
