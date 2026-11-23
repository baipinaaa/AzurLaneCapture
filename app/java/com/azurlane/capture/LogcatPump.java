package com.azurlane.capture;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * logcat 管道：Unity / il2cpp / Lua 的日志在 native 层 hook 不到时的兜底通道。
 *
 * <p>注意权限：从 Android 4.1 起普通 app 读不到日志缓冲，`logcat` 只有在 shell
 * （adb）或 root 下才有输出。所以这里优先 `su -c logcat`，拿不到 root 时退回
 * 直接执行 —— 直接执行大概率是空输出，失败原因会写进 app.log，不会静默。
 */
final class LogcatPump {
    private static final String ARGS =
            "-b main -b system -b crash -v threadtime "
                    + "-s Unity:V LuaInterface:V AndroidRuntime:E AzCap:V";

    private static final Pattern LINE = Pattern.compile(
            "^(\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d:\\d\\d[.\\d]*)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:\\s?(.*)$");

    private static Thread thread;

    private LogcatPump() {}

    static void start() {
        if (thread != null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int attempt = 0;
                while (attempt < 20) {
                    attempt++;
                    try {
                        if (pump()) {
                            attempt = 0; // 正常跑过一段，重置退避
                        }
                    } catch (Throwable th) {
                        FileSink.log(Log.WARN, 1, Config.TAG, "logcat: " + th);
                    }
                    try {
                        Thread.sleep(5000L * Math.min(attempt + 1, 6));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                FileSink.log(Log.WARN, 1, Config.TAG, "logcat: 放弃（无权限或反复退出）");
            }
        }, "azcap-logcat");
        t.setDaemon(true);
        t.start();
        thread = t;
    }

    /** 返回 true 表示本次成功读到过数据。 */
    private static boolean pump() throws Exception {
        boolean root = RootShell.available();
        Process p;
        String how;
        if (root) {
            p = new ProcessBuilder("su", "-c", "logcat " + ARGS).start();
            how = "su";
        } else {
            p = new ProcessBuilder(("logcat " + ARGS).split(" ")).start();
            how = "plain";
        }
        FileSink.log(Log.INFO, 1, Config.TAG, "logcat via " + how + (root ? "" : "（无 root，可能无输出）"));

        StringBuilder err = new StringBuilder();
        final BufferedReader er = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), Charset.forName("UTF-8")));
        Thread et = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String l;
                    while ((l = er.readLine()) != null) {
                        if (err.length() < 2048) err.append(l).append('\n');
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "azcap-logcat-err");
        et.setDaemon(true);
        et.start();

        BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")));
        boolean any = false;
        String line;
        while ((line = r.readLine()) != null) {
            any = true;
            handle(line);
        }
        int code = p.waitFor();
        if (!any) {
            FileSink.log(Log.WARN, 1, Config.TAG,
                    "logcat exited code=" + code + " 无输出 " + err.toString().trim());
        }
        return any;
    }

    private static void handle(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            FileSink.log(Log.DEBUG, FileSink.SRC_LOGCAT, "logcat", line);
            return;
        }
        String lvl = m.group(5);
        String tag = m.group(6).trim();
        String msg = m.group(7);
        FileSink.log(level(lvl), FileSink.SRC_LOGCAT, tag, msg);
    }

    private static int level(String c) {
        switch (c.charAt(0)) {
            case 'V': return Log.VERBOSE;
            case 'D': return Log.DEBUG;
            case 'I': return Log.INFO;
            case 'W': return Log.WARN;
            case 'E': return Log.ERROR;
            case 'F':
            case 'A': return Log.ASSERT;
            default: return Log.INFO;
        }
    }
}
