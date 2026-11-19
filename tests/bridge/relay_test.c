/* The web relay over MQTT signaling (touch_bridge_relay.c, touch_bridge_mqtt.c),
 * against a local broker (tests/web-bridge/mqtt_broker.py; run.sh starts it
 * and passes its URL). A test "browser" made of the same MQTT client and a
 * libdatachannel peer finds the relay's retained presence, offers, gets an
 * answer with the relay's masters, and exchanges a datagram with a local UDP
 * socket through it. Then: the peer cap answers "error full", the offer rate
 * limit answers "error busy", malformed offers get nothing, and stopping the
 * relay clears its presence. */
#define _POSIX_C_SOURCE 200809L
#include "touch_bridge.h"
#include "touch_bridge_mqtt.h"
#include "touch_bridge_relay.h"
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>

#define PREFIX "godnotic/test"
static struct touch_relay *relay;
static char presence[256], answer[65536];
static int presence_seen, answer_seen;
static atomic_int gathered, received;

static struct touch_bridge_forwarder *make_forwarder(void *c)
{
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    (void)c;
    assert(f && Touch_BridgeSetAllowLoopback(f, 1) == 0);
    return f;
}
static void log_line(void *c, const char *text) { (void)c; fputs(text, stderr); }
static void delay(void) { struct timespec ts = {0, 2000000}; nanosleep(&ts, NULL); }
static void on_message(void *c, const char *topic, const unsigned char *p, int n)
{
    (void)c;
    if (!strncmp(topic, PREFIX "/online/", strlen(PREFIX "/online/"))) {
        presence_seen++;
        snprintf(presence, sizeof(presence), "%.*s", n, (const char *)p);
    } else if (!strncmp(topic, PREFIX "/answer/", strlen(PREFIX "/answer/"))) {
        answer_seen++;
        snprintf(answer, sizeof(answer), "%.*s", n, (const char *)p);
    }
}
static void pump(struct touch_mqtt *m, int frames)
{
    int i;
    for (i = 0; i < frames; ++i) {
        if (relay) Touch_RelayFrame(relay);
        if (m) Touch_MqttPoll(m, on_message, NULL);
        delay();
    }
}
static int pump_until(struct touch_mqtt *m, int *counter, int value)
{
    int i;
    for (i = 0; i < 5000 && *counter < value; ++i) pump(m, 1);
    return *counter >= value;
}
static void desc(int pc, const char *sdp, const char *type, void *p) { (void)pc; (void)sdp; (void)type; (void)p; }
static void cand(int pc, const char *sdp, const char *mid, void *p) { (void)pc; (void)sdp; (void)mid; (void)p; }
static void incoming(int pc, int dc, void *p) { (void)pc; (void)dc; (void)p; abort(); }
static void gather(int pc, rtcGatheringState state, void *p)
{ (void)pc; (void)p; if (state == RTC_GATHERING_COMPLETE) atomic_store(&gathered, 1); }
static void open_channel(int dc, void *p) { (void)dc; (void)p; }
static void got_message(int dc, const char *data, int size, void *p)
{
    struct touch_bridge_address addr; const unsigned char *payload; int len;
    (void)dc; (void)p;
    assert(Touch_BridgeUnpack(data, size, &addr, &payload, &len) == 0);
    assert(len == 5 && !memcmp(payload, "reply", 5));
    atomic_store(&received, 1);
}
static void offer(struct touch_mqtt *m, const char *client, const char *sdp)
{
    char topic[128], *text = malloc(70000);
    int n = snprintf(text, 70000, "GODNOTIC-OFFER 1\nfrom %s\n\n%s", client, sdp);
    snprintf(topic, sizeof(topic), PREFIX "/offer/%s", Touch_RelayId(relay));
    assert(Touch_MqttPublish(m, topic, text, n, 0) == 0);
    free(text);
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    struct touch_relay_config config;
    struct touch_mqtt *browser;
    char sdp[65536], topic[128], packet[64], data[64], client[65];
    const char *body;
    struct sockaddr_in addr = {0}, source; socklen_t alen = sizeof(addr), slen = sizeof(source);
    struct touch_bridge_address dest = {0};
    int peer, channel, sock, i, n, before;
    assert(argc == 2);

    /* Parsing first: malformed offers never reach libdatachannel. */
    assert(Touch_RelayParseOffer("GODNOTIC-OFFER 1\nfrom ab-1\n\nv=0\r\nxxxxxxxx", (int)strlen("GODNOTIC-OFFER 1\nfrom ab-1\n\nv=0\r\nxxxxxxxx"), client, &body) == 0);
    assert(!strcmp(client, "ab-1") && !strncmp(body, "v=0", 3));
    assert(Touch_RelayParseOffer("GODNOTIC-OFFER 1\nfrom a/b\n\nv=0\r\nxxxxxxxx", (int)strlen("GODNOTIC-OFFER 1\nfrom a/b\n\nv=0\r\nxxxxxxxx"), client, &body) < 0);
    assert(Touch_RelayParseOffer("GODNOTIC-OFFER 2\nfrom ab\n\nv=0\r\nxxxxxxxx", (int)strlen("GODNOTIC-OFFER 2\nfrom ab\n\nv=0\r\nxxxxxxxx"), client, &body) < 0);
    assert(Touch_RelayParseOffer("GODNOTIC-OFFER 1\n\nv=0\r\nxxxxxxxxxxxx", (int)strlen("GODNOTIC-OFFER 1\n\nv=0\r\nxxxxxxxxxxxx"), client, &body) < 0);
    {
        unsigned char p[300];
        int header;
        n = Touch_MqttEncodePublish(p, sizeof(p), "t", "x", 1, 1);
        assert(n == 6 && p[0] == 0x31 && p[1] == 4);
        assert(Touch_MqttPacketSize(p, n, &header) == n && header == 2);
        assert(Touch_MqttPacketSize(p, n - 1, &header) == 0);
        n = Touch_MqttEncodeConnect(p, sizeof(p), "ab", "w", 60);
        assert(n > 0 && p[0] == 0x10 && p[9] == (0x02 | 0x04 | 0x20));
    }
    puts("relay offer parsing and MQTT packets: PASS");

    sock = socket(AF_INET, SOCK_DGRAM, 0); assert(sock >= 0);
    addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    assert(getsockname(sock, (struct sockaddr *)&addr, &alen) == 0);

    memset(&config, 0, sizeof(config));
    config.brokers = argv[1];
    config.topic_prefix = PREFIX;
    config.masters = "127.0.0.1:27950 [::1]:27777";
    config.offers_per_minute = 4;
    config.connect_timeout = 5;
    config.make_forwarder = make_forwarder;
    config.log = log_line;
    relay = Touch_RelayCreate(&config);
    assert(relay);
    for (i = 0; i < 5000 && !Touch_RelayBrokersOnline(relay); ++i) pump(NULL, 1);
    assert(Touch_RelayBrokersOnline(relay) == 1);

    browser = Touch_MqttCreate(argv[1], "gnw-test", NULL, 30);
    assert(browser);
    for (i = 0; i < 5000 && Touch_MqttPoll(browser, on_message, NULL) != TOUCH_MQTT_CONNECTED; ++i) pump(NULL, 1);
    assert(Touch_MqttSubscribe(browser, PREFIX "/online/+") == 0);
    assert(Touch_MqttSubscribe(browser, PREFIX "/answer/gnw-test") == 0);
    assert(pump_until(browser, &presence_seen, 1));
    assert(!strcmp(presence, "GODNOTIC-RELAY 1\npeers 0\nmax 8\n\n"));
    puts("relay presence on the broker: PASS");

    peer = Touch_BridgeCreatePeer(NULL, desc, cand, incoming); assert(peer >= 0);
    assert(rtcSetGatheringStateChangeCallback(peer, gather) == 0);
    channel = Touch_BridgeOpenChannel(peer, "datagrams", NULL, open_channel, got_message); assert(channel >= 0);
    assert(rtcSetLocalDescription(peer, "offer") == 0);
    for (i = 0; i < 10000 && !atomic_load(&gathered); ++i) delay();
    assert(rtcGetLocalDescription(peer, sdp, sizeof(sdp)) > 0);
    /* garbage on the offer topic: no answer, no session */
    snprintf(topic, sizeof(topic), PREFIX "/offer/%s", Touch_RelayId(relay));
    assert(Touch_MqttPublish(browser, topic, "GODNOTIC-OFFER 1\nfrom gnw-test\n\nnot sdp", (int)strlen("GODNOTIC-OFFER 1\nfrom gnw-test\n\nnot sdp"), 0) == 0);
    pump(browser, 100);
    assert(answer_seen == 0 && Touch_RelayPeers(relay) == 0);
    offer(browser, "gnw-test", sdp);
    assert(pump_until(browser, &answer_seen, 1));
    assert(!strncmp(answer, "GODNOTIC-ANSWER 1\nrelay ", 24));
    assert(strstr(answer, "\nmaster 127.0.0.1:27950\nmaster [::1]:27777\n\nv=0"));
    assert(rtcSetRemoteDescription(peer, strstr(answer, "\n\n") + 2, "answer") == 0);
    for (i = 0; i < 10000 && !rtcIsOpen(channel); ++i) pump(browser, 1);
    assert(rtcIsOpen(channel));
    pump(browser, 100); /* the presence counts the session since its offer */
    assert(strstr(presence, "peers 1\n"));
    puts("relay offer, answer with masters, data channel open: PASS");

    dest.family = 4; dest.address[0] = 127; dest.address[3] = 1; dest.port = ntohs(addr.sin_port);
    n = Touch_BridgePack(packet, sizeof(packet), &dest, "hello", 5); assert(n > 0);
    assert(Touch_BridgeSend(channel, packet, n) == 0);
    for (i = 0; i < 5000; ++i) {
        pump(browser, 1);
        n = recvfrom(sock, data, sizeof(data), MSG_DONTWAIT, (struct sockaddr *)&source, &slen);
        if (n >= 0) break;
    }
    assert(n == 5 && !memcmp(data, "hello", 5));
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&source, slen) == 5);
    for (i = 0; i < 5000 && !atomic_load(&received); ++i) pump(browser, 1);
    assert(atomic_load(&received));
    puts("relay UDP forwarding through the signaled channel: PASS");

    /* The peer cap: with one slot, a second browser hears "error full". */
    assert(Touch_BridgeSetMaxPeers(2) == 0); /* this process holds the test peer and the relay's */
    answer_seen = 0;
    {
        struct touch_mqtt *second = Touch_MqttCreate(argv[1], "gnw-second", NULL, 30);
        assert(second);
        for (i = 0; i < 5000 && Touch_MqttPoll(second, on_message, NULL) != TOUCH_MQTT_CONNECTED; ++i) pump(browser, 1);
        assert(Touch_MqttSubscribe(second, PREFIX "/answer/gnw-second") == 0);
        pump(second, 50);
        offer(second, "gnw-second", sdp);
        for (i = 0; i < 5000 && !answer_seen; ++i) { pump(browser, 1); Touch_MqttPoll(second, on_message, NULL); }
        assert(strstr(answer, "\nerror full\n"));
        puts("relay peer cap answers error full: PASS");
        /* The offer rate (4 a minute here; 2 used): the next ones are refused. */
        answer_seen = 0;
        offer(second, "gnw-third", sdp);
        offer(second, "gnw-fourth", sdp);
        pump(second, 300);
        Touch_MqttDestroy(second);
    }
    {
        struct touch_mqtt *fifth = Touch_MqttCreate(argv[1], "gnw-fifth", NULL, 30);
        assert(fifth);
        for (i = 0; i < 5000 && Touch_MqttPoll(fifth, on_message, NULL) != TOUCH_MQTT_CONNECTED; ++i) pump(browser, 1);
        assert(Touch_MqttSubscribe(fifth, PREFIX "/answer/gnw-fifth") == 0);
        pump(fifth, 50);
        answer_seen = 0;
        offer(fifth, "gnw-fifth", sdp);
        for (i = 0; i < 5000 && !answer_seen; ++i) { pump(browser, 1); Touch_MqttPoll(fifth, on_message, NULL); }
        assert(strstr(answer, "\nerror busy\n"));
        Touch_MqttDestroy(fifth);
    }
    puts("relay offer rate limit answers error busy: PASS");

    /* Stopping clears the retained presence (empty message). */
    before = presence_seen;
    Touch_RelayDestroy(relay);
    relay = NULL;
    assert(pump_until(browser, &presence_seen, before + 1) && presence[0] == 0);
    puts("relay stop clears its presence: PASS");
    Touch_MqttDestroy(browser);
    rtcDeleteDataChannel(channel);
    Touch_BridgeDeletePeer(peer);
    close(sock);
    return 0;
}
