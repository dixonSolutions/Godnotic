# work/flatpakrepo: the Flatpak remote on Pages, made a real repository

Clone `~/.cache/godnotic-work/flatpakrepo`, branch `work/flatpakrepo` (CI: `ci/flatpakrepo`),
based on `origin/work/flatpakupd` (which is `origin/main` 4b747d4 plus the `.flatpakref`,
`.flatpakrepo` and version stamping). Nothing on `origin/main` since.

## What was wrong (checked 2026-11-15 against https://dixonSolutions.github.io/Godnotic/flatpak)

| Problem | Finding |
|---|---|
| No history | `build-import-bundle` imports parentless commits, and the mirror pull ran at depth 0, so every publish replaced the head and pruned the last one. |
| Deltas | Only from-scratch deltas; nothing from the build users have. The old app ID got from-scratch deltas too (wasted space). |
| AppStream | **Was there** (the flatpakupd handoff was wrong): `appstream/{x86_64,aarch64}` and `appstream2/*` exist with name, summary, description, icons and releases (1.2.206). What was missing: screenshots (the metainfo had a `<screenshot>` with no image), and a per-release link. |
| GNOME 48 | EOL on Flathub since 2026-03-24. 49, 50 and 51 are all current on Flathub; 51 is newest. Moved to **50** (installed here, so testable; see "Not done"). |
| 503s | Not tied to particular paths: Pages' CDN answers a **cache-cold missing file** with 503 about a third of the time (30 fresh random paths: 11 x 503), and a 404 once cached. Existing files never did (25/25 200). Flatpak and OSTree fetch several files an unsigned repo lacks: `summary.sig`, `summary.idx.sig`, `objects/xx/<commit>.commitmeta` for every commit pulled, and `delta-indexes/xx/<b64>.index` for a commit that is not a delta target (any rollback). |
| Signing | None, no secret. Not set up here (owner's call); prepared. |

## What changed

- `scripts/publish-flatpak-remote.sh` (rewritten):
  1. `ostree pull --mirror --depth=KEEP_VERSIONS-1` of the Pages repo (all refs, 3 tries).
     Only a 404 for `config` means "no repo yet"; anything else (Pages down, 503 after
     retries) stops the publish with an error rather than starting a fresh repo that would
     lose the history and the old-ID rebase (`ALLOW_FRESH_REPO=1` overrides).
  2. Bundles go into a staging repo; `flatpak build-commit-from --src-repo=<stage>` commits
     each onto the current head, so the new commit's parent is the previous build.
  3. `flatpak build-update-repo --generate-static-deltas --static-delta-ignore-ref=<old ID>
     --prune --prune-depth=KEEP_VERSIONS-1` plus `--title/--comment/--homepage/--icon/
     --default-branch`: parent delta, from-scratch delta, appstream branches, summary. (The
     ignore pattern matches the app ID, not `app/<id>/*`: tested.)
  4. Empty placeholder files (a valid empty GVariant `a{sv}`) for `summary.sig`,
     `summary.idx.sig`, `summaries/*.gv.sig`, every `.commitmeta` and every missing delta
     index, written into the site only, never over real ones.
  5. Optional signing: `FLATPAK_GPG_KEY` (armored secret key, no passphrase) is imported into
     a temp GNUPGHOME; new commits, the old-ID rebase and the summary are signed, mirrored
     commits are signed afterwards (so a verifying client can roll back), `GPGKey=` goes into
     the `.flatpakref`/`.flatpakrepo`, `godnotic.gpg` is published. Unset: no change.
  6. `KEEP_VERSIONS` (default 5) replaces `PRUNE_DEPTH`. At least one bundle is needed
     (the workflow still requires both).
- `.github/workflows/build-and-publish.yml`: new job **Flatpak remote publish test**
  (`scripts/test-flatpak-remote.sh` on ubuntu-latest, every run incl. dispatches);
  `publish-pages` needs it, passes `KEEP_VERSIONS: '5'` and
  `FLATPAK_GPG_KEY: ${{ secrets.FLATPAK_GPG_KEY }}`, installs gnupg, and writes `du -sh site/*`
  to the step summary. Flatpak container `gnome-48` -> `gnome-50`.
- `scripts/test-flatpak-remote.sh` (new): see Tests.
- Metainfo: two screenshots with image URLs (`docs/media/hud-playing.jpg`,
  `hud-mapvote.jpg` on raw.githubusercontent main, both 200), developer id lowercased
  (appstreamcli warning). `scripts/set-package-version.sh`: `type="stable"`, a
  `<url type="details">` to the GitHub release, no duplicate entry when run twice, no blank
  line. It keeps the "Automated CI build X." sentence: `stage-flatpak.sh`, `start.sh` and the
  loader's `version_from_metainfo` parse it.
- GNOME 50: manifest `runtime-version`, `loader.yml`/`launcher.yml` `XT_GNOME_VERSION`,
  `loader/build.sh`, `loader/build-in-sdk.sh`, `launcher/build.sh`, `loader/scripts/{drive,shot}.sh`,
  `scripts/test-gtk-{screens,layout}.sh`, `scripts/test-flatpak-updates.sh`, docs/LAUNCHER.md,
  Cargo.toml comment. gtk4-rs/libadwaita features stay at v4_18/v1_7 (SDK 50 has GTK 4.22.5,
  libadwaita 1.9.4, GCC 15.2).
- Manifest: libxmp module cleans up `/include`, `/lib/cmake`, `/lib/pkgconfig`, `*.a` (the
  4.5 MB `libxmp.a` shipped in every install; the engine dlopens `libxmp.so`).
- docs/RELEASES.md: "History, deltas and AppStream", "Signing the Flatpak remote" (owner
  steps). README: history and rollback. docs/handoffs/flatpakupd.md: one line reworded for
  `scripts/test-old-name.sh` (it failed on the old ID written out).

## Size on Pages (limit 1 GB per site; Click and web/ share it)

Measured: the current `github-pages` artifact is 191.6 MB (207.3 MB after the Web workflow
adds `web/`). Real-data publish of 1.2.205 then 1.2.206 (x86_64): one build is 36 MB of
objects plus 4.4 MB of from-scratch delta parts (the delta's 24 MB of "fallback" objects are
the loose objects, not stored twice); the 1.2.205 -> 1.2.206 delta is 134.6 KB; the site
was 40 MB for both builds. The biggest files (engine 7.9 MB, progs/csprogs/menu + .lno
~24 MB, loader 2.6 MB, uncompressed) bound an older build's cost at ~12-15 MB compressed when
everything changes. Estimate for the Flatpak part with KEEP_VERSIONS=5, both arches and the
old ID's two commits (~60 MB, now without deltas): **~150 MB typical, ~250 MB worst case**;
whole site ~230-330 MB. Comfortably under 1 GB; lower KEEP_VERSIONS if Click/web grow.

## Tests (2026-11-15, this clone, `taskset -c 20-23`)

- `scripts/test-flatpak-remote.sh`: **44 passed, 0 failed**. Fake app (3 MiB incompressible
  file unchanged between versions), x86_64 + aarch64 bundles, python HTTP server standing in
  for Pages (logs every request), throwaway `FLATPAK_USER_DIR` (refuses to run if the
  installation it sees has remotes; in a distrobox it passes the variable through
  `distrobox-host-exec env`), all deleted at exit:
  publish 1 on a repo holding only the old ID (EOL rebase, no delta for it, no GPGKey);
  install v1; publish 2: parent = v1 (`ostree` and `flatpak remote-info --show-parent`/`--log`),
  delta v1->v2 and from-scratch delta, appstream/appstream2 for both arches with name,
  release 2.0 and icon, `xa.title` in the summary; `flatpak update` v1->v2 used the delta
  superblock, fetched **no `.filez`**, 3731 bytes; `flatpak search` finds the app;
  `update --commit=v1` rollback and forward; publish 3 with KEEP_VERSIONS=2 prunes v1 and its
  delta; unreachable Pages stops the publish; publish 4 with a throwaway ed25519 key generated
  inside the test dir: signed summary, GPGKey in both files, a gpg-verifying remote installs v4
  and rolls back to v3 (mirrored unsigned, signed by the publish). **No request for a missing
  file** in any client step or mirror pull. Plus static checks: runtime version consistent
  across manifest/container/loader/launcher, stamping (once, newest first, release link,
  version still parsed), screenshots present.
- Real data: 1.2.205 x86_64 bundle (downloaded once from the v1.2.205 release, 22 MB) and
  1.2.206 rebuilt from this machine's installed commit `03c3d570` (no download), through the
  publish script and a local server, throwaway user installation (deleted): install 28.7 MB,
  publish 2 parent link 403e5b83 -> a2f94171, **update 1.2.205 -> 1.2.206 fetched 138,362
  bytes in 7 files, 0 `.filez`, 2 delta parts, no 404**; `remote-info --log` shows both;
  `flatpak search Godnotic` shows 1.2.206 from the appstream branch. Publish took 19 s.
- Against the real Pages, metadata only (`ostree pull --mirror --depth=4
  --commit-metadata-only`, 548 KB): the depth pull works on today's repo (app commits have no
  parent; appstream history stops at 2 commits) without errors.
