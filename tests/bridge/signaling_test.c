#define _POSIX_C_SOURCE 200809L
#include "quakedef.h"
#include "touch_bridge.h"
#include <assert.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>

static xcommand_t command;
static atomic_int gathered, received;
void Cmd_AddCommand(unsigned flags, const char *name, xcommand_t fn, const char *help)
{ (void)flags; (void)help; assert(!strcmp(name, "bridge_offer")); command = fn; }
void Con_Printf(const char *fmt, ...)
{ va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap); }
struct touch_bridge_forwarder *Touch_BridgeCreateConfiguredForwarder(void)
{
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    assert(f && Touch_BridgeSetAllowLoopback(f, 1) == 0);
    return f;
}
static void desc(int pc, const char *sdp, const char *type, void *p)
{ (void)pc; (void)sdp; (void)type; (void)p; }
static void cand(int pc, const char *sdp, const char *mid, void *p)
{ (void)pc; (void)sdp; (void)mid; (void)p; }
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
static void delay(void) { struct timespec ts = {0, 1000000}; nanosleep(&ts, NULL); }
int main(void)
{
    char offerpath[] = "/tmp/bridge-offer-XXXXXX", answerpath[] = "/tmp/bridge-answer-XXXXXX";
    char sdp[65536], packet[64], data[64];
    const char *args[] = {"bridge_offer", offerpath, answerpath};
    cmd_state_t cmd = {0};
    struct sockaddr_in addr = {0}, source; socklen_t alen = sizeof(addr), slen = sizeof(source);
    struct touch_bridge_address dest = {0};
    int peer, channel, fd, sock, i, n; FILE *file;
    fd = mkstemp(offerpath); assert(fd >= 0); close(fd);
    fd = mkstemp(answerpath); assert(fd >= 0); close(fd); unlink(answerpath);
    sock = socket(AF_INET, SOCK_DGRAM, 0); assert(sock >= 0);
    addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    assert(getsockname(sock, (struct sockaddr *)&addr, &alen) == 0);
    peer = Touch_BridgeCreatePeer(NULL, desc, cand, incoming); assert(peer >= 0);
    assert(rtcSetGatheringStateChangeCallback(peer, gather) == 0);
    channel = Touch_BridgeOpenChannel(peer, "test", NULL, open_channel, got_message); assert(channel >= 0);
    assert(rtcSetLocalDescription(peer, "offer") == 0);
    for (i = 0; i < 10000 && !atomic_load(&gathered); ++i) delay();
    assert(atomic_load(&gathered));
    assert(rtcGetLocalDescription(peer, sdp, sizeof(sdp)) > 0);
    file = fopen(offerpath, "wb"); assert(file); assert(fwrite(sdp, 1, strlen(sdp), file) == strlen(sdp)); assert(fclose(file) == 0);
    Touch_BridgeSignalingInit(); assert(command);
    cmd.argc = 3; for (i = 0; i < 3; ++i) cmd.argv[i] = args[i]; command(&cmd);
    for (i = 0; i < 10000 && access(answerpath, F_OK); ++i) { Touch_BridgeSignalingFrame(); delay(); }
    file = fopen(answerpath, "rb"); assert(file); n = (int)fread(sdp, 1, sizeof(sdp)-1, file); fclose(file); assert(n > 0); sdp[n] = 0;
    assert(strstr(sdp, "a=candidate:") && rtcSetRemoteDescription(peer, sdp, "answer") == 0);
    for (i = 0; i < 10000 && !rtcIsOpen(channel); ++i) { Touch_BridgeSignalingFrame(); delay(); }
    assert(rtcIsOpen(channel));
    dest.family = 4; dest.address[0] = 127; dest.address[3] = 1; dest.port = ntohs(addr.sin_port);
    n = Touch_BridgePack(packet, sizeof(packet), &dest, "hello", 5); assert(n > 0);
    assert(Touch_BridgeSend(channel, packet, n) == 0);
    for (i = 0; i < 10000; ++i) {
        Touch_BridgeSignalingFrame();
        n = recvfrom(sock, data, sizeof(data), MSG_DONTWAIT, (struct sockaddr *)&source, &slen);
        if (n >= 0) break;
        delay();
    }
    assert(n == 5 && !memcmp(data, "hello", 5));
    assert(sendto(sock, "reply", 5, 0, (struct sockaddr *)&source, slen) == 5);
    for (i = 0; i < 10000 && !atomic_load(&received); ++i) { Touch_BridgeSignalingFrame(); delay(); }
    assert(atomic_load(&received));
    Touch_BridgeSignalingShutdown(); rtcDeleteDataChannel(channel); Touch_BridgeDeletePeer(peer);
    close(sock); unlink(offerpath); unlink(answerpath);
    puts("bridge file signaling and UDP forwarding: PASS"); return 0;
}
