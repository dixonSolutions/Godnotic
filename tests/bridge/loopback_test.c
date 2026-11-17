#define _POSIX_C_SOURCE 200809L
#include "touch_bridge.h"
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define COUNT 1000
#define SIZE 1200
struct peer { int id, other; atomic_int channel, open; };
struct event { struct event *next; int target, description; char *value, *type; };
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static struct event *head, **tail = &head;
static atomic_int received, invalid;
static unsigned char seen[COUNT];
static struct touch_bridge_forwarder *forwarder;
static atomic_int forwarding, reply_received;
static unsigned short server_port;

static void enqueue(struct peer *p, int description, const char *value, const char *type)
{
    struct event *e = calloc(1, sizeof(*e));
    if (!e || !(e->value = strdup(value)) || !(e->type = strdup(type))) abort();
    e->target = p->other; e->description = description;
    pthread_mutex_lock(&mutex); *tail = e; tail = &e->next; pthread_mutex_unlock(&mutex);
}
static void description_cb(int id, const char *sdp, const char *type, void *ptr)
{ (void)id; enqueue(ptr, 1, sdp, type); }
static void candidate_cb(int id, const char *candidate, const char *mid, void *ptr)
{ (void)id; enqueue(ptr, 0, candidate, mid); }
static void open_cb(int id, void *ptr)
{ struct peer *p = ptr; (void)id; atomic_store(&p->open, 1); }
static void message_cb(int id, const char *data, int size, void *ptr)
{
    uint32_t seq; int i; (void)id; (void)ptr;
    if (atomic_load(&forwarding)) {
        struct touch_bridge_address address;
        const unsigned char *payload;
        int len;
        if (Touch_BridgeUnpack(data, size, &address, &payload, &len) == 0 &&
            address.family == 4 && address.port == server_port &&
            !memcmp(address.address, "\177\0\0\1", 4) && len == 5 &&
            !memcmp(payload, "reply", 5)) atomic_store(&reply_received, 1);
        else atomic_fetch_add(&invalid, 1);
        return;
    }
    if (size != SIZE) { atomic_fetch_add(&invalid, 1); return; }
    memcpy(&seq, data, sizeof(seq));
    if (seq >= COUNT) { atomic_fetch_add(&invalid, 1); return; }
    for (i = sizeof(seq); i < SIZE; ++i)
        if ((unsigned char)data[i] != (unsigned char)(seq + i)) { atomic_fetch_add(&invalid, 1); return; }
    pthread_mutex_lock(&mutex);
    if (seen[seq]) atomic_fetch_add(&invalid, 1);
    else { seen[seq] = 1; atomic_fetch_add(&received, 1); }
    pthread_mutex_unlock(&mutex);
}
static void forward_cb(int id, const char *data, int size, void *ptr)
{
    (void)ptr;
    Touch_BridgeForwardMessage(id, data, size, forwarder);
}
static void channel_cb(int id, int channel, void *ptr)
{
    struct peer *p = ptr; (void)id;
    if (Touch_BridgeAcceptChannel(channel, p, open_cb, message_cb) < 0) abort();
    atomic_store(&p->channel, channel);
}
static int pump(void)
{
    struct event *e; int rc;
    for (;;) {
        pthread_mutex_lock(&mutex); e = head;
        if (e) { head = e->next; if (!head) tail = &head; }
        pthread_mutex_unlock(&mutex);
        if (!e) return 0;
        rc = e->description ? rtcSetRemoteDescription(e->target, e->value, e->type)
                            : rtcAddRemoteCandidate(e->target, e->value, e->type);
        if (rc >= 0 && e->description && !strcmp(e->type, "offer"))
            rc = rtcSetLocalDescription(e->target, "answer");
        free(e->value); free(e->type); free(e);
        if (rc < 0) { fprintf(stderr, "signaling failed: %d\n", rc); return -1; }
    }
}
static void delay(void) { struct timespec ts = {0, 1000000}; nanosleep(&ts, NULL); }
int main(void)
{
    struct peer a = {0}, b = {0}; int dc, i, sent = 0, result = 1; char data[SIZE];
    rtcInitLogger(RTC_LOG_WARNING, NULL);
    a.id = Touch_BridgeCreatePeer(&a, description_cb, candidate_cb, channel_cb);
    b.id = Touch_BridgeCreatePeer(&b, description_cb, candidate_cb, channel_cb);
    if (a.id < 0 || b.id < 0) { fprintf(stderr, "peer creation failed\n"); return 1; }
    a.other = b.id; b.other = a.id;
    dc = Touch_BridgeOpenChannel(a.id, "loopback", &a, open_cb, message_cb);
    if (dc < 0 || rtcSetLocalDescription(a.id, "offer") < 0) { fprintf(stderr, "offer failed\n"); goto done; }
    for (i = 0; i < 15000 && (!atomic_load(&a.open) || !atomic_load(&b.open)); ++i) {
        if (pump() < 0) goto done;
        delay();
    }
    if (!atomic_load(&a.open) || !atomic_load(&b.open)) { fprintf(stderr, "channel open timed out\n"); goto done; }
    for (i = 0; i < COUNT; ++i) {
        uint32_t seq = (uint32_t)i; int j;
        memcpy(data, &seq, sizeof(seq));
        for (j = sizeof(seq); j < SIZE; ++j) data[j] = (char)(seq + j);
        if (Touch_BridgeSend(dc, data, SIZE) < 0) { fprintf(stderr, "send failed at %d\n", i); goto done; }
        ++sent; delay();
    }
    for (i = 0; i < 5000 && atomic_load(&received) < COUNT; ++i) delay();
    printf("sent=%d received=%d lost=%d invalid=%d\n", sent, atomic_load(&received), sent - atomic_load(&received), atomic_load(&invalid));
    if (atomic_load(&received) > 0 && !atomic_load(&invalid)) {
        struct sockaddr_in server = {0};
        struct sockaddr_storage client;
        struct touch_bridge_address address = {4, {127, 0, 0, 1}, 0};
        unsigned char packet[128], buf[128];
        socklen_t slen = sizeof(server), clen = sizeof(client);
        struct timeval timeout = {5, 0};
        int sock = socket(AF_INET, SOCK_DGRAM, 0), n;
        if (sock < 0) goto done;
        server.sin_family = AF_INET; server.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(sock, (struct sockaddr *)&server, sizeof(server)) < 0 ||
            getsockname(sock, (struct sockaddr *)&server, &slen) < 0 ||
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) abort();
        address.port = server_port = ntohs(server.sin_port);
        forwarder = Touch_BridgeForwarderCreate();
        if (!forwarder || rtcSetMessageCallback(atomic_load(&b.channel), forward_cb) < 0) abort();
        atomic_store(&forwarding, 1);
        n = Touch_BridgePack(packet, sizeof(packet), &address, "query", 5);
        if (Touch_BridgeSend(dc, packet, n) < 0 ||
            recvfrom(sock, buf, sizeof(buf), 0, (struct sockaddr *)&client, &clen) != 5 ||
            memcmp(buf, "query", 5) ||
            sendto(sock, "reply", 5, 0, (struct sockaddr *)&client, clen) != 5) abort();
        for (i = 0; i < 5000 && !atomic_load(&reply_received); ++i) {
            if (Touch_BridgePollChannel(forwarder, atomic_load(&b.channel)) < 0) abort();
            delay();
        }
        close(sock);
        if (atomic_load(&reply_received) && !atomic_load(&invalid)) {
            puts("bridge data channel to UDP round trip: PASS"); result = 0;
        }
    }
done:
    rtcDeletePeerConnection(a.id); rtcDeletePeerConnection(b.id);
    Touch_BridgeForwarderDestroy(forwarder); rtcCleanup();
    return result;
}
