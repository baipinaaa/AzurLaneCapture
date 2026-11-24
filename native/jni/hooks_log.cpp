// hooks_log.cpp — liblog PLT hook（Unity / il2cpp / Lua / ART 的 C 侧日志）
//
// 说明：Android 上 Unity 的 Debug.Log* 与 il2cpp 异常最终都走
// __android_log_print(prio, "Unity", ...)，不经过 java.util.logging，
// 因此 Java 侧 hook android.util.Log 抓不到。这里从调用方 so 的 PLT 上抓。
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

static volatile int g_verbose = 0;

void az_set_verbose(int on) { g_verbose = on; }

static const char *safe_tag(const char *t) { return t ? t : "?"; }

// 需要保留的 tag（其余是 SDK 噪音：APM / 广告 / 支付回调 …）
static const char *kTagAllow[] = {
        "Unity",          // Unity 引擎 + il2cpp 异常 + Lua Debug.Log
        "LuaInterface",   // tolua / xLua 的 Lua 打印
        "AndroidRuntime", // Java 未捕获异常
        "DEBUG",          // libc abort / 崩溃前的 C 侧栈
        "libc",           // "Fatal signal" 前后
        "art",            // ART 运行时警告
        "AzCap",          // 本模块
        "CRASH",
        "Il2Cpp",
        "tolua",
        "Mono",
};

static int tag_allowed(const char *tag) {
    if (g_verbose) return 1;
    if (tag == nullptr || tag[0] == 0) return 0;
    for (size_t i = 0; i < sizeof(kTagAllow) / sizeof(kTagAllow[0]); i++) {
        const char *a = kTagAllow[i];
        size_t n = strlen(a);
        if (strncmp(tag, a, n) == 0) return 1;
    }
    return 0;
}

static void emit_text(int prio, const char *tag, const char *text, int n) {
    if (!text) return;
    if (!tag_allowed(tag)) return;
    if (n < 0) n = (int)strnlen(text, 8192);
    if (n > 8192) n = 8192;
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
    char buf[8192];
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
    emit_text(prio, tag, text, text ? (int)strnlen(text, 8192) : 0);
    if (old_log_write) return old_log_write(prio, tag, text);
    return real_log_write ? real_log_write(prio, safe_tag(tag), text ? text : "") : 0;
}

static int my_log_vprint(int prio, const char *tag, const char *fmt, va_list ap) {
    char buf[8192];
    int n = fmt_to_buf(buf, sizeof(buf), fmt, ap);
    emit_text(prio, tag, buf, n);
    if (old_log_vprint) return old_log_vprint(prio, tag, fmt, ap);
    return real_log_write ? real_log_write(prio, safe_tag(tag), buf) : 0;
}

static int my_log_buf_write(int bufID, int prio, const char *tag, const char *text) {
    emit_text(prio, tag, text, text ? (int)strnlen(text, 8192) : 0);
    if (old_log_buf_write) return old_log_buf_write(bufID, prio, tag, text);
    return 0;
}

int az_log_hook_mask(void) {
    return (old_log_print ? 1 : 0) | (old_log_write ? 2 : 0)
           | (old_log_vprint ? 4 : 0) | (old_log_buf_write ? 8 : 0);
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
