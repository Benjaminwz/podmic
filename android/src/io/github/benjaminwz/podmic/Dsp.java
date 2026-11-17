package io.github.benjaminwz.podmic;

import java.util.ArrayList;

/** 純運算（不碰 Android）：頻譜、殘響時間、敲擊頻率、聲道模式。電腦上可以直接測。 */
final class Dsp {
    private Dsp() {}

    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            int half = len / 2;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    float xr = (float) (re[b] * cr - im[b] * ci), xi = (float) (re[b] * ci + im[b] * cr);
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }

    static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        return w;
    }

    /** 功率頻譜（線性）；滿刻度的正弦波 = 1.0。回傳 n/2 個頻點。 */
    static float[] power(float[] x, int off, int n, float[] win) {
        float[] re = new float[n], im = new float[n];
        double ws = 0;
        for (int i = 0; i < n; i++) {
            re[i] = x[(off + i) % x.length] * win[i];
            ws += win[i];
        }
        fft(re, im);
        float[] p = new float[n / 2];
        double k = 2.0 / ws;
        for (int i = 0; i < n / 2; i++) {
            double a = Math.hypot(re[i], im[i]) * k;
            p[i] = (float) (a * a);
        }
        return p;
    }

    /** A 加權（dB），IEC 61672。 */
    static double aWeight(double f) {
        if (f < 10) return -70;
        double f2 = f * f;
        double ra = 12194.0 * 12194.0 * f2 * f2
                / ((f2 + 20.6 * 20.6) * Math.sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0));
        return 20 * Math.log10(ra) + 2.0;
    }

    static double db(double lin) {
        return 20 * Math.log10(Math.max(lin, 1e-10));
    }

    /** 帶通（RBJ，峰值 0 dB），原地處理。 */
    static void bandpass(float[] x, double fs, double fc, double q) {
        double w0 = 2 * Math.PI * fc / fs, alpha = Math.sin(w0) / (2 * q), a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i], y = b0 * in + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in; y2 = y1; y1 = y;
            x[i] = (float) y;
        }
    }

    static final double[] OCT = {125, 250, 500, 1000, 2000, 4000, 8000};

    /**
     * 衰減分析（拍手、戳氣球之後的那一段）。fc = 0 表示全頻。
     * 回傳 {EDT, T20, T30, 可用動態範圍 dB}；量不到的填 NaN。
     */
    static double[] decay(float[] x, int fs, double fc) {
        float[] y = x.clone();
        if (fc > 0) {
            bandpass(y, fs, fc, 1.0);
            bandpass(y, fs, fc, 1.0);
        }
        int hop = Math.max(1, fs / 400), nb = y.length / hop;
        double[] res = {Double.NaN, Double.NaN, Double.NaN, 0};
        if (nb < 40) return res;
        double[] e = new double[nb];
        int p = 0;
        for (int b = 0; b < nb; b++) {
            double s = 0;
            for (int i = b * hop, end = i + hop; i < end; i++) s += (double) y[i] * y[i];
            e[b] = s / hop;
            if (e[b] > e[p]) p = b;
        }
        int tail = Math.max(8, nb * 15 / 100);
        double noise = 0;
        for (int b = nb - tail; b < nb; b++) noise += e[b];
        noise = Math.max(noise / tail, 1e-14);
        res[3] = 10 * Math.log10(e[p] / noise);
        // 截斷點：平滑後掉到雜訊上方 5 dB 以內的第一格
        int t = nb - tail;
        double lim = noise * 3.16;
        for (int b = p + 2; b < nb - tail; b++) {
            double m = (e[b - 1] + e[b] + e[b + 1]) / 3;
            if (m < lim) { t = b; break; }
        }
        double[] sch = new double[nb];
        double acc = 0;
        for (int b = t; b >= p; b--) {
            acc += Math.max(e[b] - noise, 0);
            sch[b] = acc;
        }
        if (sch[p] <= 0) return res;
        double[] d = new double[t - p + 1];
        for (int b = p; b <= t; b++) d[b - p] = 10 * Math.log10(Math.max(sch[b] / sch[p], 1e-12));
        double dt = (double) hop / fs;
        res[0] = fit(d, dt, 0, -10);
        if (res[3] >= 35) res[1] = fit(d, dt, -5, -25);
        if (res[3] >= 45) res[2] = fit(d, dt, -5, -35);
        return res;
    }

    private static double fit(double[] d, double dt, double hi, double lo) {
        int i1 = -1, i2 = -1;
        for (int i = 0; i < d.length; i++) {
            if (i1 < 0 && d[i] <= hi) i1 = i;
            if (d[i] <= lo) { i2 = i; break; }
        }
        if (i1 < 0 || i2 < 0 || i2 - i1 < 3) return Double.NaN;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = i2 - i1 + 1;
        for (int i = i1; i <= i2; i++) {
            double tx = i * dt;
            sx += tx; sy += d[i]; sxx += tx * tx; sxy += tx * d[i];
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        return slope < 0 ? -60 / slope : Double.NaN;
    }

    /** 敲擊之後的主要頻率：回傳 {頻率 Hz, 相對最大峰的 dB, 餘音長度 T60 秒, 阻尼比}，最多 count 個，由強到弱。 */
    static double[][] peaks(float[] x, int fs, int count) {
        int n = 1 << 17;
        float[] re = new float[n], im = new float[n];
        int len = Math.min(x.length, n);
        for (int i = 0; i < len; i++) re[i] = (float) (x[i] * (0.5 + 0.5 * Math.cos(Math.PI * i / len)));
        fft(re, im);
        int lo = (int) (30.0 * n / fs), hi = (int) (Math.min(20000, fs * 0.45) * n / fs);
        double[] m = new double[hi + 2];
        double max = 0;
        for (int i = lo - 1; i <= hi + 1; i++) {
            m[i] = Math.hypot(re[i], im[i]);
            if (i >= lo && i <= hi && m[i] > max) max = m[i];
        }
        ArrayList<double[]> c = new ArrayList<>();
        if (max <= 0) return new double[0][];
        for (int i = lo; i <= hi; i++) {
            if (m[i] > m[i - 1] && m[i] >= m[i + 1] && m[i] > max * 0.03) {
                double a = Math.log(m[i - 1] + 1e-20), b = Math.log(m[i]), g = Math.log(m[i + 1] + 1e-20);
                double dlt = 0.5 * (a - g) / (a - 2 * b + g);
                c.add(new double[]{(i + dlt) * fs / n, 20 * Math.log10(m[i] / max)});
            }
        }
        c.sort((u, v) -> Double.compare(v[1], u[1]));
        ArrayList<double[]> out = new ArrayList<>();
        for (double[] k : c) {
            boolean near = false;
            for (double[] o : out) if (Math.abs(o[0] - k[0]) < Math.max(8, o[0] * 0.04)) near = true;
            if (near) continue;
            double t60 = ring(x, fs, k[0]);
            out.add(new double[]{k[0], k[1], t60, t60 > 0 ? Math.log(1000) / (2 * Math.PI * k[0] * t60) : Double.NaN});
            if (out.size() >= count) break;
        }
        return out.toArray(new double[0][]);
    }

    /** 單一頻率的餘音長度（掉 60 dB 要多久）。 */
    private static double ring(float[] x, int fs, double f) {
        float[] y = x.clone();
        bandpass(y, fs, f, 12);
        bandpass(y, fs, f, 12);
        double[] r = decay(y, fs, 0);
        double t = Double.isNaN(r[1]) ? r[0] : r[1];
        return Double.isNaN(t) ? -1 : t;
    }

    /** 兩端自由的長條試片（敲擊共振法）：尺寸 mm、質量 g、彎曲基頻 Hz → 楊氏模數 GPa。 */
    static double youngFree(double L, double b, double t, double m, double f) {
        L /= 1000; b /= 1000; t /= 1000; m /= 1000;
        double T = 1 + 6.585 * (t / L) * (t / L);
        return 0.9465 * (m * f * f / b) * Math.pow(L / t, 3) * T / 1e9;
    }

    /** 一端夾住的懸臂（L = 伸出來的長度、m = 伸出來那段的質量）。 */
    static double youngClamped(double L, double b, double t, double m, double f) {
        L /= 1000; b /= 1000; t /= 1000; m /= 1000;
        double w = 2 * Math.PI * f, I = b * t * t * t / 12;
        return w * w * (m / L) * Math.pow(L, 4) / (Math.pow(1.8751, 4) * I) / 1e9;
    }

    static final int M_NONE = 0, M_SINGLE = 1, M_DUAL = 2, M_SAFETY = 3, M_STEREO = 4;

    /** 由左右聲道的能量和相關性判斷 DJI 接收器的輸出模式。 */
    static int mode(double ll, double rr, double lr) {
        if (ll < 1e-9 && rr < 1e-9) return M_NONE;
        if (rr < ll * 1e-4 || ll < rr * 1e-4) return M_SINGLE;
        double corr = lr / Math.sqrt(ll * rr), ratio = 10 * Math.log10(rr / ll);
        if (corr > 0.985) {
            if (Math.abs(ratio + 6) < 1.2) return M_SAFETY;
            if (Math.abs(ratio) < 0.7) return M_DUAL;
        }
        return M_STEREO;
    }
}
