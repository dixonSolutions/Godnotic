# HANDOFF: work/andysufix (AndySU live channels named the pre-rename app)

Based on `origin/main` at 7a8d3ee (release v1.2.204). CI, scripts and docs only: no app code
changes.

## The bug

The live channel manifests (`channel-live-arm64`, `-arm32`, `-x64`, sequence 88, releases
1.2.200 to 1.2.204) named the pre-rename app id. Hosts since 1.2.195 have app
`io.github.dixonsolutions.godnotic` in `assets/andysu/config.json`, and AndySU core refuses
a manifest for another app (`Error::WrongApp`, `core/src/session.rs` step 3). So no live
install had updated since the rename. On an AndySU host `AppUpdater` stands down, so those
installs had no update path at all.

The cause: `andysu publish` (AndySU 0.1.135, `cli/src/publish.rs` `publish()`) keeps the
`app` of the manifest it adds to. Only a brand-new manifest takes the app id from the
payload descriptor. All 15 listed payloads already name the Godnotic id; only the manifest
was wrong.

## Done and verified

- `scripts/andysu-channel.sh`:
  - `repair` / `gh-repair`: if the manifest names another app, keep the releases whose
    payload descriptor names ours, set `app`, re-sign with `andysu sign` (sequence + 1, 30
    days), check, and upload. If no release names our app, delete the manifest so the next
    publish starts the channel again.
  - `check` / `gh-check`: `andysu verify --app --channel` (signature, app, channel, expiry:
    the host's rules), every listed payload descriptor names our app, the newest release
    is the expected version, all of its files are reachable, and the sequence is above the
    previous one.
  - `gh-check` runs on the release assets (through the API) and on the public URL hosts
    fetch.
- `build-and-publish.yml`, job `andysu-live`: `gh-repair` before the AndySU publish step;
  `gh-check --release $VERSION --after <previous sequence>` as the job's last step. A re-run
  that publishes nothing is allowed.
- `resign-live.yml` (weekly, and dispatch): repair, then the old re-sign, then `gh-check`.
  It runs only from `main`. The AndySU CLI and `resign-channel.sh` now come through the API
  with retries, not through anonymous URLs.
- `tests.yml`, job `andysu-channel`: `scripts/test-andysu-channel.sh`.
- `docs/ANDROID.md`, "Live updates: channel manifests".

## After merge: repair the channels without a release

The merge commit is CI-only. If it carries `[skip ci]`, no release runs, so dispatch:

```sh
gh workflow run resign-live.yml --repo dixonSolutions/Godnotic --ref main
gh run watch --repo dixonSolutions/Godnotic "$(gh run list --repo dixonSolutions/Godnotic \
  --workflow resign-live.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Each of the three matrix jobs should log `live-<abi>: manifest now names
io.github.dixonsolutions.godnotic (re-signed, uploaded)`, then `OK
io.github.dixonsolutions.godnotic channel live-<abi> sequence 89 ...` twice (release asset
and public URL). If a job says "a release is active", dispatch again once that release has
finished. If the merge does release (no `[skip ci]`), that release's `andysu-live` jobs do
the repair themselves before they publish, and the dispatch is not needed. To confirm from
anywhere afterwards:

```sh
andysu verify https://github.com/dixonSolutions/Godnotic/releases/download/channel-live-arm64/update.json \
  --pub keys/godnotic-public.pem --app io.github.dixonsolutions.godnotic --channel live-arm64
```

## Installs on the pre-rename package

Those installs are a different Android app. The channel used to pass their manifest check,
so they downloaded each new payload in full (tens of MB) and then refused it at staging
("payload is for io.github.dixonsolutions.godnotic, not ..."). They could not have updated
since 1.2.195 either way. After the repair they refuse at the manifest and download
nothing. Moving to Godnotic takes a fresh install of the Godnotic APK; their data does not
carry over through AndySU.

## Tests and results

- Before: `andysu verify <channel-live-*/update.json> --pub keys/godnotic-public.pem --app
  io.github.dixonsolutions.godnotic --channel live-*` failed for all three channels with
  `manifest app <pre-rename id> != io.github.dixonsolutions.godnotic`. `andysu client
  update` (AndySU core) on the live arm64 URL failed with `(wrong_app)`.
- Real data, throwaway key: `repair` on copies of the three real manifests kept all five
  releases, set the app and gave sequence 89. `check --release 1.2.204 --after 88` passed,
  including HEAD requests to all 21 real files of 1.2.204 on GitHub. Served locally, the
  repaired arm64 manifest was accepted by `andysu client update` (`up_to_date`,
  `last_manifest.sequence` 89; `--host-api 0`, so nothing was downloaded).
- `ANDYSU_CLI=<v0.1.135 musl> bash scripts/test-andysu-channel.sh`: PASS. It covers
  publishing with AndySU's own `publish.sh`, a stand-in `gh` and a throwaway release key
  plus certificate:
  - the bug reproduces;
  - repair drops releases whose payloads name the other app;
  - a host stages 1.3 after the repair and 1.4 after the next publish;
  - a re-run is allowed;
  - the guard fails a channel started afresh and a missing version;
  - a channel with no release of our app is reset;
  - the workflows wire the repair and the check in.
- `shellcheck` on both scripts and on every `run:` block of the changed jobs: clean.
  Python YAML loads all 11 workflows. `scripts/test-old-name.sh`: PASS. `actionlint` is not
  installed here.

## Not verified (needs Actions)

- The real signing key, the `gh release upload --clobber` of the repaired manifest, and the
  AndySU action reading it back over an anonymous URL right after the upload. If that read
  were stale, the post-publish check fails the job and a re-run fixes it.
- No device: a real host picking the update up after the repair has not been seen.
- The scheduled trigger of `resign-live.yml` (scheduled workflows here have not always
  fired; dispatch works).