- `scripts/test-flatpak-updates.sh` with the SDK 50 loader binary from the flatpakupd clone
  on **org.gnome.Platform//50** (Xvfb, offscreen): all 12 passed.
- `appstreamcli validate --no-net` (SDK 50) on a stamped metainfo: passes (1 pedantic).
- `shellcheck` (all levels) clean on both scripts; yamllint (relaxed, no line length) clean
  on the changed workflows and the manifest; `scripts/test-old-name.sh`: PASS.
- CI: see the bottom of this file.

## Not done / not verified

- **GNOME 51** is the newest runtime (Flathub, 2026-09); 50 will likely go EOL when 52 ships
  (~March 2027, going by 47/48). Moving again is the same one-line change in the places listed
  above; the SDK 51 is not installed here, so 50 is what could be tested locally.
- The engine was not built locally in SDK 50: CI's gnome-50 Flatpak job does that (the host's
  own engine builds already use GCC 15.2, the SDK 50 compiler).
- The real publish runs only on `main`: the first release after merging mirrors today's Pages
  (parentless 1.2.206 heads) and its new commits get those as parents. Watch that run's
  "Build Flatpak OSTree" step log for the `->` lines and the delta list.
- No real signing key; nothing signed on Pages. The owner steps are in docs/RELEASES.md.
  **Note:** this machine already has a GnuPG home at `~/.local/share/godnotic-flatpak-signing`
  (keyring created 2026-11-15 13:38 local, a gpg-agent for it running). It was not made or
  used by this work; the owner should know whose it is before any key goes into a secret.
