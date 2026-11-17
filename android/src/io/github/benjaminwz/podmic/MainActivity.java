package io.github.benjaminwz.podmic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static io.github.benjaminwz.podmic.Ui.*;

public class MainActivity extends Activity implements Choreographer.FrameCallback {
    interface Page {
        View view();
        void tick();
        void shown();
    }

    private Engine eng;
    private Link link;
    private SharedPreferences prefs;
    private final Page[] pages = new Page[4];
    private final TextView[] tabs = new TextView[4];
    private FrameLayout content;
    private TextView chip;
    private int cur = -1;
    private boolean resumed, held, keepOn;
    private long frame;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        den = getResources().getDisplayMetrics().density;
        eng = Engine.get(this);
        link = Link.get(this);
        prefs = eng.prefs;
        Prompt.load(prefs);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = col(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout head = row(this);
        head.setPadding(dp(18), dp(12), dp(12), dp(10));
        head.addView(bold(this, "錄音台", 21, TEXT), lp(0, WRAP, 1));
        chip = text(this, "", 12.5f, DIM);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(outline(LINE, 16));
        chip.setOnClickListener(v -> linkDialog());
        head.addView(chip);
        TextView gear = text(this, "設定", 13.5f, TEXT);
        gear.setPadding(dp(12), dp(8), dp(12), dp(8));
        press(gear, round(CARD2, 16));
        gear.setOnClickListener(v -> settings());
        head.addView(gear, lp(WRAP, WRAP, 0, 8, 0, 0, 0));
        root.addView(head);
        content = new FrameLayout(this);
        root.addView(content, lp(MATCH, 0, 1));
        LinearLayout bar = row(this);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] names = {"錄音", "提詞", "檔案", "測量"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            tabs[i] = bold(this, names[i], 14.5f, DIM);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(0, dp(12), 0, dp(12));
            tabs[i].setOnClickListener(v -> show(k));
            bar.addView(tabs[i], lp(0, WRAP, 1));
        }
        root.addView(bar);
        setContentView(root);

