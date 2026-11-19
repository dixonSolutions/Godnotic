#!/bin/bash
# The web relay opt-in ("Let web players connect through this device"):
#   1. a fresh install has it off, and the relay does not start (no broker
#      connection, bridge_relay_status off);
#   2. Settings > Privacy shows the switch (the UIB tree every native renderer
#      draws: Android, GTK, the QuakeC one), bound to cvar:bridge_relay, off;
#   3. switching it on starts the relay (status line under it) and the choice
#      is saved to config.cfg.
#
#   BASEDIR=<basedir with data/*.pk3 and the Touch pk3dir> scripts/test-web-relay-setting.sh
# Optional: ENGINE (default engine/darkplaces/darkplaces-sdl), LD_LIBRARY_PATH.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
BASEDIR="${BASEDIR:?set BASEDIR to a Xonotic basedir (data/*.pk3 plus the Touch pk3dir)}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/xt-webrelay.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

mkdir -p "$WORK/data"
cd "$WORK/data"
# Each step queues the next: the welcome's "done" clears earlier defers.
printf '%s\n' 'echo STATUS0 $bridge_relay $bridge_relay_status' \
    'uib_event {"ev":"value","node":"welcome/form/name","value":"Tester"}' 'defer 1 "exec s2.cfg"' > s1.cfg
printf '%s\n' 'uib_event {"ev":"action","node":"welcome","action":"welcome.done"}' 'defer 2 "exec s3.cfg"' > s2.cfg
printf '%s\n' 'uib_event {"ev":"action","node":"home/nav/settings","action":"settings"}' 'defer 1 "exec s4.cfg"' > s3.cfg
printf '%s\n' 'uib_event {"ev":"value","node":"settings/split","value":"privacy"}' 'defer 1 "exec s5.cfg"' > s4.cfg
printf '%s\n' 'echo DUMP-OFF' 'uib_dump' 'uib_event {"ev":"value","node":"settings/split/privacy/webrelay","value":true}' 'defer 3 "exec s6.cfg"' > s5.cfg
printf '%s\n' 'echo DUMP-ON $bridge_relay' 'uib_dump' 'defer 1 "quit"' > s6.cfg
printf '%s\n' 'defer 5 "exec s1.cfg"' > go.cfg
env SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy timeout 90 "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$WORK" \
    +vid_width 1280 +vid_height 800 +vid_fullscreen 0 +net_slist_lan_only 1 \
    +bridge_relay_brokers ws://127.0.0.1:9/mqtt +exec go.cfg > "$WORK/log" 2>&1
log="$WORK/log"
grep -aq 'STATUS0 0 off' "$log" && ok "a fresh install has the relay off (bridge_relay 0, status off)" \
    || bad "fresh state: $(grep -a STATUS0 "$log")"
before_on="$(sed -n '1,/DUMP-OFF/p' "$log")"
echo "$before_on" | grep -aq 'web relay' && bad "the relay started before the player turned it on" \
    || ok "nothing connects to a broker while it is off"
off="$(sed -n '/DUMP-OFF/,/DUMP-ON/p' "$log" | grep -ao '"id":"settings/split/privacy/webrelay"[^}]*' | head -1)"
echo "$off" | grep -q '"label":"Let web players connect through this device","bind":"cvar:bridge_relay"' \
    && echo "$off" | grep -q '"value":false' && ok "Settings > Privacy shows the switch, off: $off" || bad "Privacy switch: ${off:-missing}"
on="$(sed -n '/DUMP-ON/,$p' "$log")"
echo "$on" | grep -aq 'DUMP-ON 1' && echo "$on" | grep -aoq '"id":"settings/split/privacy/webrelay","type":"switch","props":{[^}]*"value":true' \
    && ok "switching it on sets bridge_relay 1" || bad "switch on: $(echo "$on" | grep -a DUMP-ON)"
echo "$on" | grep -aq '"id":"settings/split/privacy/webrelay_status"' && grep -aq 'web relay: starting' "$log" \
    && ok "the relay starts and its status shows under the switch" || bad "relay start / status line"
grep -aq '"bridge_relay" "1"' "$WORK/data/config.cfg" && ok "the choice is saved to config.cfg" \
    || bad "config.cfg: $(grep -a bridge_relay "$WORK/data/config.cfg")"
exit $fail
