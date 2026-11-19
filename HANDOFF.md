# Net worker handoff

Branch `work/net`. Report with per-item commits and test lines: `~/Projects/.coord/net-report.md`.

## What changed

- **Long frames no longer cost the connection** (`engine/darkplaces/touch_netkeep.c`). A watcher thread
  sends an unreliable `clc_nop` every 2 s once the main thread has been stuck in one frame for 2.5 s
  (`net_keepalive_thread 1`). This is the root cause of the owner's "Reconnecting (attempt 1 of 5)" over
  the join precache: a long CSQC_Init / shader-compile step let a Xonotic server (30 s
  `net_connecttimeout`) drop the joining client, and the client took the "Server disconnected" after that
  long frame for a freeze and joined again. `touch_net_stall <s> [join]` reproduces it.
- **Reconnect rules** (`touch_net.c`): an address change waits for 6 s of server silence before giving the
  connection up (a 2.5 s Wi-Fi flap plays on); the 3 s silence limit after any 5 s frame gap is gone
  (only a freeze over 20 s arms a 5 s check); silence is judged as of the frame's start.
- **Menu over an online match** (`touch_hudmode.h` TouchMode_MenuCover, `touch_frontend.c`,
  Android `MenuBackdrop.cover`): the native menu over a match that is not paused is see-through, so the
  engine keeps drawing at full rate behind it; audio was already unmuted (23bb34fc). Local paused games
  still cover/dim.
- **csprogs.dat download at high ping** (`touch_netdl.c`, one hook in `cl_parse.c`): acks the server's
  download blocks in order and carries the server's expected position over data already held, so a lost
  or overtaken block costs one resend instead of a resend storm (`net_download_inorder 1`).

## Tests

- `scripts/test-net-away.sh` (netns + `tests/net/udp_proxy.py`, no tc): new `stall-signon`, `stall-match`
  (`KEEP=0` must fail), `addrflap`, `freeze-svhitch`, `slow-join` (`tests/net/throttle.py`), rewritten
  `newaddress`.
- `scripts/test-net-highping.sh`: N fresh-client joins through the proxy at a given RTT/jitter/loss,
  first-attempt criterion. A realistic peer is a stock upstream dedicated server
  (`git archive origin/upstream/vendor engine/darkplaces`, `make sv-release`) with plain (non-zstd) data
  (`SV_BASEDIR`): the stock engine cannot read the zstd-packed pk3s.
- `scripts/test-touch-hudmode.sh`, Android `MenuBackdropTest`, `scripts/test-andysu-swap-kill.sh`.

## Not verified

No tablet and no real public server: the long-frame cause is reproduced with a simulated stall, not
captured from the owner's device. The live engine swap itself needs a device (Android-only code).
