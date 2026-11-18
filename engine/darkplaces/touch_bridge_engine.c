/* Engine-side forwarder construction. Call on the engine thread after cvars
 * have been loaded; each peer receives a snapshot of the configured masters. */
#include "quakedef.h"
#include "lhnet.h"
#include "touch_bridge.h"
#include <netinet/in.h>
#include <string.h>

struct touch_bridge_forwarder *Touch_BridgeCreateConfiguredForwarder(void)
{
    static const char *const names[] = {
        "sv_master1", "sv_master2", "sv_master3", "sv_master4",
        "sv_masterextra1", "sv_masterextra2", "sv_masterextra3"
    };
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    size_t i;
    if (!f) return NULL;
    for (i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        const char *value = Cvar_VariableString(&cvars_all, names[i], 0);
        lhnetaddress_t resolved;
        struct touch_bridge_address address;
        if (!value || !*value || !LHNETADDRESS_FromString(&resolved, value, 27950)) continue;
        memset(&address, 0, sizeof(address));
        if (resolved.addresstype == LHNETADDRESSTYPE_INET4) {
            struct sockaddr_in native;
            memcpy(&native, resolved.storage, sizeof(native));
            address.family = 4;
            memcpy(address.address, &native.sin_addr, 4);
        } else if (resolved.addresstype == LHNETADDRESSTYPE_INET6) {
            struct sockaddr_in6 native;
            memcpy(&native, resolved.storage, sizeof(native));
            address.family = 6;
            memcpy(address.address, &native.sin6_addr, 16);
        } else continue;
        address.port = (unsigned short)LHNETADDRESS_GetPort(&resolved);
        if (Touch_BridgeAllowMaster(f, &address) < 0) {
            Touch_BridgeForwarderDestroy(f);
            return NULL;
        }
    }
    return f;
}
