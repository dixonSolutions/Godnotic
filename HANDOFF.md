# Multiplayer list handoff

The Android list retains row positions while refresh replies change their sort values; explicit column choices still sort, and selection remains keyed by address (63e38410). Android unit tests passed with the offline Gradle test command in `~/Projects/.coord/mplist-report.md`.

The requested startup cache delivery, Home live preview, GTK/QC behavior, timings and emulator screenshot remain unfinished. The engine currently reads saved metadata to produce a summary at first frame, but populates the actual server cache on refresh. Home's card currently has no list source and uses a fixed map image. See the report for details.
