#!/bin/bash
# Publish Flatpak bundles into the public OSTree repo on GitHub Pages, on top
# of what Pages serves now.
#
# Each publish:
#   1. mirrors the current Pages repo, with the newest KEEP_VERSIONS commits of
#      every ref (Pages serves one artifact, so whatever is not carried over
#      here is gone after the deploy);
#   2. imports each bundle into a staging repo and commits it onto the
#      previous head (`flatpak build-commit-from`), so the new commit's parent
#      is the build users have installed: `flatpak remote-info --log` lists the
#      history and `flatpak update --commit=<old>` rolls back;
#   3. runs `flatpak build-update-repo`: a static delta from that parent
#      (updates download only what changed), a from-scratch delta for new
#      installs, the appstream/appstream2 branches (GNOME Software, Discover)
#      from the metainfo flatpak-builder composed into each build, the summary,
#      and pruning of everything older than KEEP_VERSIONS;
#   4. writes site/flatpak/ for the Pages deploy, with a .flatpakref and a
#      .flatpakrepo, and an empty file at each path Flatpak fetches that an
#      unsigned repo does not have (Pages' CDN answers a cache-cold missing
#      file with 503 about as often as 404, and Flatpak warns or fails on 503).
#
# Signing is optional. With FLATPAK_GPG_KEY (an ASCII-armored secret key with
# no passphrase, from the repository secret of that name) the commits and the
# summary are signed and the public key goes into the .flatpakref/.flatpakrepo
# as GPGKey=, so Flatpak verifies them. Without it the repo stays unsigned and
# those files carry no GPGKey, which tells Flatpak to skip the check for this
# remote. Owner steps: docs/RELEASES.md, "Signing the Flatpak remote".
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
FLATPAK_REMOTE_URL="${FLATPAK_REMOTE_URL:-https://dixonSolutions.github.io/Godnotic/flatpak}"
FLATPAK_APP_ID="${FLATPAK_APP_ID:-io.github.dixonSolutions.Godnotic}"
REPO_DIR="${REPO_DIR:-$ROOT/combined-repo}"
SITE_DIR="${SITE_DIR:-$ROOT/site/flatpak}"
X86_BUNDLE="${X86_BUNDLE:-}"
A64_BUNDLE="${A64_BUNDLE:-}"
PACKAGE_VERSION="${PACKAGE_VERSION:-}"
# Builds of each ref the remote keeps: the newest plus KEEP_VERSIONS-1 older.
KEEP_VERSIONS="${KEEP_VERSIONS:-5}"
# 1: publish a fresh repo when Pages has one that cannot be mirrored. That
# drops the history, the parent delta and the old-ID rebase for this release.
ALLOW_FRESH_REPO="${ALLOW_FRESH_REPO:-0}"
OSTREE="${OSTREE:-ostree}"
FLATPAK="${FLATPAK:-flatpak}"
ICON_URL="https://raw.githubusercontent.com/dixonSolutions/Godnotic/main/branding/godnotic-icon-128.png"

usage() {
    cat <<EOF
Usage: $(basename "$0") [--x86 BUNDLE] [--aarch64 BUNDLE] [--version VER]

Mirrors the OSTree repo at ${FLATPAK_REMOTE_URL}, commits the bundles onto its
current heads, generates static deltas and appstream, keeps the newest
KEEP_VERSIONS (${KEEP_VERSIONS}) commits of each ref and writes ${SITE_DIR}.
At least one bundle is required (the workflow passes both).
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --x86) X86_BUNDLE="$2"; shift 2 ;;
        --aarch64) A64_BUNDLE="$2"; shift 2 ;;
        --version) PACKAGE_VERSION="$2"; shift 2 ;;
        --repo) REPO_DIR="$2"; shift 2 ;;
        --site) SITE_DIR="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

BUNDLES=()
for b in "$X86_BUNDLE" "$A64_BUNDLE"; do
    [ -n "$b" ] || continue
    test -f "$b" || { echo "No such bundle: $b" >&2; exit 1; }
    BUNDLES+=("$(cd "$(dirname "$b")" && pwd)/$(basename "$b")")
