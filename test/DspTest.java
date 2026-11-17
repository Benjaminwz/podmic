package io.github.benjaminwz.podmic;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Random;

/** 在電腦上測純運算的部分：java -cp out io.github.benjaminwz.podmic.DspTest */
public class DspTest {
    public static void main(String[] a) throws Exception {
        int fs = 48000;
        Random r = new Random(1);
        // 1. 殘響：0.30 秒的指數衰減雜訊 + 底噪 -70 dB
        for (double rt : new double[]{0.2, 0.3, 0.6}) {
            float[] x = new float[fs * 3];
            int on = fs / 10;
            for (int i = 0; i < x.length; i++) {
                double n = r.nextGaussian() * 3e-4;
                if (i >= on) n += r.nextGaussian() * 0.3 * Math.pow(10, -3.0 * (i - on) / (rt * fs));
                x[i] = (float) n;
            }
            double[] d = Dsp.decay(x, fs, 0), b = Dsp.decay(x, fs, 1000), lo = Dsp.decay(x, fs, 125);
            System.out.printf("RT %.2f -> 全頻 EDT %.3f T20 %.3f T30 %.3f 範圍 %.0f dB | 1k T20 %.3f | 125 T20 %.3f%n",
                    rt, d[0], d[1], d[2], d[3], b[1], lo[1]);
        }
        // 2. 敲擊：440.7 Hz（T60 0.5 秒）+ 1234.5 Hz（弱 12 dB，T60 0.2 秒）
        float[] t = new float[fs];
        for (int i = 0; i < t.length; i++) {
            double s = i / (double) fs;
            t[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440.7 * s) * Math.pow(10, -3 * s / 0.5)
                    + 0.125 * Math.sin(2 * Math.PI * 1234.5 * s) * Math.pow(10, -3 * s / 0.2) + r.nextGaussian() * 1e-4);
        }
        for (double[] p : Dsp.peaks(t, fs, 4)) System.out.printf("峰 %.2f Hz  %.1f dB  T60 %.3f  阻尼 %.4f%n", p[0], p[1], p[2], p[3]);
        // 3. 楊氏模數：PLA 試片 150×20×4 mm、14.9 g、284 Hz → 約 3 GPa
        System.out.printf("E 自由 %.2f GPa｜懸臂 44.6 Hz %.2f GPa%n", Dsp.youngFree(150, 20, 4, 14.88, 284), Dsp.youngClamped(150, 20, 4, 14.88, 44.6));
        // 4. 頻譜：1 kHz 滿刻度 → 0 dB；A 加權 1 kHz = 0
        float[] s = new float[8192];
        for (int i = 0; i < s.length; i++) s[i] = (float) Math.sin(2 * Math.PI * 1000 * i / fs);
        float[] p = Dsp.power(s, 0, 8192, Dsp.hann(8192));
        int k = Math.round(1000f * 8192 / fs);
        System.out.printf("1 kHz 峰 %.2f dB｜A(1k) %.2f A(100) %.1f%n", 10 * Math.log10(p[k] + p[k - 1] + p[k + 1]) , Dsp.aWeight(1000), Dsp.aWeight(100));
        // 5. 模式
        System.out.println("模式 " + Dsp.mode(1, 0.2512, 0.5012) + " " + Dsp.mode(1, 1, 0.999) + " " + Dsp.mode(1, 0, 0) + " " + Dsp.mode(1, 0.8, 0.1));
        // 6. WAV 來回
        for (boolean flt : new boolean[]{false, true}) {
            File f = File.createTempFile("podmic", ".wav");
            Wav.Writer w = new Wav.Writer(f, fs, 2, flt);
            float[] src = new float[2 * 1001];
            for (int i = 0; i < src.length; i++) src[i] = (float) Math.sin(i * 0.01) * 0.9f;
            w.write(src, 1001, 2, new int[]{0, 1});
            ArrayList<Wav.Marker> m = new ArrayList<>();
            m.add(new Wav.Marker(500, "重點"));
            w.close(m);
            Wav.Info in = Wav.info(f);
            float[] back = new float[2002];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) { Wav.read(raf, in, 0, back, 1001); }
            double err = 0;
            for (int i = 0; i < src.length; i++) err = Math.max(err, Math.abs(src[i] - back[i]));
            System.out.printf("WAV %s：%d Hz %d 聲道 %d bit %d 格，最大誤差 %.2e，檔案 %d bytes%n", flt ? "浮點" : "24-bit", in.rate, in.channels, in.bits, in.frames, err, f.length());
            if (flt) System.out.println("留檔 " + f); else f.delete();
        }
    }
}
