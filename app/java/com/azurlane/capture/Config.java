package com.azurlane.capture;

/** 常量与运行期开关（无界面，开关由文件/默认值决定）。 */
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
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    static final int PREVIEW_BYTES = 512;

    static volatile boolean captureLog = true;
    static volatile boolean captureNet = true;
    static volatile boolean captureTls = true;

    static final int TYPE_NET = 1;
    static final int TYPE_LOG = 2;
    static final int TYPE_TLS = 3;

    static final int DIR_TX = 1;
    static final int DIR_RX = 2;

    private Config() {}
}
