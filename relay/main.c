/* godnotic-relay: the web relay without the game. Browsers reach UDP game
 * servers through it over WebRTC; it announces itself on MQTT brokers
 * (public ones, or your own: relay/README.md). The same code as the opt-in
 * relay in the game (engine/darkplaces/touch_bridge_relay.c), with every
 * limit of touch_bridge.c: masters and the servers they list only, per-peer
 * packet and byte rates, a peer cap, idle and open timeouts, an offer rate.
 *
 *   godnotic-relay [--broker URL]... [--master HOST[:PORT]]... [--topic PREFIX]
 *                  [--stun URL|""] [--max-peers N] [--offers-per-minute N]
 *                  [--allow-loopback]
 */
#define _POSIX_C_SOURCE 200809L
#include "touch_bridge.h"
#include "touch_bridge_relay.h"
#include <arpa/inet.h>
#include <netdb.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/socket.h>

#define MAX_MASTERS 16
static const char *const default_masters[] = {
    "dpmaster.deathmask.net:27950", "dpmaster.tchr.no:27950", "dpm.dpmaster.org:27777"
};
static const char *const default_brokers = "wss://broker.emqx.io:8084/mqtt wss://broker.hivemq.com:8884/mqtt";

static struct touch_bridge_address masters[MAX_MASTERS];
static int master_count, allow_loopback;
static volatile sig_atomic_t stop;

static void on_signal(int s) { (void)s; stop = 1; }
static void log_line(void *c, const char *text)
{
    (void)c;
    fputs(text, stdout);
    fflush(stdout);
}

/* host[:port] (or [v6]:port) -> every address it resolves to */
static void add_master(const char *spec, char *list, size_t size)
{
    char host[256], port[16] = "27950";
    struct addrinfo hints, *res, *a;
    const char *colon;
    memset(&hints, 0, sizeof(hints));
    hints.ai_socktype = SOCK_DGRAM;
    if (spec[0] == '[') {
        const char *end = strchr(spec, ']');
        if (!end || (size_t)(end - spec - 1) >= sizeof(host)) return;
        memcpy(host, spec + 1, (size_t)(end - spec - 1));
        host[end - spec - 1] = 0;
        if (end[1] == ':') snprintf(port, sizeof(port), "%s", end + 2);
    } else {
        colon = strrchr(spec, ':');
        size_t n = colon ? (size_t)(colon - spec) : strlen(spec);
        if (n >= sizeof(host)) return;
        memcpy(host, spec, n);
        host[n] = 0;
        if (colon) snprintf(port, sizeof(port), "%s", colon + 1);
    }
    if (getaddrinfo(host, port, &hints, &res)) {
        fprintf(stderr, "godnotic-relay: cannot resolve master %s\n", spec);
        return;
    }
    for (a = res; a && master_count < MAX_MASTERS; a = a->ai_next) {
        struct touch_bridge_address *m = &masters[master_count];
        char ip[64];
        memset(m, 0, sizeof(*m));
        if (a->ai_family == AF_INET) {
            struct sockaddr_in *s = (struct sockaddr_in *)a->ai_addr;
            m->family = 4; memcpy(m->address, &s->sin_addr, 4); m->port = ntohs(s->sin_port);
        } else if (a->ai_family == AF_INET6) {
            struct sockaddr_in6 *s = (struct sockaddr_in6 *)a->ai_addr;
            m->family = 6; memcpy(m->address, &s->sin6_addr, 16); m->port = ntohs(s->sin6_port);
        } else continue;
        inet_ntop(a->ai_family, m->address, ip, sizeof(ip));
        snprintf(list + strlen(list), size - strlen(list), m->family == 4 ? "%s%s:%u" : "%s[%s]:%u",
                 *list ? " " : "", ip, (unsigned)m->port);
        ++master_count;
    }
    freeaddrinfo(res);
}

static struct touch_bridge_forwarder *make_forwarder(void *c)
{
    struct touch_bridge_forwarder *f = Touch_BridgeForwarderCreate();
    int i;
    (void)c;
    if (!f) return NULL;
    Touch_BridgeSetAllowLoopback(f, allow_loopback);
    for (i = 0; i < master_count; ++i)
        if (Touch_BridgeAllowMaster(f, &masters[i]) < 0) { Touch_BridgeForwarderDestroy(f); return NULL; }
    return f;
}

static void usage(void)
{
    fputs("usage: godnotic-relay [--broker URL]... [--master HOST[:PORT]]... [--topic PREFIX]\n"
          "                      [--stun URL] [--max-peers N] [--offers-per-minute N] [--allow-loopback]\n", stderr);
    exit(2);
}

int main(int argc, char **argv)
{
    static char brokers[2048], master_list[2048];
    const char *master_specs[MAX_MASTERS];
    int specs = 0, i, max_peers = 8, offers = 30;
    struct touch_relay_config config;
    struct touch_relay *relay;
    const char *topic = NULL, *stun = "stun:stun.l.google.com:19302";
    for (i = 1; i < argc; ++i) {
        const char *a = argv[i];
        if (!strcmp(a, "--allow-loopback")) { allow_loopback = 1; continue; }
        if (i + 1 >= argc) usage();
        if (!strcmp(a, "--broker")) snprintf(brokers + strlen(brokers), sizeof(brokers) - strlen(brokers), "%s%s", *brokers ? " " : "", argv[++i]);
        else if (!strcmp(a, "--master") && specs < MAX_MASTERS) master_specs[specs++] = argv[++i];
        else if (!strcmp(a, "--topic")) topic = argv[++i];
        else if (!strcmp(a, "--stun")) stun = argv[++i];
        else if (!strcmp(a, "--max-peers")) max_peers = atoi(argv[++i]);
        else if (!strcmp(a, "--offers-per-minute")) offers = atoi(argv[++i]);
        else usage();
    }
    if (max_peers < 1 || offers < 1) usage();
    if (!*brokers) snprintf(brokers, sizeof(brokers), "%s", default_brokers);
    if (!specs) for (i = 0; i < 3; ++i) master_specs[specs++] = default_masters[i];
    for (i = 0; i < specs; ++i) add_master(master_specs[i], master_list, sizeof(master_list));
    if (!master_count) { fputs("godnotic-relay: no master resolved\n", stderr); return 1; }
    Touch_BridgeSetMaxPeers((unsigned)max_peers);
    memset(&config, 0, sizeof(config));
    config.brokers = brokers;
    config.topic_prefix = topic;
    config.masters = master_list;
    config.stun = stun;
    config.offers_per_minute = (unsigned)offers;
    config.make_forwarder = make_forwarder;
    config.log = log_line;
    relay = Touch_RelayCreate(&config);
    if (!relay) { fputs("godnotic-relay: cannot start\n", stderr); return 1; }
    printf("godnotic-relay %s: brokers %s; masters %s; up to %d peers\n", Touch_RelayId(relay), brokers, master_list, max_peers);
    fflush(stdout);
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    while (!stop) {
        struct timespec ts = {0, 5000000};
        Touch_RelayFrame(relay);
        nanosleep(&ts, NULL);
    }
    Touch_RelayDestroy(relay); /* clears the presence */
    puts("godnotic-relay: stopped");
    return 0;
}
