package com.azurlane.capture;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 游戏长连接（8017/8018）的 protobuf 帧切分。
 *
 * <p>线上格式（客户端/服务端一致）：{@code [2B size][1B ?][2B pid][body]}，
 * size 只算 body 之后的长度还是整包按长度字段自增，这里两种解释都写进
 * frames.jsonl（sizeBe / sizeLe），用一屏数据就能看出哪种成立。
 */
final class FrameAssembler {
    /** 单连接缓冲上限，超过就判为流错位并重置。 */
    private static final int MAX_BUF = 1 << 16;
    private static final int MAX_FRAME_HEX = 256;

    private static final Map<Integer, Buf> BUFS = new HashMap<Integer, Buf>();

    private static final class Buf {
        final ByteArrayOutputStream ba = new ByteArrayOutputStream(8192);
        boolean little = false;
        boolean decided = false;
        long frames = 0;
        long bad = 0;
    }

    private FrameAssembler() {}

    /** 喂入一段流数据，返回可写入 frames.jsonl 的文本（可能为空）。 */
    static synchronized String feed(int fd, int dir, int seq, int port, long ts, long elapsedMs,
                                    byte[] data) {
        if (!Config.frameSplit || !Config.isGamePort(port) || data == null || data.length == 0) {
            return null;
        }
        int key = (fd << 1) | (dir == Config.DIR_TX ? 0 : 1);
        Buf b = BUFS.get(key);
        if (b == null) {
            b = new Buf();
            BUFS.put(key, b);
        }
        if (b.ba.size() + data.length > MAX_BUF) {
            b.ba.reset();
            b.bad++;
        }
        b.ba.write(data, 0, data.length);

        StringBuilder out = new StringBuilder(256);
        byte[] buf = b.ba.toByteArray();
        int off = 0;
        for (;;) {
            int remain = buf.length - off;
            if (remain < 5) break;
            int be = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
            int le = ((buf[off + 1] & 0xFF) << 8) | (buf[off] & 0xFF);
            int size = b.little ? le : be;
            if (!b.decided) {
                b.little = !plausible(be) && plausible(le);
                size = b.little ? le : be;
                b.decided = plausible(size);
            }
            if (!plausible(size)) {
                // 流错位：丢掉 1 字节重新对齐，最多丢 64 字节
                int skip = 1;
                while (skip < 64 && skip < remain) {
                    int nb = ((buf[off + skip] & 0xFF) << 8) | (buf[off + skip + 1] & 0xFF);
                    int nl = ((buf[off + skip + 1] & 0xFF) << 8) | (buf[off + skip] & 0xFF);
                    if (plausible(b.little ? nl : nb)) break;
                    skip++;
                }
                off += skip;
                b.bad++;
                continue;
            }
            int total = size + 2;
            if (total > MAX_BUF) {
                off += 1;
                b.bad++;
                continue;
            }
            if (remain < total) break;
            int flag = buf[off + 2] & 0xFF;
            int pidBe = ((buf[off + 3] & 0xFF) << 8) | (buf[off + 4] & 0xFF);
            int pidLe = ((buf[off + 4] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
            int pid = b.little ? pidLe : pidBe;
            int bodyLen = Math.max(0, total - 5);
            String hex = hex(buf, off + 5, Math.min(bodyLen, MAX_FRAME_HEX));
            b.frames++;
            out.append("{\"t\":").append(ts)
                    .append(",\"ms\":").append(elapsedMs)
                    .append(",\"fd\":").append(fd)
                    .append(",\"seq\":").append(seq)
                    .append(",\"dir\":\"").append(dir == Config.DIR_TX ? "tx" : "rx").append('"')
                    .append(",\"port\":").append(port)
                    .append(",\"n\":").append(b.frames)
                    .append(",\"size\":").append(size)
                    .append(",\"sizeBe\":").append(be)
                    .append(",\"sizeLe\":").append(le)
                    .append(",\"flag\":").append(flag)
                    .append(",\"pid\":").append(pid)
                    .append(",\"endian\":\"").append(b.little ? "le" : "be").append('"')
                    .append(",\"body\":").append(bodyLen)
                    .append(",\"hex\":\"").append(hex).append("\"}\n");
            off += total;
        }
        if (off > 0) {
            byte[] rest = new byte[buf.length - off];
            System.arraycopy(buf, off, rest, 0, rest.length);
            b.ba.reset();
            b.ba.write(rest, 0, rest.length);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** size 字段是否像一个合法长度（含 2B 头后的总长）。 */
    private static boolean plausible(int size) {
        return size >= 3 && size <= 4096;
    }

    static synchronized String statsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<Integer, Buf> e : BUFS.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Buf b = e.getValue();
            sb.append(String.format(Locale.US,
                    "{\"fd\":%d,\"dir\":\"%s\",\"frames\":%d,\"bad\":%d,\"buf\":%d}",
                    e.getKey() >> 1, (e.getKey() & 1) == 0 ? "tx" : "rx",
                    b.frames, b.bad, b.ba.size()));
        }
        return sb.append(']').toString();
    }

    static synchronized void reset() {
        BUFS.clear();
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            sb.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }
}
