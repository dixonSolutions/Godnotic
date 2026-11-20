#include "touch_bridge.h"
#include <assert.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct result { int received; struct touch_bridge_address address; };
static void reply(void *ctx, const void *packet, int size)
{
    struct result *r = ctx;
    const unsigned char *payload;
    int len;
    assert(Touch_BridgeUnpack(packet, size, &r->address, &payload, &len) == 0);
    assert(len == 5 && memcmp(payload, "reply", 5) == 0);
    ++r->received;
}

static void ignore_reply(void *ctx, const void *packet, int size)
{
    (void)ctx; (void)packet; (void)size;
}

static void exercise(int family)
{
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    struct touch_bridge_address a = {0};
    struct sockaddr_storage server = {0}, client;
    socklen_t server_len, client_len = sizeof(client);
    unsigned char packet[128], buffer[128];
    struct result r = {0};
    int sock = socket(family, SOCK_DGRAM, 0), n;
    assert(f && sock >= 0);
    if (family == AF_INET) {
        struct sockaddr_in *s = (struct sockaddr_in *)&server;
        s->sin_family = family; s->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        server_len = sizeof(*s); a.family = 4; a.address[0] = 127; a.address[3] = 1;
    } else {
        struct sockaddr_in6 *s = (struct sockaddr_in6 *)&server;
        s->sin6_family = family; s->sin6_addr = in6addr_loopback;
        server_len = sizeof(*s); a.family = 6; a.address[15] = 1;
    }
    assert(bind(sock, (struct sockaddr *)&server, server_len) == 0);
    assert(getsockname(sock, (struct sockaddr *)&server, &server_len) == 0);
    a.port = family == AF_INET ? ntohs(((struct sockaddr_in *)&server)->sin_port) :
                                ntohs(((struct sockaddr_in6 *)&server)->sin6_port);
    n = Touch_BridgePack(packet, sizeof(packet), &a, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) < 0);
    assert(Touch_BridgeAllowAddress(f, &a) == 0);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    assert(recvfrom(sock, buffer, sizeof(buffer), 0, (struct sockaddr *)&client, &client_len) == 5);
    assert(memcmp(buffer, "query", 5) == 0);
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&client, client_len) == 5);
    for (n = 0; n < 1000 && !r.received; ++n) {
        assert(Touch_BridgePoll(f, reply, &r) >= 0);
        if (!r.received) usleep(1000);
    }
    assert(r.received == 1 && r.address.family == a.family && r.address.port == a.port);
    assert(memcmp(r.address.address, a.address, family == AF_INET ? 4 : 16) == 0);
    if (family == AF_INET) {
        /* A malformed response cannot grant even its preceding complete entry. */
        const unsigned char malformed[] = "\xff\xff\xff\xffgetserversResponse\\" "\x7f\x00\x00\x01\x70\x01" "?";
        const unsigned char valid[] = "\xff\xff\xff\xffgetserversResponse\\" "\x7f\x00\x00\x01\x70\x01" "\\EOT";
        struct touch_bridge_address learned = a;
        learned.port = 0x7001;
        struct touch_bridge_forwarder *other = Touch_BridgeForwarderCreate();
        const unsigned char extended[] = "\xff\xff\xff\xffgetserversExtResponse/"
            "\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x01\x70\x02" "\\EOT\x00\x00\x00";
        struct touch_bridge_address learned6 = {6, {0}, 0x7002};
        learned6.address[15] = 1;
        assert(other);
        /* A permitted server is not automatically a trusted master. */
        assert(sendto(sock, valid, sizeof(valid)-1, 0, (struct sockaddr *)&client, client_len) == sizeof(valid)-1);
        usleep(1000);
        assert(Touch_BridgePoll(f, ignore_reply, NULL) == 1);
        n = Touch_BridgePack(packet, sizeof(packet), &learned, "query", 5);
        assert(Touch_BridgeForward(f, packet, n) < 0);
        assert(Touch_BridgeAllowMaster(f, &a) == 0);
        assert(sendto(sock, malformed, sizeof(malformed)-1, 0, (struct sockaddr *)&client, client_len) == sizeof(malformed)-1);
        usleep(1000);
        assert(Touch_BridgePoll(f, ignore_reply, NULL) == 1);
        n = Touch_BridgePack(packet, sizeof(packet), &learned, "query", 5);
        assert(Touch_BridgeForward(f, packet, n) < 0);
        assert(sendto(sock, valid, sizeof(valid)-1, 0, (struct sockaddr *)&client, client_len) == sizeof(valid)-1);
        usleep(1000);
        assert(Touch_BridgePoll(f, ignore_reply, NULL) == 1);
        assert(Touch_BridgeForward(f, packet, n) == 0);
        assert(Touch_BridgeForward(other, packet, n) < 0);
        assert(sendto(sock, extended, sizeof(extended)-1, 0, (struct sockaddr *)&client, client_len) == sizeof(extended)-1);
        usleep(1000);
        assert(Touch_BridgePoll(f, ignore_reply, NULL) == 1);
        n = Touch_BridgePack(packet, sizeof(packet), &learned6, "query", 5);
        assert(Touch_BridgeForward(f, packet, n) == 0);
        assert(Touch_BridgeForward(other, packet, n) < 0);
        Touch_BridgeForwarderDestroy(other);
    }
    assert(Touch_BridgeForward(f, packet, 1) < 0);
    close(sock);
    Touch_BridgeForwarderDestroy(f);
}
int main(void)
{
    exercise(AF_INET);
    exercise(AF_INET6);
    puts("bridge UDP forwarding: PASS (IPv4 and IPv6)");
    return 0;
}
