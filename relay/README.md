# godnotic-relay: a self-hosted web relay

Browsers cannot send UDP, so the web build reaches game servers through a
**relay**: normally a native Godnotic whose player turned on *Settings >
Privacy > Let web players connect through this device*. This directory is the
same relay without the game, for anyone who wants one that is always on. It
is written and tested locally; nobody runs one for Godnotic.

How it fits together is in [docs/WEB.md, "Online play in the browser"](../docs/WEB.md#online-play-in-the-browser).

## Build and run

```sh
scripts/build-libdatachannel.sh          # once: the pinned libdatachannel
make -C relay
relay/godnotic-relay                     # public brokers, the default masters
relay/godnotic-relay --broker wss://mqtt.example.org/mqtt --max-peers 32
```

Options: `--broker URL` (repeat; default the public brokers
`wss://broker.emqx.io:8084/mqtt` and `wss://broker.hivemq.com:8884/mqtt`),
`--master HOST[:PORT]` (repeat; default the three DarkPlaces masters),
`--topic PREFIX` (default `godnotic/relay/v1`), `--stun URL` (default
Google's; `""` for none), `--max-peers N` (8), `--offers-per-minute N` (30),
`--allow-loopback` (tests only). Ctrl+C or SIGTERM clears its presence.

The relay forwards only to the masters and to the servers their replies
list, with per-browser packet (200/s) and byte (1 MiB/s) rates, an idle
timeout (120 s) and an open timeout (20 s).

## Your own signaling broker (Docker)

```sh
docker compose -f relay/docker-compose.yml up -d --build
```

starts Mosquitto (MQTT over WebSocket on port 8080) and the relay. A page on
https can only open `wss://`, so publish port 8080 through a TLS proxy (for
example Caddy: `mqtt.example.org { reverse_proxy 127.0.0.1:8080 }`), then
point the page at it: build it with
`XT_WEB_RELAY_BROKERS=wss://mqtt.example.org/mqtt scripts/build-web.sh`, or
open it with `?relaybroker=wss://mqtt.example.org/mqtt`. The relay needs the
host's UDP ports for WebRTC, hence `network_mode: host`.

Not tested here: the Docker image was not built (no image downloads on this
machine); the binary and the broker protocol are (`tests/web-bridge/join-e2e.sh`
with `RELAY_BIN=relay/godnotic-relay`).
