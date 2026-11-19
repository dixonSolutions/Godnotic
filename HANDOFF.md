# Spectator and input work

Commit 79c99515 changes spectator hints in the bundled CSQC and suppresses the pressed-keys panel on touch devices without a keyboard. The spectator test passes, including the new source guard, and `taskset -c 8-11 make -C engine/data/xonotic-data.pk3dir -j4 qc` passes with the cached gmqcc copied from cx-vote2.

The task is incomplete: stock-server hints, device-specific controls and hot-plug, aim assist and auto-shoot policy, and the dead-to-spectator HUD state still need implementation and live-match tests. No real tablet testing was performed. See `~/Projects/.coord/spec-report.md`.
