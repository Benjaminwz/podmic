package io.github.benjaminwz.podmic;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** 畫面的共用零件。全部用 dp／向量畫，不管螢幕多細都清楚。 */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0D0D0F, CARD = 0xFF19191D, CARD2 = 0xFF232329, LINE = 0xFF2E2E36, TEXT = 0xFFF3F1EB,
            DIM = 0xFF96938B, GOLD = 0xFFD0AC62, RED = 0xFFEC4D52, GREEN = 0xFF4CC982, AMBER = 0xFFEBA83F;
    static float den = 2;

    static int dp(float v) { return Math.round(v * den); }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView bold(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    static GradientDrawable outline(int stroke, float r) {
        GradientDrawable g = round(0, r);
        g.setStroke(dp(1.2f), stroke);
        return g;
    }

    static void press(View v, android.graphics.drawable.Drawable bg) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg));
        v.setClickable(true);
    }

    /** 膠囊按鈕。filled：金底黑字；否則深色底。 */
    static TextView pill(Context c, String s, boolean filled) {
        TextView t = bold(c, s, 15, filled ? 0xFF17130A : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        press(t, round(filled ? GOLD : CARD2, 22));
        return t;
    }

    static void pillStyle(TextView t, boolean on) {
        t.setTextColor(on ? 0xFF17130A : TEXT);
        press(t, round(on ? GOLD : CARD2, 22));
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(CARD, 18));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static String time(double sec) {
        long s = (long) sec;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static String size(long b) {
        return b >= 1 << 30 ? String.format(Locale.US, "%.2f GB", b / 1073741824.0) : String.format(Locale.US, "%.0f MB", Math.max(1, b / 1048576.0));
    }

    interface Text { void got(String s); }

    static void ask(Context c, String title, String init, boolean multi, Text cb) {
        EditText e = new EditText(c);
        e.setText(init);
        e.setTextColor(TEXT);
        e.setTextSize(multi ? 16 : 18);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(8);
            e.setMaxLines(16);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
            e.setSelection(e.getText().length());
        }
        FrameLayout f = new FrameLayout(c);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        new AlertDialog.Builder(c).setTitle(title).setView(f)
                .setPositiveButton("確定", (d, w) -> cb.got(e.getText().toString()))
                .setNegativeButton("取消", null).show();
    }

    static int levelColor(double db) { return db > -3 ? RED : db > -12 ? AMBER : GREEN; }

    // ---------- 音量條 ----------
    static final class Meter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double rms = -120, peak = -120, hold = -120;
        private long holdAt;
        static final double MIN = -60;

        Meter(Context c) { super(c); }

        void set(double rmsDb, double peakDb) {
            // 往下掉得慢一點，比較好讀
            rms = Math.max(rmsDb, rms - 1.2);
            peak = Math.max(peakDb, peak - 1.5);
            long now = System.currentTimeMillis();
            if (peakDb >= hold || now - holdAt > 1800) { hold = peakDb; holdAt = now; }
            invalidate();
        }

        double holdDb() { return hold; }

        private float x(double db) { return (float) (Math.max(0, Math.min(1, (db - MIN) / -MIN)) * getWidth()); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD2);
            cv.drawRoundRect(0, 0, w, h, r, r, p);
            // 建議範圍 −12～−6 dB 的淡色底
            p.setColor(0x22D0AC62);
            cv.drawRect(x(-12), 0, x(-6), h, p);
            float xp = x(peak);
            if (xp > 1) {
                p.setColor(levelColor(peak) & 0x55FFFFFF);
                cv.drawRoundRect(0, 0, Math.max(xp, h), h, r, r, p);
            }
            float xr = x(rms);
            if (xr > 1) {
                p.setColor(levelColor(peak));
                cv.drawRoundRect(0, 0, Math.max(xr, h), h, r, r, p);
            }
            p.setColor(0x66000000);
            for (int d = -48; d < 0; d += 12) cv.drawRect(x(d) - dp(0.5f), 0, x(d) + dp(0.5f), h, p);
            if (hold > MIN) {
                p.setColor(levelColor(hold));
                float xh = Math.min(x(hold), w - dp(2));
                cv.drawRect(xh - dp(1.5f), 0, xh + dp(1.5f), h, p);
            }
        }
    }

    // ---------- 捲動波形 ----------
    static final class Wave extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Engine eng;

        Wave(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            if (eng == null) return;
            float w = getWidth(), h = getHeight(), bar = dp(2), gap = dp(1.2f), step = bar + gap;
            int n = (int) (w / step), pos = eng.wavePos;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(bar);
            for (int i = 0; i < n; i++) {
                int k = pos - 1 - i;
                if (k < 0) break;
                float v = eng.wave[k & (eng.wave.length - 1)];
                boolean rec = v > 0;
                double db = Dsp.db(Math.abs(v));
                float a = (float) Math.max(0.02, Math.min(1, (db + 54) / 54));
                p.setColor(rec ? (db > -1 ? RED : GOLD) : 0xFF4A4A52);
                float x = w - i * step - bar, y = a * (h / 2 - bar);
                cv.drawLine(x, h / 2 - y, x, h / 2 + y, p);
            }
        }
    }

    // ---------- 頻譜 ----------
    static final class Spectrum extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float[] cols;       // 每一個畫素欄的 dB（平滑過）
        private float[] hold;
        static final double F0 = 20, F1 = 20000, D0 = -120, D1 = 0;
        double mark = -1;           // 標示的頻率（敲擊結果）

        Spectrum(Context c) { super(c); }

        float xOf(double f) { return (float) (Math.log(f / F0) / Math.log(F1 / F0) * getWidth()); }

        /** pw：Dsp.power 的結果。 */
        void feed(float[] pw, int fs, float smooth) {
            int w = getWidth();
            if (w <= 0) return;
            if (cols == null || cols.length != w) { cols = new float[w]; hold = new float[w]; java.util.Arrays.fill(cols, -140); java.util.Arrays.fill(hold, -140); }
            int n = pw.length * 2;
            for (int x = 0; x < w; x++) {
                double fa = F0 * Math.pow(F1 / F0, (double) x / w), fb = F0 * Math.pow(F1 / F0, (double) (x + 1) / w);
                int a = (int) Math.floor(fa * n / fs), b = (int) Math.ceil(fb * n / fs);
                a = Math.max(1, Math.min(a, pw.length - 1));
                b = Math.max(a + 1, Math.min(b, pw.length));
                float m = 0;
                for (int i = a; i < b; i++) if (pw[i] > m) m = pw[i];
                float db = (float) (10 * Math.log10(m + 1e-14));
                cols[x] = cols[x] < -139 ? db : cols[x] * smooth + db * (1 - smooth);
                hold[x] = Math.max(hold[x] - 0.15f, cols[x]);
            }
            invalidate();
        }

        void clearHold() { if (hold != null) java.util.Arrays.fill(hold, -140); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD);
            cv.drawRoundRect(0, 0, w, h, dp(14), dp(14), p);
            p.setTextSize(dp(10.5f));
            for (double f : new double[]{50, 100, 200, 500, 1000, 2000, 5000, 10000}) {
                float x = xOf(f);
                p.setColor(LINE);
                cv.drawRect(x, 0, x + dp(0.7f), h, p);
                p.setColor(DIM);
                cv.drawText(f >= 1000 ? (int) (f / 1000) + "k" : String.valueOf((int) f), x + dp(3), h - dp(5), p);
            }
            for (int d = -100; d < 0; d += 20) {
                float y = (float) ((D1 - d) / (D1 - D0) * h);
                p.setColor(LINE);
                cv.drawRect(0, y, w, y + dp(0.7f), p);
                p.setColor(DIM);
                cv.drawText(d + " dB", dp(5), y - dp(3), p);
            }
            if (cols == null) return;
            draw(cv, hold, 0x55D0AC62, false);
            draw(cv, cols, GOLD, true);
            if (mark > F0 && mark < F1) {
                p.setColor(GREEN);
                cv.drawRect(xOf(mark) - dp(0.8f), 0, xOf(mark) + dp(0.8f), h, p);
            }
        }

        private void draw(Canvas cv, float[] v, int color, boolean fill) {
            float h = getHeight();
            path.rewind();
            for (int x = 0; x < v.length; x++) {
                float y = (float) ((D1 - Math.max(D0, v[x])) / (D1 - D0) * h);
                if (x == 0) path.moveTo(0, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.4f));
            p.setColor(color);
            cv.drawPath(path, p);
            if (fill) {
                path.lineTo(v.length, h);
                path.lineTo(0, h);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x22D0AC62);
                cv.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    // ---------- 檔案的波形縮圖（可以點、拖來跳位置） ----------
    static final class Overview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] bins;
        double[] marks = new double[0];   // 0..1
        double pos;
        interface Seek { void to(double frac); }
        Seek seek;

        Overview(Context c) { super(c); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (seek != null && getWidth() > 0) {
                getParent().requestDisallowInterceptTouchEvent(true);
                seek.to(Math.max(0, Math.min(1, e.getX() / getWidth())));
            }
            return true;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(CARD2);
            cv.drawRoundRect(0, 0, w, h, dp(10), dp(10), p);
            if (bins != null) {
                p.setStrokeWidth(Math.max(1, w / bins.length * 0.7f));
                for (int i = 0; i < bins.length; i++) {
                    float x = (i + 0.5f) * w / bins.length;
                    float a = (float) Math.max(0.03, Math.min(1, (Dsp.db(bins[i]) + 48) / 48)) * (h / 2 - dp(4));
                    p.setColor((double) i / bins.length <= pos ? GOLD : 0xFF5A5A63);
                    cv.drawLine(x, h / 2 - a, x, h / 2 + a, p);
                }
            }
            p.setColor(GREEN);
            for (double m : marks) cv.drawRect((float) (m * w) - dp(0.8f), 0, (float) (m * w) + dp(0.8f), dp(10), p);
            p.setColor(TEXT);
            cv.drawRect((float) (pos * w) - dp(1), 0, (float) (pos * w) + dp(1), h, p);
        }
    }
}
