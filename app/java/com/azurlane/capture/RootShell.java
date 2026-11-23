package com.azurlane.capture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/** root 通道：探测 su 可用性、执行一次性 root 命令。 */
final class RootShell {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile int state = 0; // 0 未探测 1 可用 -1 不可用

    static boolean available() {
        if (state != 0) return state > 0;
        synchronized (RootShell.class) {
            if (state != 0) return state > 0;
            String out = exec("id", 8000);
            state = (out != null && out.contains("uid=0")) ? 1 : -1;
            return state > 0;
        }
    }

    /** 执行一次 root 命令，返回 stdout；失败/超时返回 null。 */
    static String exec(String cmd, int timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    } catch (IOException ignored) {}
                }
            }, "azcap-su-read");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            reader.join(500);
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable t) {
            if (p != null) p.destroy();
            return null;
        }
    }

    /** 每个文件一个常驻 `cat >>` 进程（二进制安全）。 */
    static final class Writer {
        private final String path;
        private final boolean root;
        private Process proc;
        private OutputStream out;
        long bytes = 0;

        Writer(String path, boolean root) {
            this.path = path;
            this.root = root;
        }

        synchronized void write(byte[] b, int off, int len) throws IOException {
            ensure();
            out.write(b, off, len);
            out.flush();
            bytes += len;
        }

        private void ensure() throws IOException {
            if (proc != null && proc.isAlive()) return;
            if (!root && out != null) return;
            close();
            if (root) {
                String dir = parentOf(path);
                String cmd = "mkdir -p '" + dir + "' && chmod 0775 '" + dir
                        + "' 2>/dev/null; exec cat >> '" + path + "'";
                proc = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                out = proc.getOutputStream();
            } else {
                File f = new File(path);
                File d = f.getParentFile();
                if (d != null && !d.exists()) d.mkdirs();
                out = new java.io.FileOutputStream(f, true);
            }
        }

        synchronized void close() {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
            proc = null;
            out = null;
        }

        private static String parentOf(String p) {
            int i = p.lastIndexOf('/');
            return i > 0 ? p.substring(0, i) : ".";
        }
    }

    private RootShell() {}
}
