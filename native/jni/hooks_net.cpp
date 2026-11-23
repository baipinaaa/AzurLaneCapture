// hooks_net.cpp — socket I/O PLT hook（send/recv/sendto/recvfrom/write/read/close）
#include "azcap.h"

#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <string.h>
#include <unistd.h>

#include "xhook.h"

// 只 hook 这几类 so 的 PLT：游戏本体 + Unity + 可能的 SDK 载体
static const char *kNetLibs =
        "libunity\\.so$|libil2cpp\\.so$|libmain\\.so$|libreactnativejni\\.so$|libc\\+\\+_shared\\.so$";

// logd 写入走 liblog.so；Unity 若静态链接 liblog，调用点在 libunity 里，两个都挂
static const char *kLogdLibs =
        "libunity\\.so$|libil2cpp\\.so$|libmain\\.so$|libreactnativejni\\.so$|libc\\+\\+_shared\\.so$|liblog\\.so$";

typedef ssize_t (*fn_send)(int, const void *, size_t, int);
typedef ssize_t (*fn_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
typedef ssize_t (*fn_recv)(int, void *, size_t, int);
typedef ssize_t (*fn_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
typedef ssize_t (*fn_write)(int, const void *, size_t);
typedef ssize_t (*fn_read)(int, void *, size_t);
typedef int (*fn_close)(int);
typedef ssize_t (*fn_sendmsg)(int, const struct msghdr *, int);
typedef ssize_t (*fn_writev)(int, const struct iovec *, int);

static fn_send old_send = nullptr;
static fn_sendto old_sendto = nullptr;
static fn_recv old_recv = nullptr;
static fn_recvfrom old_recvfrom = nullptr;
static fn_write old_write = nullptr;
static fn_read old_read = nullptr;
static fn_close old_close = nullptr;
static fn_sendmsg old_sendmsg = nullptr;
static fn_writev old_writev = nullptr;

// 防止 az_emit_log -> liblog -> sendmsg -> 本 hook 递归
static __thread int g_in_logd = 0;

// ── logd 写入通道：解析 liblog 的 log_msg，拿到 Unity/il2cpp 的日志 ──
// 格式：struct log_msg { uint32 len; uint16 hdr_size; int16 pid; uint16 tid; uint16 uid;
//                       uint16 security; } 然后 msg[] = [prio(1B)][tag\0][text\0]
static void logd_frame(const void *buf, int n) {
    if (n < 24) return;
    const unsigned char *b = (const unsigned char *)buf;
    unsigned hdr = (unsigned)b[4] | ((unsigned)b[5] << 8);
    if (hdr < 12 || hdr > 64 || (int)hdr + 2 > n) hdr = 20;
    int prio = b[hdr] & 0x7;
    const char *tag = (const char *)b + hdr + 1;
    const char *end = (const char *)b + n;
    const char *nul = (const char *)memchr(tag, 0, (size_t)(end - tag));
    if (!nul) return;
    const char *text = nul + 1;
    int tlen = (int)(nul - tag);
    if (tlen <= 0 || tlen > 64 || text >= end) return;
    int mlen = (int)strnlen(text, (size_t)(end - text));
    if (mlen <= 0) return;
    az_emit_log(prio, tag, text, mlen);
}

static void logd_iov(const struct iovec *iov, int cnt) {
    char tmp[8192];
    int n = 0;
    for (int i = 0; i < cnt && i < 8; i++) {
        size_t l = iov[i].iov_len;
        if (l > sizeof(tmp) - (size_t)n) l = sizeof(tmp) - (size_t)n;
        if (l == 0) continue;
        memcpy(tmp + n, iov[i].iov_base, l);
        n += (int)l;
        if (n >= (int)sizeof(tmp)) break;
    }
    if (n > 0) logd_frame(tmp, n);
}

static void logd_maybe(int fd, const struct iovec *iov, int cnt) {
    if (g_in_logd) return;
    AzConn c;
    if (!az_conn_note(fd, &c)) return;
    if (c.is_sock != 3) return;
    g_in_logd = 1;
    logd_iov(iov, cnt);
    g_in_logd = 0;
}

// probe=1: fd 可能是 socket（send/recv 系列，值得探测）
// probe=0: fd 可能是文件（read/write，只在已知 inet socket 时记录）
static void record(int fd, int dir, const void *buf, int n, int probe) {
    if (n <= 0) return;
    AzConn c;
    if (probe) {
        az_conn_note(fd, &c);
    } else if (!az_conn_lookup(fd, &c)) {
        return;
    }
    if (c.is_sock != 2) return;
    az_emit(AZ_TYPE_NET, dir, fd, c.seq, c.addr, c.port, az_now_us(), buf, n, n);
}

static ssize_t my_send(int fd, const void *buf, size_t len, int flags) {
    if (!old_send) return -1;
    ssize_t r = old_send(fd, buf, len, flags);
    record(fd, AZ_DIR_TX, buf, (int)r, 1);
    return r;
}

static ssize_t my_sendto(int fd, const void *buf, size_t len, int flags,
                         const struct sockaddr *addr, socklen_t alen) {
    if (!old_sendto) return -1;
    struct iovec iov = {(void *)buf, len};
    logd_maybe(fd, &iov, 1);
    ssize_t r = old_sendto(fd, buf, len, flags, addr, alen);
    record(fd, AZ_DIR_TX, buf, (int)r, 1);
    return r;
}

static ssize_t my_recv(int fd, void *buf, size_t len, int flags) {
    if (!old_recv) return -1;
    ssize_t r = old_recv(fd, buf, len, flags);
    record(fd, AZ_DIR_RX, buf, (int)r, 1);
    return r;
}

static ssize_t my_recvfrom(int fd, void *buf, size_t len, int flags,
                           struct sockaddr *addr, socklen_t *alen) {
    if (!old_recvfrom) return -1;
    ssize_t r = old_recvfrom(fd, buf, len, flags, addr, alen);
    record(fd, AZ_DIR_RX, buf, (int)r, 1);
    return r;
}

static ssize_t my_write(int fd, const void *buf, size_t len) {
    if (!old_write) return -1;
    ssize_t r = old_write(fd, buf, len);
    record(fd, AZ_DIR_TX, buf, (int)r, 0);
    return r;
}

static ssize_t my_read(int fd, void *buf, size_t len) {
    if (!old_read) return -1;
    ssize_t r = old_read(fd, buf, len);
    record(fd, AZ_DIR_RX, buf, (int)r, 0);
    return r;
}

static ssize_t my_sendmsg(int fd, const struct msghdr *msg, int flags) {
    if (!old_sendmsg) return -1;
    if (msg != nullptr) logd_maybe(fd, msg->msg_iov, (int)msg->msg_iovlen);
    return old_sendmsg(fd, msg, flags);
}

static ssize_t my_writev(int fd, const struct iovec *iov, int cnt) {
    if (!old_writev) return -1;
    logd_maybe(fd, iov, cnt);
    return old_writev(fd, iov, cnt);
}

static int my_close(int fd) {
    if (!old_close) return -1;
    int r = old_close(fd);
    az_conn_forget(fd);
    return r;
}

void az_register_net_hooks() {
    xhook_register(kNetLibs, "send", (void *)my_send, (void **)&old_send);
    xhook_register(kNetLibs, "sendto", (void *)my_sendto, (void **)&old_sendto);
    xhook_register(kNetLibs, "recv", (void *)my_recv, (void **)&old_recv);
    xhook_register(kNetLibs, "recvfrom", (void *)my_recvfrom, (void **)&old_recvfrom);
    xhook_register(kNetLibs, "write", (void *)my_write, (void **)&old_write);
    xhook_register(kNetLibs, "read", (void *)my_read, (void **)&old_read);
    xhook_register(kNetLibs, "close", (void *)my_close, (void **)&old_close);
    xhook_register(kLogdLibs, "sendmsg", (void *)my_sendmsg, (void **)&old_sendmsg);
    xhook_register(kLogdLibs, "writev", (void *)my_writev, (void **)&old_writev);
}