done
[ "${#BUNDLES[@]}" -gt 0 ] || { echo "No bundle given (--x86, --aarch64)" >&2; exit 1; }
case "$KEEP_VERSIONS" in
    ''|*[!0-9]*|0) echo "KEEP_VERSIONS must be a number >= 1" >&2; exit 1 ;;
esac
PRUNE_DEPTH=$((KEEP_VERSIONS - 1))

command -v "$FLATPAK" >/dev/null
command -v "$OSTREE" >/dev/null
command -v curl >/dev/null

WORK="$(mktemp -d)"
cleanup() {
    if [ -d "$WORK/gnupg" ]; then gpgconf --homedir "$WORK/gnupg" --kill gpg-agent 2>/dev/null || true; fi
    rm -rf "$WORK"
}
trap cleanup EXIT

# --- Optional signing -------------------------------------------------------
GPG_ARGS=()
GPG_KEY_ID=""
GPG_KEY_B64=""
if [ -n "${FLATPAK_GPG_KEY:-}" ]; then
    command -v gpg >/dev/null
    export GNUPGHOME="$WORK/gnupg"
    mkdir -m 700 "$GNUPGHOME"
    printf '%s\n' "$FLATPAK_GPG_KEY" | gpg --batch --quiet --import
    GPG_KEY_ID="$(gpg --batch --with-colons --list-secret-keys | awk -F: '/^fpr:/ { print $10; exit }')"
    [ -n "$GPG_KEY_ID" ] || { echo "FLATPAK_GPG_KEY holds no secret key" >&2; exit 1; }
    # Fail here, not halfway through, when the key wants a passphrase.
    echo probe | gpg --batch --pinentry-mode error --local-user "$GPG_KEY_ID" --sign >/dev/null \
        || { echo "FLATPAK_GPG_KEY cannot sign without a passphrase" >&2; exit 1; }
    GPG_KEY_B64="$(gpg --batch --export "$GPG_KEY_ID" | base64 -w0)"
    GPG_ARGS=(--gpg-sign="$GPG_KEY_ID" --gpg-homedir="$GNUPGHOME")
    printf 'Signing with GPG key %s\n' "$GPG_KEY_ID"
else
    printf 'No FLATPAK_GPG_KEY: the repo stays unsigned\n'
fi

# --- 1. The current remote ----------------------------------------------------
new_repo() {
    rm -rf "$REPO_DIR"
    mkdir -p "$REPO_DIR"
    "$OSTREE" init --repo="$REPO_DIR" --mode=archive-z2
}
new_repo

# 200: there is a repo to build on. 404: none yet. Anything else (Pages down,
# 503 after retries, no network) must not be taken for "none": a fresh repo
# would drop the history and the old-ID rebase.
remote_status() {
    curl -sS --retry 4 --retry-delay 5 -o /dev/null -w '%{http_code}' "$FLATPAK_REMOTE_URL/config" 2>/dev/null || true
}

pull_existing_remote() {
    "$OSTREE" remote add --repo="$REPO_DIR" --no-gpg-verify origin "$FLATPAK_REMOTE_URL"
    local try
    for try in 1 2 3; do
        # Every ref the remote has (app, old-ID rebase, appstream), each with
        # PRUNE_DEPTH parents: the delta from the parent needs its files.
        if "$OSTREE" pull --repo="$REPO_DIR" --mirror --depth="$PRUNE_DEPTH" origin; then
            "$OSTREE" remote delete --repo="$REPO_DIR" origin
            return 0
        fi
        printf 'Mirror pull failed (attempt %s of 3)\n' "$try" >&2
        sleep $((try * 10))
    done
    "$OSTREE" remote delete --repo="$REPO_DIR" origin
    return 1
}

