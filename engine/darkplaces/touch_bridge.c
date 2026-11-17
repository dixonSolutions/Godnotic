#include "touch_bridge.h"
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>

int Touch_BridgeCreatePeer(void *context, rtcDescriptionCallbackFunc description,
                           rtcCandidateCallbackFunc candidate, rtcDataChannelCallbackFunc channel)
{
    rtcConfiguration config;
    int peer;
    memset(&config, 0, sizeof(config));
    config.disableAutoNegotiation = true;
    peer = rtcCreatePeerConnection(&config);
    if (peer < 0)
        return peer;
    rtcSetUserPointer(peer, context);
    if (rtcSetLocalDescriptionCallback(peer, description) < 0 ||
        rtcSetLocalCandidateCallback(peer, candidate) < 0 ||
        rtcSetDataChannelCallback(peer, channel) < 0) {
        rtcDeletePeerConnection(peer);
        return RTC_ERR_FAILURE;
    }
    return peer;
}

int Touch_BridgeAcceptChannel(int channel, void *context, rtcOpenCallbackFunc open,
                              rtcMessageCallbackFunc message)
{
    rtcSetUserPointer(channel, context);
    if (rtcSetOpenCallback(channel, open) < 0 || rtcSetMessageCallback(channel, message) < 0)
        return RTC_ERR_FAILURE;
    return 0;
}

int Touch_BridgeOpenChannel(int peer, const char *label, void *context,
                            rtcOpenCallbackFunc open, rtcMessageCallbackFunc message)
{
    rtcDataChannelInit init;
    int channel;
    memset(&init, 0, sizeof(init));
    init.reliability.unordered = true;
    init.reliability.unreliable = true;
    init.reliability.maxRetransmits = 0;
    channel = rtcCreateDataChannelEx(peer, label, &init);
    if (channel >= 0 && Touch_BridgeAcceptChannel(channel, context, open, message) < 0) {
        rtcDeleteDataChannel(channel);
        return RTC_ERR_FAILURE;
    }
    return channel;
}

int Touch_BridgeSend(int channel, const void *bytes, int size)
{
    if (!bytes || size <= 0)
        return RTC_ERR_INVALID;
    return rtcSendMessage(channel, (const char *)bytes, size);
}

int Touch_BridgePack(void *out, int capacity, const struct touch_bridge_address *address,
                     const void *payload, int size)
{
    unsigned char *p = (unsigned char *)out;
    int n, header;
    if (!out || !address || !payload || size <= 0 || size > TOUCH_BRIDGE_MAX_DATAGRAM ||
        !address->port || (address->family != 4 && address->family != 6))
        return RTC_ERR_INVALID;
    n = address->family == 4 ? 4 : 16;
    header = n + 4;
    if (capacity < header || size > capacity - header)
        return RTC_ERR_INVALID;
    p[0] = TOUCH_BRIDGE_VERSION;
    p[1] = address->family;
    memcpy(p + 2, address->address, (size_t)n);
    p[2 + n] = (unsigned char)(address->port >> 8);
    p[3 + n] = (unsigned char)address->port;
    memcpy(p + header, payload, (size_t)size);
    return header + size;
}

int Touch_BridgeUnpack(const void *packet, int size, struct touch_bridge_address *address,
                       const unsigned char **payload, int *payload_size)
{
    const unsigned char *p = (const unsigned char *)packet;
    int n, header;
    if (!p || !address || !payload || !payload_size || size < 2 ||
        p[0] != TOUCH_BRIDGE_VERSION || (p[1] != 4 && p[1] != 6))
        return RTC_ERR_INVALID;
    n = p[1] == 4 ? 4 : 16;
    header = n + 4;
    if (size <= header || size - header > TOUCH_BRIDGE_MAX_DATAGRAM ||
        !(p[2 + n] | p[3 + n]))
        return RTC_ERR_INVALID;
    memset(address, 0, sizeof(*address));
    address->family = p[1];
    memcpy(address->address, p + 2, (size_t)n);
    address->port = (unsigned short)((p[2 + n] << 8) | p[3 + n]);
    *payload = p + header;
    *payload_size = size - header;
    return 0;
}

/* Separate sockets keep IPv4 and IPv6 behavior independent of IPV6_V6ONLY. */
struct touch_bridge_forwarder {
    int ipv4, ipv6;
};

