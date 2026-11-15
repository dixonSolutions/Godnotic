#!/usr/bin/env bash
# AndySU live channels: keep each channel manifest's app id equal to andysu.toml's, and check a
# published channel the way an installed host checks it.
#
# Why: `andysu publish` (AndySU 0.1.135) keeps the app id of the manifest it adds a release to;
# only a brand-new manifest takes the id from the payload. The live channels were created before
# the rename, so every manifest published since kept the pre-rename id, and every Godnotic host
# rejected it (WrongApp) and never updated. See docs/ANDROID.md, "Live updates: channel manifests".
#
#   andysu-channel.sh app
#       Print the app id from andysu.toml.
#   andysu-channel.sh repair <site> <channel> <app>
#       If <site>/<channel>/update.json names another app: keep the releases whose payload
#       descriptor names <app>, set the manifest's app, and re-sign it with `andysu sign`
#       (sequence + 1, a fresh 30 days). Prints "unchanged", "repaired", or "reset" (no release
#       named <app>: the manifest is deleted, so the next publish starts the channel afresh).
#   andysu-channel.sh check <manifest path or URL> <channel> <app> [--release ID] [--after SEQ]
#       `andysu verify --app --channel` (signature, app, channel, expiry: the host's rules), then
#       every listed release's payload descriptor names <app>, every component of the newest
#       release can be fetched, the newest release is ID, and the sequence is above SEQ.
#   andysu-channel.sh gh-repair <channel> [--release ID]
#       `repair` on the GitHub channel release channel-<channel>: download, repair, check, upload
#       (or delete the manifest assets on "reset"). Writes sequence=<n> (0: no manifest) and
#       has_release=true|false (ID already listed) to $GITHUB_OUTPUT.
#   andysu-channel.sh gh-check <channel> [--release ID] [--after SEQ]
#       `check` on the channel release's assets (through the API) and on the public manifest URL
#       hosts fetch.
#
# Environment: ANDYSU_CLI (default: andysu on PATH); ANDYSU_ROOT_PUB (trusted root, default
# keys/godnotic-public.pem); ANDYSU_KEY_FILE and ANDYSU_CERT_FILE, or ANDYSU_SIGNING_KEY and
# ANDYSU_SIGNING_CERT (contents), sign a repair; GITHUB_REPOSITORY (default
# dixonSolutions/Godnotic); ANDYSU_GITHUB_URL (default https://github.com) is where hosts fetch
# release assets from, as in AndySU's publish action.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cli="${ANDYSU_CLI:-andysu}"
pub="${ANDYSU_ROOT_PUB:-$root/keys/godnotic-public.pem}"
repo="${GITHUB_REPOSITORY:-dixonSolutions/Godnotic}"
github="${ANDYSU_GITHUB_URL:-https://github.com}"

tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT
newtmp() { mktemp -d -p "$tmp_root"; }

die() { echo "andysu-channel: $*" >&2; exit 1; }

app_id() {
  python3 - "$root/andysu.toml" <<'PY'
import sys, tomllib
print(tomllib.load(open(sys.argv[1], "rb"))["app"])
PY
}

