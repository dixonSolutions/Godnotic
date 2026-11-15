#!/bin/bash
# End-to-end test of scripts/publish-flatpak-remote.sh with a tiny fake app.
#
# A local HTTP server stands in for GitHub Pages. Four publishes run against
# it, each mirroring what the previous one deployed, and a throwaway --user
# installation (FLATPAK_USER_DIR, deleted at the end) installs and updates:
#
#   1. v1 (x86_64 + aarch64) on a Pages repo that only holds the pre-rename
#      app ID: fresh history, the old ID gets its end-of-life rebase, no delta.
#   2. v2: the new commit's parent is v1, a static delta v1 -> v2 exists, the
#      appstream branches carry v2's release, name and icon. The installed v1
#      updates through the delta (no loose file objects fetched), and
#      `flatpak update --commit=<v1>` rolls back.
#   3. v3 with KEEP_VERSIONS=2: v1 is pruned, v2 and v3 stay.
#   -  Pages unreachable: the publish stops rather than start a fresh repo.
#   4. v4 signed with a throwaway GPG key (skipped without gpg, or with
#      FLATPAK_TEST_GPG=0): summary and commits signed, GPGKey in the
#      .flatpakrepo, a verifying installation installs and rolls back.
#
# Every HTTP request is logged; the test fails if a client asked for a file
# the site does not have (Pages answers such requests with 503 at random).
#
# Needs flatpak, ostree, python3, curl (and gpg for step 4). In a distrobox,
# flatpak runs on the host: the script passes FLATPAK_USER_DIR through
# distrobox-host-exec explicitly and checks the installation is empty first.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBLISH="$ROOT/scripts/publish-flatpak-remote.sh"
SET_VERSION="$ROOT/scripts/set-package-version.sh"
APP=io.github.dixonSolutions.Godnotic
OLD_APP="${OLD_FLATPAK_APP_ID:-io.github.dixonSolutions.XonoticTouch}"
OSTREE="${OSTREE:-ostree}"

T="$(mktemp -d "${FLATPAK_TEST_TMP:-${TMPDIR:-/tmp}}/flatpak-remote-test.XXXXXX")"
PAGES="$T/pages"
USERDIR="$T/user"
SERVER_PID=""
cleanup() {
    [ -z "$SERVER_PID" ] || kill "$SERVER_PID" 2>/dev/null || true
    if [ -d "$T/test-gnupg" ]; then gpgconf --homedir "$T/test-gnupg" --kill gpg-agent 2>/dev/null || true; fi
    if [ "${KEEP_TEST_DIR:-0}" = 1 ]; then echo "kept $T"; else rm -rf "$T"; fi
}
trap cleanup EXIT
mkdir -p "$PAGES" "$USERDIR"

pass=0
fail=0
ok() { printf 'PASS %s\n' "$*"; pass=$((pass + 1)); }
bad() { printf 'FAIL %s\n' "$*"; fail=$((fail + 1)); }
check() { local what="$1"; shift; if "$@"; then ok "$what"; else bad "$what"; fi; }

# --- flatpak, in the throwaway installation only ----------------------------
FLATPAK_BIN="$(command -v flatpak)"
if [ "$(basename "$(readlink -f "$FLATPAK_BIN")")" = distrobox-host-exec ]; then
    # The host's flatpak gets no environment from the container.
    FP=(distrobox-host-exec env FLATPAK_USER_DIR="$USERDIR" flatpak)
    FP_TOOL=(distrobox-host-exec flatpak)
else
    FP=(env FLATPAK_USER_DIR="$USERDIR" flatpak)
    FP_TOOL=(flatpak)
fi
fp() { "${FP[@]}" "$@"; }
# A wrapper the publish script runs as $FLATPAK (repo tools only, no installation).
cat > "$T/flatpak-tool" <<EOF
#!/bin/sh
exec $(printf '%q ' "${FP_TOOL[@]}") "\$@"
EOF
chmod +x "$T/flatpak-tool"

