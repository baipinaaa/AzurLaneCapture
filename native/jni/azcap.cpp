// azcap.cpp — 队列 / flush 线程 / JNI 桥 / hook 启动
#include "azcap.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#include "xhook.h"

#define TAG "azcap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

JavaVM *g_az_vm = nullptr;
static jclass g_bridge = nullptr;
static jmethodID g_on_frame = nullptr;

// ── 帧队列 ────────────────────────────────────────────────────────────────
struct Frame {
    Frame *next;
    int type, dir, fd, seq, port;
    int len, orig;
    int64_t ts_us;
    char addr[64];
    char *data;
};

static pthread_mutex_t g_q_lock = PTHREAD_MUTEX_INITIALIZER;
static Frame *g_head = nullptr;
static Frame *g_tail = nullptr;
static int g_count = 0;
static size_t g_bytes = 0;

#define AZ_MAX_QUEUE 1024
#define AZ_MAX_BYTES (4 * 1024 * 1024)

static pthread_mutex_t g_stat_lock = PTHREAD_MUTEX_INITIALIZER;
static long g_st_dropped = 0;
static long g_st_frames = 0;
static long g_st_bytes = 0;
static long g_st_per[4] = {0};

int64_t az_now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

static void stat_add(int type, int len) {
    pthread_mutex_lock(&g_stat_lock);
    g_st_frames++;
    g_st_bytes += len;
    if (type >= 0 && type < 4) g_st_per[type]++;
    pthread_mutex_unlock(&g_stat_lock);
}

void az_emit(int type, int dir, int fd, int seq, const char *addr, int port,
             int64_t ts_us, const void *data, int len, int orig_len) {
    if (len < 0) return;
    int copy = len > AZ_MAX_FRAME ? AZ_MAX_FRAME : len;
    if (orig_len < 0) orig_len = len;

    Frame *f = (Frame *)calloc(1, sizeof(Frame));
    if (!f) return;
    if (copy > 0) {
        f->data = (char *)malloc((size_t)copy);
        if (!f->data) { free(f); return; }
        memcpy(f->data, data, (size_t)copy);
    }
    f->type = type; f->dir = dir; f->fd = fd; f->seq = seq; f->port = port;
    f->ts_us = ts_us; f->len = copy; f->orig = orig_len;
    if (addr) strncpy(f->addr, addr, sizeof(f->addr) - 1);

    pthread_mutex_lock(&g_q_lock);
    if (g_count >= AZ_MAX_QUEUE || g_bytes + (size_t)copy > AZ_MAX_BYTES) {
        g_st_dropped++;
        pthread_mutex_unlock(&g_q_lock);
        free(f->data);
        free(f);
        return;
    }
    if (g_tail) g_tail->next = f; else g_head = f;
    g_tail = f;
    g_count++;
    g_bytes += (size_t)copy;
    pthread_mutex_unlock(&g_q_lock);
    stat_add(type, copy);
}

// dir 字段复用为来源+级别编码：10+prio 表示 native 日志（Java 侧据此还原级别）
void az_emit_log(int prio, const char *tag, const char *text, int len) {
    az_emit(AZ_TYPE_LOG, 10 + prio, -1, 0, tag, 0, az_now_us(), text, len, len);
}

// ── flush 线程 ────────────────────────────────────────────────────────────
static void deliver(JNIEnv *env, Frame *f) {
    if (!env || !g_bridge || !g_on_frame) return;
    jstring jaddr = env->NewStringUTF(f->addr[0] ? f->addr : "");
    jbyteArray jdata = nullptr;
    if (f->len > 0) {
        jdata = env->NewByteArray(f->len);
        if (jdata) env->SetByteArrayRegion(jdata, 0, f->len, (const jbyte *)f->data);
    }
    env->CallStaticVoidMethod(g_bridge, g_on_frame,
                              (jint)f->type, (jint)f->dir, (jint)f->fd,
                              (jint)f->seq, (jint)f->port, (jlong)f->ts_us,
                              (jint)f->orig, jaddr, jdata);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (jaddr) env->DeleteLocalRef(jaddr);
    if (jdata) env->DeleteLocalRef(jdata);
}

static void *flush_main(void *) {
    JNIEnv *env = nullptr;
    if (g_az_vm) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = (char *)"azcap-flush";
        args.group = nullptr;
        if (g_az_vm->AttachCurrentThread(&env, &args) != JNI_OK) {
            LOGI("flush thread attach failed");
            env = nullptr;
        }
    }
    for (;;) {
        pthread_mutex_lock(&g_q_lock);
        Frame *f = g_head;
        if (f) {
            g_head = f->next;
            if (!g_head) g_tail = nullptr;
            g_count--;
            g_bytes -= (size_t)f->len;
        }
        pthread_mutex_unlock(&g_q_lock);

        if (!f) {
            usleep(40 * 1000);
            continue;
        }
        deliver(env, f);
        free(f->data);
        free(f);
    }
    return nullptr;
}

// ── JNI ──────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_az_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_azurlane_capture_NativeBridge_nativeInit(JNIEnv *env, jclass clazz) {
    if (g_az_vm == nullptr) env->GetJavaVM(&g_az_vm);
    if (g_bridge) env->DeleteGlobalRef(g_bridge);
    g_bridge = (jclass)env->NewGlobalRef(clazz);
    g_on_frame = env->GetStaticMethodID(
            clazz, "onNativeFrame", "(IIIIIIJILjava/lang/String;[B)V");
    LOGI("nativeInit bridge=%p method=%p", (void *)g_bridge, (void *)g_on_frame);
}

JNIEXPORT void JNICALL
Java_com_azurlane_capture_NativeBridge_nativeStart(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    static int started = 0;
    if (started) return;
    started = 1;

    pthread_t th;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&th, &attr, flush_main, nullptr);
    pthread_attr_destroy(&attr);

    xhook_enable_debug(0);
    xhook_enable_sigsegv_protection(1);
    az_register_net_hooks();
    az_register_log_hooks();
    az_register_ssl_hooks();
    int n = xhook_refresh(0);
    LOGI("nativeStart: hooks refreshed, failures=%d", n);
}

JNIEXPORT jint JNICALL
Java_com_azurlane_capture_NativeBridge_nativeRefresh(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return xhook_refresh(0);
}

JNIEXPORT jstring JNICALL
Java_com_azurlane_capture_NativeBridge_nativeStats0(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char buf[512];
    az_stats_json(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

}  // extern "C"

void az_stats_json(char *out, size_t cap) {
    pthread_mutex_lock(&g_stat_lock);
    long frames = g_st_frames, bytes = g_st_bytes, drop = g_st_dropped;
    long net = g_st_per[AZ_TYPE_NET], lg = g_st_per[AZ_TYPE_LOG], tls = g_st_per[AZ_TYPE_TLS];
    pthread_mutex_unlock(&g_stat_lock);
    pthread_mutex_lock(&g_q_lock);
    int q = g_count;
    size_t qb = g_bytes;
    pthread_mutex_unlock(&g_q_lock);
    snprintf(out, cap,
             "{\"frames\":%ld,\"bytes\":%ld,\"dropped\":%ld,\"queue\":%d,\"queueBytes\":%zu,"
             "\"net\":%ld,\"log\":%ld,\"tls\":%ld}",
             frames, bytes, drop, q, qb, net, lg, tls);
}
