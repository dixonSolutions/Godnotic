#define _POSIX_C_SOURCE 200809L
#include "touch_bridge.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static time_t clock_offset;
int bridge_test_clock(clockid_t id, struct timespec *time)
{
    int result = clock_gettime(id, time);
    if (!result && id == CLOCK_MONOTONIC) time->tv_sec += clock_offset;
    return result;
}

static void description(int id, const char *sdp, const char *type, void *ctx)
{ (void)id; (void)sdp; (void)type; (void)ctx; }
static void candidate(int id, const char *value, const char *mid, void *ctx)
{ (void)id; (void)value; (void)mid; (void)ctx; }
static void channel(int id, int dc, void *ctx)
{ (void)id; (void)dc; (void)ctx; }
static void reply(void *ctx, const void *packet, int size)
{ (void)ctx; (void)packet; (void)size; }

int main(void)
{
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    struct touch_bridge_forwarder *other = Touch_BridgeForwarderCreate();
    struct touch_bridge_address address = {4, {127, 0, 0, 1}, 12345};
    unsigned char packet[128];
    struct timespec wait = {0, 600000000};
    FILE *log = tmpfile();
    char lines[1024];
    int saved = dup(STDERR_FILENO), n, peer;
    assert(f && other && log && saved >= 0);
    assert(dup2(fileno(log), STDERR_FILENO) >= 0);
    assert(Touch_BridgeSetIdleTimeout(f, 0) == RTC_ERR_INVALID);
    assert(Touch_BridgeSetIdleTimeout(f, 1) == 0);
    n = Touch_BridgePack(packet, sizeof(packet), &address, "test", 4);
    assert(Touch_BridgeForward(f, packet, 1) == RTC_ERR_INVALID);
    assert(Touch_BridgeForward(f, packet, 1) == RTC_ERR_INVALID);
    assert(Touch_BridgeForward(f, packet, n) == RTC_ERR_FAILURE);
    assert(Touch_BridgeDropCount(f, TOUCH_BRIDGE_DROP_INVALID) == 2);
    assert(Touch_BridgeDropCount(f, TOUCH_BRIDGE_DROP_DENIED) == 1);
    assert(Touch_BridgeDropCount(other, TOUCH_BRIDGE_DROP_INVALID) == 0);
    assert(Touch_BridgeForward(other, packet, 1) == RTC_ERR_INVALID); /* global log throttle */
    assert(Touch_BridgeAllowAddress(f, &address) == 0);
    nanosleep(&wait, NULL);
    assert(Touch_BridgeForward(f, packet, n) == 0); /* accepted traffic resets idle */
    nanosleep(&wait, NULL);
    assert(Touch_BridgePoll(f, reply, NULL) >= 0);
    assert(Touch_BridgeForward(f, packet, 1) == RTC_ERR_INVALID); /* does not reset */
    nanosleep(&wait, NULL);
    peer = Touch_BridgeCreatePeer(NULL, description, candidate, channel);
    assert(peer >= 0);
    assert(Touch_BridgePollPeer(f, peer, -1) == RTC_ERR_NOT_AVAIL);
    assert(Touch_BridgeDeletePeer(peer) == RTC_ERR_INVALID);
    assert(Touch_BridgeForward(f, packet, n) == RTC_ERR_NOT_AVAIL);
    assert(Touch_BridgePoll(f, reply, NULL) == RTC_ERR_NOT_AVAIL);
    assert(Touch_BridgeDropCount(f, TOUCH_BRIDGE_DROP_IDLE) == 1);
    assert(Touch_BridgePoll(other, reply, NULL) >= 0);
    clock_offset = 61;
    assert(Touch_BridgeForward(other, packet, 1) == RTC_ERR_INVALID);
    fflush(stderr);
    assert(dup2(saved, STDERR_FILENO) >= 0);
    close(saved);
    rewind(log);
    n = (int)fread(lines, 1, sizeof(lines) - 1, log); lines[n] = 0;
    assert(strstr(lines, "invalid drops=1") && strstr(lines, "denied drops=1") && strstr(lines, "idle drops=1"));
    assert(!strstr(lines, "invalid drops=2") && !strstr(lines, "invalid drops=3"));
    assert(strstr(lines, "invalid drops=5")); /* next minute logs the cumulative count */
    fclose(log);
    Touch_BridgeForwarderDestroy(f); Touch_BridgeForwarderDestroy(other);
    rtcCleanup();
    puts("bridge idle timeout and drop reporting: PASS");
    return 0;
}
