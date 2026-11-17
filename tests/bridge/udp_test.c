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
