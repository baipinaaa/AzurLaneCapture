// hooks_ssl.cpp — TLS 明文 hook（OpenSSL 1.1：SSL_write / SSL_read / *_ex）
#include "azcap.h"

#include <dlfcn.h>
#include <stddef.h>
#include <string.h>

#include "xhook.h"

static const char *kTlsLibs = "libssl.*\\.so$|libunity\\.so$|libil2cpp\\.so$|libmonobdwgc.*\\.so$";

typedef int (*fn_ssl_write)(void *, const void *, int);
typedef int (*fn_ssl_read)(void *, void *, int);
typedef int (*fn_ssl_write_ex)(void *, const void *, size_t, size_t *);
typedef int (*fn_ssl_read_ex)(void *, void *, size_t, size_t *);
typedef int (*fn_ssl_get_fd)(void *);

static fn_ssl_write old_ssl_write = nullptr;
static fn_ssl_read old_ssl_read = nullptr;
static fn_ssl_write_ex old_ssl_write_ex = nullptr;
static fn_ssl_read_ex old_ssl_read_ex = nullptr;
static fn_ssl_get_fd real_ssl_get_fd = nullptr;

static void record_tls(void *ssl, int dir, const void *buf, int n) {
    if (n <= 0) return;
    AzConn c;
    memset(&c, 0, sizeof(c));
    if (real_ssl_get_fd) {
        int fd = real_ssl_get_fd(ssl);
        az_conn_note(fd, &c);
        if (c.is_sock != 2) {
            c.is_sock = 2;
            strncpy(c.addr, "tls", sizeof(c.addr) - 1);
            c.port = 443;
        }
        az_emit(AZ_TYPE_TLS, dir, fd, c.seq, c.addr, c.port, az_now_us(), buf, n, n);
    } else {
        az_emit(AZ_TYPE_TLS, dir, -1, 0, "tls", 443, az_now_us(), buf, n, n);
    }
}

static int my_ssl_write(void *ssl, const void *buf, int num) {
    if (!old_ssl_write) return -1;
    int r = old_ssl_write(ssl, buf, num);
    record_tls(ssl, AZ_DIR_TX, buf, r);
    return r;
}

static int my_ssl_read(void *ssl, void *buf, int num) {
    if (!old_ssl_read) return -1;
    int r = old_ssl_read(ssl, buf, num);
    record_tls(ssl, AZ_DIR_RX, buf, r);
    return r;
}

static int my_ssl_write_ex(void *ssl, const void *buf, size_t num, size_t *written) {
    if (!old_ssl_write_ex) return 0;
    int r = old_ssl_write_ex(ssl, buf, num, written);
    if (r == 1 && written) record_tls(ssl, AZ_DIR_TX, buf, (int)*written);
    return r;
}

static int my_ssl_read_ex(void *ssl, void *buf, size_t num, size_t *readbytes) {
    if (!old_ssl_read_ex) return 0;
    int r = old_ssl_read_ex(ssl, buf, num, readbytes);
    if (r == 1 && readbytes) record_tls(ssl, AZ_DIR_RX, buf, (int)*readbytes);
    return r;
}

void az_register_ssl_hooks() {
    real_ssl_get_fd = (fn_ssl_get_fd)dlsym(RTLD_DEFAULT, "SSL_get_fd");
    if (!real_ssl_get_fd) real_ssl_get_fd = (fn_ssl_get_fd)dlsym(RTLD_DEFAULT, "SSL_get_rfd");
    xhook_register(kTlsLibs, "SSL_write", (void *)my_ssl_write, (void **)&old_ssl_write);
    xhook_register(kTlsLibs, "SSL_read", (void *)my_ssl_read, (void **)&old_ssl_read);
    xhook_register(kTlsLibs, "SSL_write_ex", (void *)my_ssl_write_ex, (void **)&old_ssl_write_ex);
    xhook_register(kTlsLibs, "SSL_read_ex", (void *)my_ssl_read_ex, (void **)&old_ssl_read_ex);
}
