package com.azurlane.capture;

/** 十六进制转储工具（streams.txt 用）。 */
final class HexDump {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void hexByte(StringBuilder sb, int b) {
        sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
    }

    /** 单行预览：最多 max 字节。 */
    static String preview(byte[] d, int len, int max) {
        if (d == null || len <= 0) return "";
        int n = Math.min(len, max);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) hexByte(sb, d[i] & 0xff);
        if (len > n) sb.append("...");
        return sb.toString();
    }

    /** 多行 hexdump：每行 16 字节，右侧 ASCII。 */
    static String dump(String header, byte[] d, int len, int max) {
        int n = Math.min(len, max);
        StringBuilder sb = new StringBuilder(80 + (n / 16 + 1) * 80);
        sb.append(header).append('\n');
        for (int off = 0; off < n; off += 16) {
            sb.append(String.format("%06x  ", off));
            for (int i = 0; i < 16; i++) {
                int idx = off + i;
                if (idx < n) hexByte(sb, d[idx] & 0xff);
                else sb.append("  ");
                sb.append(i == 7 ? "  " : " ");
            }
            sb.append(" |");
            for (int i = 0; i < 16 && off + i < n; i++) {
                int c = d[off + i] & 0xff;
                sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
            }
            sb.append("|\n");
        }
        if (len > n) sb.append("... (truncated, ").append(len - n).append(" bytes omitted)\n");
        return sb.toString();
    }

    private HexDump() {}
}
