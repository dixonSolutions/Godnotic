# Releases and packaging

**Godnotic** ships as:

| Format | Targets | Notes |
|--------|---------|--------|
| **Flatpak** | Linux desktop + tablets (`x86_64`, `aarch64`) | OSTree remote on GitHub Pages; updates from Settings > Updates |
| **Click** | Ubuntu Touch (`arm64`, `armhf`) | GitHub Pages download remote + versioned GitHub Release assets |

Packages are slim: compiled game logic and touch configs ship in the app; **textures, maps, and music download on first launch**.

All builds and publishing run through [GitHub Actions](.github/workflows/build-and-publish.yml).

Each push to `main` creates a **new immutable version** `1.2.<run_number>` (tag `v1.2.<run_number>`). Older GitHub Releases are **not** deleted. The Flatpak remote gets the new commit on top of the previous one and keeps the last five builds (see below).

## Flatpak

### Public remote (GitHub Pages)

| | |
|---|---|
| **Remote URL** | `https://dixonSolutions.github.io/Godnotic/flatpak` |
| **Remote name** | `godnotic` |
| **App ID** | `io.github.dixonSolutions.Godnotic` |
| **Architectures** | `x86_64` (desktop/laptop), `aarch64` (ARM tablets/phones) |

```bash
flatpak install --user https://dixonSolutions.github.io/Godnotic/flatpak/io.github.dixonSolutions.Godnotic.flatpakref
flatpak update --user io.github.dixonSolutions.Godnotic
```

The remote is **not GPG-signed** yet (see *Signing the Flatpak remote* below for turning it
on). `scripts/publish-flatpak-remote.sh` publishes `io.github.dixonSolutions.Godnotic.flatpakref`
(runtime from Flathub) and `godnotic.flatpakrepo` without a `GPGKey`, which tells Flatpak to
skip signature checks for this remote. A bare `flatpak remote-add <url>` expects signatures and
fails ("GPG verification enabled, but no summary signatures found"); add `--no-gpg-verify`
there.

### History, deltas and AppStream

Every publish (the "Publish Pages" job, only on `main`) mirrors the repository Pages serves,
then commits each new bundle **onto the previous head** (`flatpak build-commit-from`), so:

- the remote keeps the last **5** builds of each ref (`KEEP_VERSIONS` in the workflow; older
  ones are pruned), listed by `flatpak remote-info --user --log godnotic io.github.dixonSolutions.Godnotic`;
- going back is `flatpak update --user --commit=<commit> io.github.dixonSolutions.Godnotic`
  (then `flatpak mask` if it should stay there; a plain `flatpak update` goes forward again);
- an update downloads a **static delta** from the previous build: 138 KB for 1.2.205 to 1.2.206
  instead of 28.7 MB, measured. An install two or more builds behind fetches only the changed
  files. New installs use a from-scratch delta;
- `flatpak build-update-repo` writes the `appstream/<arch>` and `appstream2/<arch>` branches
  from the metainfo flatpak-builder composes into each build (name, summary, description,
  icons, screenshots, releases), which GNOME Software, Discover and `flatpak search` read. CI
  stamps a release per build into `flatpak/io.github.dixonSolutions.Godnotic.metainfo.xml`
  (`scripts/set-package-version.sh`, with a link to the GitHub release);
- the app ID from before the rename (`OLD_FLATPAK_APP_ID` in the script) keeps its end-of-life rebase and
  gets no deltas;
- every path Flatpak asks for exists: an unsigned repo has no `summary.sig`,
  `summary.idx.sig`, `<commit>.commitmeta` or delta index for older commits, and Pages answers
  a cache-cold missing file with 503 about as often as 404. The script writes them as empty
  files (an empty GVariant `a{sv}`, read as "none").

Size on Pages (1 GB per site, shared with the Click downloads and `web/`): one build is about
36 MB of objects and 4.4 MB of from-scratch delta per architecture; each older build adds only
the files it changed (0.1 to 15 MB) plus one parent delta. Five builds of two architectures,
plus the old ID, come to roughly 150-250 MB; the job prints the site's size in its summary.

