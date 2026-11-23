package com.azurlane.capture;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 落盘核心：有界队列 -> 单后台线程 -> 按文件分代写入。
 * 写入优先走 root（su cat >>），否则降级到目标 app 的私有外部目录。
 */
final class FileSink {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ArrayBlockingQueue<Rec> Q =
            new ArrayBlockingQueue<Rec>(Config.QUEUE_CAPACITY);
    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong written = new AtomicLong();

    private static volatile boolean started = false;
    private static volatile String baseDir = null;
    private static volatile boolean rootMode = false;
    private static volatile String modeNote = "";

    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US);
    private static final SimpleDateFormat LINE_TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final LinkedHashMap<String, Gen> gens =
            new LinkedHashMap<String, Gen>(32, 0.75f, true);
    private static final LinkedHashMap<String, RootShell.Writer> writers =
            new LinkedHashMap<String, RootShell.Writer>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RootShell.Writer> e) {
                    if (size() > 24) {
                        e.getValue().close();
                        return true;
                    }
                    return false;
                }
            };

    static final long STARTED_AT = System.currentTimeMillis();
    private static long lastStatsAt = 0;

    static final class Rec {
        int type, dir, fd, seq, port, prio, orig;
        long ts;
        String addr, tag;
        byte[] data;
    }

    static final class Gen {
        int gen;
        long bytes;
    }

    // ── 对外入口（可在任意线程调用，只入队） ─────────────────────────────
    static void log(int prio, int source, String tag, String msg) {
        if (!Config.captureLog || !started) return;
        String text = msg == null ? "" : msg;
        if (text.length() > Config.MAX_LINE) {
            text = text.substring(0, Config.MAX_LINE) + "…(+" + (text.length() - Config.MAX_LINE) + ")";
        }
        Rec r = new Rec();
        r.type = Config.TYPE_LOG;
        r.dir = source;
        r.prio = prio;
        r.tag = tag;
        r.ts = System.currentTimeMillis();
        r.data = text.getBytes(UTF8);
        offer(r);
    }

    static void crash(Thread t, Throwable e) {
        if (!started) return;
        StringBuilder sb = new StringBuilder();
        sb.append("thread=").append(t == null ? "?" : t.getName())
          .append(" time=").append(LINE_TS.format(new Date())).append('\n');
        if (e != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw.toString());
        }
        Rec r = new Rec();
        r.type = Config.TYPE_LOG;
        r.dir = 3;
        r.prio = Log.ERROR;
        r.tag = "CRASH";
        r.ts = System.currentTimeMillis();
        r.data = sb.toString().getBytes(UTF8);
        offer(r);
    }

    static void frame(int type, int dir, int fd, int seq, int port, long tsUs,
                      int orig, String addr, byte[] data) {
        if (!started) return;
        if (type == Config.TYPE_LOG && !Config.captureLog) return;
        if (type == Config.TYPE_NET && !Config.captureNet) return;
        if (type == Config.TYPE_TLS && !Config.captureTls) return;
        Rec r = new Rec();
        r.type = type;
        r.dir = dir;
        r.fd = fd;
        r.seq = seq;
        r.port = port;
        r.ts = tsUs / 1000;
        r.orig = orig;
        r.addr = addr;
        r.tag = addr; // native 帧用 addr 字段承载 tag
        r.data = data;
        offer(r);
    }

    private static void offer(Rec r) {
        if (!Q.offer(r)) dropped.incrementAndGet();
    }

    // ── 启动 ──────────────────────────────────────────────────────────────
    static void start(final Context ctx) {
        if (started) return;
        synchronized (FileSink.class) {
            if (started) return;
            started = true;
        }
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        final String stamp = FILE_TS.format(new Date());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                initPrivate(app, stamp);
                writeMeta(app, stamp);
                if (Config.USE_ROOT) kickOffRootProbe(app, stamp);
                loop();
            }
        }, "azcap-sink");
        t.setDaemon(true);
        t.start();
    }

    /** 私有外部目录：游戏进程自身一定有写权限，先落这里不丢数据。 */
    private static void initPrivate(Context ctx, String stamp) {
        File ext = ctx.getExternalFilesDir(null);
        File d = new File(ext != null ? ext : ctx.getFilesDir(),
                "logs/azurlane/" + stamp + processSuffix(ctx));
        if (!d.mkdirs() && !d.isDirectory()) {
            d = new File(ctx.getFilesDir(), "logs/" + stamp);
            d.mkdirs();
        }
        baseDir = d.getAbsolutePath();
        rootMode = false;
        modeNote = "private";
    }

    private static String processSuffix(Context ctx) {
        String proc = currentProcess();
        String pkg = ctx.getPackageName();
        if (proc.equals(pkg) || proc.isEmpty() || "?".equals(proc)) return "";
        return "/proc_" + proc.replace(':', '_').replace('/', '_');
    }

    /** 延迟 25s 再探测 root（避开启动期反作弊扫描），成功后切目录并搬运已有文件。 */
    private static void kickOffRootProbe(final Context ctx, final String stamp) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(25000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!RootShell.available()) {
                    modeNote = "no-root";
                    return;
                }
                String target = Config.ROOT_BASE + "/" + stamp + processSuffix(ctx);
                String out = RootShell.exec(
                        "mkdir -p '" + target + "' && chmod 0775 '" + target + "' && echo AZOK", 12000);
                if (out == null || !out.contains("AZOK")) {
                    modeNote = "root-failed";
                    return;
                }
                String old = baseDir;
                synchronized (writers) {
                    for (RootShell.Writer w : writers.values()) w.close();
                    writers.clear();
                }
                gens.clear();
                baseDir = target;
                rootMode = true;
                modeNote = "root";
                if (old != null && !old.equals(target)) {
                    RootShell.exec("cp -a '" + old + "/.' '" + target + "/' 2>/dev/null; echo AZOK", 30000);
                }
                log(Log.INFO, 1, Config.TAG, "write dir -> " + target);
            }
        }, "azcap-rootprobe");
        t.setDaemon(true);
        t.start();
    }

    private static void writeMeta(Context ctx, String stamp) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n")
          .append("  \"module\": \"").append(Config.MODULE_PKG).append("\",\n")
          .append("  \"target\": \"").append(ctx.getPackageName()).append("\",\n")
          .append("  \"process\": \"").append(currentProcess()).append("\",\n")
          .append("  \"startedAt\": ").append(STARTED_AT).append(",\n")
          .append("  \"stamp\": \"").append(stamp).append("\",\n")
          .append("  \"dir\": \"").append(baseDir).append("\",\n")
          .append("  \"write\": \"").append(modeNote).append("\",\n")
          .append("  \"root\": ").append(rootMode).append(",\n")
          .append("  \"device\": \"").append(Build.MANUFACTURER).append(' ')
          .append(Build.MODEL).append("\",\n")
          .append("  \"android\": \"").append(Build.VERSION.RELEASE).append(" (sdk ")
          .append(Build.VERSION.SDK_INT).append(")\",\n")
          .append("  \"config\": {\"log\": ").append(Config.captureLog)
          .append(", \"net\": ").append(Config.captureNet)
          .append(", \"tls\": ").append(Config.captureTls).append("},\n")
          .append("  \"hooks\": ").append(NativeBridge.hooksJson()).append("\n")
          .append("}\n");
        append("meta.json", sb.toString().getBytes(UTF8));
        Log.i(Config.TAG, "sink at " + baseDir + " (" + modeNote + ")");
    }

    private static String currentProcess() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"));
            String line = r.readLine();
            r.close();
            if (line == null) return "?";
            int nul = line.indexOf('\u0000');
            if (nul >= 0) line = line.substring(0, nul);
            line = line.trim();
            return line.isEmpty() ? "?" : line;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ── 消费循环 ──────────────────────────────────────────────────────────
    private static void loop() {
        for (;;) {
            Rec r = null;
            try {
                r = Q.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (r == null) {
                maybeStats();
                continue;
            }
            try {
                dispatch(r);
            } catch (Throwable t) {
                Log.w(Config.TAG, "sink write failed", t);
            }
        }
    }

    private static void maybeStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatsAt < 60000) return;
        lastStatsAt = now;
        try {
            String line = "{\"t\":" + now + ",\"queued\":" + Q.size()
                    + ",\"dropped\":" + dropped.get()
                    + ",\"written\":" + written.get()
                    + ",\"native\":" + NativeBridge.nativeStats()
                    + ",\"hooks\":" + NativeBridge.hooksJson() + "}\n";
            append("stats.jsonl", line.getBytes(UTF8));
        } catch (Throwable ignored) {
        }
    }

    private static void dispatch(Rec r) {
        switch (r.type) {
            case Config.TYPE_LOG: {
                String text = r.data == null ? "" : new String(r.data, UTF8);
                if (r.dir == 3) {
                    String ts = FILE_TS.format(new Date(r.ts));
                    String name = "crash/" + ts + "-" + safeName(r.tag) + ".txt";
                    append(name, r.data == null ? new byte[0] : r.data);
                    append("app.log", line("E", "CrashHandler", text).getBytes(UTF8));
                } else if (r.dir >= 10) {
                    append("app.log", line(nativeLevel(r.dir - 10), "native/" + nz(r.tag), text)
                            .getBytes(UTF8));
                } else {
                    append("app.log", line("J", nz(r.tag), text).getBytes(UTF8));
                }
                break;
            }
            case Config.TYPE_NET:
            case Config.TYPE_TLS:
                writeNet(r);
                break;
            default:
                break;
        }
    }

    /** android.util.Log 优先级 -> 单字符级别 */
    private static String nativeLevel(int prio) {
        switch (prio) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            case Log.ASSERT: return "A";
            default: return "?";
        }
    }

    private static String line(String level, String tag, String text) {
        StringBuilder sb = new StringBuilder(text.length() + 64);
        sb.append(LINE_TS.format(new Date())).append("  [").append(level).append("] ");
        sb.append(pad(tag, 18)).append(" : ").append(text);
        if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') sb.append('\n');
        return sb.toString();
    }

    private static void writeNet(Rec r) {
        String sub = r.type == Config.TYPE_TLS ? "tls" : "net";
        String conn = String.format(Locale.US, "c%03d_%s_%d", r.seq, safeName(nz(r.addr)), r.port);
        boolean tx = r.dir == Config.DIR_TX;
        int len = r.data == null ? 0 : r.data.length;

        if (len > 0) {
            append(sub + "/" + conn + (tx ? ".tx.bin" : ".rx.bin"), r.data);
        }
        String idx = "{\"t\":" + r.ts + ",\"fd\":" + r.fd + ",\"seq\":" + r.seq
                + ",\"dir\":\"" + (tx ? "tx" : "rx") + "\""
                + ",\"addr\":\"" + nz(r.addr) + "\",\"port\":" + r.port
                + ",\"len\":" + r.orig
                + ",\"preview\":\"" + HexDump.preview(r.data, len, Config.PREVIEW_BYTES) + "\"}\n";
        append(sub + "/index.jsonl", idx.getBytes(UTF8));

        String hdr = "== " + conn + (tx ? " tx " : " rx ")
                + LINE_TS.format(new Date(r.ts)) + " len=" + r.orig;
        append(sub + "/streams.txt",
                HexDump.dump(hdr, r.data == null ? new byte[0] : r.data, len, Config.PREVIEW_BYTES)
                        .getBytes(UTF8));
    }

    // ── 文件写入（仅 sink 线程调用） ──────────────────────────────────────
    private static synchronized void append(String rel, byte[] data) {
        if (baseDir == null || data == null || data.length == 0) return;
        Gen g = gens.get(rel);
        if (g == null) {
            g = new Gen();
            gens.put(rel, g);
        }
        if (g.bytes > Config.MAX_FILE_BYTES) {
            g.gen++;
            g.bytes = 0;
        }
        String real = g.gen == 0 ? rel : rel + "." + g.gen;
        if (written.get() > Config.MAX_TOTAL_BYTES) {
            dropped.incrementAndGet();
            return;
        }
        try {
            RootShell.Writer w = writers.get(real);
            if (w == null) {
                w = new RootShell.Writer(baseDir + "/" + real, rootMode);
                writers.put(real, w);
            }
            w.write(data, 0, data.length);
            g.bytes += data.length;
            written.addAndGet(data.length);
        } catch (Throwable t) {
            RootShell.Writer w = writers.remove(real);
            if (w != null) w.close();
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────
    private static String nz(String s) { return s == null ? "" : s; }

    private static String safeName(String s) {
        if (s == null) return "_";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        if (s.length() >= w) return s;
        StringBuilder sb = new StringBuilder(w);
        sb.append(s);
        for (int i = s.length(); i < w; i++) sb.append(' ');
        return sb.toString();
    }

    private FileSink() {}
}
