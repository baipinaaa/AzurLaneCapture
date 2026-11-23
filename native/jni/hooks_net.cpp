// hooks_net.cpp — socket I/O PLT hook（send/recv/sendto/recvfrom/write/read/close）
#include "azcap.h"

#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include "xhook.h"

// 只 hook 这几类 so 的 PLT：游戏本体 + Unity + 可能的 SDK 载体
static const char *kNetLibs =
        "libunity\\.so$|libil2cpp\\.so$|libmain\\.so$|libreactnativejni\\.so$|libc\\+\\+_shared\\.so$";

typedef ssize_t (*fn_send)(int, const void *, size_t, int);
typedef ssize_t (*fn_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
typedef ssize_t (*fn_recv)(int, void *, size_t, int);
typedef ssize_t (*fn_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
typedef ssize_t (*fn_write)(int, const void *, size_t);
typedef ssize_t (*fn_read)(int, void *, size_t);
typedef int (*fn_close)(int);

static fn_send old_send = nullptr;
static fn_sendto old_sendto = nullptr;
static fn_recv old_recv = nullptr;
static fn_recvfrom old_recvfrom = nullptr;
static fn_write old_write = nullptr;
static fn_read old_read = nullptr;
static fn_close old_close = nullptr;

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
}
