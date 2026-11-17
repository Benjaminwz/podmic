package io.github.benjaminwz.podmic;

import android.content.SharedPreferences;

/** 提詞稿和捲動狀態。手機和電腦各有一份，誰最後動過（seq 比較大）就聽誰的。 */
final class Prompt {
    private Prompt() {}

    static String title = "", text = "";
    static long rev;            // 稿子最後修改的時間（毫秒）；比較新的蓋掉舊的
    static double pos;          // 讀到第幾個字
    static boolean run;
    static float speed = 4f;    // 每秒幾個字（4 = 每分鐘 240 字）
    static long seq;
    static float font = 30f;
    static boolean mirror;
    static long changed;        // 畫面用：稿子被換掉時 +1
    private static long lastTick;
    static boolean follow;      // 跟著聲音：有人講話才捲，停下來它也停
    static boolean held;        // 因為沒聲音而暫時停住
    private static long voiceAt;

    /** 每一格畫面呼叫一次；level = 現在的音量（線性）。 */
    static synchronized void gate(float level) {
        if (!follow) { held = false; return; }
        long now = System.currentTimeMillis();
        if (level > 0.012f) voiceAt = now;
        boolean voice = now - voiceAt < 700;
        if (run && !voice) { run = false; held = true; seq++; }
        else if (held && voice && pos < text.length()) { run = true; held = false; seq++; lastTick = 0; }
    }

    static void load(SharedPreferences p) {
        title = p.getString("p_title", "");
        text = p.getString("p_text", "");
        rev = p.getLong("p_rev", 0);
        speed = p.getFloat("p_speed", 4f);
        font = p.getFloat("p_font", 30f);
        mirror = p.getBoolean("p_mirror", false);
        follow = p.getBoolean("p_follow", false);
    }

    static void save(SharedPreferences p) {
        p.edit().putString("p_title", title).putString("p_text", text).putLong("p_rev", rev)
                .putFloat("p_speed", speed).putFloat("p_font", font).putBoolean("p_mirror", mirror).putBoolean("p_follow", follow).apply();
    }

    static synchronized void setScript(String t, String x, long r) {
        title = t; text = x; rev = r;
        pos = Math.min(pos, text.length());
        changed++;
    }

    /** 這一邊的使用者動了捲動（開始、暫停、拖動、改速度）。 */
    static synchronized void touch() { seq++; held = false; }

    static synchronized void adopt(long s, double p, boolean r, float sp) {
        if (s <= seq) return;
        seq = s; pos = p; run = r; speed = sp;
        lastTick = 0;
    }

    static synchronized void tick() {
        long now = System.nanoTime();
        if (run && lastTick != 0) {
            pos += (now - lastTick) / 1e9 * speed;
            if (pos >= text.length()) { pos = text.length(); run = false; }
        }
        lastTick = now;
    }
}
