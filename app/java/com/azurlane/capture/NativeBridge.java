package com.azurlane.capture;

import android.content.pm.ApplicationInfo;
import android.util.Log;

/** native 库加载与 JNI 回调入口。 */
final class NativeBridge {
    private static volatile boolean loaded = false;
    private static volatile boolean started = false;
    private static volatile String status = "not-started";
    private static volatile String lastRefresh = "";

    static native void nativeInit();

    static native void nativeStart();

    static native int nativeRefresh();

    static native String nativeStats0();

    /** 由 JNI flush 线程调用。 */
    @SuppressWarnings("unused")
    static void onNativeFrame(int type, int dir, int fd, int seq, int port, long tsUs,
                              int orig, String addr, byte[] data) {
        FileSink.frame(type, dir, fd, seq, port, tsUs, orig, addr, data);
    }

    static void start(ApplicationInfo moduleInfo) {
        if (started) return;
        started = true;
        try {
            String dir = moduleInfo == null ? null : moduleInfo.nativeLibraryDir;
            if (dir == null) {
                status = "no-nativeLibraryDir";
                return;
            }
            System.load(dir + "/libazcap.so");
            loaded = true;
            nativeInit();
            nativeStart();
            status = "started";
            scheduleRefresh();
        } catch (Throwable t) {
            status = "load-failed:" + t.getClass().getSimpleName();
            Log.e(Config.TAG, "native load failed: " + dirOf(moduleInfo), t);
        }
    }

    private static String dirOf(ApplicationInfo ai) {
        return ai == null ? "null" : String.valueOf(ai.nativeLibraryDir);
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
                + ",\"refresh\":\"" + esc(lastRefresh) + "\"}";
    }

    static String nativeStats() {
        if (!loaded) return "{\"loaded\":false}";
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