if [ -n "$(fp --user remotes --columns=name 2>/dev/null)" ]; then
    echo "FLATPAK_USER_DIR did not reach flatpak (it lists remotes): refusing to touch a real installation" >&2
    exit 1
fi

# --- Static checks: runtime version, metainfo stamping ---------------------
rt="$(sed -n "s/^runtime-version: '\(.*\)'/\1/p" "$ROOT/flatpak/$APP.yml")"
check "manifest runtime GNOME $rt matches the Flatpak job's container" \
    grep -q "flatpak-github-actions:gnome-$rt\$" "$ROOT/.github/workflows/build-and-publish.yml"
check "loader and launcher workflows build against GNOME $rt" \
    sh -c "grep -q \"XT_GNOME_VERSION: '$rt'\" '$ROOT/.github/workflows/loader.yml' && grep -q \"XT_GNOME_VERSION: '$rt'\" '$ROOT/.github/workflows/launcher.yml'"
mkdir -p "$T/stamp/flatpak"
cp "$ROOT/flatpak/$APP.metainfo.xml" "$T/stamp/flatpak/"
ROOT="$T/stamp" PACKAGE_DATE=2026-11-15 bash "$SET_VERSION" 1.2.999 >/dev/null
ROOT="$T/stamp" bash "$SET_VERSION" 1.2.999 >/dev/null
meta="$T/stamp/flatpak/$APP.metainfo.xml"
check "CI stamps the release once, newest first, with its release page" \
    sh -c "[ \$(grep -c 'release version=\"1.2.999\"' '$meta') = 1 ] && grep -m1 '<release ' '$meta' | grep -q 1.2.999 && grep -q 'releases/tag/v1.2.999' '$meta'"
check "stage-flatpak.sh still reads the stamped version" \
    [ "$(sed -n 's|.*Automated CI build \(.*\)\.</p>.*|\1|p' "$meta" | head -n 1)" = 1.2.999 ]
check "metainfo has screenshots with images" grep -q '<image type="source"' "$meta"

# --- A Pages stand-in that logs every request -------------------------------
cat > "$T/server.py" <<'PY'
import http.server, os, socketserver, sys
root, log, portfile = sys.argv[1], sys.argv[2], sys.argv[3]
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=root, **k)
    def log_request(self, code="-", size="-"):
        with open(log, "a") as f:
            f.write(f"{code} {size} {self.path}\n")
    def log_message(self, *a):
        pass
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()
    def copyfile(self, src, dst):
        n = os.fstat(src.fileno()).st_size if hasattr(src, "fileno") else 0
        with open(log, "a") as f:
            f.write(f"bytes {n} {self.path}\n")
        super().copyfile(src, dst)
class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
s = S(("127.0.0.1", 0), H)
open(portfile, "w").write(str(s.server_address[1]))
s.serve_forever()
PY
python3 -I "$T/server.py" "$PAGES" "$T/http.log" "$T/port" &
SERVER_PID=$!
for _ in $(seq 50); do [ -s "$T/port" ] && break; sleep 0.1; done
URL="http://127.0.0.1:$(cat "$T/port")"
: > "$T/http.log"
log_mark() { : > "$T/http.log"; }
misses() { awk '$1 == "404" { print $3 }' "$T/http.log" | sort -u; }
fetched_bytes() { awk '$1 == "bytes" { s += $2 } END { print s + 0 }' "$T/http.log"; }

# --- Fake builds ---------------------------------------------------------------
python3 -I -c 'import random,sys; random.seed(7); sys.stdout.buffer.write(random.randbytes(3 << 20))' > "$T/big.bin"
python3 -I -c '
import struct, sys, zlib
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
raw = b"".join(b"\0" + b"\x30\x80\xc0" * 64 for _ in range(64))
sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
' > "$T/icon.png"

