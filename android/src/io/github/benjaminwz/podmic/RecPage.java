package io.github.benjaminwz.podmic;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import static io.github.benjaminwz.podmic.Ui.*;

/** 錄音頁：裝置、時間、兩軌音量、波形、標記／錄音／暫停。 */
final class RecPage implements MainActivity.Page {
    private final Context c;
    private final Engine eng;
    private final SharedPreferences prefs;
    private final ScrollView root;
    private final TextView dev, mode, tip, timer, state, warn, title, info, name1, name2, db1, db2, pause, markBtn;
    private final View dot, recInner;
    private final Meter m1, m2;
    private final Wave wave;
    private final LinearLayout rowB;
    private double maxRecent = -120;
    private long maxAt, lastInfo;

    RecPage(Context c) {
        this.c = c;
        eng = Engine.get(c);
        prefs = eng.prefs;
        root = new ScrollView(c);
        root.setFillViewport(true);
        LinearLayout v = col(c);
        v.setPadding(dp(16), dp(4), dp(16), dp(12));
        root.addView(v);

        // 裝置
        LinearLayout card = card(c);
        LinearLayout r = row(c);
        dot = new View(c);
        dot.setBackground(round(DIM, 6));
        r.addView(dot, lp(dp(10), dp(10), 0, 0, 0, 10, 0));
        dev = bold(c, "", 17, TEXT);
        r.addView(dev, lp(0, WRAP, 1));
        card.addView(r);
        mode = text(c, "", 13, DIM);
        mode.setLineSpacing(dp(3), 1);
        card.addView(mode, lp(MATCH, WRAP, 0, 20, 6, 0, 0));
        tip = text(c, "", 13, GOLD);
        tip.setLineSpacing(dp(3), 1);
        card.addView(tip, lp(MATCH, WRAP, 0, 20, 6, 0, 0));
        TextView help = text(c, "DJI Mic Mini 2 怎麼設定最好 ›", 13, GOLD);
        card.addView(help, lp(MATCH, WRAP, 0, 20, 8, 0, 0));
        press(card, round(CARD, 18));
        card.setOnClickListener(x -> micHelp());
        v.addView(card, lp(MATCH, WRAP, 0));
        if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).apply();
            new android.app.AlertDialog.Builder(c).setTitle("三步驟開始錄")
                    .setMessage("1. 把 DJI Mic Mini 2 的接收器插上手機，發射器開機夾好。上面的燈變綠色、出現麥克風名稱就是接好了。\n\n"
                            + "2. 講幾句話看音量條：最大聲落在淺金色那一段（−12～−6 dB）最剛好。太小或破音，下面會提醒你怎麼調。\n\n"
                            + "3. 按紅色按鈕開始錄。錄音中按音量鍵可以打標記。錄完在「檔案」頁聽、剪、傳到電腦。\n\n"
                            + "第一次用建議先到「測量 → 環境檢查」花 20 秒看房間適不適合錄。")
                    .setPositiveButton("開始", null).setNeutralButton("麥克風設定建議", (d, w) -> micHelp()).show();
        }

        // 時間
        state = bold(c, "待命", 14, DIM);
        state.setGravity(Gravity.CENTER);
        v.addView(state, lp(MATCH, WRAP, 0, 0, 22, 0, 0));
        timer = text(c, "00:00", 64, TEXT);
        timer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timer.setFontFeatureSettings("tnum");
        timer.setGravity(Gravity.CENTER);
        v.addView(timer, lp(MATCH, WRAP, 0, 0, 2, 0, 0));
        title = text(c, "", 15, GOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(12), dp(10), dp(12), dp(10));
        title.setOnClickListener(x -> ask(c, "這一集的名稱（會變成檔名）", prefs.getString("title", ""), false, s -> {
            prefs.edit().putString("title", s.trim()).apply();
            refresh();
        }));
        v.addView(title, lp(MATCH, WRAP, 0));

        // 音量
        LinearLayout mc = card(c);
        name1 = bold(c, "", 13, TEXT);
        db1 = text(c, "", 13, DIM);
        m1 = new Meter(c);
        mc.addView(meterRow(name1, db1, "name1"));
        mc.addView(m1, lp(MATCH, dp(14), 0, 0, 7, 0, 0));
        rowB = col(c);
        name2 = bold(c, "", 13, TEXT);
        db2 = text(c, "", 13, DIM);
        m2 = new Meter(c);
        rowB.addView(meterRow(name2, db2, "name2"), lp(MATCH, WRAP, 0, 0, 14, 0, 0));
        rowB.addView(m2, lp(MATCH, dp(14), 0, 0, 7, 0, 0));
        mc.addView(rowB);
        warn = text(c, "", 13, AMBER);
        warn.setLineSpacing(dp(3), 1);
        mc.addView(warn, lp(MATCH, WRAP, 0, 0, 10, 0, 0));
        v.addView(mc, lp(MATCH, WRAP, 0, 0, 6, 0, 0));

        wave = new Wave(c);
        wave.eng = eng;
        v.addView(wave, lp(MATCH, dp(64), 0, 0, 14, 0, 0));
        info = text(c, "", 12.5f, DIM);
        info.setGravity(Gravity.CENTER);
        v.addView(info, lp(MATCH, WRAP, 0, 0, 8, 0, 0));
        v.addView(new View(c), lp(MATCH, 0, 1));

        // 按鈕
        LinearLayout b = row(c);
        b.setGravity(Gravity.CENTER);
        markBtn = pill(c, "標記", false);
        markBtn.setOnClickListener(x -> {
            if (eng.rec == Engine.IDLE) return;
            eng.mark("");
            x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        markBtn.setOnLongClickListener(x -> {
            if (eng.rec == Engine.IDLE) return true;
            final long at = eng.recFrames;
            ask(c, "標記的名稱", "", false, s -> eng.mark(s.trim()));
            return true;
        });
        b.addView(markBtn, lp(dp(92), WRAP, 0));
        FrameLayout rec = new FrameLayout(c);
        GradientDrawable ring = outline(TEXT, 44);
        ring.setStroke(dp(3), TEXT);
        rec.setBackground(ring);
        recInner = new View(c);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER);
        rec.addView(recInner, fl);
        rec.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (count > 0) { count = 0; refresh(); return; }   // 倒數中再按一次 = 取消
            if (eng.rec != Engine.IDLE) eng.stopRec();
            else if (prefs.getBoolean("countdown", false)) { count = 3; countdown(); }
            else if (!eng.startRec(prefs.getString("title", ""))) refresh();
        });
        b.addView(rec, lp(dp(84), dp(84), 0, 26, 0, 26, 0));
        pause = pill(c, "暫停", false);
        pause.setOnClickListener(x -> eng.pauseRec());
        b.addView(pause, lp(dp(92), WRAP, 0));
        v.addView(b, lp(MATCH, WRAP, 0, 0, 16, 0, 4));
        refresh();
    }

    private LinearLayout meterRow(TextView name, TextView db, String key) {
        LinearLayout r = row(c);
        r.addView(name, lp(0, WRAP, 1));
        db.setFontFeatureSettings("tnum");
        r.addView(db);
        name.setOnClickListener(x -> ask(c, "這一軌是誰", prefs.getString(key, key.equals("name1") ? "主持人" : "來賓"), false, s -> {
            if (!s.trim().isEmpty()) prefs.edit().putString(key, s.trim()).apply();
            refresh();
        }));
        return r;
    }

    /** 針對 Mic Mini 2 的設定建議（接收器轉盤 5 段增益、降噪兩段、自動限幅、M／S／Ms 三種模式）。 */
    private void micHelp() {
        new android.app.AlertDialog.Builder(c).setTitle("DJI Mic Mini 2 建議設定")
                .setMessage("【一個人講話】\n・模式 M（單聲道）；這一集很重要就用 Ms（安全音軌）：多錄一軌小 6 dB 的備份，破音時電腦後製會自動補回來。\n"
                        + "・降噪：安靜的房間關掉最自然；有冷氣、風扇聲開「基礎」。\n・自動限幅：開。\n\n"
                        + "【兩個人各一支】\n・模式 S（立體聲）：兩個人各一軌，後製才能分開把音量拉齊。\n・降噪、限幅同上。\n\n"
                        + "【錄樂器、唱歌】\n・降噪一定要關（它會把延音當雜音吃掉，聲音變糊）。\n・模式 S；兩支發射器離樂器一樣遠。\n\n"
                        + "【音量】\n轉接收器的轉盤（5 段），讓最大聲落在 −12～−6 dB。太小聲事後放大會有沙沙聲，太大聲會破音。\n\n"
                        + "【位置】\n發射器夾在領口，離嘴巴一個拳頭，不要被衣服蓋住或摩擦到。\n\n"
                        + "模式和安全音軌在 DJI Mimo App 裡切換；這個 App 會自動認出現在是哪一種，顯示在最上面。")
                .setPositiveButton("好", null).show();
    }

    private int count;

    private void countdown() {
        if (count <= 0) return;
        state.setText("倒數 " + count);
        state.setTextColor(AMBER);
        root.postDelayed(() -> {
            if (count <= 0) return;
            if (--count == 0) { eng.startRec(prefs.getString("title", "")); refresh(); }
            else countdown();
        }, 1000);
    }

    @Override public View view() { return root; }

    @Override public void shown() { refresh(); }

    /** 狀態變了才需要重排的東西。 */
    void refresh() {
        int rec = eng.rec;
        boolean two = eng.chanMode == Dsp.M_STEREO;
        name1.setText(two || eng.chanMode == Dsp.M_SAFETY ? prefs.getString("name1", "主持人") + (eng.chanMode == Dsp.M_SAFETY ? "（主音軌）" : "") : "麥克風");
        name2.setText(eng.chanMode == Dsp.M_SAFETY ? "備用音軌（−6 dB）" : prefs.getString("name2", "來賓"));
        rowB.setVisibility(two || eng.chanMode == Dsp.M_SAFETY ? View.VISIBLE : View.GONE);
        dev.setText(eng.ok ? eng.devName : (eng.error.isEmpty() ? "正在打開麥克風…" : eng.error));
        dot.setBackground(round(!eng.ok ? RED : eng.usb ? GREEN : AMBER, 6));
        String fmt = "48 kHz · " + (prefs.getBoolean("float", false) ? "32-bit 浮點" : "24-bit") + " · 存成 " + (eng.wantChannels() == 1 ? "單聲道" : "2 聲道");
        String m;
        switch (eng.chanMode) {
            case Dsp.M_SINGLE: m = "一支發射器"; break;
            case Dsp.M_DUAL: m = "單聲道模式（兩軌相同，只存一軌）"; break;
            case Dsp.M_SAFETY: m = "安全音軌模式：第 2 軌小 6 dB 備用，傳到電腦後破音的地方會自動用它補回來"; break;
            case Dsp.M_STEREO: m = eng.usb ? "立體聲模式：兩支發射器各錄一軌，後製可以分開調" : "立體聲"; break;
            default: m = "等待聲音…";
        }
        mode.setText(m + "\n" + fmt);
        if (!eng.ok) tip.setText("");
        else if (!eng.usb) tip.setText("現在用的是手機自己的麥克風。把 DJI Mic 2 的接收器插上手機的 USB-C 就會自動切過去。");
        else if (eng.chanMode == Dsp.M_DUAL) tip.setText("兩個人一起錄的話，在接收器上把模式切成「立體聲」，兩個人的聲音才會分開。");
        else tip.setText("");
        tip.setVisibility(tip.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String t = prefs.getString("title", "");
        title.setText(t.isEmpty() ? "點這裡幫這一集取名" : t);
        state.setText(rec == Engine.REC ? "● 錄音中" : rec == Engine.PAUSE ? "暫停中" : "待命");
        state.setTextColor(rec == Engine.REC ? RED : rec == Engine.PAUSE ? AMBER : DIM);
        recInner.setBackground(round(RED, rec == Engine.IDLE ? 33 : 10));
        float sc = rec == Engine.IDLE ? 1f : 0.5f;
        recInner.animate().scaleX(sc).scaleY(sc).setDuration(140).start();
        pause.setText(rec == Engine.PAUSE ? "繼續" : "暫停");
        pause.setAlpha(rec == Engine.IDLE ? 0.35f : 1);
        markBtn.setAlpha(rec == Engine.IDLE ? 0.35f : 1);
        markBtn.setText(rec != Engine.IDLE && !eng.markers.isEmpty() ? "標記 " + eng.markers.size() : "標記");
    }

    @Override public void tick() {
        float p0, p1, r0, r1;
        synchronized (eng.peak) {
            p0 = eng.peak[0]; p1 = eng.peak[1]; r0 = eng.rms[0]; r1 = eng.rms[1];
            eng.peak[0] = eng.peak[1] = 0;
        }
        double d0 = Dsp.db(p0), d1 = Dsp.db(p1);
        m1.set(Dsp.db(r0), d0);
        m2.set(Dsp.db(r1), d1);
        db1.setText(m1.holdDb() < -99 ? "−∞" : String.format(Locale.US, "%.1f dB", m1.holdDb()));
        db2.setText(m2.holdDb() < -99 ? "−∞" : String.format(Locale.US, "%.1f dB", m2.holdDb()));
        db1.setTextColor(m1.holdDb() > -3 ? RED : DIM);
        db2.setTextColor(m2.holdDb() > -3 ? RED : DIM);
        wave.invalidate();
        timer.setText(time(eng.recSeconds()));
        long now = System.currentTimeMillis();
        double mx = eng.chanMode == Dsp.M_SAFETY ? d0 : Math.max(d0, d1);
        if (mx > maxRecent || now - maxAt > 6000) { maxRecent = mx; maxAt = now; }
        if (now - lastInfo > 500) {
            lastInfo = now;
            String w = "";
            int col = AMBER;
            if (!eng.error.isEmpty() && eng.ok) { w = eng.error; col = RED; }
            else if (eng.lostMic && eng.rec != Engine.IDLE) { w = "麥克風斷線過：已經改用" + eng.devName + "繼續錄，斷掉的位置有標記"; col = RED; }
            else if (now - eng.clipAt < 4000) { w = "破音了：把接收器的增益調低一點（或離麥克風遠一點）"; col = RED; }
            else if (maxRecent > -3) w = "有點太大聲：最大聲的地方落在 −12～−6 dB 最剛好";
            else if (maxRecent < -32 && maxRecent > -70) w = "聲音偏小：把接收器的增益調高，或把麥克風夾近一點（離嘴巴一個拳頭）";
            warn.setText(w);
            warn.setTextColor(col);
            warn.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
            long free = eng.freeSeconds();
            info.setText("還能錄 " + (free >= 7200 ? free / 3600 + " 小時" : free / 60 + " 分鐘")
                    + (eng.rec != Engine.IDLE && eng.recFile != null ? "　·　" + eng.recFile.getName() : ""));
            info.setTextColor(free < 600 ? RED : DIM);
        }
    }
}
