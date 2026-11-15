# work/flatpakupd: Flatpak updates from Settings > Updates

Clone `~/.cache/godnotic-work/flatpakupd`, branch `work/flatpakupd` (CI: `ci/flatpakupd`),
based on `origin/work/integration4` with `origin/main` (1.2.206, 4b747d4) merged in.

## The remote, checked 2026-11-15

| Check | Result |
|---|---|
| Remote URL | `https://dixonSolutions.github.io/Godnotic/flatpak` (scripts/publish-flatpak-remote.sh, "Publish Pages" job) |
| Newest commit | x86_64 `03c3d5707b0f`, aarch64 `6909a2fc6c97`, both "Built from 4b747d4" = **1.2.206** (newer than 1.2.205 / 35dcf95, released 04:18 UTC); `summary` last-modified 04:22: Pages is not lagging |
| Old name | `app/io.github.dixonSolutions.XonoticTouch/*` carry an end-of-life rebase to the new ID: fine |
| **Signature** | **Not signed**: no `summary.sig`, nothing signs in the publish script. `flatpak remote-add --user godnotic <url>` (what README and the release notes said) then fails at `remote-ls`/`install`: "GPG verification enabled, but no summary signatures found". Only `--no-gpg-verify` works (the remotes on this machine were all added that way) |
| **.flatpakref / .flatpakrepo** | **None published** (both 404). Fixed here: the publish script writes both, without `GPGKey` (Flatpak then sets no-gpg-verify) and with `RuntimeRepo=` Flathub; README and release notes use the `.flatpakref`. Tested the generated files in a throwaway installation (`FLATPAK_USER_DIR`): `remote-add --from godnotic.flatpakrepo` gives a no-gpg-verify remote listing both arches; `install --from ….flatpakref --no-deps` installed 1.2.206 (03c3d5707b0f), then removed |
| **History** | **None**: `remote-info --show-parent` is `-`, `--log` shows no history. `build-import-bundle` imports parentless commits, so `--prune-depth=40` keeps nothing older; the "history is retained / roll back" text on the remote page and in RELEASES.md was wrong (corrected in both). Not fixed (would need `build-commit-from` onto the previous head; untested here without bundles) |
| AppStream | No `appstream`/`appstream2` refs: GNOME Software/Discover see no remote metadata for the app (installed metainfo only). Not fixed |
| Runtime | `org.gnome.Platform//48` is **end of life** on Flathub (since March 2026): flatpak warns on install/update. Not changed |
| 503s | Pages answers 503 (not 404) for some missing files (`summary.idx.sig`, `*.flatpakref` before this): flatpak prints "Could not update extra metadata … 503" on `remote-add`. Harmless |
| **version.txt** | **Empty in every Flatpak so far**: `PACKAGE_VERSION` never reaches `flatpak-builder`, so `/app/share/xonotic/version.txt` is blank (the installed metainfo says 1.2.206). Fixed: `stage-flatpak.sh` reads the CI stamp from the metainfo; `start.sh` and the app fall back to it for builds already out |
| This machine | `io.github.dixonSolutions.Godnotic` 1.2.206 (03c3d5707b0f) from `godnotic` (user): up to date, `remote-ls --updates` empty. Not touched. There are also user remotes `chk` and `old` (same URL, no-gpg-verify) left by someone; not removed |

## What changed

- **loader/crates/core/src/flatpak_update.rs** (pure): the update state machine (idle, checking,
  up to date, available, updating with progress, restart, error, no portal), the
  `update-status.txt` it writes (`via=flatpak`, `portal=yes|none`, `checked_min/result`,
  `step/steps`, commits), the request tokens (`check`, `install`, `restart`), `/.flatpak-info`,
  the changelog's newest version, the CI version in the metainfo.
- **loader/crates/core/src/portal.rs** (feature `portal`, GIO D-Bus): the
  `org.freedesktop.portal.Flatpak` client (version property, `CreateUpdateMonitor`,
  `UpdateAvailable`, `Update` + `Progress`, `Spawn` with the latest-version flag, `Close`), and
  Check now: the remote's OSTree `summary` (GVariant) for this ref's commit plus the changelog.
