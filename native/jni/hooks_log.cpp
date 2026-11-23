// hooks_log.cpp — liblog PLT hook（Unity/C#/Lua/SDK 的 C 侧日志）
#include "azcap.h"

#include <stdarg.h>
#include <string.h>
#include <stdio.h>
#include <dlfcn.h>

#include "xhook.h"

// 日志 hook 只针对游戏本体/脚本/SDK 载体，**不碰反作弊与广告库**（避免触发自校验）
static const char *kLogLibs =
        "libunity\\.so$|libil2cpp\\.so$|libmain\\.so$|libtolua\\.so$|libblkv\\.so$|libreactnativejni\\.so$";

typedef int (*fn_log_print)(int, const char *, const char *, ...);
typedef int (*fn_log_write)(int, const char *, const char *);
typedef int (*fn_log_vprint)(int, const char *, const char *, va_list);
typedef int (*fn_log_buf_write)(int, int, const char *, const char *);

static fn_log_print old_log_print = nullptr;
static fn_log_write old_log_write = nullptr;
static fn_log_vprint old_log_vprint = nullptr;
static fn_log_buf_write old_log_buf_write = nullptr;

// 真实 liblog 入口（dlsym 拿到符号本体，绕过任何 PLT 替换）
static fn_log_write real_log_write = nullptr;

static const char *safe_tag(const char *t) { return t ? t : "?"; }

static void emit_text(int prio, const char *tag, const char *text, int n) {
    if (!text) return;
    if (n < 0) n = (int)strnlen(text, 4096);
    if (n > 4096) n = 4096;
    az_emit_log(prio, safe_tag(tag), text, n);
}

static int fmt_to_buf(char *buf, size_t cap, const char *fmt, va_list ap) {
    if (!fmt) { buf[0] = 0; return 0; }
    va_list ap2;
    va_copy(ap2, ap);
    int n = vsnprintf(buf, cap, fmt, ap2);
    va_end(ap2);
    if (n < 0) { buf[0] = 0; return 0; }
    if ((size_t)n >= cap) n = (int)cap - 1;
    return n;
}

static int my_log_print(int prio, const char *tag, const char *fmt, ...) {
    char buf[4096];
    va_list ap;
    va_start(ap, fmt);
    int n = fmt_to_buf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    emit_text(prio, tag, buf, n);
    // 变参无法转发给原函数，改用已格式化的 __android_log_write 输出，效果等价
    if (real_log_write) return real_log_write(prio, safe_tag(tag), buf);
    return 0;
}

static int my_log_write(int prio, const char *tag, const char *text) {
    emit_text(prio, tag, text, text ? (int)strnlen(text, 4096) : 0);
    if (old_log_write) return old_log_write(prio, tag, text);
    return real_log_write ? real_log_write(prio, safe_tag(tag), text ? text : "") : 0;
}

static int my_log_vprint(int prio, const char *tag, const char *fmt, va_list ap) {
    char buf[4096];
    int n = fmt_to_buf(buf, sizeof(buf), fmt, ap);
    emit_text(prio, tag, buf, n);
    if (old_log_vprint) return old_log_vprint(prio, tag, fmt, ap);
    return real_log_write ? real_log_write(prio, safe_tag(tag), buf) : 0;
}

static int my_log_buf_write(int bufID, int prio, const char *tag, const char *text) {
    emit_text(prio, tag, text, text ? (int)strnlen(text, 4096) : 0);
    if (old_log_buf_write) return old_log_buf_write(bufID, prio, tag, text);
    return 0;
}

void az_register_log_hooks() {
    real_log_write = (fn_log_write)dlsym(RTLD_DEFAULT, "__android_log_write");
    // 忽略自身，防止 my_log_print -> __android_log_write 被自己的 PLT hook 再捕获
    xhook_ignore("libazcap\\.so$", nullptr);
    xhook_register(kLogLibs, "__android_log_print", (void *)my_log_print, (void **)&old_log_print);
    xhook_register(kLogLibs, "__android_log_write", (void *)my_log_write, (void **)&old_log_write);
    xhook_register(kLogLibs, "__android_log_vprint", (void *)my_log_vprint, (void **)&old_log_vprint);
    xhook_register(kLogLibs, "__android_log_buf_write", (void *)my_log_buf_write,
                   (void **)&old_log_buf_write);
}
