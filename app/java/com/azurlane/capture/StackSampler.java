package com.azurlane.capture;

import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主线程栈采样。
 *
 * <p>卡死时最有用的证据是"同一段栈持续了多久"。每 {@link #PERIOD_MS} 采一次
 * main 线程栈，按前 {@link #KEY_FRAMES} 帧做指纹聚合，逐条写 stacks.jsonl，
 * 每 60 秒把出现次数最多的栈写进 stacks_summary.txt。
 */
final class StackSampler {
    private static final long PERIOD_MS = 5000;
    private static final int KEY_FRAMES = 12;
    private static final int TOP_N = 20;

    private static final Map<String, Integer> COUNT = new HashMap<String, Integer>();

    private static volatile int lastHash = 0;
    private static volatile long sameSince = 0;
    private static volatile Thread thread;

    private StackSampler() {}

    static void start() {
        if (thread != null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastSummary = 0;
                while (true) {
                    try {
                        Thread.sleep(PERIOD_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        tick();
                    } catch (Throwable ignored) {
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastSummary >= 60000) {
                        lastSummary = now;
                        try {
                            dumpSummary();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "azcap-stacks");
        t.setDaemon(true);
        t.start();
        thread = t;
    }

    private static void tick() {
        Looper main = Looper.getMainLooper();
        if (main == null) return;
        Thread mt = main.getThread();
        StackTraceElement[] st = mt.getStackTrace();
        if (st == null || st.length == 0) return;

        StringBuilder top = new StringBuilder(256);
        for (int i = 0; i < st.length && i < KEY_FRAMES; i++) {
            if (i > 0) top.append(" <- ");
            top.append(st[i].toString());
        }
        String key = top.toString();
        int hash = key.hashCode();
        if (hash == lastHash) {
            if (sameSince == 0) sameSince = System.currentTimeMillis();
        } else {
            lastHash = hash;
            sameSince = System.currentTimeMillis();
        }
        long stuckMs = sameSince == 0 ? 0 : System.currentTimeMillis() - sameSince;

        synchronized (COUNT) {
            Integer c = COUNT.get(key);
            COUNT.put(key, c == null ? 1 : c + 1);
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"t\":").append(System.currentTimeMillis())
                .append(",\"ms\":").append(FileSink.elapsedMs())
                .append(",\"state\":\"").append(mt.getState()).append('"')
                .append(",\"depth\":").append(st.length)
                .append(",\"sameMs\":").append(stuckMs)
                .append(",\"top\":\"").append(esc(st.length > 0 ? st[0].toString() : "")).append('"')
                .append(",\"frames\":\"").append(esc(key)).append("\"}\n");
        FileSink.writeRaw("stacks.jsonl", sb.toString());

        if (stuckMs > 30000 && stuckMs < 35000) {
            // 同一段栈卡住超过 30 秒，单独留一条醒目的标记
            FileSink.log(Log.WARN, 1, "MainThreadStuck",
                    "主线程同一栈已持续 " + (stuckMs / 1000) + "s: " + key);
        }
    }

    private static void dumpSummary() {
        List<Map.Entry<String, Integer>> list;
        synchronized (COUNT) {
            list = new ArrayList<Map.Entry<String, Integer>>(COUNT.entrySet());
        }
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# top stacks @ ").append(System.currentTimeMillis())
                .append(" (每 5s 一次采样，按前 ").append(KEY_FRAMES).append(" 帧聚合)\n");
        int n = Math.min(TOP_N, list.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            sb.append("\n[").append(i + 1).append("] x").append(e.getValue()).append('\n')
                    .append(e.getKey().replace(" <- ", "\n    <- ")).append('\n');
        }
        FileSink.writeRaw("stacks_summary.txt", sb.toString());
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
