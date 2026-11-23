# dlfast handoff (work/dlfast)

## Done and verified (desktop, local dedicated server, headless)
- net_download_overlap (default 1): csprogs.dat over the game connection runs beside the HTTP packs.
  scripts/test-touch-dlfast.sh: 6 MB pack at 700 KB/s + csprogs.dat: 24.1 s stock order -> 14.1 s overlapped.
- curl_mirrors + curl_expect (size + crc32): pack taken from a mirror by exact file name when the server URL 404s: 13.4 s; wrong crc or size is rejected. Default mirror list is EMPTY (no verified public mirror layout; dl.xonotic.org is release zips only).
- A pack the map needs that fails for good now stops the join with the "Missing game files" card (Retry / Back on Android, Retry / Close in the QC dialog): 404, 503, drop mid-file, stall, not a zip, bad crc, disk full, one of two packs bad.
- Crash fixes: negative/oversized UDP download block (wrote before buffer, Host_Error), UDP download size cap 64 MB, FS_Close(NULL) when dlcache is full/read-only (CLEAR_AND_RETRY), pending retries of a left join (cl_begindownloads after disconnect), leaving a join now cancels its downloads (partials kept for resume).
- scripts/test-touch-packfail.sh: notfound http503 drop stall notzip badcrc diskfull onebad cancel mapchange restart: all checks passed (log in the run output; client never crashed or froze, no complete-but-broken pack left, restart on the leftovers joins and the pack is whole).
- Builds: engine (sdl-release), make sv-release, APK (x86_64 debug) built; APK contains the changes.

## NOT verified
- Android emulator run (scripts in ~/.cache/godnotic-work/dlfast-emu.sh): the emulator hung ("QEMU2 main loop" hanging) while host load was 160-200; no frames, no logcat, no real-touch Retry/Back. NO SCREENSHOTS EXIST: coordinator should not take the UI claims for the card; the Kotlin card code (FrontendHost.kt "files" -> Missing game files) is unchanged.
- Real public server download (Feris2 / Votable Insta+Hook), KB/s on device, network-off mid-download on device.
- scripts/build-web.sh --engine-only, the join suites (touch-join-sequence, joinpreview, join-picture, csqc-download), cargo tests: not run.
- test-touch-dlfast.sh was last run before its nomirror/dead checks were changed to expect the card; those two checks are untested.
- Not done: server-side emission of curl_expect; HTTP start before connect; metered-aware defaults.
