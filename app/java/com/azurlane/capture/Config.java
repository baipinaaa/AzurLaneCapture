package com.azurlane.capture;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 常量与运行期开关。
 *
 * <p>开关可以在运行时改：把 JSON 写到游戏私有目录下的
 * {@code files/logs/azurlane/config.json}，sink 线程每 15 秒重新读一次，
 * 改完无需重启游戏。可改字段：verbose / log / net / tls / http /
 * frame / maxSizeMb / keepSessions。
 */
final class Config {
    static final String TAG = "AzCap";
    static final String MODULE_PKG = "com.azurlane.capture";
    static final String TARGET_PKG = "com.bilibili.azurlane";

    /** 期望落盘根目录（root 通道） */
    static final String ROOT_BASE = "/storage/emulated/0/logs/azurlane";

    /** 是否尝试 root 直写；false 时只写 app 私有外部目录。 */
    static final boolean USE_ROOT = true;

    static final int QUEUE_CAPACITY = 8192;
    static final int MAX_LINE = 8 * 1024;
    static final int PREVIEW_BYTES = 512;

    /** 游戏长连接端口（明文 protobuf，按帧切分最有价值）。 */
    static final int[] GAME_PORTS = {8017, 8018};

    // ── 运行期开关（默认值，可被 config.json 覆盖） ──────────────────────
    static volatile boolean verbose = false;
    static volatile boolean captureLog = true;
    static volatile boolean captureNet = true;
    static volatile boolean captureTls = false;   // TLS 明文性价比最低，默认关
    static volatile boolean captureHttp = false;  // SDK / 统计 HTTP 噪音大，默认关
    static volatile boolean frameSplit = true;    // 8017/8018 按 protobuf 帧切分
    static volatile int maxFileMb = 8;
    static volatile int keepSessions = 10;

    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    static long maxFileBytes() {
        int mb = maxFileMb;
        if (mb < 1) mb = 1;
        if (mb > 128) mb = 128;
        return mb * 1024L * 1024L;
    }

    static final int TYPE_NET = 1;
    static final int TYPE_LOG = 2;
    static final int TYPE_TLS = 3;
    static final int TYPE_RAW = 4;

    static final int DIR_TX = 1;
    static final int DIR_RX = 2;

    /** 是否是需要记录的端口（HTTP/HTTPS 噪音默认丢弃）。 */
    static boolean portWanted(int port) {
        if (isGamePort(port)) return true;
        if (captureTls && (port == 443 || port == 8443)) return true;
        return captureHttp;
    }

    static boolean isGamePort(int port) {
        for (int p : GAME_PORTS) if (p == port) return true;
        return false;
    }

    /** 运行时加载 config.json；解析失败的字段保持原值。 */
    static void reload(File f) {
        if (f == null || !f.isFile()) return;
        String text = readAll(f);
        if (text == null || text.isEmpty()) return;
        Boolean b;
        Integer i;
        if ((b = bool(text, "verbose")) != null) applyVerbose(b);
        if ((b = bool(text, "log")) != null) captureLog = b;
        if ((b = bool(text, "net")) != null) captureNet = b;
        if ((b = bool(text, "tls")) != null) captureTls = b;
        if ((b = bool(text, "http")) != null) captureHttp = b;
        if ((b = bool(text, "frame")) != null) frameSplit = b;
        if ((i = num(text, "maxSizeMb")) != null) maxFileMb = i;
        if ((i = num(text, "keepSessions")) != null) keepSessions = i;
    }

    private static void applyVerbose(boolean on) {
        verbose = on;
        try {
            NativeBridge.setVerbose(on);
        } catch (Throwable ignored) {
        }
    }

    // ── 极简 JSON 取值（只处理扁平的 "key": value） ───────────────────────
    private static String raw(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int c = json.indexOf(':', i + k.length());
        if (c < 0) return null;
        int s = c + 1;
        int n = json.length();
        while (s < n && Character.isWhitespace(json.charAt(s))) s++;
        int e = s;
        while (e < n && ",}\n\r".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(s, e).trim();
    }

    private static Boolean bool(String json, String key) {
        String v = raw(json, key);
        if (v == null) return null;
        if (v.equalsIgnoreCase("true") || v.equals("1")) return Boolean.TRUE;
        if (v.equalsIgnoreCase("false") || v.equals("0")) return Boolean.FALSE;
        return null;
    }

    private static Integer num(String json, String key) {
        String v = raw(json, key);
        if (v == null) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readAll(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024) break;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private Config() {}
}
