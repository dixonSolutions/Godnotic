# Join preview handoff

Work is incomplete on `work/join3`. The `work/srvload` branch is merged. Commit
`45af64c8` removes generic and similar-map levelshots, guards old serverinfo
while connecting, and stops the menu theme when a join starts. It does not
fetch an unavailable map's levelshot before its pack arrives; server-list map
information may itself become stale. Map music start timing still needs a live
check. No new model/sound precache optimization has been implemented.

Validated: SDL and dedicated engine release builds; `scripts/test-touch-joinpreview.sh`,
`scripts/test-join-world.sh`, `scripts/test-touch-join-picture.sh`,
`scripts/test-touch-join-download.sh`, and the compiled `tests/touch-music/policy.c`.
The picture and download tests used an offscreen engine and a basedir linked to
this clone. No tablet testing was performed. The detailed report and benchmark
results are in `~/Projects/.coord/join3-report.md`.