cannot_mirror() {
    if [ "$ALLOW_FRESH_REPO" != "1" ]; then
        echo "::error::Could not mirror the Flatpak remote at $FLATPAK_REMOTE_URL ($1). Publishing now would drop its history and the old-ID rebase: rerun the job, or set ALLOW_FRESH_REPO=1 to start over." >&2
        exit 1
    fi
    echo "::warning::Could not mirror $FLATPAK_REMOTE_URL ($1); starting a fresh repo (ALLOW_FRESH_REPO=1)" >&2
    new_repo
}

status="$(remote_status)"
case "$status" in
    200)
        printf 'Mirroring %s (newest %s commits of each ref)\n' "$FLATPAK_REMOTE_URL" "$KEEP_VERSIONS"
        pull_existing_remote || cannot_mirror "the pull failed"
        ;;
    404)
        printf 'No repo at %s yet: starting a fresh one\n' "$FLATPAK_REMOTE_URL"
        ;;
    *)
        cannot_mirror "config answered HTTP ${status:-nothing}"
        ;;
esac

# --- 2. The new builds, each on top of the head it replaces -----------------
STAGE="$WORK/stage"
"$OSTREE" init --repo="$STAGE" --mode=archive-z2
for b in "${BUNDLES[@]}"; do
    printf 'Importing %s\n' "$b"
    "$FLATPAK" build-import-bundle --no-update-summary "$STAGE" "$b"
done
mapfile -t NEW_REFS < <("$OSTREE" refs --repo="$STAGE" | grep '^app/' | sort)
[ "${#NEW_REFS[@]}" -gt 0 ] || { echo "The bundles hold no app ref" >&2; exit 1; }
for ref in "${NEW_REFS[@]}"; do
    old="$("$OSTREE" rev-parse --repo="$REPO_DIR" "$ref" 2>/dev/null || true)"
    # The new commit is a child of $ref's head in REPO_DIR, with the bundle's
    # subject, body and metadata.
    "$FLATPAK" build-commit-from --no-update-summary --src-repo="$STAGE" \
        ${GPG_ARGS[@]+"${GPG_ARGS[@]}"} "$REPO_DIR" "$ref"
    new="$("$OSTREE" rev-parse --repo="$REPO_DIR" "$ref")"
    if [ -z "$old" ]; then
        printf '%s: %s (first commit)\n' "$ref" "${new:0:12}"
    elif [ "$old" = "$new" ]; then
        printf '%s: unchanged at %s (same content as the published build)\n' "$ref" "${new:0:12}"
    else
        printf '%s: %s -> %s\n' "$ref" "${old:0:12}" "${new:0:12}"
    fi
done

# An install made under the app ID from before the rename follows it to this
# one: an end-of-life rebase on the old ref makes `flatpak update` switch the
# app over, and Flatpak carries ~/.var/app/<old id> across with it.
OLD_FLATPAK_APP_ID="${OLD_FLATPAK_APP_ID:-io.github.dixonSolutions.XonoticTouch}"
DELTA_IGNORE=()
if [ "$OLD_FLATPAK_APP_ID" != "$FLATPAK_APP_ID" ]; then
    # Nobody installs the old ID any more and the rebase is read from the
    # summary: deltas for it would only take room on Pages.
    # (The pattern matches the app ID, not the whole ref.)
    DELTA_IGNORE=(--static-delta-ignore-ref="$OLD_FLATPAK_APP_ID")
    for arch in x86_64 aarch64; do
        old_ref="app/${OLD_FLATPAK_APP_ID}/${arch}/master"
        if "$OSTREE" rev-parse --repo="$REPO_DIR" "$old_ref" >/dev/null 2>&1 \
            && ! "$OSTREE" show --repo="$REPO_DIR" --print-metadata-key=ostree.endoflife-rebase "$old_ref" >/dev/null 2>&1; then
            printf 'Rebasing %s onto %s\n' "$old_ref" "$FLATPAK_APP_ID"
            "$FLATPAK" build-commit-from --no-update-summary \
                ${GPG_ARGS[@]+"${GPG_ARGS[@]}"} \
                --end-of-life-rebase="${OLD_FLATPAK_APP_ID}=${FLATPAK_APP_ID}" \
                --src-ref="$old_ref" "$REPO_DIR" "$old_ref"
        fi
    done
