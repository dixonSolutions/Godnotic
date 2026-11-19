# Handoff: work/web — online play in the browser

Report with commands and pass/fail lines: `~/Projects/.coord/web-report.md`.

## What landed

- **Browser transport** (`ad225bf1`): on the web build, `lhnet.c` hands IPv4
  datagrams to `engine/darkplaces/touch_webnet.c` (two `// Touch:` hooks), which
  calls `Module.godnoticNet` (`web/bridge-net.mjs`, bundled into `a/bridge.*.js`
  by `scripts/lib/bridge_bundle.py`). `ServerList_QueryList` asks the page for a
  relay instead of returning early (`netconn.c`, Touch's own block). The relay's
  answer carries its masters as addresses; the page sets `sv_masterextra1..3`.
- **Relay with public signaling** (`ad225bf1`): `touch_bridge_mqtt.c` (MQTT 3.1.1
  over libdatachannel's WebSocket), `touch_bridge_relay.c` (presence, offers,
  sessions, limits), `touch_bridge_relay_engine.c` (cvars `bridge_relay`
  default 0, `bridge_relay_brokers`, `_stun`, `_topic`, `_status`).
- **Opt-in switch** (`43f7caf1`): Settings > Privacy, "Let web players connect
  through this device", only where the engine has the relay.
- **Self-hostable relay** (`f1449b36`): `relay/` (binary, Dockerfile, compose
  with Mosquitto, README). Not deployed.
- **Docs** (`33bf92eb`): docs/WEB.md "Online play in the browser" — design,
  why MQTT, privacy, failure modes, limits, tests.

## Tests

- `tests/bridge/run.sh` (adds `relay_test.c`, starts `tests/web-bridge/mqtt_broker.py`)
- `node tests/web-bridge/*.test.mjs`
- `tests/web-bridge/join-e2e.sh` (headless Chromium joins through a relay;
  `BROKER=`, `PUBLIC=1`, `RELAY_BIN=` variants)
- `scripts/test-web-relay-setting.sh`, `tests/uibridge/settings_audit_test.py`

## Not done / not verified

- **Android relay**: the Android engine is not built with libdatachannel, so
  the switch does not show there. Needs libdatachannel + libjuice + usrsctp
  cross-built with the NDK (Mbed TLS is already in the Android deps) and the
  `touch_bridge*` objects in the Android engine build.
- **Speed (item 2)**: measured only; the change tried (pausing the background
  download during map loads) did not beat the noise and was reverted. See the
  report for numbers and where the time goes.
- STUN/NAT traversal between two real networks: every test ran on one
  machine (host candidates). No TURN server exists, so symmetric NAT on both
  sides cannot connect.
- The Docker image was not built here (no image downloads).
- No real tablet or phone; no deployed page.
- Components: `engine/darkplaces` and `engine/data/xonotic-data.pk3dir`
  changed; the integrator runs `scripts/component-push.sh all`.
