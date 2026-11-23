// azcap.h — 公共定义：帧类型、队列出口、JNI 句柄
#pragma once

#include <jni.h>
#include <stdint.h>
#include <stddef.h>

// 帧类型（与 Java 侧 NativeBridge 保持一致）
enum : int {
    AZ_TYPE_NET = 1,   // TCP/UDP 明文
    AZ_TYPE_LOG = 2,   // __android_log_* 捕获
    AZ_TYPE_TLS = 3,   // SSL_write / SSL_read 明文
};

// 方向
enum : int {
    AZ_DIR_TX = 1,
    AZ_DIR_RX = 2,
    AZ_DIR_NONE = 0,
};

// 单帧最大负载（超出截断，origLen 保留原始长度）
#define AZ_MAX_FRAME (64 * 1024)

extern JavaVM *g_az_vm;

// 单调递增的墙钟微秒（CLOCK_REALTIME）
int64_t az_now_us(void);

// 入队（内部拷贝 data，调用方无需持有）
void az_emit(int type, int dir, int fd, int seq, const char *addr, int port,
             int64_t ts_us, const void *data, int len, int orig_len);

// 日志便捷入口：prio 为 android Log 优先级
void az_emit_log(int prio, const char *tag, const char *text, int len);

// 统计（返回 JSON 字符串，由 Java 取走）
void az_stats_json(char *out, size_t cap);

// hooks_* 注册入口
void az_register_net_hooks();
void az_register_log_hooks();
void az_register_ssl_hooks();

// fd → 连接信息
struct AzConn {
    int is_sock;    // 0 未知/非 socket, 1 socket 但非 inet, 2 inet
    int seq;        // 连接序号
    int port;
    char addr[64];
};
// 查询（命中返回 1 并填充 out；未命中返回 0）
int az_conn_lookup(int fd, AzConn *out);
// 记录 / 更新（返回填充后的连接信息）
void az_conn_note(int fd, AzConn *out);
// 关闭时移除（重建哈希表，避免线性探测链断裂）
void az_conn_forget(int fd);
