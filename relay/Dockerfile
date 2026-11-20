# A self-hosted web relay: docker build -f relay/Dockerfile -t godnotic-relay .
# (from the repository root; relay/README.md). Not deployed anywhere by us.
FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake git ca-certificates libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/build-libdatachannel.sh scripts/
RUN GODNOTIC_DATACHANNEL_CACHE=/opt/datachannel JOBS=4 scripts/build-libdatachannel.sh
COPY engine/darkplaces/touch_bridge.c engine/darkplaces/touch_bridge.h \
     engine/darkplaces/touch_bridge_mqtt.c engine/darkplaces/touch_bridge_mqtt.h \
     engine/darkplaces/touch_bridge_relay.c engine/darkplaces/touch_bridge_relay.h engine/darkplaces/
COPY relay/ relay/
RUN make -C relay DATACHANNEL=/opt/datachannel/prefix

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends libssl3 ca-certificates \
    && rm -rf /var/lib/apt/lists/* && useradd --system --no-create-home relay
COPY --from=build /src/relay/godnotic-relay /usr/local/bin/godnotic-relay
USER relay
ENTRYPOINT ["godnotic-relay"]