# make_bundle ID VERSION ARCH [EXTRA_METADATA]
make_bundle() {
    local id="$1" ver="$2" arch="$3" d="$T/build/$1-$2-$3"
    rm -rf "$d"
    mkdir -p "$d/files/bin" "$d/files/share/app-info/xmls" \
        "$d/files/share/app-info/icons/flatpak/64x64" "$d/export"
    cat > "$d/metadata" <<EOF
[Application]
name=$id
runtime=org.gnome.Platform/$arch/50
sdk=org.gnome.Sdk/$arch/50
command=godnotic-test
EOF
    printf '#!/bin/sh\necho %s\n' "$ver" > "$d/files/bin/godnotic-test"
    chmod 755 "$d/files/bin/godnotic-test"
    cp "$T/big.bin" "$d/files/share/big.bin"          # the same in every version
    echo "$ver" > "$d/files/share/version.txt"         # the only file that changes
    cp "$T/icon.png" "$d/files/share/app-info/icons/flatpak/64x64/$id.png"
    gzip -9n > "$d/files/share/app-info/xmls/$id.xml.gz" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<components version="0.8" origin="flatpak">
  <component type="desktop-application">
    <id>$id</id>
    <name>Godnotic</name>
    <summary>Touch arena FPS for Linux phones, tablets and PCs</summary>
    <description><p>Test build $ver.</p></description>
    <icon type="cached" width="64" height="64">$id.png</icon>
    <categories><category>Game</category></categories>
    <releases><release version="$ver" date="2026-11-15"/></releases>
    <bundle type="flatpak">app/$id/$arch/master</bundle>
  </component>
</components>
EOF
    rm -rf "$T/build-repo-$arch"
    "$T/flatpak-tool" build-export --no-update-summary --arch="$arch" \
        --subject="Built from test $ver" "$T/build-repo-$arch" "$d" master >/dev/null
    "$T/flatpak-tool" build-bundle --arch="$arch" "$T/build-repo-$arch" \
        "$T/$id-$ver-$arch.flatpak" "$id" master
}

publish() {
    local ver="$1"; shift
    rm -rf "$T/site"
    env "$@" ROOT="$T" OSTREE="$OSTREE" FLATPAK="$T/flatpak-tool" \
        FLATPAK_REMOTE_URL="$URL" FLATPAK_APP_ID="$APP" OLD_FLATPAK_APP_ID="$OLD_APP" \
        REPO_DIR="$T/combined-repo" SITE_DIR="$T/site/flatpak" \
        bash "$PUBLISH" --x86 "$T/$APP-$ver-x86_64.flatpak" \
            --aarch64 "$T/$APP-$ver-aarch64.flatpak" --version "$ver" > "$T/publish-$ver.log" 2>&1 \
        || { cat "$T/publish-$ver.log"; return 1; }
    # The Pages deploy: the site replaces what was served.
    rm -rf "$PAGES"
    cp -a "$T/site/flatpak" "$PAGES"
}

rev() { "$OSTREE" rev-parse --repo="$PAGES" "$1"; }
parent_of() { "$OSTREE" rev-parse --repo="$PAGES" "$1^" 2>/dev/null || echo none; }
has_commit() { "$OSTREE" show --repo="$PAGES" "$1" >/dev/null 2>&1; }
installed() { fp --user info --show-commit "$APP" 2>/dev/null; }
ARCH="$(fp --default-arch)"

for v in 1.0 2.0 3.0 4.0; do
    make_bundle "$APP" "$v" x86_64
    make_bundle "$APP" "$v" aarch64
done

# --- 0. Pages before: only the pre-rename ID -----------------------------------
make_bundle "$OLD_APP" 0.9 x86_64
"$OSTREE" init --repo="$PAGES" --mode=archive-z2
"$T/flatpak-tool" build-import-bundle --no-update-summary "$PAGES" "$T/$OLD_APP-0.9-x86_64.flatpak" >/dev/null
"$T/flatpak-tool" build-update-repo "$PAGES" >/dev/null

# --- 1. v1 -------------------------------------------------------------------------
publish 1.0
V1="$(rev "app/$APP/$ARCH/master")"
check "v1 published as the first commit (no parent)" [ "$(parent_of "$V1")" = none ]
check "old ID carries an end-of-life rebase to $APP" \
    sh -c "'$OSTREE' show --repo='$PAGES' --print-metadata-key=ostree.endoflife-rebase 'app/$OLD_APP/x86_64/master' | grep -q '$APP'"
