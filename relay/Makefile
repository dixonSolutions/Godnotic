# godnotic-relay: the web relay without the game (relay/README.md).
#   make -C relay                     (libdatachannel from scripts/build-libdatachannel.sh)
#   make -C relay DATACHANNEL=/prefix  (an installed static libdatachannel)
ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
COMMIT := 4e4f4892dccb2a57fe3a490d0c9d958de4244e74
DATACHANNEL_CACHE ?= $(if $(GODNOTIC_DATACHANNEL_CACHE),$(GODNOTIC_DATACHANNEL_CACHE),$(HOME)/.cache/godnotic/libdatachannel-$(COMMIT))
DATACHANNEL ?= $(DATACHANNEL_CACHE)/prefix
ENGINE := $(ROOT)/engine/darkplaces
SRC := main.c $(ENGINE)/touch_bridge.c $(ENGINE)/touch_bridge_mqtt.c $(ENGINE)/touch_bridge_relay.c
CFLAGS ?= -O2
CFLAGS += -std=c11 -Wall -Wextra -DRTC_STATIC -DRTC_ENABLE_MEDIA=0 -I$(DATACHANNEL)/include -I$(ENGINE)
LIBS := $(DATACHANNEL)/lib/libdatachannel.a $(DATACHANNEL)/lib/libjuice.a $(DATACHANNEL)/lib/libusrsctp.a \
	-lstdc++ -lssl -lcrypto -lpthread -lm

godnotic-relay: $(SRC) $(ENGINE)/touch_bridge.h $(ENGINE)/touch_bridge_mqtt.h $(ENGINE)/touch_bridge_relay.h
	$(CC) $(CFLAGS) $(SRC) $(LIBS) -o $@

clean:
	rm -f godnotic-relay
.PHONY: clean
