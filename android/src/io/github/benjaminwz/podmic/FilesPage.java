package io.github.benjaminwz.podmic;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import static io.github.benjaminwz.podmic.Ui.*;

/** 檔案頁：錄好的清單，點開可以聽、看標記、傳到電腦、分享、改名、刪除。 */
final class FilesPage implements MainActivity.Page {
    private final Context c;
    private final Engine eng;
    private final Link link;
    private final ScrollView root;
    private final LinearLayout list;
    private final TextView status;
    private final Player player = new Player();
    private final Handler main = new Handler(Looper.getMainLooper());
    private File open;
    private Overview ov;
    private TextView playBtn, posText;
    private String lastStatus = "";

    FilesPage(Context c) {
        this.c = c;
        eng = Engine.get(c);
        link = Link.get(c);
        root = new ScrollView(c);
        LinearLayout v = col(c);
        v.setPadding(dp(16), dp(4), dp(16), dp(16));
        status = text(c, "", 13, GOLD);
        status.setVisibility(View.GONE);
        v.addView(status, lp(MATCH, WRAP, 0, 4, 0, 4, 10));
        list = col(c);
        v.addView(list);
        root.addView(v);
    }

    @Override public View view() { return root; }

    @Override public void shown() { refresh(); }

    void hidden() { player.stop(); }