check "no static delta for the old ID" \
    sh -c "! '$OSTREE' static-delta list --repo='$PAGES' | grep -q '$(rev "app/$OLD_APP/x86_64/master")'"
check ".flatpakrepo and .flatpakref without GPGKey" \
    sh -c "! grep -q GPGKey '$PAGES/godnotic.flatpakrepo' '$PAGES/$APP.flatpakref'"

log_mark
fp --user remote-add --from godnotic "$URL/godnotic.flatpakrepo"
fp --user install -y --noninteractive --no-deps --no-related godnotic "$APP" >/dev/null
check "v1 installs" [ "$(installed)" = "$V1" ]
m="$(misses)"; check "no missing file requested by remote-add/install${m:+: $m}" [ -z "$m" ]

# --- 2. v2 -------------------------------------------------------------------------
log_mark
publish 2.0
m="$(misses)"; check "mirror pull asked for no missing file${m:+: $m}" [ -z "$m" ]
V2="$(rev "app/$APP/$ARCH/master")"
check "v2's parent is v1" [ "$(parent_of "$V2")" = "$V1" ]
check "remote-info --show-parent says v1" \
    sh -c "$(printf '%q ' "${FP[@]}") --user remote-info --show-parent godnotic $APP | grep -qx '$V1'"
check "remote-info --log lists v1" \
    sh -c "$(printf '%q ' "${FP[@]}") --user remote-info --log godnotic $APP | grep -q '${V1:0:12}'"
check "static delta v1 -> v2" sh -c "'$OSTREE' static-delta list --repo='$PAGES' | grep -qx '$V1-$V2'"
check "from-scratch delta for v2 (new installs)" sh -c "'$OSTREE' static-delta list --repo='$PAGES' | grep -qx '$V2'"
for a in x86_64 aarch64; do
    check "appstream/$a and appstream2/$a refs" sh -c "'$OSTREE' rev-parse --repo='$PAGES' appstream/$a >/dev/null && '$OSTREE' rev-parse --repo='$PAGES' appstream2/$a >/dev/null"
    xml="$("$OSTREE" cat --repo="$PAGES" "appstream2/$a" /appstream.xml | tr -d '\n')"
    check "appstream2/$a: name, release 2.0, icon" \
        sh -c "printf '%s' '$xml' | grep -q '<name>Godnotic</name>' && printf '%s' '$xml' | grep -q 'version=\"2.0\"' && '$OSTREE' ls --repo='$PAGES' appstream2/$a /icons/64x64/$APP.png >/dev/null"
done
check "summary carries the repo title" sh -c "'$OSTREE' summary --repo='$PAGES' --view | grep -q \"xa.title.*Godnotic\""

log_mark
fp --user update -y --noninteractive --no-deps --no-related "$APP" >/dev/null
check "v1 updates to v2" [ "$(installed)" = "$V2" ]
check "the update used the v1 -> v2 delta" grep -q "^200 .*/deltas/.*/superblock" "$T/http.log"
check "the update fetched no loose file objects" sh -c "! grep -q '^200 .*\\.filez' '$T/http.log'"
got="$(fetched_bytes)"
check "the update fetched ${got} bytes, well under the unchanged 3 MiB file" [ "$got" -lt 1000000 ]
m="$(misses)"; check "no missing file requested by the update${m:+: $m}" [ -z "$m" ]

log_mark
fp --user update -y --noninteractive --appstream godnotic >/dev/null 2>&1 || true
check "flatpak search finds the app through the appstream branch" \
    sh -c "$(printf '%q ' "${FP[@]}") --user search --columns=application Godnotic | grep -qx '$APP'"