# Payload descriptors and component reachability. Relative URLs resolve against the manifest's
# own location, as the host resolves them.
inspect() { # manifest-file base-url app mode(filter|check) [release] [after]
  python3 - "$@" <<'PY'
import hashlib, json, os, sys, time, urllib.parse, urllib.request
path, base, app, mode = sys.argv[1:5]
want = sys.argv[5] if len(sys.argv) > 5 else ""
after = sys.argv[6] if len(sys.argv) > 6 else ""
DESC = "andysu-payload.json"

def urls(c, default):
    return [urllib.parse.urljoin(base, u) for u in (c.get("urls") or [default])]

def fetch(u, head=False):
    if u.startswith("file:"):
        p = urllib.request.url2pathname(urllib.parse.urlparse(u).path)
        if head:
            return os.path.getsize(p)
        return open(p, "rb").read()
    last = None
    for i in range(4):
        try:
            req = urllib.request.Request(u, method="HEAD" if head else "GET",
                                         headers={"User-Agent": "godnotic-andysu-channel"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return int(r.headers.get("Content-Length") or -1) if head else r.read()
        except Exception as e:  # transient 5xx and timeouts: GitHub sheds anonymous load
            last = e
            time.sleep(5 * (i + 1))
    raise SystemExit(f"cannot fetch {u}: {last}")

def descriptor_app(r):
    d = [c for c in r["components"] if c["path"] == DESC]
    if not d:
        return None
    d = d[0]
    b = fetch(urls(d, f"../blobs/sha256/{d['sha256']}")[0])
    if hashlib.sha256(b).hexdigest() != d["sha256"]:
        raise SystemExit(f"release {r['id']}: descriptor does not match its sha256")
    return json.loads(b).get("app")

m = json.load(open(path))
if mode == "filter":
    if m["app"] == app:
        print("unchanged")
        sys.exit(0)
    keep = []
    for r in m["releases"]:
        got = descriptor_app(r)
        print(f"  release {r['id']} seq {r['sequence']}: payload app {got}"
              + ("" if got == app else " (dropped)"), file=sys.stderr)
        if got == app:
            keep.append(r)
    print(f"  manifest app {m['app']} -> {app}", file=sys.stderr)
    if not keep:
        print("reset")
        sys.exit(0)
    m["app"] = app
    m["releases"] = keep
    with open(path, "w") as f:
        json.dump(m, f, indent=2)
        f.write("\n")
    print("repaired")
    sys.exit(0)

errors = []
for r in m["releases"]:
    got = descriptor_app(r)
    if got != app:
        errors.append(f"release {r['id']}: payload app {got}, expected {app}")
newest = max(m["releases"], key=lambda r: r["sequence"]) if m["releases"] else None
if newest is None:
    errors.append("the manifest lists no release")
else:
    for c in newest["components"]:
        u = urls(c, f"../blobs/sha256/{c['sha256']}")[0]
        n = fetch(u, head=True)
        if n not in (-1, c["size"]):
            errors.append(f"release {newest['id']}: {c['path']} is {n} bytes at {u}, expected {c['size']}")
    print(f"  newest release {newest['id']} seq {newest['sequence']}: {len(newest['components'])} components reachable,"
          f" payload descriptors name {app}")
    if want and newest["id"] != want:
        errors.append(f"newest release is {newest['id']}, expected {want}")
if after and int(m["sequence"]) <= int(after):
    errors.append(f"manifest sequence {m['sequence']} is not above {after} (a channel published afresh"
                  " or a stale manifest: hosts that saw the higher sequence reject it)")
for e in errors:
    print(f"FAIL: {e}", file=sys.stderr)
sys.exit(1 if errors else 0)
PY
}

signing_files() { # writes the key (and certificate) to files when only their contents are given
  if [ -z "${ANDYSU_KEY_FILE:-}" ]; then
    [ -n "${ANDYSU_SIGNING_KEY:-}" ] || die "re-signing needs ANDYSU_KEY_FILE or ANDYSU_SIGNING_KEY"
    keys="$(newtmp)"
    (umask 077; printf '%s\n' "$ANDYSU_SIGNING_KEY" > "$keys/key")
    export ANDYSU_KEY_FILE="$keys/key"
    if [ -n "${ANDYSU_SIGNING_CERT:-}" ]; then
      (umask 077; printf '%s\n' "$ANDYSU_SIGNING_CERT" > "$keys/cert.json")
      export ANDYSU_CERT_FILE="$keys/cert.json"
    fi
  fi
}

repair() { # site channel app
  local site="$1" channel="$2" app="$3" manifest state
  manifest="$site/$channel/update.json"
  [ -f "$manifest" ] || die "no $manifest"
  state="$(inspect "$manifest" "file://$(cd "$(dirname "$manifest")" && pwd)/update.json" "$app" filter)"
  case "$state" in
    unchanged) ;;
    repaired)
      signing_files
      "$cli" sign --site "$site" --channel "$channel" --expires-days 30 >&2 ;;
    reset) rm -f "$manifest" "$manifest.sig" ;;
    *) die "unexpected state $state" ;;
  esac
  echo "$state"
}

