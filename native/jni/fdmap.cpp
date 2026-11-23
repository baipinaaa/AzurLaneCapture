// fdmap.cpp — fd → 对端地址/连接序号 缓存
#include "azcap.h"

#include <pthread.h>
#include <string.h>
#include <stdio.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>

#define TAB_SIZE 512
#define MAX_ADDR 64

struct Ent {
    int used;
    int fd;
    uint64_t tick;
    AzConn c;
};

static Ent g_tab[TAB_SIZE];
static Ent g_keep[TAB_SIZE];  // forget 重建时的暂存（避免 40KB 栈分配）
static pthread_mutex_t g_m = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_tick = 0;
static int g_next_seq = 0;

static uint32_t hash_fd(int fd) {
    uint32_t x = (uint32_t)fd;
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    return x & (TAB_SIZE - 1);
}

int az_conn_lookup(int fd, AzConn *out) {
    if (fd < 0) return 0;
    uint32_t h = hash_fd(fd);
    int hit = 0;
    pthread_mutex_lock(&g_m);
    for (int i = 0; i < TAB_SIZE; i++) {
        Ent *e = &g_tab[(h + i) & (TAB_SIZE - 1)];
        if (!e->used) break;
        if (e->fd == fd) {
            e->tick = ++g_tick;
            if (out) *out = e->c;
            hit = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_m);
    return hit;
}

// AF_UNIX 且对端是 logd 的写入 socket 时返回 1（is_sock=3 专用通道）
static int is_logd_unix(const struct sockaddr *sa) {
    if (sa == nullptr || sa->sa_family != AF_UNIX) return 0;
    const struct sockaddr_un *un = (const struct sockaddr_un *)sa;
    size_t n = sizeof(un->sun_path);
    // sun_path 不保证 NUL 结尾，用 strnlen 保护
    size_t len = strnlen(un->sun_path, n);
    if (len == 0) return 0;
    return strstr(un->sun_path, "logdw") != nullptr || strstr(un->sun_path, "logd") != nullptr;
}

// 探测 fd 是否为 inet socket；返回 AzConn（is_sock: 0 非 socket, 1 非 inet socket, 2 inet, 3 logd）
static void probe(int fd, AzConn *out) {
    memset(out, 0, sizeof(*out));
    struct sockaddr_storage ss;
    socklen_t sl = sizeof(ss);
    if (getpeername(fd, (struct sockaddr *)&ss, &sl) != 0) {
        sl = sizeof(ss);
        if (getsockname(fd, (struct sockaddr *)&ss, &sl) != 0) {
            out->is_sock = 0;
            return;
        }
        if (ss.ss_family != AF_INET && ss.ss_family != AF_INET6) {
            if (is_logd_unix((struct sockaddr *)&ss)) {
                out->is_sock = 3;
                strncpy(out->addr, "logd", MAX_ADDR - 1);
                return;
            }
            out->is_sock = 1;
            return;
        }
        // 未连接的 socket：记录本地端口，地址留空
        out->is_sock = 2;
        if (ss.ss_family == AF_INET) {
            out->port = ntohs(((struct sockaddr_in *)&ss)->sin_port);
        } else {
            out->port = ntohs(((struct sockaddr_in6 *)&ss)->sin6_port);
        }
        out->seq = 0;
        return;
    }
    if (ss.ss_family == AF_INET) {
        struct sockaddr_in *in = (struct sockaddr_in *)&ss;
        inet_ntop(AF_INET, &in->sin_addr, out->addr, MAX_ADDR);
        out->port = ntohs(in->sin_port);
        out->is_sock = 2;
    } else if (ss.ss_family == AF_INET6) {
        struct sockaddr_in6 *in6 = (struct sockaddr_in6 *)&ss;
        inet_ntop(AF_INET6, &in6->sin6_addr, out->addr, MAX_ADDR);
        out->port = ntohs(in6->sin6_port);
        out->is_sock = 2;
    } else if (is_logd_unix((struct sockaddr *)&ss)) {
        out->is_sock = 3;
        strncpy(out->addr, "logd", MAX_ADDR - 1);
    } else {
        out->is_sock = 1;
    }
}

void az_conn_note(int fd, AzConn *out) {
    AzConn c;
    if (az_conn_lookup(fd, &c)) {
        if (out) *out = c;
        return;
    }
    probe(fd, &c);
    c.seq = (c.is_sock == 2) ? __atomic_add_fetch(&g_next_seq, 1, __ATOMIC_RELAXED) : 0;

    uint32_t h = hash_fd(fd);
    pthread_mutex_lock(&g_m);
    Ent *slot = nullptr;
    for (int i = 0; i < TAB_SIZE; i++) {
        Ent *e = &g_tab[(h + i) & (TAB_SIZE - 1)];
        if (!e->used) { slot = e; break; }
        if (e->fd == fd) { slot = e; break; }
    }
    if (!slot) {
        // 表满：淘汰最久未用
        Ent *oldest = &g_tab[0];
        for (int i = 1; i < TAB_SIZE; i++) {
            if (g_tab[i].tick < oldest->tick) oldest = &g_tab[i];
        }
        slot = oldest;
    }
    slot->used = 1;
    slot->fd = fd;
    slot->tick = ++g_tick;
    slot->c = c;
    pthread_mutex_unlock(&g_m);

    if (out) *out = c;
}

void az_conn_forget(int fd) {
    if (fd < 0) return;
    pthread_mutex_lock(&g_m);
    // 收集存活的条目，排除目标 fd
    Ent *keep = g_keep;
    int n = 0;
    for (int i = 0; i < TAB_SIZE; i++) {
        if (g_tab[i].used && g_tab[i].fd != fd) keep[n++] = g_tab[i];
    }
    memset(g_tab, 0, sizeof(g_tab));
    for (int i = 0; i < n; i++) {
        uint32_t h = hash_fd(keep[i].fd);
        for (int j = 0; j < TAB_SIZE; j++) {
            Ent *e = &g_tab[(h + j) & (TAB_SIZE - 1)];
            if (!e->used) {
                *e = keep[i];
                break;
            }
        }
    }
    pthread_mutex_unlock(&g_m);
}