fi

# Commits mirrored from the unsigned remote (or signed by an earlier key) get
# a signature too, so a verifying client can still roll back to them.
if [ -n "$GPG_KEY_ID" ]; then
    for ref in $("$OSTREE" refs --repo="$REPO_DIR"); do
        rev="$("$OSTREE" rev-parse --repo="$REPO_DIR" "$ref")"
        n=0
        while [ -n "$rev" ] && [ "$n" -le "$PRUNE_DEPTH" ] \
            && "$OSTREE" show --repo="$REPO_DIR" "$rev" >/dev/null 2>&1; do
            if ! out="$("$OSTREE" gpg-sign --repo="$REPO_DIR" --gpg-homedir="$GNUPGHOME" "$rev" "$GPG_KEY_ID" 2>&1)"; then
                case "$out" in *"already signed"*) ;; *) printf '%s\n' "$out" >&2; exit 1 ;; esac
            fi
            rev="$("$OSTREE" rev-parse --repo="$REPO_DIR" "$rev^" 2>/dev/null || true)"
            n=$((n + 1))
        done
    done
fi

# --- 3. Deltas, appstream, summary, pruning ---------------------------------
"$FLATPAK" build-update-repo "$REPO_DIR" \
    --title="Godnotic" \
    --comment="Touch-first Xonotic for Linux phones, tablets and PCs" \
    --homepage="https://github.com/dixonSolutions/Godnotic" \
    --icon="$ICON_URL" \
    --default-branch=master \
    --generate-static-deltas \
    ${DELTA_IGNORE[@]+"${DELTA_IGNORE[@]}"} \
    --prune --prune-depth="$PRUNE_DEPTH" \
    ${GPG_ARGS[@]+"${GPG_ARGS[@]}"}

for ref in "${NEW_REFS[@]}"; do
    printf '%s keeps:\n' "$ref"
    "$OSTREE" log --repo="$REPO_DIR" "$ref" | awk '/^commit /{c=$2} /^Date:/{print "  " c "  " $2 " " $3}'
done
printf 'Static deltas:\n'
"$OSTREE" static-delta list --repo="$REPO_DIR" | sed 's/^/  /'

# --- 4. The site ---------------------------------------------------------------
rm -rf "$SITE_DIR"
mkdir -p "$SITE_DIR"
# tmp/ holds the mirror's caches and state/ the local pull bookkeeping.
(cd "$REPO_DIR" && tar cf - --exclude=./tmp --exclude=./state --exclude=./.lock .) | (cd "$SITE_DIR" && tar xf -)

# Files Flatpak and OSTree fetch whether or not they exist: the summary's and
# each commit's signatures (summary.sig, summary.idx.sig, <commit>.commitmeta)
# and, for the commit being pulled, its static-delta index (none for an older
# commit, e.g. on `flatpak update --commit=<old>`). Each is a GVariant a{sv};
# an empty file is a valid empty one, which OSTree reads as "no signatures" or
# "no deltas", just as it reads a 404, minus Pages' 503s.
python3 -I - "$SITE_DIR" <<'PY'
import base64, pathlib, sys
site = pathlib.Path(sys.argv[1])
made = 0
def placeholder(p):
    global made
    if not p.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.touch()
        made += 1
placeholder(site / "summary.sig")
placeholder(site / "summary.idx.sig")
for sub in site.glob("summaries/*.gv"):
    placeholder(sub.with_name(sub.name + ".sig"))
for commit in site.glob("objects/*/*.commit"):
    checksum = commit.parent.name + commit.stem
    placeholder(commit.with_suffix(".commitmeta"))
    # OSTree's modified base64: no padding, '/' becomes '_'.
    b64 = base64.b64encode(bytes.fromhex(checksum)).decode().rstrip("=").replace("/", "_")
    placeholder(site / "delta-indexes" / b64[:2] / (b64[2:] + ".index"))
