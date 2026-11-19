#define _POSIX_C_SOURCE 200809L
#include "touch_bridge.h"
#include <arpa/inet.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static void description(int id, const char *sdp, const char *type, void *ctx)
{ (void)id; (void)sdp; (void)type; (void)ctx; }
static void candidate(int id, const char *value, const char *mid, void *ctx)
{ (void)id; (void)value; (void)mid; (void)ctx; }
static void channel(int id, int dc, void *ctx)
{ (void)id; (void)dc; (void)ctx; }
static void reply(void *ctx, const void *packet, int size)
{ (void)packet; (void)size; ++*(int *)ctx; }

int main(void)
{
    int peers[8], i, sock, n, received = 0;
    struct touch_bridge_forwarder *f, *other;
    struct touch_bridge_address address = {4, {127, 0, 0, 1}, 0};
    struct sockaddr_in server = {0};
    struct sockaddr_storage client;
    socklen_t slen = sizeof(server), clen = sizeof(client);
    unsigned char packet[128], buf[128];
    struct timespec wait = {1, 100000000};
    struct timespec short_wait = {0, 10000000};

    for (i = 0; i < 8; ++i) {
        peers[i] = Touch_BridgeCreatePeer(NULL, description, candidate, channel);
        assert(peers[i] >= 0);
    }
    assert(Touch_BridgeCreatePeer(NULL, description, candidate, channel) < 0);
    assert(Touch_BridgeSetMaxPeers(7) < 0);
    assert(Touch_BridgeDeletePeer(peers[0]) >= 0);
    assert(Touch_BridgeDeletePeer(peers[0]) < 0);
    peers[0] = Touch_BridgeCreatePeer(NULL, description, candidate, channel);
    assert(peers[0] >= 0);
    for (i = 0; i < 8; ++i) assert(Touch_BridgeDeletePeer(peers[i]) >= 0);
    assert(Touch_BridgeSetMaxPeers(1) == 0);
    peers[0] = Touch_BridgeCreatePeer(NULL, description, candidate, channel);
    assert(peers[0] >= 0 && Touch_BridgeCreatePeer(NULL, description, candidate, channel) < 0);
    assert(Touch_BridgeDeletePeer(peers[0]) >= 0);
    assert(Touch_BridgeSetMaxPeers(8) == 0);
    rtcCleanup();

    f = Touch_BridgeForwarderCreate(); other = Touch_BridgeForwarderCreate();
    sock = socket(AF_INET, SOCK_DGRAM, 0);
    assert(f && other && sock >= 0);
    server.sin_family = AF_INET; server.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(sock, (struct sockaddr *)&server, sizeof(server)) == 0);
    assert(getsockname(sock, (struct sockaddr *)&server, &slen) == 0);
    address.port = ntohs(server.sin_port);
    assert(Touch_BridgeAllowAddress(f, &address) == 0);
    assert(Touch_BridgeAllowAddress(other, &address) == 0);
    n = Touch_BridgePack(packet, sizeof(packet), &address, "123456", 6);
    assert(Touch_BridgeSetRates(f, 1, 100) == 0);
    assert(Touch_BridgeSetRates(f, 0, 100) < 0);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    assert(Touch_BridgeForward(f, packet, n) < 0);
    assert(Touch_BridgeDropCount(f, TOUCH_BRIDGE_DROP_RATE) == 1);
    assert(Touch_BridgeForward(other, packet, n) == 0); /* independent peer */
    assert(recvfrom(sock, buf, sizeof(buf), 0, (struct sockaddr *)&client, &clen) == 6);
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&client, clen) == 5);
    nanosleep(&short_wait, NULL);
    assert(Touch_BridgePoll(f, reply, &received) == 0); /* shared bucket */
    assert(received == 0);
    assert(Touch_BridgeDropCount(f, TOUCH_BRIDGE_DROP_RATE) == 2);
    nanosleep(&wait, NULL);
    assert(Touch_BridgeForward(f, packet, n) == 0); /* refill */
    assert(Touch_BridgeSetRates(f, 100, 10) == 0);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    assert(Touch_BridgeForward(f, packet, n) < 0); /* bytes, not packets */
    nanosleep(&wait, NULL);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    assert(Touch_BridgeSetRates(f, 100, 100) == 0);
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&client, clen) == 5);
    for (i = 0; i < 100 && !received; ++i) {
        assert(Touch_BridgePoll(f, reply, &received) >= 0);
        if (!received) nanosleep(&short_wait, NULL);
    }
    assert(received == 1);
    close(sock);
    Touch_BridgeForwarderDestroy(f); Touch_BridgeForwarderDestroy(other);
    puts("bridge peer and traffic limits: PASS");
    return 0;
}
