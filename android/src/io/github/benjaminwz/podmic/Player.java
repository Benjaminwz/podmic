package io.github.benjaminwz.podmic;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.File;
import java.io.RandomAccessFile;

/** 播放自己錄的 WAV（24-bit、浮點都能播），可以拖動位置。 */
final class Player {
    File file;
    Wav.Info info;
    volatile boolean playing;
    volatile long frame;
    private volatile long seekTo = -1;
    private Thread thread;

    void open(File f) throws Exception {
        stop();
        info = Wav.info(f);
        file = f;
        frame = 0;
    }

    double seconds() { return info == null ? 0 : info.seconds(); }

    double position() { return info == null || info.rate == 0 ? 0 : frame / (double) info.rate; }

    void seek(double frac) {
        if (info == null) return;
        long t = (long) (Math.max(0, Math.min(1, frac)) * info.frames);
        if (playing) seekTo = t; else frame = t;
    }

    void toggle() {
        if (playing) { stop(); return; }
        if (info == null || info.frames == 0) return;
        if (frame >= info.frames - 1) frame = 0;
        playing = true;
        thread = new Thread(this::loop, "podmic-play");
        thread.start();
    }

    void stop() {
        playing = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) try { t.join(500); } catch (InterruptedException ignored) {}
    }

    private void loop() {
        AudioTrack tr = null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int ch = Math.min(info.channels, 2);
            int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(info.rate, mask, AudioFormat.ENCODING_PCM_FLOAT);
            tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(info.rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(mask).build())
                    .setBufferSizeInBytes(Math.max(min, info.rate / 5 * ch * 4)).setTransferMode(AudioTrack.MODE_STREAM).build();
            tr.play();
            int n = info.rate / 20;
            float[] in = new float[n * info.channels], out = new float[n * ch];
            while (playing) {
                long s = seekTo;
                if (s >= 0) { frame = s; seekTo = -1; }
                int got = Wav.read(raf, info, frame, in, n);
                if (got <= 0) break;
                if (info.channels == ch) System.arraycopy(in, 0, out, 0, got * ch);
                else for (int i = 0; i < got; i++) for (int c = 0; c < ch; c++) out[i * ch + c] = in[i * info.channels + c];
                tr.write(out, 0, got * ch, AudioTrack.WRITE_BLOCKING);
                frame += got;
            }
        } catch (Exception ignored) {
        } finally {
            if (tr != null) { try { tr.stop(); } catch (Exception ignored) {} tr.release(); }
            playing = false;
        }
    }

    /** 整個檔案的波形縮圖（每一格取峰值），在背景執行緒呼叫。 */
    static float[] overview(File f, int bins) {
        float[] out = new float[bins];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            Wav.Info in = Wav.info(f);
            if (in.frames == 0) return out;
            // 很長的檔案每格只抽一小段，不必整個讀完
            int take = (int) Math.min(in.frames / bins + 1, 2048);
            float[] buf = new float[take * in.channels];
            for (int b = 0; b < bins; b++) {
                long at = in.frames * b / bins;
                int got = Wav.read(raf, in, at, buf, take);
                float m = 0;
                for (int i = 0; i < got * in.channels; i++) m = Math.max(m, Math.abs(buf[i]));
                out[b] = m;
            }
        } catch (Exception ignored) {}
        return out;
    }
}
