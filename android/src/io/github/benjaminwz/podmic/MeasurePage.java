package io.github.benjaminwz.podmic;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import static io.github.benjaminwz.podmic.Ui.*;

/** 測量頁：即時頻譜、錄音環境檢查（底噪、音量、回音）、殘響時間、敲擊頻率（可算楊氏模數）。 */
final class MeasurePage implements MainActivity.Page {
    private static final int FS = Engine.RATE, NF = 8192;
    private final Context c;
    private final Engine eng;
    private final ScrollView root;
    private final Spectrum spec;
    private final TextView[] chips = new TextView[4];
    private final TextView out, hint, btn, btn2, btn3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] win = Dsp.hann(NF);
    private int mode;
    private boolean frozen, listening, busy;
    private double bg = 1e-4;
    private long trigPos = -1, stepEnd;
    private int envStep;
    private final StringBuilder env = new StringBuilder();
    private double envNoise, envSpeech;
    private final ArrayList<double[][]> rts = new ArrayList<>();     // 每次拍手：各頻帶 {EDT,T20,T30,範圍}
    private final ArrayList<double[][]> taps = new ArrayList<>();
    private String note = "";
    private long lastText;

    MeasurePage(Context c) {
        this.c = c;
        eng = Engine.get(c);
        root = new ScrollView(c);
        LinearLayout v = col(c);
        v.setPadding(dp(16), dp(4), dp(16), dp(16));
        LinearLayout tabs = row(c);
        String[] names = {"頻譜", "環境檢查", "殘響", "敲擊"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            chips[i] = pill(c, names[i], false);
            chips[i].setTextSize(13.5f);
            chips[i].setPadding(dp(6), dp(10), dp(6), dp(10));
            chips[i].setOnClickListener(x -> setMode(k));
            tabs.addView(chips[i], lp(0, WRAP, 1, i == 0 ? 0 : 3, 0, i == 3 ? 0 : 3, 0));
        }
        v.addView(tabs);
        spec = new Spectrum(c);
        v.addView(spec, lp(MATCH, dp(210), 0, 0, 12, 0, 0));
        hint = text(c, "", 13.5f, DIM);
        hint.setLineSpacing(dp(4), 1);
        v.addView(hint, lp(MATCH, WRAP, 0, 2, 12, 2, 0));
        LinearLayout b = row(c);
        btn = pill(c, "", true);
        btn.setOnClickListener(x -> action());
        b.addView(btn, lp(0, WRAP, 1));
        btn2 = pill(c, "", false);
        btn2.setOnClickListener(x -> action2());
        b.addView(btn2, lp(WRAP, WRAP, 0, 8, 0, 0, 0));
        btn3 = pill(c, "分享結果", false);
        btn3.setOnClickListener(x -> c.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out().getText().toString()), "分享結果")));
        b.addView(btn3, lp(WRAP, WRAP, 0, 8, 0, 0, 0));
        v.addView(b, lp(MATCH, WRAP, 0, 0, 12, 0, 0));
        out = text(c, "", 14.5f, TEXT);
        out.setLineSpacing(dp(5), 1);
        out.setTextIsSelectable(true);
        out.setFontFeatureSettings("tnum");
        LinearLayout card = card(c);
        card.addView(out);
        v.addView(card, lp(MATCH, WRAP, 0, 0, 12, 0, 0));
        root.addView(v);
        setMode(0);
    }

    private TextView out() { return out; }

    @Override public View view() { return root; }

    @Override public void shown() {}

    private void setMode(int m) {
        mode = m;
        listening = false;
        frozen = false;
        envStep = 0;
        trigPos = -1;
        spec.mark = -1;
        for (int i = 0; i < 4; i++) pillStyle(chips[i], i == m);
        String[] hints = {
                "現在收到的聲音有哪些頻率。金色細線是最近出現過的最大值。",
                "錄 podcast 之前花 20 秒檢查：房間夠不夠安靜、音量對不對、回音多不多。\n量之前先把接收器上的「降噪」關掉，數字才準。",
                "量房間的回音要多久才消失（越短越乾淨）。按開始之後，在離麥克風 1～2 公尺的地方用力拍一下手或戳破一顆氣球，可以多拍幾次取平均。\n接收器的降噪和低切要關掉。",
                "敲一下東西（葉片、試片、杯子…），算出它「嗡」的頻率和聲音消失的速度。麥克風靠近到 2～5 公分。有裂痕的東西通常頻率變低、消失得更快。"};
        hint.setText(hints[m]);
        note = "";
        show();
    }

    private void show() {
        btn2.setVisibility(View.GONE);
        btn3.setVisibility(View.GONE);
        if (mode == 0) btn.setText(frozen ? "繼續" : "凍結畫面");
        else if (mode == 1) btn.setText(envStep == 0 ? "開始檢查" : "取消");
        else {
            btn.setText(listening ? "停止" : mode == 2 ? "開始聽（拍手或戳氣球）" : "開始聽（敲一下）");
            boolean has = mode == 2 ? !rts.isEmpty() : !taps.isEmpty();
            if (has) {
                btn2.setText(mode == 2 ? "清除" : "算楊氏模數");
                btn2.setVisibility(View.VISIBLE);
                btn3.setVisibility(View.VISIBLE);
            }
        }
        if (mode == 1 && envStep == 0 && env.length() > 0) btn3.setVisibility(View.VISIBLE);
        render();
    }

    private void action() {
        if (mode == 0) { frozen = !frozen; if (!frozen) spec.clearHold(); }
        else if (mode == 1) {
            if (envStep == 0) { env.setLength(0); envStep = 1; stepEnd = System.currentTimeMillis() + 5500; }
            else envStep = 0;
        } else {
            listening = !listening;
            trigPos = -1;
            note = "";
        }
        show();
    }

    private void action2() {
        if (mode == 2) { rts.clear(); show(); }
        else if (mode == 3) young();
    }

    // ---------- 每一格畫面 ----------

    @Override public void tick() {
        if (!eng.ok) { out.setText(eng.error.isEmpty() ? "正在打開麥克風…" : eng.error); return; }
        float[] pw = null;
        if (!frozen) {
            pw = Dsp.power(eng.recent(NF), 0, NF, win);
            spec.feed(pw, FS, 0.6f);
        }
        long now = System.currentTimeMillis();
        // 短時間的音量（偵測拍手、敲擊用）
        float[] blk = eng.recent(1600);
        double mx = 0, ss = 0;
        for (float f : blk) { double a = Math.abs(f); if (a > mx) mx = a; ss += f * f; }
        double rms = Math.sqrt(ss / blk.length);
        boolean want = (listening && (mode == 2 || mode == 3)) || (mode == 1 && envStep == 3);
        if (want && !busy) {
            if (trigPos < 0) {
                if (mx > Math.max(bg * 30, 0.02)) {
                    trigPos = eng.ringPos;
                    note = "收到了，分析中…";
                } else bg = bg * 0.95 + rms * 0.05;
            } else {
                double wait = mode == 3 ? 1.3 : 2.4;
                if (eng.ringPos - trigPos > wait * FS) {
                    int n = (int) (eng.ringPos - trigPos) + FS / 8;
                    final float[] x = eng.recent(n);
                    trigPos = -1;
                    busy = true;
                    final int m = mode;
                    new Thread(() -> analyze(m, x)).start();
                }
            }
        } else if (!want) bg = bg * 0.95 + rms * 0.05;

        if (mode == 1 && envStep > 0 && envStep < 3 && now >= stepEnd) envNext();
        if (mode == 1 && envStep == 3 && trigPos < 0 && !busy && now >= stepEnd) {
            env.append("回音：沒有聽到拍手，這一項跳過。\n");
            envDone();
        }
        if (now - lastText > 250) {
            lastText = now;
            if (mode == 0 && pw != null) {
                int k = 1;
                double sum = 0, sumA = 0;
                for (int i = 2; i < pw.length; i++) {
                    if (pw[i] > pw[k]) k = i;
                    sum += pw[i];
                    sumA += pw[i] * Math.pow(10, Dsp.aWeight((double) i * FS / NF) / 10);
                }
                out.setText(String.format(Locale.US, "最強的頻率　%s\n整體音量　%.1f dBFS\nA 加權（接近人耳感覺）　%.1f dBFS(A)\n\ndBFS 是「離破音還有多遠」：0 就是破音，數字越負越小聲。",
                        hz((double) k * FS / NF), 10 * Math.log10(sum / 3 + 1e-14), 10 * Math.log10(sumA / 3 + 1e-14)));
            } else if (mode != 0) render();
        }
    }

    private static String hz(double f) {
        return f >= 1000 ? String.format(Locale.US, "%.2f kHz", f / 1000) : String.format(Locale.US, "%.1f Hz", f);
    }

    // ---------- 環境檢查 ----------

    private void envNext() {
        float[] x = eng.recent(FS * 5);
        if (envStep == 1) {
            double ss = 0;
            for (float f : x) ss += f * f;
            envNoise = 10 * Math.log10(ss / x.length + 1e-14);
            int n = 32768;
            float[] w = Dsp.hann(n), acc = new float[n / 2];
            int frames = 0;
            for (int o = 0; o + n <= x.length; o += n / 2, frames++) {
                float[] p = Dsp.power(x, o, n, w);
                for (int i = 0; i < acc.length; i++) acc[i] += p[i];
            }
            double sumA = 0;
            for (int i = 1; i < acc.length; i++) sumA += acc[i] / frames * Math.pow(10, Dsp.aWeight((double) i * FS / n) / 10);
            double a = 10 * Math.log10(sumA / 3 + 1e-14);
            String hum = "";
            for (int f0 : new int[]{60, 50}) {
                double pk = 0;
                for (int i = Math.round((f0 - 2.5f) * n / FS); i <= Math.round((f0 + 2.5f) * n / FS); i++) pk = Math.max(pk, acc[i]);
                int a0 = Math.round(32f * n / FS), a1 = Math.round(44f * n / FS), b0 = Math.round(72f * n / FS), b1 = Math.round(88f * n / FS);
                float[] side = new float[(a1 - a0) + (b1 - b0)];
                int k = 0;
                for (int i = a0; i < a1; i++) side[k++] = acc[i];
                for (int i = b0; i < b1; i++) side[k++] = acc[i];
                Arrays.sort(side);
                if (pk > side[side.length / 2] * 60) { hum = "有 " + f0 + " Hz 的電源哼聲：離充電器、延長線、電腦遠一點，或別邊充電邊錄。"; break; }
            }
            env.append(String.format(Locale.US, "底噪　%.0f dBFS（A 加權 %.0f）\n", envNoise, a));
            if (!hum.isEmpty()) env.append(hum).append('\n');
            envStep = 2;
            stepEnd = System.currentTimeMillis() + 5500;
        } else if (envStep == 2) {
            int bl = FS / 20, nb = x.length / bl;
            double[] e = new double[nb];
            double pk = 0;
            for (int b = 0; b < nb; b++) {
                double s = 0;
                for (int i = b * bl; i < (b + 1) * bl; i++) { s += x[i] * x[i]; pk = Math.max(pk, Math.abs(x[i])); }
                e[b] = s / bl;
            }
            Arrays.sort(e);
            double s = 0;
            int from = nb * 4 / 10;
            for (int b = from; b < nb; b++) s += e[b];
            envSpeech = 10 * Math.log10(s / (nb - from) + 1e-14);
            double pkDb = Dsp.db(pk), snr = envSpeech - envNoise;
            env.append(String.format(Locale.US, "講話　平均 %.0f dBFS、最大 %.0f dBFS\n", envSpeech, pkDb));
            if (snr < 12) env.append("　→ 好像沒有收到講話的聲音，再檢查一次。\n");
            else {
                env.append(pkDb > -3 ? "　→ 太大聲，容易破音：把接收器的增益調低。\n" : pkDb < -20 ? "　→ 偏小聲：增益調高，或麥克風夾近一點（離嘴巴一個拳頭）。\n" : "　→ 音量剛好。\n");
                env.append(String.format(Locale.US, "人聲比底噪大　%.0f dB　%s\n", snr,
                        snr >= 50 ? "很安靜，很好" : snr >= 40 ? "可以" : snr >= 30 ? "普通：關冷氣、電風扇、窗戶會更好" : "太吵：換個房間，或等安靜一點再錄"));
            }
            envStep = 3;
            trigPos = -1;
            stepEnd = System.currentTimeMillis() + 12000;
        }
        show();
    }

    private void envDone() {
        envStep = 0;
        show();
    }

    // ---------- 分析（背景執行緒） ----------

    private void analyze(int m, float[] x) {
        double mx = 0;
        for (float f : x) mx = Math.max(mx, Math.abs(f));
        final boolean clip = mx >= 0.999;
        if (m == 3) {
            int on = 0;
            while (on < x.length && Math.abs(x[on]) < 0.25 * mx) on++;
            on = Math.max(0, on - FS / 1000);
            float[] y = Arrays.copyOfRange(x, on, Math.min(x.length, on + FS));
            final double[][] p = Dsp.peaks(y, FS, 5);
            main.post(() -> {
                busy = false;
                if (p.length > 0) { taps.add(p); spec.mark = p[0][0]; note = clip ? "這一下破音了，敲輕一點或離遠一點。" : ""; }
                else note = "沒有抓到清楚的頻率，再敲一次。";
                show();
            });
        } else {
            final double[][] r = new double[Dsp.OCT.length + 1][];
            r[0] = Dsp.decay(x, FS, 0);
            for (int i = 0; i < Dsp.OCT.length; i++) r[i + 1] = Dsp.decay(x, FS, Dsp.OCT[i]);
            main.post(() -> {
                busy = false;
                boolean good = false;
                for (double[] b : r) if (!Double.isNaN(b[1])) good = true;
                if (mode == 1 && envStep == 3) {
                    if (good) {
                        double t = mid(r);
                        env.append(String.format(Locale.US, "回音（殘響時間）　%.2f 秒\n　→ %s\n", t,
                                t < 0.3 ? "很乾淨，適合錄音。" : t < 0.5 ? "可以。麥克風夾近一點會更乾淨。"
                                        : "回音偏多：拉上窗簾、鋪地毯或棉被、選東西多的房間；麥克風離嘴巴近一點（一個拳頭）。"));
                    } else env.append("回音：拍手不夠大聲，量不出來。\n");
                    envDone();
                    return;
                }
                if (good) { rts.add(r); note = clip ? "這一下破音了（數字仍可參考）：離遠一點或把增益調低。" : ""; }
                else note = "聲音不夠大，量不出來：拍大力一點，或靠近一點。";
                show();
            });
        }
    }

    /** 500 Hz～2 kHz 的平均 T20（講話最有關的範圍）。 */
    private static double mid(double[][] r) {
        double s = 0;
        int n = 0;
        for (int i = 3; i <= 5; i++) {
            double t = Double.isNaN(r[i][1]) ? r[i][0] : r[i][1];
            if (!Double.isNaN(t)) { s += t; n++; }
        }
        if (n == 0) return Double.isNaN(r[0][1]) ? r[0][0] : r[0][1];
        return s / n;
    }

    // ---------- 結果文字 ----------

    private void render() {
        StringBuilder s = new StringBuilder();
        if (mode == 1) {
            long left = Math.max(0, (stepEnd - System.currentTimeMillis() + 999) / 1000);
            if (envStep == 1) s.append("① 保持安靜　").append(left).append(" 秒\n\n");
            else if (envStep == 2) s.append("② 現在用平常錄音的音量講話　").append(left).append(" 秒\n\n");
            else if (envStep == 3) s.append(busy || trigPos >= 0 ? "分析中…\n\n" : "③ 用力拍一下手\n\n");
            s.append(env);
            if (envStep == 0 && env.length() == 0) s.append("還沒檢查。");
        } else if (mode == 2) {
            if (listening) s.append(trigPos >= 0 || busy ? "分析中…\n\n" : "正在聽…拍手或戳氣球\n\n");
            if (!note.isEmpty()) s.append(note).append("\n\n");
            if (rts.isEmpty()) s.append("還沒有結果。");
            else {
                s.append(String.format(Locale.US, "講話範圍的殘響時間　%.2f 秒（%d 次平均）\n\n", avgMid(), rts.size()));
                s.append("各頻率（T20，秒）\n");
                for (int i = 0; i <= Dsp.OCT.length; i++) {
                    double sum = 0;
                    int n = 0;
                    for (double[][] r : rts) if (!Double.isNaN(r[i][1])) { sum += r[i][1]; n++; }
                    String name = i == 0 ? "全部" : Dsp.OCT[i - 1] >= 1000 ? (int) (Dsp.OCT[i - 1] / 1000) + " kHz" : (int) Dsp.OCT[i - 1] + " Hz";
                    s.append(String.format(Locale.US, "%-7s　%s\n", name, n == 0 ? "量不到（聲音不夠大）" : String.format(Locale.US, "%.2f", sum / n)));
                }
                double[][] last = rts.get(rts.size() - 1);
                s.append(String.format(Locale.US, "\n最後一次：可用範圍 %.0f dB、EDT %.2f 秒", last[0][3], last[0][0]));
            }
        } else if (mode == 3) {
            if (listening) s.append(trigPos >= 0 || busy ? "分析中…\n\n" : "正在聽…敲一下\n\n");
            if (!note.isEmpty()) s.append(note).append("\n\n");
            if (taps.isEmpty()) s.append("還沒有結果。");
            else {
                double[][] p = taps.get(taps.size() - 1);
                s.append("這一次\n");
                for (double[] k : p)
                    s.append(String.format(Locale.US, "%9s　%5.1f dB　餘音 %s\n", hz(k[0]), k[1],
                            k[2] > 0 ? String.format(Locale.US, "%.2f 秒（阻尼比 %.4f）", k[2], k[3]) : "太短量不到"));
                double[] st = tapStats();
                s.append(String.format(Locale.US, "\n最強的頻率　%d 次平均 %s（± %.2f Hz）\n", taps.size(), hz(st[0]), st[1]));
                s.append("\n同一個東西多敲幾次，數字穩定才可信。比較兩個東西時：頻率比的平方 = 剛性比（例如頻率掉 5% → 剛性掉約 10%）。");
            }
        }
        out.setText(s.toString());
    }

    private double avgMid() {
        double s = 0;
        for (double[][] r : rts) s += mid(r);
        return s / rts.size();
    }

    private double[] tapStats() {
        double m = 0, v = 0;
        for (double[][] p : taps) m += p[0][0];
        m /= taps.size();
        for (double[][] p : taps) v += (p[0][0] - m) * (p[0][0] - m);
        return new double[]{m, Math.sqrt(v / taps.size())};
    }

    private void young() {
        LinearLayout f = col(c);
        f.setPadding(dp(22), dp(8), dp(22), 0);
        String[] labels = {"長度 mm（懸臂＝伸出來的長度）", "寬度 mm", "厚度 mm", "質量 g（懸臂＝伸出來那段）", "頻率 Hz"};
        String[] keys = {"y_l", "y_b", "y_t", "y_m", ""};
        EditText[] e = new EditText[5];
        for (int i = 0; i < 5; i++) {
            f.addView(text(c, labels[i], 12.5f, DIM), lp(MATCH, WRAP, 0, 0, 8, 0, 0));
            e[i] = new EditText(c);
            e[i].setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e[i].setTextColor(TEXT);
            e[i].setText(i < 4 ? eng.prefs.getString(keys[i], "") : String.format(Locale.US, "%.1f", tapStats()[0]));
            f.addView(e[i]);
        }
        final boolean[] clamped = {eng.prefs.getBoolean("y_clamp", false)};
        TextView kind = pill(c, "", false);
        Runnable upd = () -> kind.setText(clamped[0] ? "擺法：一端夾住（懸臂）" : "擺法：兩端自由（放在兩條橡皮筋上）");
        upd.run();
        kind.setOnClickListener(x -> { clamped[0] = !clamped[0]; upd.run(); });
        f.addView(kind, lp(MATCH, WRAP, 0, 0, 12, 0, 0));
        ScrollView sv = new ScrollView(c);
        sv.addView(f);
        new AlertDialog.Builder(c).setTitle("算楊氏模數（長條、最低的彎曲頻率）").setView(sv)
                .setPositiveButton("計算", (d, w) -> {
                    try {
                        double[] v = new double[5];
                        for (int i = 0; i < 5; i++) v[i] = Double.parseDouble(e[i].getText().toString().trim());
                        eng.prefs.edit().putString("y_l", e[0].getText().toString()).putString("y_b", e[1].getText().toString())
                                .putString("y_t", e[2].getText().toString()).putString("y_m", e[3].getText().toString()).putBoolean("y_clamp", clamped[0]).apply();
                        double E = clamped[0] ? Dsp.youngClamped(v[0], v[1], v[2], v[3], v[4]) : Dsp.youngFree(v[0], v[1], v[2], v[3], v[4]);
                        note = String.format(Locale.US, "楊氏模數 E ≈ %.2f GPa（%s、%.1f Hz、%s×%s×%s mm、%s g）", E, clamped[0] ? "懸臂" : "兩端自由", v[4],
                                e[0].getText(), e[1].getText(), e[2].getText(), e[3].getText());
                    } catch (Exception ex) {
                        note = "每一格都要填數字。";
                    }
                    show();
                }).setNegativeButton("取消", null).show();
    }
}