log_mark
fp --user update -y --noninteractive --no-deps --no-related --commit="$V1" "$APP" >/dev/null
check "rollback to v1 with update --commit" [ "$(installed)" = "$V1" ]
fp --user update -y --noninteractive --no-deps --no-related "$APP" >/dev/null
check "and forward again to v2" [ "$(installed)" = "$V2" ]
m="$(misses)"; check "no missing file requested by rollback/forward${m:+: $m}" [ -z "$m" ]

# --- 3. v3, keeping two versions --------------------------------------------------
publish 3.0 KEEP_VERSIONS=2
V3="$(rev "app/$APP/$ARCH/master")"
check "v3's parent is v2" [ "$(parent_of "$V3")" = "$V2" ]
check "KEEP_VERSIONS=2 pruned v1" sh -c "! '$OSTREE' show --repo='$PAGES' '$V1' >/dev/null 2>&1"
check "KEEP_VERSIONS=2 kept v2" has_commit "$V2"
check "old ID still there with its rebase" \
    sh -c "'$OSTREE' show --repo='$PAGES' --print-metadata-key=ostree.endoflife-rebase 'app/$OLD_APP/x86_64/master' | grep -q '$APP'"
check "the v1 -> v2 delta went with v1" sh -c "! '$OSTREE' static-delta list --repo='$PAGES' | grep -q '^$V1'"

# --- Pages unreachable: no fresh start ---------------------------------------
dead="$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
if env ROOT="$T" OSTREE="$OSTREE" FLATPAK="$T/flatpak-tool" FLATPAK_REMOTE_URL="http://127.0.0.1:$dead" \
    REPO_DIR="$T/combined-repo" SITE_DIR="$T/site-dead/flatpak" \
    bash "$PUBLISH" --x86 "$T/$APP-4.0-x86_64.flatpak" > "$T/publish-dead.log" 2>&1; then
    bad "a publish with Pages unreachable went ahead with a fresh repo"
else
    check "a publish with Pages unreachable stops instead of starting a fresh repo" \
        grep -q "Could not mirror" "$T/publish-dead.log"
fi

# --- 4. v4, signed -------------------------------------------------------------------
if [ "${FLATPAK_TEST_GPG:-1}" = 1 ] && command -v gpg >/dev/null; then
    # A throwaway key for this test only; it lives and dies in $T.
    export GNUPGHOME="$T/test-gnupg"
    mkdir -m 700 "$GNUPGHOME"
    gpg --batch --quiet --passphrase '' --quick-gen-key "Godnotic test key (throwaway) <test@invalid>" ed25519 sign never
    KEY="$(gpg --batch --armor --export-secret-keys)"
    unset GNUPGHOME
    publish 4.0 FLATPAK_GPG_KEY="$KEY"
    V4="$(rev "app/$APP/$ARCH/master")"
    check "v4's parent is v3" [ "$(parent_of "$V4")" = "$V3" ]
    check "summary is signed (non-empty summary.sig)" test -s "$PAGES/summary.sig"
    check ".flatpakrepo and .flatpakref carry GPGKey" sh -c "grep -q '^GPGKey=' '$PAGES/godnotic.flatpakrepo' && grep -q '^GPGKey=' '$PAGES/$APP.flatpakref'"
    fp --user remote-add --from signed "$URL/godnotic.flatpakrepo"
    check "the remote added from the .flatpakrepo verifies signatures" \
        sh -c "! $(printf '%q ' "${FP[@]}") --user remotes --show-details | grep '^signed' | grep -q no-gpg-verify"
    fp --user uninstall -y --noninteractive "$APP" >/dev/null
    log_mark
    fp --user install -y --noninteractive --no-deps --no-related signed "$APP" >/dev/null
    check "signed v4 installs with verification on" [ "$(installed)" = "$V4" ]
    fp --user update -y --noninteractive --no-deps --no-related --commit="$V3" "$APP" >/dev/null
    check "rollback to v3 (mirrored, signed afterwards) with verification on" [ "$(installed)" = "$V3" ]
    m="$(misses)"; check "no missing file requested by the signed install/rollback${m:+: $m}" [ -z "$m" ]
else
    echo "SKIP signed publish (no gpg, or FLATPAK_TEST_GPG=0)"
fi

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
