# Net worker handoff

The work is incomplete. Commit 23bb34fc removes the explicit engine mute for an online game behind the menu and removes the QuakeC menu scrim for unpaused games. The Android native menu still has a scrim. Test: `taskset -c 16-19 bash scripts/test-touch-hudmode.sh` passed; SDL and dedicated engine release builds passed with `DP_LINK_JPEG=dlopen`. No live menu or tablet test was completed.

Connection resilience remains open. The loading path already sends keepalives through `CL_KeepaliveMessage`; `TouchNet` forcibly disconnects after short silence, IP change and a configurable background limit. Determine safe behavior with real server tests before changing those paths. `scripts/test-net-away.sh freeze60 loss-freeze60` failed to join for freeze60; the loss case could not run because `tc` is missing. A tc-free proxy test remains needed.

Update work remains open. Android update unit tests passed (93 tests); `test-andysu-channel.sh`, `test-andysu-release.sh`, and `test-andysu-healthy.sh` passed, though the release script skipped its AndySU CLI check. The E2E script installs and launches on an adb device and was not run because this worker does not own a device. In-match staging and actual live swaps remain unverified. Web engine build attempted with the cached dependency path but could not write to that read-only directory.
