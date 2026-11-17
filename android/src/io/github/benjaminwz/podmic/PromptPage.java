package io.github.benjaminwz.podmic;

import android.content.Context;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static io.github.benjaminwz.podmic.Ui.*;

/** 提詞頁：稿子照講話速度往上捲，金色箭頭那一行就是現在該唸的。下面一條可以直接錄音。 */
final class PromptPage implements MainActivity.Page {
    private final Context c;
    private final Engine eng;
    private final LinearLayout root;
    private final ScrollView scroll;
    private final TextView tv, speed, play, recBtn, recTime, head, empty;
    private final Meter m1;
    private final View guide;
    private long seen = -1;
    private boolean drag;
    private float downY;
    private long downAt;
    private int guideY;

    PromptPage(Context c) {
        this.c = c;
        eng = Engine.get(c);
        root = col(c);

        LinearLayout top = row(c);
        top.setPadding(dp(16), dp(2), dp(10), dp(6));
        head = bold(c, "", 15, TEXT);
        head.setSingleLine(true);
        top.addView(head, lp(0, WRAP, 1));
        final TextView fol = small("跟聲音", null);
        fol.setTextColor(Prompt.follow ? GOLD : TEXT);
        fol.setOnClickListener(v -> {
            Prompt.follow = !Prompt.follow;
            fol.setTextColor(Prompt.follow ? GOLD : TEXT);
            Prompt.save(eng.prefs);
            android.widget.Toast.makeText(c, Prompt.follow ? "跟聲音：有講話稿子才捲，停下來它也停" : "固定速度", android.widget.Toast.LENGTH_SHORT).show();
        });
        top.addView(fol);
        top.addView(small("鏡像", v -> { Prompt.mirror = !Prompt.mirror; apply(); }));
        top.addView(small("字小", v -> { Prompt.font = Math.max(16, Prompt.font - 2); apply(); }));
        top.addView(small("字大", v -> { Prompt.font = Math.min(72, Prompt.font + 2); apply(); }));
        top.addView(small("編輯", v -> edit()));
        root.addView(top);

        FrameLayout area = new FrameLayout(c);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tv = text(c, "", Prompt.font, TEXT);
        tv.setLineSpacing(0, 1.35f);
        tv.setPadding(dp(30), 0, dp(20), 0);
        scroll.addView(tv);
        area.addView(scroll);
        guide = new View(c) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Path tri = new android.graphics.Path();
            @Override protected void onDraw(android.graphics.Canvas cv) {
                p.setColor(GOLD);
                tri.rewind();
                tri.moveTo(dp(6), getHeight() / 2f - dp(9));
                tri.lineTo(dp(20), getHeight() / 2f);
                tri.lineTo(dp(6), getHeight() / 2f + dp(9));
                tri.close();
                cv.drawPath(tri, p);
            }
        };
        area.addView(guide, new FrameLayout.LayoutParams(dp(26), dp(40)));
        empty = text(c, "還沒有稿子。\n\n按右上角「編輯」貼上，或在電腦的錄音台寫好，連線後會自動送過來。", 16, DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1);
        empty.setPadding(dp(40), 0, dp(40), 0);
        area.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(area, lp(MATCH, 0, 1));
        area.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { areaH = b - t; fixPad(); });
        scroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = true; downY = e.getY(); downAt = System.currentTimeMillis();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean tap = Math.abs(e.getY() - downY) < dp(8) && System.currentTimeMillis() - downAt < 300;
                    synchronized (Prompt.class) {
                        if (tap) Prompt.run = !Prompt.run && Prompt.text.length() > 0;
                        else Prompt.pos = posOf(scroll.getScrollY());
                        Prompt.touch();
                    }
                    drag = false;
                    break;
            }
            return false;
        });

        LinearLayout ctl = row(c);
        ctl.setPadding(dp(12), dp(8), dp(12), dp(4));
        ctl.addView(small("回開頭", v -> { synchronized (Prompt.class) { Prompt.pos = 0; Prompt.run = false; Prompt.touch(); } }));
        ctl.addView(new View(c), lp(0, 1, 1));
        ctl.addView(small("慢", v -> setSpeed(-0.25f)));
        speed = text(c, "", 14, TEXT);
        speed.setGravity(Gravity.CENTER);
        speed.setFontFeatureSettings("tnum");
        ctl.addView(speed, lp(dp(92), WRAP, 0));
        ctl.addView(small("快", v -> setSpeed(0.25f)));
        ctl.addView(new View(c), lp(0, 1, 1));
        play = pill(c, "開始", true);
        play.setOnClickListener(v -> { synchronized (Prompt.class) { Prompt.run = !Prompt.run && Prompt.text.length() > 0; Prompt.touch(); } });
        ctl.addView(play, lp(dp(96), WRAP, 0));
        root.addView(ctl);

        LinearLayout bar = row(c);
        bar.setBackground(round(CARD, 16));
        bar.setPadding(dp(14), dp(10), dp(10), dp(10));
        recTime = text(c, "00:00", 15, TEXT);
        recTime.setFontFeatureSettings("tnum");
        bar.addView(recTime, lp(dp(64), WRAP, 0));
        m1 = new Meter(c);
        bar.addView(m1, lp(0, dp(10), 1, 4, 0, 12, 0));
        recBtn = pill(c, "錄音", false);
        recBtn.setOnClickListener(v -> {
            if (eng.rec == Engine.IDLE) {
                if (eng.startRec(eng.prefs.getString("title", "")) && eng.prefs.getBoolean("autoprompt", true)) {
                    synchronized (Prompt.class) { if (Prompt.text.length() > 0) { Prompt.run = true; Prompt.touch(); } }
                }
            } else eng.stopRec();
        });
        bar.addView(recBtn, lp(dp(88), WRAP, 0));
        root.addView(bar, lp(MATCH, WRAP, 0, 12, 4, 12, 10));
        apply();
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView t = text(c, s, 13.5f, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        press(t, round(CARD2, 18));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = lp(WRAP, WRAP, 0, 3, 0, 3, 0);
        t.setLayoutParams(p);
        return t;
    }

    private void setSpeed(float d) {
        synchronized (Prompt.class) {
            Prompt.speed = Math.max(1f, Math.min(10f, Prompt.speed + d));
            Prompt.touch();
        }
        Prompt.save(eng.prefs);
    }

    private void edit() {
        ask(c, "提詞稿", Prompt.text, true, s -> {
            Prompt.setScript(Prompt.title, s, System.currentTimeMillis());
            Prompt.save(eng.prefs);
        });
    }

    private int areaH;

    /** 上下留白：第一行從箭頭開始、最後一行能捲到箭頭。 */
    private void fixPad() {
        if (areaH <= 0) return;
        guideY = (int) (areaH * 0.32f);
        int top = Math.max(0, guideY - tv.getLineHeight() / 2), bottom = areaH - guideY;
        if (tv.getPaddingTop() != top || tv.getPaddingBottom() != bottom) tv.setPadding(dp(30), top, dp(20), bottom);
        guide.setTranslationY(guideY - dp(20));
    }

    private void apply() {
        tv.setTextSize(Prompt.font);
        fixPad();
        scroll.setScaleX(Prompt.mirror ? -1 : 1);
        Prompt.save(eng.prefs);
        seen = -1;
    }

    private int yOf(double pos) {
        Layout l = tv.getLayout();
        if (l == null) return 0;
        int o = (int) Math.max(0, Math.min(pos, tv.length()));
        int line = l.getLineForOffset(o), ls = l.getLineStart(line), le = l.getLineEnd(line);
        double f = le > ls ? (pos - ls) / (le - ls) : 0;
        return (int) (l.getLineTop(line) + Math.max(0, Math.min(1, f)) * (l.getLineBottom(line) - l.getLineTop(line)));
    }

    private double posOf(int y) {
        Layout l = tv.getLayout();
        if (l == null) return 0;
        int line = l.getLineForVertical(y), ls = l.getLineStart(line), le = l.getLineEnd(line);
        int top = l.getLineTop(line), h = Math.max(1, l.getLineBottom(line) - top);
        return ls + (le - ls) * Math.max(0, Math.min(1, (y - top) / (double) h));
    }

    @Override public View view() { return root; }

    @Override public void shown() { seen = -1; }

    @Override public void tick() {
        if (seen != Prompt.changed) {
            seen = Prompt.changed;
            tv.setText(Prompt.text);
            head.setText(Prompt.title.isEmpty() ? "提詞稿" : Prompt.title);
            empty.setVisibility(Prompt.text.isEmpty() ? View.VISIBLE : View.GONE);
            guide.setVisibility(Prompt.text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (!drag) scroll.scrollTo(0, yOf(Prompt.pos));
        play.setText(Prompt.run ? "暫停" : "開始");
        speed.setText(Math.round(Prompt.speed * 60) + " 字/分");
        // 標題旁邊顯示照這個速度還要唸多久
        String h = (Prompt.title.isEmpty() ? "提詞稿" : Prompt.title)
                + (Prompt.text.isEmpty() ? "" : "　剩 " + time((Prompt.text.length() - Prompt.pos) / Prompt.speed));
        if (!h.contentEquals(head.getText())) head.setText(h);
        float p, r;
        synchronized (eng.peak) {
            p = Math.max(eng.peak[0], eng.peak[1]); r = Math.max(eng.rms[0], eng.rms[1]);
            eng.peak[0] = eng.peak[1] = 0;
        }
        m1.set(Dsp.db(r), Dsp.db(p));
        recTime.setText(time(eng.recSeconds()));
        boolean rec = eng.rec != Engine.IDLE;
        if (rec != (recBtn.getCurrentTextColor() == 0xFFFFFFFF)) {
            recBtn.setText(rec ? "停止" : "錄音");
            recBtn.setTextColor(rec ? 0xFFFFFFFF : TEXT);
            press(recBtn, round(rec ? RED : CARD2, 22));
        }
    }
}
