#!/usr/bin/env bash
# The live channels' app id, end to end and offline: AndySU's own publish action (pinned
# publish.sh) against a stand-in `gh` whose release assets are served over HTTP like
# github.com/<repo>/releases/download/<tag>/<asset>, a throwaway signing key, and AndySU's
# simulated client as the installed host.
#
#   1. A channel created under an earlier app id keeps that id through later publishes (the
#      AndySU bug), so hosts of the current id refuse it (wrong_app).
#   2. scripts/andysu-channel.sh gh-repair rewrites the id and re-signs; gh-check passes; a host
#      stages from it; the next publish keeps the right id; the post-publish guard catches a
#      channel started afresh.
#   3. A channel with no release of this app is reset, and the next publish starts it right.
#   4. The release and repair workflows call the repair before publishing and the check after.
#
# Needs the AndySU CLI and its publish action script at the pinned version: ANDYSU_CLI and
# ANDYSU_PUBLISH_SH, or an authenticated `gh` to fetch both. No secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
version=0.1.135
t="$(mktemp -d)"
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true; rm -rf "$t"' EXIT

retry() { local i; for i in 1 2 3 4; do "$@" && return 0; [ "$i" -lt 4 ] || return 1; sleep $((i * 10)); done; }
if [ -z "${ANDYSU_CLI:-}" ]; then
  if command -v andysu >/dev/null; then
    ANDYSU_CLI="$(command -v andysu)"
  else
    retry gh release download "v$version" --repo dixonSolutions/AndySU --dir "$t" --clobber \
      --pattern andysu-x86_64-unknown-linux-musl
    ANDYSU_CLI="$t/andysu-x86_64-unknown-linux-musl"
    chmod +x "$ANDYSU_CLI"
  fi
fi
if [ -z "${ANDYSU_PUBLISH_SH:-}" ]; then
  publish_sh() {
    gh api "repos/dixonSolutions/AndySU/contents/actions/publish/publish.sh?ref=v$version" \
      -H 'Accept: application/vnd.github.raw' > "$t/publish.sh"
  }
  retry publish_sh
  ANDYSU_PUBLISH_SH="$t/publish.sh"
fi
export ANDYSU_CLI
cli="$ANDYSU_CLI"
NEW="$(bash scripts/andysu-channel.sh app)"
OLD=io.github.example.beforerename
REPO=owner/godnotic-test
export GITHUB_REPOSITORY="$REPO"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }
json() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))" "$@"; }

# Throwaway keys, signed like the real releases: a release key certified by a root.
"$cli" keygen --dir "$t/keys" --name root >/dev/null
"$cli" keygen --dir "$t/keys" --name rel --release-from "$t/keys/root-root.key" >/dev/null
export ANDYSU_ROOT_PUB="$t/keys/root-public.pem"
export ANDYSU_KEY_FILE="$t/keys/rel-release.key" ANDYSU_CERT_FILE="$t/keys/rel-release.cert.json"

# Stand-in gh: release download|upload|view|create|edit|delete-asset over $GH_ROOT.
export GH_ROOT="$t/github"
mkdir -p "$GH_ROOT" "$t/bin"
cat > "$t/bin/gh" <<'GH'
#!/bin/bash
set -euo pipefail
[ "$1" = release ] || { echo "gh stand-in: unsupported $*" >&2; exit 1; }
sub="$2"; tag="$3"; shift 3
repo="" dir="" files=() patterns=()
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) repo="$2"; shift 2 ;;
    --dir) dir="$2"; shift 2 ;;
    --pattern) patterns+=("$2"); shift 2 ;;
    --title|--notes) shift 2 ;;
    --latest|--latest=*|--prerelease|--prerelease=*|--clobber|-y) shift ;;
    *) files+=("$1"); shift ;;
  esac