`scripts/test-flatpak-remote.sh` (the "Flatpak remote publish test" job of every "Build and
publish" run, dispatched ones included; the publish waits for it) runs the script four times against a local stand-in for Pages with a
small fake app: parent links, the delta, appstream, an installed build updating through the
delta, rollback, pruning, a signed publish, and no request for a missing file.

### Signing the Flatpak remote

Signing is off until the repository has a `FLATPAK_GPG_KEY` secret. With it, the next release
signs every kept commit and the summary, publishes the public key as `flatpak/godnotic.gpg`,
and writes it into the `.flatpakref` and `.flatpakrepo` as `GPGKey=`, so new installs verify.
Without the secret nothing changes. The owner sets it up once, on a trusted machine:

```bash
export GNUPGHOME="$(mktemp -d)"
# CI signs unattended, so the key has no passphrase; the secret store protects it.
gpg --batch --pinentry-mode loopback --passphrase '' \
    --quick-gen-key "Godnotic Flatpak repository" rsa4096 sign never
KEYID="$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr:/ { print $10; exit }')"
gpg --armor --export-secret-keys "$KEYID" > godnotic-flatpak.secret.asc
gpg --armor --export "$KEYID" > godnotic-flatpak.public.asc
gh secret set FLATPAK_GPG_KEY -R dixonSolutions/Godnotic < godnotic-flatpak.secret.asc
# Keep godnotic-flatpak.secret.asc somewhere safe offline (a password manager): with the
# key lost, every verifying install has to import a new one by hand. Then:
shred -u godnotic-flatpak.secret.asc
gpgconf --kill gpg-agent; rm -rf "$GNUPGHOME"; unset GNUPGHOME
```

Then push or rerun a release. Check: `curl -sI .../flatpak/summary.sig` has a non-zero length
and the `.flatpakrepo` has a `GPGKey=` line. Installs made before signing keep their
`no-gpg-verify` remote and keep updating; to verify them too:

```bash
curl -sO https://dixonSolutions.github.io/Godnotic/flatpak/godnotic.gpg
flatpak remote-modify --user --gpg-import=godnotic.gpg --gpg-verify godnotic
```

To rotate the key, replace the secret: the next release re-signs the kept commits with the new
key, and verifying installs import the new `godnotic.gpg` the same way.

### Updates from inside the app

Settings > Updates in the Flatpak checks for and installs new versions itself, through the
Flatpak portal (`org.freedesktop.portal.Flatpak`, Flatpak 1.6 or newer): the Linux app
(`loader/crates/gtk/src/updates.rs`) keeps an update monitor, Check now reads the remote's
`summary` and `changelog.txt` (a few KB), Update now is the portal's `Update` with a progress
bar, and Restart to finish starts the new build through the portal's `Spawn` (latest version)
and quits the old one. The first update asks, in a desktop dialog, whether Godnotic may update
itself; an update that would add sandbox permissions is refused by the portal (GNOME Software
or `flatpak update` then). Without the portal the screen says how updates arrive, as before.
Tests: `cargo test -p godnotic-loader-core --features portal` (a stub portal on a private
dbus-daemon), `tests/uibridge/flatpak_updates_test.py` (the screen, in `test-uibridge.sh
--engine`) and `scripts/test-flatpak-updates.sh` (the GTK app end to end, offscreen and offline).

First launch downloads game assets (~3 GB) into:

| Install | Path |
|---------|------|
| **Flatpak** | `~/.var/app/io.github.dixonSolutions.Godnotic/data/godnotic/data/` (wiped by Delete app data / `flatpak uninstall --delete-data`) |
| **Click (Ubuntu Touch)** | `~/.local/share/godnotic.dixonsolutions/data/` (AppArmor-writable `APP_PKGNAME` dir) |
| **Native / unpackaged** | `~/.local/share/godnotic/data/` |

An install from before the rename to Godnotic keeps its packs: the Flatpak moves to the new app ID by itself at the next `flatpak update` (Flatpak carries `~/.var/app/<old id>` over), and the first start renames the data folder inside it. A native install has its `~/.local/share` folder renamed the same way. To force a clean first-run wizard now:

```bash
flatpak uninstall --user --delete-data io.github.dixonSolutions.Godnotic
rm -rf ~/.local/share/godnotic \
  ~/.var/app/io.github.dixonSolutions.Godnotic
```

User config and touch layout overrides remain in `~/.xonotic/`.

### Local Flatpak build

```bash
./scripts/install-flatpak.sh
./scripts/install-flatpak.sh --from-remote --run
```

## Ubuntu Touch (.click)

Click packages target Ubuntu Touch devices (ARM). App ID: `godnotic.dixonsolutions` (must match the [OpenStore](https://open-store.io/app/godnotic.dixonsolutions) listing).

There is no OSTree/apt remote for `.click` like Flatpak — instead each `main` push publishes a **stable download remote** on GitHub Pages (always-latest URLs) plus versioned files and GitHub Release attachments. OpenStore gets the arm64 + armhf revisions from `.github/workflows/openstore.yml`, weekly and on demand, not on every push (see **OpenStore** below).

### Public download remote (GitHub Pages)

| | |
|---|---|
| **Index** | `https://dixonSolutions.github.io/Godnotic/click/` |
| **Always-latest arm64** | `https://dixonSolutions.github.io/Godnotic/click/latest-arm64.click` |
| **Always-latest armhf** | `https://dixonSolutions.github.io/Godnotic/click/latest-armhf.click` |
| **Machine-readable** | `https://dixonSolutions.github.io/Godnotic/click/latest.json` |

```bash
wget https://dixonSolutions.github.io/Godnotic/click/latest-arm64.click
pkcon install-local --allow-untrusted latest-arm64.click
```

### Install from GitHub Releases

1. Open the [latest release](https://github.com/dixonSolutions/Godnotic/releases/latest) (or any older `v1.2.*` tag).
2. Download `godnotic.dixonsolutions_*_arm64.click` (or `_armhf.click`).
3. On the device:

```bash
pkcon install-local --allow-untrusted godnotic.dixonsolutions_*_arm64.click
```

### Local Click build

With [Clickable](https://clickable-ut.dev/) (recommended):

```bash
clickable build --arch arm64
clickable build --arch armhf
```

Standalone:

```bash
./scripts/build-click.sh --arch arm64 --install-deps
./scripts/install-click.sh --arch arm64 --skip-build
```

Metadata lives under `click/` (`manifest.json.in`, desktop hook, AppArmor). Framework: `ubuntu-touch-24.04-1.x`.

## GitHub Releases (automatic)

Each push to `main` publishes a **new** release tag `v1.2.<run_number>` (marked latest). Previous tags stay available.

Requires **Settings → Actions → General → Workflow permissions → Read and write permissions** (otherwise `release` fails with `403 Resource not accessible by integration`).

Attached assets:

- `Godnotic-<version>-x86_64.flatpak`
- `Godnotic-<version>-aarch64.flatpak`
- `godnotic.dixonsolutions_<version>_arm64.click`
- `godnotic.dixonsolutions_<version>_armhf.click`
- `Godnotic-<version>-arm64-v8a.apk`
- `Godnotic-<version>-armeabi-v7a.apk`
- `Godnotic-<version>-x86_64.apk`

Each release's notes open with **Changes**: the subjects of the commits since
the previous release tag (`scripts/release-changelog.sh markdown`). The Pages
job publishes the same history as plain text at
https://dixonSolutions.github.io/Godnotic/changelog.txt (newest release
first, kept under 15 KB). The in-game Settings → Updates screen shows that file
under "What's new", on every platform. `scripts/test-release-changelog.sh`
covers the script in CI.

Browse all versions: https://github.com/dixonSolutions/Godnotic/releases  
Latest: https://github.com/dixonSolutions/Godnotic/releases/latest

Offline Flatpak install:

```bash
flatpak install --user Godnotic-1.2.<N>-x86_64.flatpak
```

> The old `continuous` tag (if still present) is legacy from before versioned releases. Prefer `releases/latest` or a specific `v1.2.*` tag.

## CI overview

| Job | Trigger | Output |
|-----|---------|--------|
| `version` | push to `main` | `1.2.<run_number>` / `v1.2.<run_number>` |
| `flatpak` (`x86_64` on `ubuntu-latest`, `aarch64` on native `ubuntu-24.04-arm`) | push to `main` | Versioned Flatpak bundles |
| `click` (matrix `arm64`, `armhf`) | push to `main` | Versioned `.click` packages |
| `publish-pages` | push to `main` | GitHub Pages: Flatpak OSTree + Click downloads |
| `release` | push to `main` | New GitHub Release tag (old tags kept) |
| `openstore.yml` | Mondays 03:17 UTC, or **Run workflow** (optional `tag`) | Uploads the latest release's clicks to OpenStore (`OPENSTORE_API_KEY` required) |

### OpenStore

<a href="https://open-store.io/app/godnotic.dixonsolutions"><img src="https://open-store.io/badges/en_US.svg" alt="OpenStore" width="200" /></a>

| | |
|---|---|
| **App id** | `godnotic.dixonsolutions` |
| **Title** | Godnotic |
| **Manage** | https://open-store.io/manage/godnotic.dixonsolutions |
| **Public page** | https://open-store.io/app/godnotic.dixonsolutions |
| **Secret** | Repo secret `OPENSTORE_API_KEY` |
| **Icon** | Official `engine/misc/logos/icons_png/xonotic_256.png` inside the `.click` (extracted on revision upload) |
| **Changelog** | Each upload sets a short revision changelog linking the GitHub release notes; OpenStore prepends it to the app's changelog |
| **Schedule** | Weekly, not per push. Until 2026-11-08 every push to `main` uploaded a pair: 151 revisions and 3.9 GiB of clicks stored there, about four times the next largest app. From then on every new upload got HTTP 500 while re-uploads of existing versions and metadata edits still worked. Old revisions cannot be deleted through the API; ask the OpenStore maintainers if it happens again |

After the first successful OpenStore upload, open the manage page and set **Published = Yes** once automated review passes.

### GitHub Pages setup

Enable **GitHub Pages** for this repository:

1. Settings → Pages → Build and deployment → **GitHub Actions**

## Asset download sources

On first launch, `fetch-assets-runtime.sh` tries:

1. **Git sparse clone** from GitLab when `git` is available
2. **Xonotic autobuild ZIPs** via `curl`

Android does not use either: `android/app/src/main/assets/godnotic-data-url.txt`
pins the slim archive in the `data-slim` release
(`https://github.com/dixonSolutions/Godnotic-data/releases/download/data-slim/Godnotic-data.zip`,
see [PHONE_ASSETS.md](PHONE_ASSETS.md)) for GPUs that decode ASTC, and the app falls back to
the pinned full set in the same repository otherwise, or when that archive cannot be planned.

| Variable | Purpose |
|----------|---------|
| `XONOTIC_SKIP_ASSET_FETCH=1` | Skip download (dev/testing) |
| `XONOTIC_AUTOBUILD_URL` | Autobuild base URL |
| `GODNOTIC_DATA_DIR` | Asset cache directory |
