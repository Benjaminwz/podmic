package io.github.benjaminwz.podmic;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** WAV 讀寫（24-bit 整數或 32-bit 浮點），結尾寫入標記（cue），剪輯軟體讀得到。 */
final class Wav {
    private Wav() {}

    static final class Marker {
        final long frame;
        final String label;
        Marker(long frame, String label) { this.frame = frame; this.label = label; }
    }

    static final class Writer {
        final File file;
        final int rate, channels;
        final boolean flt;
        private final RandomAccessFile raf;
        private final FileChannel ch;
        private final ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        long frames;
        private long lastHeader;

        Writer(File file, int rate, int channels, boolean flt) throws IOException {
            this.file = file; this.rate = rate; this.channels = channels; this.flt = flt;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            ch = raf.getChannel();
            header();
        }

        int frameBytes() { return channels * (flt ? 4 : 3); }

        long dataBytes() { return frames * frameBytes(); }

        private void header() throws IOException {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            long data = dataBytes();
            int bits = flt ? 32 : 24;
            h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) Math.min(0xFFFFFFFFL, Math.max(36 + data, ch.size() - 8)));
            h.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
            h.putShort((short) (flt ? 3 : 1)).putShort((short) channels).putInt(rate);
            h.putInt(rate * frameBytes()).putShort((short) frameBytes()).putShort((short) bits);
            h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) Math.min(0xFFFFFFFFL, data));
            h.flip();
            ch.write(h, 0);
            if (ch.position() < 44) ch.position(44);
        }

        /** src：交錯排列、srcCh 聲道；pick：要寫進檔案的聲道。 */
        void write(float[] src, int n, int srcCh, int[] pick) throws IOException {
            for (int i = 0; i < n; i++) {
                if (buf.remaining() < 16) flush();
                for (int c : pick) {
                    float v = src[i * srcCh + c];
                    if (flt) {
                        buf.putFloat(v);
                    } else {
                        int s = (int) Math.max(-8388608, Math.min(8388607, Math.round(v * 8388608.0)));
                        buf.put((byte) s).put((byte) (s >> 8)).put((byte) (s >> 16));
                    }
                }
            }
            frames += n;
            // 每 5 秒更新一次檔頭：就算程式被關掉，檔案也讀得出來
            if (frames - lastHeader > rate * 5L) {
                flush();
                header();
                lastHeader = frames;
            }
        }

        /** 把另一個同格式檔案的第 from～to 格原封不動接上來（剪輯用）。 */
        void copy(RandomAccessFile src, Info in, long from, long to) throws IOException {
            flush();
            int fb = in.frameBytes();
            byte[] b = new byte[fb * 16384];
            src.seek(in.dataOff + from * fb);
            for (long left = to - from; left > 0; ) {
                int n = (int) Math.min(left, 16384);
                src.readFully(b, 0, n * fb);
                ch.write(ByteBuffer.wrap(b, 0, n * fb));
                left -= n;
                frames += n;
            }
        }

        private void flush() throws IOException {
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
            buf.clear();
        }

        void close(List<Marker> marks) throws IOException {
            flush();
            if ((dataBytes() & 1) == 1) ch.write(ByteBuffer.wrap(new byte[1]));
            if (marks != null && !marks.isEmpty()) {
                int n = marks.size();
                ByteBuffer c = ByteBuffer.allocate(12 + 24 * n).order(ByteOrder.LITTLE_ENDIAN);
                c.put("cue ".getBytes(StandardCharsets.US_ASCII)).putInt(4 + 24 * n).putInt(n);
                ByteBuffer l = ByteBuffer.allocate(12 + n * 300).order(ByteOrder.LITTLE_ENDIAN);
                l.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("adtl".getBytes(StandardCharsets.US_ASCII));
                for (int i = 0; i < n; i++) {
                    Marker m = marks.get(i);
                    c.putInt(i + 1).putInt((int) m.frame).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putInt((int) m.frame);
                    byte[] t = m.label.getBytes(StandardCharsets.UTF_8);
                    if (t.length > 250) t = java.util.Arrays.copyOf(t, 250);
                    int sz = 4 + t.length + 1;
                    l.put("labl".getBytes(StandardCharsets.US_ASCII)).putInt(sz).putInt(i + 1).put(t).put((byte) 0);
                    if ((sz & 1) == 1) l.put((byte) 0);
                }
                l.putInt(4, l.position() - 8);
                c.flip();
                l.flip();
                ch.write(c);
                ch.write(l);
            }
            header();
            ch.force(false);
            raf.close();
        }
    }

    static final class Info {
        int rate, channels, bits;
        boolean flt;
        long dataOff, frames;
        int frameBytes() { return channels * bits / 8; }
        double seconds() { return rate > 0 ? (double) frames / rate : 0; }
    }

    static Info info(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[12];
            f.readFully(h);
            if (h[0] != 'R' || h[8] != 'W') throw new IOException("not wav");
            Info in = new Info();
            long pos = 12, len = f.length();
            byte[] ck = new byte[8];
            while (pos + 8 <= len) {
                f.seek(pos);
                f.readFully(ck);
                String id = new String(ck, 0, 4, StandardCharsets.US_ASCII);
                long sz = ByteBuffer.wrap(ck, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                if (id.equals("fmt ")) {
                    byte[] b = new byte[16];
                    f.readFully(b);
                    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                    int tag = bb.getShort() & 0xFFFF;
                    in.channels = bb.getShort();
                    in.rate = bb.getInt();
                    bb.getInt(); bb.getShort();
                    in.bits = bb.getShort();
                    in.flt = tag == 3;
                } else if (id.equals("data")) {
                    in.dataOff = pos + 8;
                    // 檔頭還沒更新（錄到一半被關掉）→ 用實際的檔案長度
                    long avail = len - in.dataOff;
                    if (sz == 0 || sz > avail) sz = avail;
                    if (in.channels > 0 && in.bits > 0) in.frames = sz / in.frameBytes();
                    return in;
                }
                pos += 8 + sz + (sz & 1);
            }
            throw new IOException("no data");
        }
    }

    /** 從第 frame 格開始讀 n 格，解成交錯排列的 float；回傳實際讀到的格數。 */
    static int read(RandomAccessFile f, Info in, long frame, float[] out, int n) throws IOException {
        n = (int) Math.max(0, Math.min(n, in.frames - frame));
        if (n == 0) return 0;
        int fb = in.frameBytes(), bps = in.bits / 8;
        byte[] raw = new byte[n * fb];
        f.seek(in.dataOff + frame * fb);
        f.readFully(raw);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int total = n * in.channels;
        for (int i = 0; i < total; i++) {
            int o = i * bps;
            if (in.flt) out[i] = bb.getFloat(o);
            else if (bps == 3) out[i] = (((raw[o + 2]) << 16) | ((raw[o + 1] & 0xFF) << 8) | (raw[o] & 0xFF)) / 8388608f;
            else if (bps == 2) out[i] = bb.getShort(o) / 32768f;
            else if (bps == 4) out[i] = bb.getInt(o) / 2147483648f;
            else out[i] = ((raw[o] & 0xFF) - 128) / 128f;
        }
        return n;
    }
}