done
d="$GH_ROOT/$repo/releases/download/$tag"
case "$sub" in
  view|edit) [ -d "$d" ] || { echo "release not found" >&2; exit 1; } ;;
  create) mkdir -p "$d"; for f in "${files[@]}"; do cp "$f" "$d/"; done ;;
  upload)
    [ -d "$d" ] || { echo "release not found" >&2; exit 1; }
    for f in "${files[@]}"; do cp "$f" "$d/"; done ;;
  delete-asset)
    [ -f "$d/${files[0]}" ] || { echo "asset not found" >&2; exit 1; }
    rm "$d/${files[0]}" ;;
  download)
    [ -d "$d" ] || { echo "release not found" >&2; exit 1; }
    n=0
    for p in "${patterns[@]}"; do
      for f in "$d"/$p; do [ -f "$f" ] && cp "$f" "$dir/" && n=$((n + 1)); done
    done
    [ "$n" -gt 0 ] || { echo "no assets match the file pattern" >&2; exit 1; } ;;
  *) echo "gh stand-in: unsupported release $sub" >&2; exit 1 ;;
esac
GH
chmod +x "$t/bin/gh"
real_path="$PATH"
export PATH="$t/bin:$PATH"

port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
"$cli" serve "$GH_ROOT" --addr "127.0.0.1:$port" >/dev/null 2>&1 &
pids+=($!)
export ANDYSU_GITHUB_URL="http://127.0.0.1:$port"
sleep 0.3

payload() { # dir app version
  mkdir -p "$1/dex" "$1/assets"
  head -c 65536 /dev/zero | tr '\0' 'a' > "$1/assets/data.bin"
  printf 'v%s' "$3" >> "$1/assets/data.bin"
  printf 'dex %s' "$3" > "$1/dex/classes.dex"
  printf '{"schema": 1, "app": "%s", "version": "%s", "host_api_min": 1, "entry": {"dex_class": "a.Main"}}\n' \
    "$2" "$3" > "$1/andysu-payload.json"
}

publish() { # app channel version: what the andysu-live job's publish step runs
  local cfg="$t/cfg-$1-$2.toml" p="$t/p-$1-$3"
  printf 'app = "%s"\nhost_api = 3\npublic_key = "k.pem"\n[remote]\ntype = "github"\nrepo = "%s"\nchannel = "%s"\n' \
    "$1" "$REPO" "$2" > "$cfg"
  [ -d "$p" ] || payload "$p" "$1" "$3"
  INPUT_PAYLOAD="$p" INPUT_CONFIG="$cfg" INPUT_SIGNING_KEY="$(cat "$ANDYSU_KEY_FILE")" \
    INPUT_SIGNING_CERT="$(cat "$ANDYSU_CERT_FILE")" INPUT_VERSION="$3" INPUT_CHANNEL="" INPUT_PACK_ARGS="--no-rename" \
    INPUT_ROLLOUT="" INPUT_PAGES_BRANCH=gh-pages INPUT_ANDYSU_VERSION="$version" GH_TOKEN=x \
    RUNNER_TEMP="$t/runner" GITHUB_OUTPUT=/dev/null bash "$ANDYSU_PUBLISH_SH" >"$t/publish.log" 2>&1 ||
    { cat "$t/publish.log" >&2; fail "publish $1 $2 $3"; }
}

client() { # root app channel -> prints the outcome JSON, fails on refusal
  "$cli" client --root "$t/dev-$1" update --url "$ANDYSU_GITHUB_URL/$REPO/releases/download/channel-$3/update.json" \
    --pub "$ANDYSU_ROOT_PUB" --app "$2" --channel "$3" --host-api 3
}

manifest() { echo "$GH_ROOT/$REPO/releases/download/channel-$1/update.json"; }