- **loader/crates/gtk/src/updates.rs**: runs it when `/.flatpak-info` exists. Owns
  `<user data>/touch/update-status.txt` (start.sh skips its own when `GODNOTIC_UPDATE_STATUS=loader`),
  polls update-request.txt (both the user-data and the engine-userdir copy) once a second,
  checks once 8 s after start unless the network is metered, rewrites the status every 30 s or
  when something overwrote it. Restart: portal `Spawn(["godnotic-loader"], LATEST_VERSION,
  GODNOTIC_RESTARTED=1)`, then kills the engine and quits; the new instance waits for the old one
  to leave the bus (`wait_for_old_instance`, one line in main.rs).
- **QuakeC** (`touch_uib_settings.qc`, `touch_update_util.q*`): `UIB_SecUpdatesFlatpak` when
  `via=flatpak portal=yes`: installed and newest version, Update now, progress bar with step,
  Restart to finish, the portal's error with Try again, Check now (asks the app) and the last
  check. `portal=none` keeps today's text plus "needs Flatpak 1.6 or newer"; Check now there
  still asks the changelog. New action `updates.restart`. Click and local builds unchanged.
- `packaging/start.sh`, `scripts/stage-flatpak.sh`: the version (above) and the skip.
- `scripts/publish-flatpak-remote.sh`, README, release notes, docs/RELEASES.md: `.flatpakref`,
  `.flatpakrepo`, honest text about signing and history.
- CI: `loader.yml` installs `libglib2.0-dev dbus-daemon` and runs core tests/clippy with
  `--features http-ureq,portal`; `loader/build.sh --check` does the same.

## Tests (all run 2026-11-15 in this clone, pinned to CPUs 20-23)

- `cargo test --offline -p godnotic-loader-core --features http-ureq,portal`: all pass,
  including `tests/flatpak_portal.rs` (a stub portal on a private `dbus-daemon`: update flow with
  progress and Spawn, permission error, refused Update, portal too old, no portal; Check now on a
  synthetic summary) and the state machine's unit tests.
- `cargo test --offline -p godnotic-loader-core` (the gate, no features): pass.
- `loader/build.sh --check` (SDK 50 here; CI uses 48): pass.
- `scripts/test-uibridge.sh`: pass. `scripts/test-old-name.sh`: pass.
- `scripts/test-uibridge.sh --engine` with this clone's own basedir: engine_test, input_test,
  firstrun and the new `tests/uibridge/flatpak_updates_test.py` (every Flatpak state on screen,
  the requests; 28 checks) pass; `settings-updates-linux` is "same". Fixture drift in `create`, `map-preview`, `seq-map-select`, `singleplayer`,
  `singleplayer-create-lan` comes from the data set: the stock 20230620 pk3s the suite bases linked
  to are gone from this machine (the org.xonotic.Xonotic Flatpak was removed), so `.suite-base`
  uses the 20261107 packs from `~/.cache/godnotic-infra/repack/src`, whose mapinfo lists other
  modes per map (`kh`, `dom` differ in `map-preview`): nothing to do with this branch. Only
  `settings-updates-linux` was regenerated (`uib-fixtures.py --write --only settings-updates-linux`).
- `scripts/test-flatpak-updates.sh`: the GTK app end to end, offscreen and offline (Xvfb, private
  bus, `tests/flatpakupd/fake_portal.py`, local HTTP for summary and changelog): check, install
  with progress, ready, Spawn and quit, and the no-portal case. 12/12.
- Real portal, real remote: the new loader run inside the installed Godnotic sandbox
  (Xvfb, own HOME and user base, no install or restart sent): the portal made a monitor
  (`portal=yes`), version 1.2.206 from the metainfo fallback, Check now read the Pages summary:
  remote `03c3d5707b0f` = running, "Up to date".

## Not done / not verified

- `Update` and `Spawn` against the real portal: the first `Update` opens the desktop's "Update
  Godnotic?" dialog and Spawn opens a window, and both would change the user's install. Only the
  stub portals exercised them.
- No GPG signing (needs a key in a secret), no commit history, no AppStream, GNOME 48 EOL: reported.
- The classic menu's Updates dialog (`dialog_settings_updates.qc`) now sees a non-managed state on
  Flatpak and offers its own check/install buttons; their tokens (`check`, `install`, `download`)
  are served, the auto-update switch is not.
- The Update call passes no parent window, so the permission dialog is not attached to ours.
- Nothing checked on the tablet (not relevant: Linux only).
