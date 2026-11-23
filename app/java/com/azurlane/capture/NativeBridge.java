package com.azurlane.capture;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

/** native 库加载与 JNI 回调入口。 */
final class NativeBridge {
    private static volatile boolean loaded = false;
    private static volatile boolean started = false;
    private static volatile String status = "not-started";
    private static volatile String lastRefresh = "";
    private static volatile String detail = "";

    static native void nativeInit();

    static native void nativeStart();

    static native int nativeRefresh();

    static native void nativeSetVerbose(boolean on);

    static native String nativeStats0();

    static void setVerbose(boolean on) {
        if (!loaded) return;
        try {
            nativeSetVerbose(on);
        } catch (Throwable ignored) {
        }
    }

    /** 由 JNI flush 线程调用。 */
    @SuppressWarnings("unused")
    static void onNativeFrame(int type, int dir, int fd, int seq, int port, long tsUs,
                              int orig, String addr, byte[] data) {
        FileSink.frame(type, dir, fd, seq, port, tsUs, orig, addr, data);
    }

    static void start(ApplicationInfo moduleInfo) {
        if (started) return;
        started = true;
        String dir = moduleInfo == null ? null : moduleInfo.nativeLibraryDir;
        detail = "path=" + dir + " abi=" + abi();
        if (dir == null) {
            status = "no-nativeLibraryDir";
            return;
        }
        try {
            System.load(dir + "/libazcap.so");
            loaded = true;
        } catch (Throwable t) {
            // 这一层失败通常是 so 没打进 APK 的 arm64-v8a 目录
            status = "load-failed";
            detail += " dlopen=" + oneLine(t);
            Log.e(Config.TAG, "native load failed: " + dir, t);
            return;
        }
        try {
            nativeInit();
            nativeStart();
            if (Config.verbose) setVerbose(true);
            status = "started";
            scheduleRefresh();
            Log.i(Config.TAG, "native started: " + detail);
        } catch (Throwable t) {
            // 典型：NoSuchMethodError —— Java 方法与 so 里 GetStaticMethodID 签名不一致
            status = "init-failed";
            detail += " init=" + oneLine(t);
            Log.e(Config.TAG, "native init failed: " + detail, t);
        }
    }

    private static String abi() {
        try {
            String[] a = Build.SUPPORTED_ABIS;
            return a != null && a.length > 0 ? a[0] : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String oneLine(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** 后加载的 so（libunity/libil2cpp/libssl）需要多次 refresh 才能被 hook。 */
    private static void scheduleRefresh() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int[] delays = {2000, 3000, 5000, 10000, 20000, 40000};
                int failures = -1;
                try {
                    failures = nativeRefresh();
                } catch (Throwable ignored) {
                }
                for (int d : delays) {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        failures = nativeRefresh();
                    } catch (Throwable th) {
                        lastRefresh = "refresh-error:" + th.getClass().getSimpleName();
                    }
                }
                lastRefresh = "last-failures=" + failures;
                Log.i(Config.TAG, "native refresh done: " + lastRefresh);
            }
        }, "azcap-refresh");
        t.setDaemon(true);
        t.start();
    }

    static String hooksJson() {
        return "{\"native\":\"" + esc(status) + "\",\"loaded\":" + loaded
                + ",\"refresh\":\"" + esc(lastRefresh) + "\""
                + ",\"detail\":\"" + esc(detail) + "\"}";
    }

    static String nativeStats() {
        if (!loaded) return "{\"loaded\":false,\"status\":\"" + esc(status) + "\"}";
        try {
            String s = nativeStats0();
            return s == null ? "{\"loaded\":true}" : s;
        } catch (Throwable t) {
            return "{\"loaded\":true,\"error\":\"" + esc(t.getClass().getSimpleName()) + "\"}";
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private NativeBridge() {}
}