print(f"Wrote {made} empty signature, detached-metadata and delta-index files")
PY

GPGKEY_LINE=""
SIGNED_NOTE="The repository is not GPG-signed; these files tell Flatpak so."
if [ -n "$GPG_KEY_B64" ]; then
    GPGKEY_LINE="GPGKey=${GPG_KEY_B64}"
    SIGNED_NOTE="Commits and summary are GPG-signed with key <code>${GPG_KEY_ID}</code> (<a href=\"godnotic.gpg\">public key</a>), which these files carry."
    gpg --batch --export "$GPG_KEY_ID" > "$SITE_DIR/godnotic.gpg"
fi

# What `flatpak remote-add --from` and `flatpak install` read. Without GPGKey
# Flatpak turns signature checks off for this remote. The app's runtime comes
# from Flathub (RuntimeRepo).
{
    echo "[Flatpak Repo]"
    echo "Title=Godnotic"
    echo "Url=${FLATPAK_REMOTE_URL}/"
    echo "Homepage=https://github.com/dixonSolutions/Godnotic"
    echo "Comment=Touch-first Xonotic for Linux phones, tablets and PCs"
    echo "Icon=${ICON_URL}"
    if [ -n "$GPGKEY_LINE" ]; then echo "$GPGKEY_LINE"; fi
} > "$SITE_DIR/godnotic.flatpakrepo"
{
    echo "[Flatpak Ref]"
    echo "Name=${FLATPAK_APP_ID}"
    echo "Branch=master"
    echo "Title=Godnotic"
    echo "Url=${FLATPAK_REMOTE_URL}/"
    echo "SuggestRemoteName=godnotic"
    echo "RuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo"
    echo "IsRuntime=false"
    echo "Homepage=https://github.com/dixonSolutions/Godnotic"
    if [ -n "$GPGKEY_LINE" ]; then echo "$GPGKEY_LINE"; fi
} > "$SITE_DIR/${FLATPAK_APP_ID}.flatpakref"

VERSION_NOTE=""
if [ -n "$PACKAGE_VERSION" ]; then
    VERSION_NOTE="Latest build: <code>${PACKAGE_VERSION}</code><br>"
fi

cat > "$SITE_DIR/index.html" <<EOF
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><title>Godnotic Flatpak remote</title></head>
<body>
<h1>Godnotic Flatpak remote</h1>
<p>${VERSION_NOTE}Install with <a href="${FLATPAK_APP_ID}.flatpakref">${FLATPAK_APP_ID}.flatpakref</a>
(the runtime comes from Flathub), or add the remote with <a href="godnotic.flatpakrepo">godnotic.flatpakrepo</a>.
${SIGNED_NOTE}</p>
<pre>
flatpak install --user ${FLATPAK_REMOTE_URL}/${FLATPAK_APP_ID}.flatpakref

# or the remote first:
flatpak remote-add --user --if-not-exists --from godnotic ${FLATPAK_REMOTE_URL}/godnotic.flatpakrepo
flatpak install --user godnotic ${FLATPAK_APP_ID}

flatpak update --user ${FLATPAK_APP_ID}
</pre>
<p>The remote keeps the last ${KEEP_VERSIONS} builds, and an update downloads only what changed
since the previous one. To go back to an older build:</p>
<pre>
flatpak remote-info --user --log godnotic ${FLATPAK_APP_ID}
flatpak update --user --commit=&lt;commit&gt; ${FLATPAK_APP_ID}
</pre>
</body>
</html>
EOF

printf 'Flatpak remote staged at %s: %s\n' "$SITE_DIR" "$(du -sh "$SITE_DIR" | cut -f1)"
du -sh "$SITE_DIR/objects" "$SITE_DIR/deltas" 2>/dev/null | sed 's/^/  /' || true
