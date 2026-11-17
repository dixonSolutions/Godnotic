#include "touch_bridge.h"
#include <assert.h>
#include <string.h>
#include <stdio.h>

int main(void)
{
    struct touch_bridge_address a = {4, {127, 0, 0, 1}, 26000}, b;
    unsigned char packet[128];
    const unsigned char *payload;
    int n, len;
    const char msg[] = "getinfo";
    n = Touch_BridgePack(packet, sizeof(packet), &a, msg, sizeof(msg));
    assert(n == 8 + sizeof(msg));
    assert(Touch_BridgeUnpack(packet, n, &b, &payload, &len) == 0);
    assert(b.family == 4 && b.port == a.port && !memcmp(a.address, b.address, 4));
    assert(len == sizeof(msg) && !memcmp(payload, msg, len));
    assert(Touch_BridgeUnpack(packet, 8, &b, &payload, &len) < 0);
    packet[0] = 2;
    assert(Touch_BridgeUnpack(packet, n, &b, &payload, &len) < 0);
    a.family = 6; a.address[15] = 1;
    n = Touch_BridgePack(packet, sizeof(packet), &a, msg, sizeof(msg));
    assert(n == 20 + sizeof(msg));
    assert(Touch_BridgeUnpack(packet, n, &b, &payload, &len) == 0);
    assert(b.family == 6 && b.port == a.port && !memcmp(a.address, b.address, 16));
    assert(Touch_BridgePack(packet, 4, &a, msg, sizeof(msg)) < 0);
    puts("bridge framing: PASS");
    return 0;
}
