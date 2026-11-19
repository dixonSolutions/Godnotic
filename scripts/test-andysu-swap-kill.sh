#!/bin/bash
# Device-free check of the durable trial record after SIGKILL during a swap.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
pid=
cleanup() { [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; rm -rf "$tmp"; }
trap cleanup EXIT
pkg=android/app/src/main/java/io/github/dixonsolutions/godnotic
javac -d "$tmp" "$pkg/AndySUState.java" "$pkg/UpdateRetry.java" "$pkg/SwapTrial.java" "$pkg/CrashReport.java" tests/andysu/SwapKillHarness.java
java -cp "$tmp" io.github.dixonsolutions.godnotic.SwapKillHarness begin "$tmp/state" > "$tmp/log" &
pid=$!
for _ in $(seq 1 100); do grep -q 'trial durable' "$tmp/log" && break; sleep .1; done
grep -q 'trial durable' "$tmp/log"
kill -9 "$pid"
wait "$pid" 2>/dev/null || true
pid=
java -cp "$tmp" io.github.dixonsolutions.godnotic.SwapKillHarness verify "$tmp/state"
