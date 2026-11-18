#include "quakedef.h"
#include "lhnet.h"
#include "touch_bridge.h"
#include <arpa/inet.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

cvar_state_t cvars_all;
static int lookups;
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
    struct touch_bridge_forwarder *f = Touch_BridgeCreateConfiguredForwarder();
    struct touch_bridge_address master = {4, {127, 0, 0, 1}, 27950};
    unsigned char packet[64];
    int n;
    assert(f && lookups == 7);
    n = Touch_BridgePack(packet, sizeof(packet), &master, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) == 0);
    master.port++;
    n = Touch_BridgePack(packet, sizeof(packet), &master, "query", 5);
    assert(Touch_BridgeForward(f, packet, n) < 0);
    Touch_BridgeForwarderDestroy(f);
    puts("bridge configured masters: PASS");
    return 0;
}