static int bridge_socket(int family)
{
    int fd = socket(family, SOCK_DGRAM, 0);
    if (fd >= 0 && fcntl(fd, F_SETFL, O_NONBLOCK) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

struct touch_bridge_forwarder *Touch_BridgeForwarderCreate(void)
{
    struct touch_bridge_forwarder *f = malloc(sizeof(*f));
    if (!f)
        return NULL;
    f->ipv4 = bridge_socket(AF_INET);
    f->ipv6 = bridge_socket(AF_INET6);
    if (f->ipv4 < 0 && f->ipv6 < 0) {
        free(f);
        return NULL;
    }
    return f;
}

void Touch_BridgeForwarderDestroy(struct touch_bridge_forwarder *f)
{
    if (!f)
        return;
    if (f->ipv4 >= 0) close(f->ipv4);
    if (f->ipv6 >= 0) close(f->ipv6);
    free(f);
}

int Touch_BridgeForward(struct touch_bridge_forwarder *f, const void *packet, int size)
{
    struct touch_bridge_address address;
    const unsigned char *payload;
    int len, fd;
    struct sockaddr_storage storage;
    socklen_t addrlen;
    if (!f || Touch_BridgeUnpack(packet, size, &address, &payload, &len) < 0)
        return RTC_ERR_INVALID;
    memset(&storage, 0, sizeof(storage));
    if (address.family == 4) {
        struct sockaddr_in *a = (struct sockaddr_in *)&storage;
        a->sin_family = AF_INET;
        a->sin_port = htons(address.port);
        memcpy(&a->sin_addr, address.address, 4);
        addrlen = sizeof(*a);
        fd = f->ipv4;
    } else {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&storage;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons(address.port);
        memcpy(&a->sin6_addr, address.address, 16);
        addrlen = sizeof(*a);
        fd = f->ipv6;
    }
    if (fd < 0 || sendto(fd, payload, (size_t)len, 0, (struct sockaddr *)&storage, addrlen) != len)
        return RTC_ERR_FAILURE;
    return 0;
}

static int bridge_poll_socket(int fd, touch_bridge_reply_fn reply, void *context)
{
    unsigned char payload[TOUCH_BRIDGE_MAX_DATAGRAM + 1];
    unsigned char packet[TOUCH_BRIDGE_MAX_DATAGRAM + 20];
    struct sockaddr_storage storage;
    struct touch_bridge_address address;
    int count = 0;
    if (fd < 0)
        return 0;
    for (;;) {
        socklen_t addrlen = sizeof(storage);
        ssize_t len = recvfrom(fd, payload, sizeof(payload), 0, (struct sockaddr *)&storage, &addrlen);
        int size;
        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) return count;
            if (errno == EINTR) continue;
            return RTC_ERR_FAILURE;
        }
        if (len == 0 || len > TOUCH_BRIDGE_MAX_DATAGRAM) continue;
        memset(&address, 0, sizeof(address));
        if (storage.ss_family == AF_INET) {
            const struct sockaddr_in *a = (const struct sockaddr_in *)&storage;
            address.family = 4;
            address.port = ntohs(a->sin_port);
            memcpy(address.address, &a->sin_addr, 4);
        } else if (storage.ss_family == AF_INET6) {
            const struct sockaddr_in6 *a = (const struct sockaddr_in6 *)&storage;
            address.family = 6;
            address.port = ntohs(a->sin6_port);
            memcpy(address.address, &a->sin6_addr, 16);
        } else continue;
        size = Touch_BridgePack(packet, sizeof(packet), &address, payload, (int)len);
        if (size > 0) {
            reply(context, packet, size);
            ++count;
        }
    }
}

int Touch_BridgePoll(struct touch_bridge_forwarder *f, touch_bridge_reply_fn reply, void *context)
{
    int a, b;
    if (!f || !reply) return RTC_ERR_INVALID;
    a = bridge_poll_socket(f->ipv4, reply, context);
    b = bridge_poll_socket(f->ipv6, reply, context);
    return a < 0 || b < 0 ? RTC_ERR_FAILURE : a + b;
}

void Touch_BridgeForwardMessage(int channel, const char *message, int size, void *context)
{
    (void)channel;
    /* Negative sizes represent text messages and are rejected by Unpack. */
    Touch_BridgeForward((struct touch_bridge_forwarder *)context, message, size);
}

static void bridge_send_reply(void *context, const void *packet, int size)
{
    int channel = *(const int *)context;
    Touch_BridgeSend(channel, packet, size);
}

int Touch_BridgePollChannel(struct touch_bridge_forwarder *f, int channel)
{
    return Touch_BridgePoll(f, bridge_send_reply, &channel);
}