- The old ID's commits (~60 MB) stay on Pages for the end-of-life rebase. They could probably
  be replaced by an empty-tree commit carrying the rebase, but that path was not tested.
- The test needs `flatpak`, `ostree` and `gpg`. Locally there is no `ostree` binary: the
  Ubuntu 26.04 `ostree` deb (+ `libostree-1-1`, `libavahi-glib1`, `libcomposefs1`) is
  unpacked in `~/.cache/godnotic-work/flatpakrepo-tmp/ostree-root`, wrapper `../bin/ostree`.

## CI (commit ed7b948 on `ci/flatpakrepo`)

- "Build and publish" dispatched on `ci/flatpakrepo`, run 43684872140: **success**. Flatpak
  remote publish test 44/44 on ubuntu-latest (Flatpak 1.16.6, libostree 2025.7, the same as
  the publish job); Flatpak (x86_64) and (aarch64) built in the `gnome-50` container
  ("Dependency Runtime: org.gnome.Platform 50", engine compiled with the SDK's GCC 15);
  Loader GTK app built and clippy-clean in `org.gnome.Sdk//50` on both arches; Click and
  Android green; Publish Pages and GitHub Release skipped (not main).
- Bundles: x86_64 20.36 MB (was 22.08 MB for 1.2.206), aarch64 19.99 MB (was 20.89 MB):
  the libxmp cleanup ("Removing files/lib/pkgconfig/libxmp.pc", `libxmp.a`).
- Push runs on `ci/flatpakrepo`: Tests, Loader, Launcher (Kotlin launcher against GNOME 50
  headers) success; the push-triggered Android run was cancelled by the dispatched run's
  Android job (same concurrency group), which passed.