# ---- 1. the bug: a channel created under OLD keeps OLD
for v in 1.0 1.1; do publish "$OLD" live-arm64 "$v"; done
for v in 1.2 1.3; do publish "$NEW" live-arm64 "$v"; done
m="$(manifest live-arm64)"
[ "$(json "$m" 'd["app"]')" = "$OLD" ] || fail "expected publish to keep the earlier app id (the AndySU behaviour this works around)"
[ "$(json "$m" '[r["id"] for r in d["releases"]]')" = "['1.0', '1.1', '1.2', '1.3']" ] || fail "unexpected releases"
seq0="$(json "$m" 'd["sequence"]')"
if out="$(bash scripts/andysu-channel.sh gh-check live-arm64 2>&1)"; then fail "gh-check passed a channel naming $OLD"; fi
grep -q "manifest app $OLD != $NEW" <<<"$out" || { echo "$out" >&2; fail "gh-check failed for another reason"; }
if out="$(client new0 "$NEW" live-arm64 2>&1)"; then fail "a $NEW host accepted a channel naming $OLD"; fi
grep -q wrong_app <<<"$out" || { echo "$out" >&2; fail "expected wrong_app"; }
ok "publish kept $OLD after $NEW releases (sequence $seq0); gh-check and a $NEW host refuse it: $out"
# A host still on the earlier id accepts the manifest, downloads the newest payload, and refuses it.
if out="$(client old0 "$OLD" live-arm64 2>&1)"; then fail "an $OLD host staged a $NEW payload"; fi
grep -q "payload is for $NEW, not $OLD" <<<"$out" || { echo "$out" >&2; fail "expected the payload app check"; }
ok "an $OLD host downloads the newest payload and then refuses it: $out"

# ---- 2. repair, check, host update, next publish, guard
: > "$t/out"
GITHUB_OUTPUT="$t/out" bash scripts/andysu-channel.sh gh-repair live-arm64 --release 1.4 > "$t/repair.log" 2>&1 ||
  { cat "$t/repair.log" >&2; fail "gh-repair"; }
grep -q "manifest now names $NEW" "$t/repair.log" || { cat "$t/repair.log" >&2; fail "gh-repair did not repair"; }
[ "$(json "$m" 'd["app"]')" = "$NEW" ] || fail "repaired manifest does not name $NEW"
[ "$(json "$m" '[r["id"] for r in d["releases"]]')" = "['1.2', '1.3']" ] || fail "repair kept a release of another app"
seq1="$(json "$m" 'd["sequence"]')"
[ "$seq1" -gt "$seq0" ] || fail "repair did not raise the sequence"
{ grep -qx "sequence=$seq1" "$t/out" && grep -qx has_release=false "$t/out"; } || { cat "$t/out" >&2; fail "gh-repair outputs"; }
ok "gh-repair: releases 1.0 and 1.1 (payloads of $OLD) dropped, app $NEW, sequence $seq0 -> $seq1"
bash scripts/andysu-channel.sh gh-check live-arm64 --after "$seq0" > "$t/check.log" 2>&1 || { cat "$t/check.log" >&2; fail "gh-check after repair"; }
sed 's/^/    /' "$t/check.log"
client new1 "$NEW" live-arm64 | grep -q '"staged": "1.3"' || fail "a $NEW host did not stage 1.3 after the repair"
ok "a $NEW host stages 1.3 from the repaired channel"
GITHUB_OUTPUT=/dev/null bash scripts/andysu-channel.sh gh-repair live-arm64 | grep -q "already names $NEW" || fail "a second gh-repair changed something"
ok "gh-repair on a right manifest only reads it"
if out="$(client old1 "$OLD" live-arm64 2>&1)"; then fail "an $OLD host accepted the repaired channel"; fi
grep -q wrong_app <<<"$out" || { echo "$out" >&2; fail "expected wrong_app for an $OLD host"; }
ok "an $OLD host now refuses at the manifest, before downloading anything: $out"

