#include "quakedef.h"
#include "lhnet.h"
#include "touch_bridge.h"
#include <arpa/inet.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

cvar_state_t cvars_all;
static int lookups;
static cvar_t *loopback_cvar;
void Cvar_RegisterVariable(cvar_t *var)
{
    assert(!strcmp(var->name, "bridge_allow_loopback") && !strcmp(var->string, "0"));
    loopback_cvar = var;
}
extern void Touch_BridgeInit(void);
static void receive_reply(void *context, const void *packet, int size)
{
    const unsigned char *payload;
    struct touch_bridge_address address;
    int payload_size;
    assert(Touch_BridgeUnpack(packet, size, &address, &payload, &payload_size) == 0);
    assert(address.family == 4 && address.address[0] == 127 && address.address[3] == 1);
    assert(payload_size == 5 && !memcmp(payload, "reply", 5));
    ++*(int *)context;
}
const char *Cvar_VariableString(cvar_state_t *state, const char *name, unsigned flags)
{
    static const char *const names[] = { "sv_master1", "sv_master2", "sv_master3", "sv_master4", "sv_masterextra1", "sv_masterextra2", "sv_masterextra3" };
    (void)flags;
    assert(state == &cvars_all && lookups < 7 && !strcmp(name, names[lookups]));
    return ++lookups == 1 ? "test-master" : "";
}
int LHNETADDRESS_FromString(lhnetaddress_t *out, const char *value, int port)
{
    struct sockaddr_in native = {0};
    assert(!strcmp(value, "test-master") && port == 27950);
    memset(out, 0, sizeof(*out));
    out->addresstype = LHNETADDRESSTYPE_INET4;
    native.sin_family = AF_INET;
    native.sin_port = htons(27950);
    native.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    memcpy(out->storage, &native, sizeof(native));
    return 1;
}
int LHNETADDRESS_GetPort(const lhnetaddress_t *address)
{
    struct sockaddr_in native;
    memcpy(&native, address->storage, sizeof(native));
    return ntohs(native.sin_port);
}
int main(void)
{
    struct touch_bridge_forwarder *f, *enabled, *disabled;
    struct touch_bridge_address master = {4, {127, 0, 0, 1}, 27950};
    struct touch_bridge_address local = {4, {127, 0, 0, 1}, 0};
    struct sockaddr_in server = {0}, client;
    socklen_t len = sizeof(server), client_len = sizeof(client);
    unsigned char packet[64], buffer[64];
    int n, received = 0, sock = socket(AF_INET, SOCK_DGRAM, 0);
    assert(sock >= 0);
    server.sin_family = AF_INET; server.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(sock, (struct sockaddr *)&server, sizeof(server)) == 0);
    assert(getsockname(sock, (struct sockaddr *)&server, &len) == 0);
    local.port = ntohs(server.sin_port);
    Touch_BridgeInit();
    assert(loopback_cvar && loopback_cvar->integer == 0);
    f = Touch_BridgeCreateConfiguredForwarder();
    assert(f && lookups == 7);
    n = Touch_BridgePack(packet, sizeof(packet), &master, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    master.port++;
    n = Touch_BridgePack(packet, sizeof(packet), &master, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) < 0);
    n = Touch_BridgePack(packet, sizeof(packet), &local, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) < 0);
    loopback_cvar->integer = 1; lookups = 0;
    enabled = Touch_BridgeCreateConfiguredForwarder();
    assert(enabled && lookups == 7);
    assert(Touch_BridgeForward(f, packet, n) < 0); /* existing peer stays disabled */
    assert(Touch_BridgeForward(enabled, packet, n) == 0);
    assert(recvfrom(sock, buffer, sizeof(buffer), 0, (struct sockaddr *)&client, &client_len) == 5);
    assert(!memcmp(buffer, "query", 5));
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&client, client_len) == 5);
    for (n = 0; n < 10000 && !received; ++n)
        assert(Touch_BridgePoll(enabled, receive_reply, &received) >= 0);
    assert(received == 1);
    local.port = local.port == 65535 ? local.port - 1 : local.port + 1;
    n = Touch_BridgePack(packet, sizeof(packet), &local, "query", 5);
    assert(Touch_BridgeForward(enabled, packet, n) == 0);
    local.address[3] = 2;
    n = Touch_BridgePack(packet, sizeof(packet), &local, "query", 5);
    assert(Touch_BridgeForward(enabled, packet, n) < 0);
    memset(local.address, 0, sizeof(local.address)); local.family = 6; local.address[15] = 1;
    n = Touch_BridgePack(packet, sizeof(packet), &local, "query", 5);
    assert(Touch_BridgeForward(enabled, packet, n) < 0);
    loopback_cvar->integer = 0; lookups = 0;
    disabled = Touch_BridgeCreateConfiguredForwarder();
    assert(disabled && lookups == 7);
    local.family = 4; memset(local.address, 0, sizeof(local.address)); local.address[0] = 127; local.address[3] = 1;
    n = Touch_BridgePack(packet, sizeof(packet), &local, "query", 5);
    assert(Touch_BridgeForward(disabled, packet, n) < 0);
    assert(Touch_BridgeForward(enabled, packet, n) == 0); /* snapshot remains enabled */
    Touch_BridgeForwarderDestroy(enabled);
    Touch_BridgeForwarderDestroy(disabled);
    Touch_BridgeForwarderDestroy(f);
    close(sock);
    puts("bridge configured masters: PASS");
    puts("bridge loopback test cvar: PASS");
    return 0;
}