check() { # src channel app [--release ID] [--after SEQ]
  local src="$1" channel="$2" app="$3" release="" after="" i out base file tmp
  shift 3
  while [ $# -gt 0 ]; do
    case "$1" in
      --release) release="$2"; shift 2 ;;
      --after) after="$2"; shift 2 ;;
      *) die "check: unknown argument $1" ;;
    esac
  done
  for i in 1 2 3 4; do
    if out="$("$cli" verify "$src" --pub "$pub" --app "$app" --channel "$channel" 2>&1)"; then break; fi
    echo "$out" >&2
    # The host's rules failed: retrying cannot help. Anything else may be the network.
    case "$out" in *"manifest app"*|*"manifest channel"*|*expired*|*ignature*) die "$src fails the host's checks" ;; esac
    [ "$i" -lt 4 ] || die "$src fails the host's checks"
    sleep $((i * 10))
  done
  echo "$out"
  if [[ "$src" == *://* ]]; then
    tmp="$(newtmp)"
    python3 - "$src" "$tmp/update.json" <<'PY'
import sys, time, urllib.request
for i in range(4):
    try:
        req = urllib.request.Request(sys.argv[1], headers={"User-Agent": "godnotic-andysu-channel"})
        open(sys.argv[2], "wb").write(urllib.request.urlopen(req, timeout=60).read())
        break
    except Exception as e:
        if i == 3:
            raise SystemExit(f"cannot fetch {sys.argv[1]}: {e}")
        time.sleep(5 * (i + 1))
PY
    file="$tmp/update.json" base="$src"
  else
    file="$src" base="file://$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
  fi
  inspect "$file" "$base" "$app" check "$release" "$after"
}

download_channel() { # channel site: 0 when the channel release has a manifest
  mkdir -p "$2/$1"
  local i
  for i in 1 2 3 4; do
    if gh release download "channel-$1" --repo "$repo" --dir "$2/$1" --clobber \
        --pattern update.json --pattern update.json.sig 2>"$2/gh.err"; then
      [ -f "$2/$1/update.json" ] && [ -f "$2/$1/update.json.sig" ] && return 0
      return 1
    fi
    # No channel release yet, or one without a manifest: the first publish creates it.
    if grep -qiE 'release not found|no assets to download|no asset|not found' "$2/gh.err"; then return 1; fi
    cat "$2/gh.err" >&2
    [ "$i" -lt 4 ] || die "cannot download the channel-$1 manifest"
    sleep $((i * 15))
  done
}

output() { [ -z "${GITHUB_OUTPUT:-}" ] || echo "$1" >> "$GITHUB_OUTPUT"; }

gh_repair() { # channel [--release ID]
  local channel="$1" release="" app site state seq has=false
  shift
  [ "${1:-}" != --release ] || release="$2"
  app="$(app_id)"
  site="$(newtmp)"
  if ! download_channel "$channel" "$site"; then
    echo "$channel: no channel manifest yet; the first publish creates it for $app"
    output sequence=0
    output has_release=false
    return 0
  fi
  state="$(repair "$site" "$channel" "$app")"
  case "$state" in
    unchanged) echo "$channel: manifest already names $app" ;;
    repaired)
      check "$site/$channel/update.json" "$channel" "$app"
      gh release upload "channel-$channel" --repo "$repo" --clobber \
        "$site/$channel/update.json" "$site/$channel/update.json.sig"
      echo "$channel: manifest now names $app (re-signed, uploaded)" ;;
    reset)
      gh release delete-asset "channel-$channel" update.json --repo "$repo" -y
      gh release delete-asset "channel-$channel" update.json.sig --repo "$repo" -y
      echo "$channel: no release names $app; manifest removed, the next publish starts the channel afresh"
      output sequence=0
      output has_release=false
      return 0 ;;
  esac
  seq="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sequence"])' "$site/$channel/update.json")"
  if [ -n "$release" ] && python3 -c 'import json,sys; sys.exit(0 if sys.argv[2] in [r["id"] for r in json.load(open(sys.argv[1]))["releases"]] else 1)' \
      "$site/$channel/update.json" "$release"; then
    has=true
  fi
  output "sequence=$seq"
  output "has_release=$has"
}

gh_check() { # channel [--release ID] [--after SEQ]
  local channel="$1" app site
  shift
  app="$(app_id)"
  site="$(newtmp)"
  download_channel "$channel" "$site" || die "channel-$channel has no manifest"
  echo "== channel-$channel release assets"
  check "$site/$channel/update.json" "$channel" "$app" "$@"
  echo "== $github/$repo/releases/download/channel-$channel/update.json (what hosts fetch)"
  check "$github/$repo/releases/download/channel-$channel/update.json" "$channel" "$app" "$@"
}

cmd="${1:-}"
[ $# -eq 0 ] || shift
case "$cmd" in
  app) app_id ;;
  repair) [ $# -eq 3 ] || die "usage: repair <site> <channel> <app>"; repair "$@" ;;
  check) [ $# -ge 3 ] || die "usage: check <manifest> <channel> <app> [--release ID] [--after SEQ]"; check "$@" ;;
  gh-repair) [ $# -ge 1 ] || die "usage: gh-repair <channel> [--release ID]"; gh_repair "$@" ;;
  gh-check) [ $# -ge 1 ] || die "usage: gh-check <channel> [--release ID] [--after SEQ]"; gh_check "$@" ;;
  *) sed -n '2,/^set -euo/p' "$0" | sed '$d' >&2; exit 2 ;;
esac