publish "$NEW" live-arm64 1.4
[ "$(json "$m" 'd["app"]')" = "$NEW" ] || fail "the next publish lost $NEW"
bash scripts/andysu-channel.sh gh-check live-arm64 --release 1.4 --after "$seq1" > "$t/check.log" 2>&1 || { cat "$t/check.log" >&2; fail "gh-check after the next publish"; }
client new2 "$NEW" live-arm64 | grep -q '"staged": "1.4"' || fail "a $NEW host did not stage 1.4"
ok "the next publish keeps $NEW; the guard passes for 1.4; a $NEW host stages 1.4"
seq2="$(json "$m" 'd["sequence"]')"
# A re-run of the release: the version is listed already, publish does nothing, the guard allows it.
: > "$t/out"
GITHUB_OUTPUT="$t/out" bash scripts/andysu-channel.sh gh-repair live-arm64 --release 1.4 >/dev/null
grep -qx has_release=true "$t/out" || fail "gh-repair did not see 1.4 listed"
publish "$NEW" live-arm64 1.4
[ "$(json "$m" 'd["sequence"]')" = "$seq2" ] || fail "re-running publish changed the channel"
bash scripts/andysu-channel.sh gh-check live-arm64 --release 1.4 --after "$((seq2 - 1))" >/dev/null 2>&1 || fail "guard refused a re-run"
ok "a re-run publishes nothing and the guard (after = sequence - 1) passes"
# The guard catches a publish that started the channel afresh (what a failed manifest fetch in the
# publish action does): sequence 1, so hosts that saw a higher one would refuse it.
rm "$m" "$m.sig"
publish "$NEW" live-arm64 1.5
if out="$(bash scripts/andysu-channel.sh gh-check live-arm64 --release 1.5 --after "$seq2" 2>&1)"; then fail "guard passed a channel started afresh"; fi
grep -q "is not above $seq2" <<<"$out" || { echo "$out" >&2; fail "guard failed for another reason"; }
ok "the guard fails a channel restarted at sequence $(json "$m" 'd["sequence"]') after $seq2"
if bash scripts/andysu-channel.sh gh-check live-arm64 --release 1.6 >/dev/null 2>&1; then fail "guard passed a missing version"; fi
ok "the guard fails when the newest release is not this version"

# ---- 3. a channel with no release of this app is reset
for v in 1.0 1.1; do publish "$OLD" live-x64 "$v"; done
: > "$t/out"
GITHUB_OUTPUT="$t/out" bash scripts/andysu-channel.sh gh-repair live-x64 --release 2.0 | grep -q "manifest removed" || fail "no reset"
{ [ ! -e "$(manifest live-x64)" ] && grep -qx sequence=0 "$t/out"; } || fail "reset left the manifest"
publish "$NEW" live-x64 2.0
bash scripts/andysu-channel.sh gh-check live-x64 --release 2.0 --after 0 >/dev/null 2>&1 || fail "gh-check after a reset"
client x64 "$NEW" live-x64 | grep -q '"staged": "2.0"' || fail "a $NEW host did not stage 2.0 after a reset"
ok "a channel without a release of $NEW is reset; the next publish starts it under $NEW"
# The first publish of a brand-new channel.
publish "$NEW" live-arm32 3.0
: > "$t/out"
GITHUB_OUTPUT="$t/out" bash scripts/andysu-channel.sh gh-repair live-arm32 --release 3.0 | grep -q "already names" || fail "new channel"
rm -rf "$GH_ROOT/$REPO/releases/download/channel-none"
GITHUB_OUTPUT="$t/out" bash scripts/andysu-channel.sh gh-repair live-none | grep -q "no channel manifest yet" || fail "absent channel"
ok "a new channel is created under $NEW; a missing one is left to the first publish"

# ---- 4. the workflows wire it in
export PATH="$real_path"
python3 - <<'PY'
import yaml
def steps(path, job):
    return yaml.safe_load(open(path))["jobs"][job]["steps"]
def index(st, text):
    hits = [i for i, s in enumerate(st) if text in (s.get("run") or "") or text in str(s.get("uses") or "")]
    assert hits, text
    return hits[0]
live = steps(".github/workflows/build-and-publish.yml", "andysu-live")
repair = index(live, "andysu-channel.sh gh-repair")
publish = index(live, "dixonSolutions/AndySU/actions/publish@")
check = index(live, "andysu-channel.sh gh-check")
assert repair < publish < check, (repair, publish, check)
assert live[repair]["id"] == "channel"
assert "--release" in live[check]["run"] and "--after" in live[check]["run"]
assert "ANDYSU_SIGNING_KEY" in live[repair]["env"]
wf = yaml.safe_load(open(".github/workflows/resign-live.yml"))
assert wf["jobs"]["resign"]["if"] == "github.ref == 'refs/heads/main'"
rs = wf["jobs"]["resign"]["steps"]
assert index(rs, "gh-repair") < index(rs, 'resign-channel.sh" "$site"') < index(rs, "gh-check")
assert "workflow_dispatch" in wf[True] and "schedule" in wf[True]
print("ok: andysu-live repairs before publishing and checks after; resign-live repairs, re-signs and checks (main only)")
PY
echo "PASS: live channel app id repair and guard"