        eng.onChange = () -> { if (pages[0] != null) ((RecPage) pages[0]).refresh(); };
        eng.onFinished = () -> { if (cur == 2) ((FilesPage) pages[2]).refresh(); };
        show(0);
        link.start();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private void show(int i) {
        if (i == cur) return;
        if (cur == 2) ((FilesPage) pages[2]).hidden();
        if (pages[i] == null) {
            pages[i] = i == 0 ? new RecPage(this) : i == 1 ? new PromptPage(this) : i == 2 ? new FilesPage(this) : new MeasurePage(this);
            content.addView(pages[i].view(), new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        for (int k = 0; k < 4; k++) {
            if (pages[k] != null) pages[k].view().setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(k == i ? GOLD : DIM);
        }
        cur = i;
        pages[i].shown();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (resumed) hold();
        eng.restart();
    }

    private void hold() {
        if (!held && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            eng.acquire();
            held = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        hold();
        Choreographer.getInstance().postFrameCallback(this);
        if (cur >= 0) pages[cur].shown();
    }

    @Override protected void onPause() {
        resumed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        if (held) { eng.release(); held = false; }
        if (pages[2] != null) ((FilesPage) pages[2]).hidden();
        Prompt.save(prefs);
        super.onPause();
    }

    @Override public void doFrame(long t) {
        if (!resumed) return;
        Choreographer.getInstance().postFrameCallback(this);
        Prompt.gate(Math.max(eng.rms[0], eng.rms[1]));
        Prompt.tick();
        frame++;
        // 提詞要每一格都動才順；其他頁 30 fps 就夠
        if (cur == 1 || (frame & 1) == 0) pages[cur].tick();
        if (frame % 20 == 0) {
            int s = link.state;
            if (s == Link.ON && link.newApk > myCode()) {
                chip.setText(updating ? "更新中…" : "有新版，點這裡更新");
                chip.setTextColor(GOLD);
                return;
            }
            chip.setText(s == Link.ON ? "電腦：" + link.pcName : s == Link.WAIT ? "請在電腦上按「允許」" : s == Link.SEARCH ? "找電腦中…" : "沒有連電腦");
            chip.setTextColor(s == Link.ON ? GREEN : s == Link.WAIT ? AMBER : DIM);
            boolean on = eng.rec != Engine.IDLE || Prompt.run;
            if (on != keepOn) {
                keepOn = on;
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    /** 錄音中按音量鍵 = 打標記（不用看螢幕）。 */
    @Override public boolean onKeyDown(int key, android.view.KeyEvent e) {
        if ((key == android.view.KeyEvent.KEYCODE_VOLUME_UP || key == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && eng.rec != Engine.IDLE) {
            if (e.getRepeatCount() == 0) {
                eng.mark("");
                content.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
            return true;
        }
        return super.onKeyDown(key, e);
    }

    private boolean updating;
    private int code;

    private int myCode() {
        if (code == 0) try { code = (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { code = 1; }
        return code;
    }

    /** 自行更新：從電腦抓新版 → 交給系統安裝（系統會跳一次確認）。 */
    private void update() {
        if (eng.rec != Engine.IDLE) { toast("錄音中，錄完再更新"); return; }
        if (updating) return;
        updating = true;
        new Thread(() -> {
            try {
                java.io.File f = link.downloadApk(getCacheDir());
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                int id = pi.createSession(new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
                    try (java.io.OutputStream o = s.openWrite("apk", 0, f.length()); java.io.InputStream in = new java.io.FileInputStream(f)) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        s.fsync(o);
                    }
                    android.content.Intent i = new android.content.Intent(this, MainActivity.class).setAction("podmic.INSTALL");
                    s.commit(android.app.PendingIntent.getActivity(this, 2, i,
                            android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
                }
            } catch (Exception e) {
                runOnUiThread(() -> { updating = false; toast("更新失敗：" + e.getMessage()); });
            }
        }).start();
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        if (!"podmic.INSTALL".equals(i.getAction())) return;
        int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            android.content.Intent c = i.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
            if (c != null) startActivity(c);
        } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            updating = false;
            toast("沒有更新成功：" + i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    private void linkDialog() {
        if (link.state == Link.ON && link.newApk > myCode()) { update(); return; }
        String msg = link.state == Link.ON ? "已經連上「" + link.pcName + "」（" + link.host + "）。\n錄音狀態、音量、提詞稿會同步，錄完自動把檔案傳過去。"
                : "打開電腦上的「錄音台」，手機和電腦接同一個 Wi-Fi（或用 USB 線開「USB 網路共用」），就會自動找到。\n\n一直找不到的話，可以手動輸入電腦的位址。";
        new AlertDialog.Builder(this).setTitle("跟電腦連線").setMessage(msg)
                .setPositiveButton("好", null)
                .setNeutralButton("手動輸入位址", (d, w) -> ask(this, "電腦的位址（例如 192.168.1.23；留空 = 自動找）", prefs.getString("manual", ""), false, s -> {
                    prefs.edit().putString("manual", s.trim()).putString("host", "").apply();
                    link.reset();
                })).show();
    }

    private void settings() {
        LinearLayout v = col(this);
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        choice(v, "錄音格式", "float", new String[]{"24-bit（一般用，檔案較小）", "32-bit 浮點（後製空間最大）"});
        choiceStr(v, "聲道", "chan", new String[]{"auto", "stereo", "mono"}, new String[]{"自動（照接收器的模式）", "一律 2 聲道", "一律單聲道"});
        choiceStr(v, "麥克風", "src", new String[]{"auto", "phone"}, new String[]{"自動（有接 USB 麥克風就用它）", "一律用手機自己的"});
        toggle(v, "按下錄音後倒數 3 秒才開始", "countdown", false);
        toggle(v, "錄完自動傳到電腦", "autoup", true);
        toggle(v, "在提詞頁按錄音時，稿子自動開始捲", "autoprompt", true);
        toggle(v, "跟電腦連線", "link", true);
        v.addView(text(this, "小技巧：錄音中按音量鍵可以打標記；長按「標記」可以幫標記取名。", 12.5f, GOLD), lp(MATCH, WRAP, 0, 14, 12, 14, 0));
        v.addView(text(this, "檔案存在手機的 Android/data/io.github.benjaminwz.podmic/files/recordings。\n版本 " + ver(), 12, DIM), lp(MATCH, WRAP, 0, 14, 14, 14, 6));
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this).setTitle("設定").setView(sv).setPositiveButton("完成", (d, w) -> {
            eng.restart();
            if (prefs.getBoolean("link", true)) link.start(); else link.stop();
            if (pages[0] != null) ((RecPage) pages[0]).refresh();
        }).show();
    }

    private String ver() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private void toggle(LinearLayout v, String label, String key, boolean def) {
        TextView t = text(this, "", 15, TEXT);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        Runnable u = () -> t.setText((prefs.getBoolean(key, def) ? "✓　" : "　　") + label);
        u.run();
        press(t, round(0, 10));
        t.setOnClickListener(x -> { prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); u.run(); });
        v.addView(t);
    }

    private void choice(LinearLayout v, String label, String key, String[] names) {
        choiceRow(v, label, names, () -> prefs.getBoolean(key, false) ? 1 : 0, i -> prefs.edit().putBoolean(key, i == 1).apply());
    }

    private void choiceStr(LinearLayout v, String label, String key, String[] vals, String[] names) {
        choiceRow(v, label, names, () -> Math.max(0, java.util.Arrays.asList(vals).indexOf(prefs.getString(key, vals[0]))),
                i -> prefs.edit().putString(key, vals[i]).apply());
    }

    interface Get { int get(); }
    interface Set { void set(int i); }

    private void choiceRow(LinearLayout v, String label, String[] names, Get g, Set s) {
        TextView t = text(this, "", 15, TEXT);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        t.setLineSpacing(dp(4), 1);
        Runnable u = () -> t.setText(label + "\n" + names[g.get()]);
        u.run();
        press(t, round(0, 10));
        t.setOnClickListener(x -> { s.set((g.get() + 1) % names.length); u.run(); });
        v.addView(t);
    }
}