    void refresh() {
        list.removeAllViews();
        ov = null;
        File[] fs = eng.dir().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".wav"));
        if (fs == null || fs.length == 0) {
            TextView t = text(c, "還沒有錄音。\n到「錄音」頁按紅色按鈕開始。", 15, DIM);
            t.setGravity(android.view.Gravity.CENTER);
            t.setLineSpacing(dp(4), 1);
            list.addView(t, lp(MATCH, WRAP, 0, 0, 80, 0, 0));
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        SimpleDateFormat df = new SimpleDateFormat("M/d HH:mm", Locale.US);
        for (File f : fs) {
            boolean busy = f.equals(eng.recFile) && eng.rec != Engine.IDLE;
            LinearLayout card = card(c);
            String n = f.getName();
            card.addView(bold(c, n.substring(0, n.length() - 4), 15.5f, TEXT));
            String sub;
            JSONObject j = Engine.readJson(f);
            try {
                Wav.Info in = Wav.info(f);
                sub = time(in.seconds()) + " · " + (in.channels == 1 ? "單聲道" : in.channels + " 聲道") + " " + (in.flt ? "32-bit 浮點" : in.bits + "-bit")
                        + " · " + size(f.length()) + " · " + df.format(new Date(f.lastModified()));
            } catch (Exception e) {
                sub = size(f.length());
            }
            JSONArray ms = j.optJSONArray("markers");
            if (busy) sub = "錄音中…";
            else {
                if (ms != null && ms.length() > 0) sub += " · 標記 " + ms.length();
                if (j.optBoolean("sent")) sub += " · 已傳到電腦";
            }
            TextView st = text(c, sub, 12.5f, busy ? RED : DIM);
            card.addView(st, lp(MATCH, WRAP, 0, 0, 5, 0, 0));
            if (f.equals(open) && !busy) card.addView(detail(f, j));
            press(card, round(CARD, 18));
            card.setOnClickListener(x -> {
                if (busy) return;
                player.stop();
                open = f.equals(open) ? null : f;
                refresh();
            });
            list.addView(card, lp(MATCH, WRAP, 0, 0, 0, 0, 10));
        }
    }

    private View detail(File f, JSONObject j) {
        LinearLayout d = col(c);
        d.setPadding(0, dp(12), 0, 0);
        ov = new Overview(c);
        d.addView(ov, lp(MATCH, dp(56), 0));
        try {
            player.open(f);
        } catch (Exception e) {
            d.addView(text(c, "這個檔案讀不出來", 13, RED));
            return d;
        }
        JSONArray ms = j.optJSONArray("markers");
        double total = Math.max(0.001, player.seconds());
        if (ms != null) {
            double[] mk = new double[ms.length()];
            for (int i = 0; i < mk.length; i++) mk[i] = ms.optJSONObject(i).optDouble("t") / total;
            ov.marks = mk;
        }
        ov.seek = fr -> { player.seek(fr); ov.pos = fr; ov.invalidate(); };
        final Overview mine = ov;
        new Thread(() -> {
            float[] b = Player.overview(f, 160);
            main.post(() -> { mine.bins = b; mine.invalidate(); });
        }).start();
        LinearLayout r = row(c);
        playBtn = pill(c, "播放", true);
        playBtn.setOnClickListener(x -> player.toggle());
        r.addView(playBtn, lp(dp(84), WRAP, 0));
        posText = text(c, "", 13, DIM);
        posText.setFontFeatureSettings("tnum");
        r.addView(posText, lp(0, WRAP, 1, 12, 0, 0, 0));
        d.addView(r, lp(MATCH, WRAP, 0, 0, 10, 0, 0));
        if (ms != null && ms.length() > 0) {
            LinearLayout ml = col(c);
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                final double t = m.optDouble("t");
                TextView row = text(c, time(t) + "　" + m.optString("label"), 13.5f, TEXT);
                row.setPadding(dp(4), dp(7), dp(4), dp(7));
                row.setOnClickListener(x -> { player.seek(t / total); if (!player.playing) player.toggle(); });
                ml.addView(row);
            }
            d.addView(ml, lp(MATCH, WRAP, 0, 0, 8, 0, 0));
        }
        // 剪輯：先播到想要的位置，按 A、B 定出一段，再選留下或刪掉
        final double[] ab = {0, total};
        LinearLayout cut = row(c);
        final TextView ta = act("A " + time(0), null), tb = act("B " + time(total), null);
        ta.setOnClickListener(x -> { ab[0] = Math.min(player.position(), ab[1]); ta.setText("A " + time(ab[0])); });
        tb.setOnClickListener(x -> { ab[1] = Math.max(player.position(), ab[0]); tb.setText("B " + time(ab[1])); });
        cut.addView(ta, lp(0, WRAP, 1, 0, 0, 3, 0));
        cut.addView(tb, lp(0, WRAP, 1, 3, 0, 3, 0));
        cut.addView(act("只留 A–B", x -> trim(f, j, ab[0], ab[1], true)), lp(0, WRAP, 1.2f, 3, 0, 3, 0));
        cut.addView(act("刪掉 A–B", x -> trim(f, j, ab[0], ab[1], false)), lp(0, WRAP, 1.2f, 3, 0, 0, 0));
        d.addView(text(c, "剪輯：播到想要的位置按 A 或 B 記下來，再選要留還是刪。原檔不會動，會另存一份。", 12, DIM), lp(MATCH, WRAP, 0, 0, 12, 0, 6));
        d.addView(cut);
        LinearLayout a = row(c);
        a.addView(act("傳到電腦", x -> {
            if (link.state != Link.ON) { toast("還沒連上電腦：先打開電腦上的錄音台，手機和電腦接同一個 Wi-Fi"); return; }
            link.queue(f, true);
            toast("開始傳送");
        }), lp(0, WRAP, 1, 0, 0, 3, 0));
        a.addView(act("分享", x -> new AlertDialog.Builder(c).setItems(new String[]{"分享給別的 App", "存到手機的「音樂／錄音台」資料夾"}, (dd, which) -> {
            if (which == 1) { export(f); return; }
            Uri u = Uri.parse("content://io.github.benjaminwz.podmic.files/" + Uri.encode(f.getName()));
            Intent i = new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(android.content.ClipData.newRawUri(f.getName(), u));
            c.startActivity(Intent.createChooser(i, "分享錄音"));
        }).show()), lp(0, WRAP, 1, 3, 0, 3, 0));
        a.addView(act("改名", x -> ask(c, "新的名稱", f.getName().substring(0, f.getName().length() - 4), false, s -> {
            String n = Engine.clean(s);
            if (n.isEmpty()) return;
            File to = new File(f.getParentFile(), n + ".wav");
            if (to.exists()) { toast("已經有同名的檔案"); return; }
            player.stop();
            File js = Engine.jsonOf(f);
            if (f.renameTo(to)) { if (js.exists()) js.renameTo(Engine.jsonOf(to)); open = to; }
            refresh();
        })), lp(0, WRAP, 1, 3, 0, 3, 0));
        a.addView(act("刪除", x -> new AlertDialog.Builder(c).setTitle("刪除這個錄音？").setMessage(f.getName() + "\n刪掉就救不回來了。")
                .setPositiveButton("刪除", (dd, w) -> {
                    player.stop();
                    f.delete();
                    Engine.jsonOf(f).delete();
                    open = null;
                    refresh();
                }).setNegativeButton("取消", null).show()), lp(0, WRAP, 1, 3, 0, 0, 0));
        d.addView(a, lp(MATCH, WRAP, 0, 0, 12, 0, 0));
        return d;
    }

    /** keep = 只留 a～b；否則把 a～b 挖掉。另存成「原名_剪.wav」，標記的時間跟著調整。 */
    private void trim(File f, JSONObject j, double a, double b, boolean keep) {
        if (b - a < 0.05) { toast("先用 A、B 定出一段"); return; }
        player.stop();
        toast("處理中…");
        new Thread(() -> {
            String msg = "已另存一份（檔名後面多了「_剪」）";
            try (java.io.RandomAccessFile src = new java.io.RandomAccessFile(f, "r")) {
                Wav.Info in = Wav.info(f);
                long fa = Math.min(in.frames, (long) (a * in.rate)), fb = Math.min(in.frames, (long) (b * in.rate));
                String base = f.getName().substring(0, f.getName().length() - 4);
                File to = new File(f.getParentFile(), base + "_剪.wav");
                for (int i = 2; to.exists(); i++) to = new File(f.getParentFile(), base + "_剪" + i + ".wav");
                if (in.bits != 24 && !in.flt) throw new Exception("只能剪這個 App 錄的檔案");
                Wav.Writer w = new Wav.Writer(to, in.rate, in.channels, in.flt);
                if (keep) w.copy(src, in, fa, fb);
                else { w.copy(src, in, 0, fa); w.copy(src, in, fb, in.frames); }
                java.util.ArrayList<Wav.Marker> ms = new java.util.ArrayList<>();
                JSONArray old = j.optJSONArray("markers"), out = new JSONArray();
                for (int i = 0; old != null && i < old.length(); i++) {
                    double t = old.optJSONObject(i).optDouble("t");
                    if (keep ? (t < a || t > b) : (t >= a && t <= b)) continue;
                    double nt = keep ? t - a : (t > b ? t - (b - a) : t);
                    String label = old.optJSONObject(i).optString("label");
                    ms.add(new Wav.Marker((long) (nt * in.rate), label));
                    out.put(new JSONObject().put("t", nt).put("label", label));
                }
                w.close(ms);
                JSONObject nj = new JSONObject(j.toString());
                nj.remove("sent");
                nj.put("markers", out).put("frames", w.frames);
                Engine.writeJson(to, nj);
                open = to;
            } catch (Exception e) {
                msg = "剪輯失敗：" + e.getMessage();
            }
            final String m = msg;
            main.post(() -> { toast(m); refresh(); });
        }).start();
    }

    /** 複製一份到手機的音樂資料夾：檔案管理員、剪輯 App 都看得到。 */
    private void export(File f) {
        toast("複製中…");
        new Thread(() -> {
            String msg = "已存到「音樂／錄音台」";
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Music/錄音台");
                Uri u = c.getContentResolver().insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                try (java.io.OutputStream o = c.getContentResolver().openOutputStream(u); java.io.InputStream in = new java.io.FileInputStream(f)) {
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
            } catch (Exception e) {
                msg = "複製失敗：" + e.getMessage();
            }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }

    private TextView act(String s, View.OnClickListener l) {
        TextView t = pill(c, s, false);
        t.setTextSize(13);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setOnClickListener(l);
        return t;
    }

    private void toast(String s) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_LONG).show(); }

    @Override public void tick() {
        String s = "";
        if (!link.upName.isEmpty()) s = "正在傳到電腦：" + link.upName + "　" + Math.round(link.upProg * 100) + "%" + (link.upError.isEmpty() ? "" : "\n" + link.upError);
        if (!s.equals(lastStatus)) {
            boolean done = !lastStatus.isEmpty() && s.isEmpty();
            lastStatus = s;
            status.setText(s);
            status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            if (done) refresh();
        }
        if (ov != null && player.info != null) {
            if (player.playing) { ov.pos = player.position() / Math.max(0.001, player.seconds()); ov.invalidate(); }
            if (playBtn != null) playBtn.setText(player.playing ? "暫停" : "播放");
            if (posText != null) posText.setText(time(player.position()) + " / " + time(player.seconds()));
        }
    }
}
