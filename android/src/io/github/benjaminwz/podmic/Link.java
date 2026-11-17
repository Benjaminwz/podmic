package io.github.benjaminwz.podmic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 跟電腦上的「錄音台」連線：同一個 Wi-Fi（或 USB 網路共用）自動找到 → 遙控、音量、提詞同步、錄完自動傳檔。 */
final class Link {
    private static Link I;

    static synchronized Link get(Context c) {
        if (I == null) I = new Link(c.getApplicationContext());
        return I;
    }

    static final int PORT = 47870, FIND_PORT = 47871;
    static final int OFF = 0, SEARCH = 1, WAIT = 2, ON = 3;

    private final Context app;
    private final SharedPreferences prefs;
    private final Engine eng;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String id;
    private Thread thread, upThread;
    private volatile boolean run;
    volatile int state = OFF;
    volatile String pcName = "", host = "";
    volatile String upName = "";
    volatile float upProg;
    volatile String upError = "";
    private long pcRev = -1;
    private int fails;
    private final ConcurrentLinkedQueue<File> ups = new ConcurrentLinkedQueue<>();
    Runnable onChange;

    private Link(Context c) {
        app = c;
        prefs = c.getSharedPreferences("podmic", Context.MODE_PRIVATE);
        eng = Engine.get(c);
        String i = prefs.getString("id", "");
        if (i.isEmpty()) {
            i = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString("id", i).apply();
        }
        id = i;
    }

    synchronized void start() {
        if (thread != null || !prefs.getBoolean("link", true)) return;
        run = true;
        thread = new Thread(this::loop, "podmic-link");
        thread.start();
        upThread = new Thread(this::upLoop, "podmic-upload");
        upThread.start();
    }

    synchronized void stop() {
        run = false;
        if (thread != null) thread.interrupt();
        if (upThread != null) upThread.interrupt();
        thread = null;
        upThread = null;
        set(OFF);
    }

    void reset() {
        host = "";
        fails = 0;
        set(SEARCH);
    }

    private void set(int s) {
        if (state == s) return;
        state = s;
        Runnable c = onChange;
        if (c != null) main.post(c);
    }

    /** force = 使用者自己按「傳到電腦」；否則照「錄完自動傳」的設定。 */
    void queue(File f, boolean force) {
        if (!force && !prefs.getBoolean("autoup", true)) return;
        if (!ups.contains(f)) ups.add(f);
    }

    private void loop() {
        host = prefs.getString("host", "");
        while (run) {
            try {
                if (host.isEmpty()) {
                    set(SEARCH);
                    String manual = prefs.getString("manual", "").trim();
                    host = manual.isEmpty() ? discover() : manual;
                    if (host.isEmpty()) { Thread.sleep(2500); continue; }
                }
                if (state != ON) {
                    JSONObject r = post("/hello", new JSONObject().put("id", id).put("name", Build.MODEL).toString().getBytes(StandardCharsets.UTF_8), 2500);
                    if (r.optBoolean("ok")) {
                        pcName = r.optString("name");
                        pcRev = -1;
                        fails = 0;
                        prefs.edit().putString("host", host).apply();
                        set(ON);
                    } else {
                        pcName = r.optString("name");
                        set(WAIT);
                        Thread.sleep(1500);
                    }
                    continue;
                }
                sync();
                fails = 0;
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (++fails >= 3) {
                    fails = 0;
                    host = "";   // 電腦換了位址或關掉了 → 重新找
                    set(SEARCH);
                }
                try { Thread.sleep(800); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private String discover() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(900);
            byte[] q = "PODMIC?".getBytes(StandardCharsets.US_ASCII);
            try { s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), FIND_PORT)); } catch (Exception ignored) {}
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() == null) continue;
                    try { s.send(new DatagramPacket(q, q.length, ia.getBroadcast(), FIND_PORT)); } catch (Exception ignored) {}
                }
            }
            byte[] b = new byte[512];
            DatagramPacket p = new DatagramPacket(b, b.length);
            s.receive(p);
            JSONObject j = new JSONObject(new String(b, 0, p.getLength(), StandardCharsets.UTF_8));
            if (j.optInt("podmic") == 1) return p.getAddress().getHostAddress();
        } catch (Exception ignored) {}
        return "";
    }

    private JSONObject post(String path, byte[] body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + path).openConnection();
        try {
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 6000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("X-Id", id);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            if (c.getResponseCode() == 403) { set(WAIT); throw new Exception("403"); }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    volatile int newApk;   // 電腦上那份安裝檔的版本號

    /** 從電腦下載新版安裝檔。 */
    File downloadApk(File dir) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + "/apk").openConnection();
        File f = new File(dir, "update.apk");
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-Id", id);
            try (InputStream in = c.getInputStream(); OutputStream o = new java.io.FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
        } finally {
            c.disconnect();
        }
        return f;
    }

    private static double db(float v) { return Math.round(Dsp.db(v) * 10) / 10.0; }

    private void sync() throws Exception {
        JSONObject j = new JSONObject();
        float p0, p1, r0, r1;
        synchronized (eng.peak) {
            p0 = eng.peak2[0]; p1 = eng.peak2[1]; r0 = eng.rms[0]; r1 = eng.rms[1];
            eng.peak2[0] = eng.peak2[1] = 0;
        }
        File f = eng.recFile;
        j.put("rec", eng.rec).put("t", eng.recSeconds()).put("peak", new JSONArray().put(db(p0)).put(db(p1)))
                .put("rms", new JSONArray().put(db(r0)).put(db(r1)))
                .put("clip", System.currentTimeMillis() - eng.clipAt < 1500).put("dev", eng.devName).put("usb", eng.usb)
                .put("dji", eng.dji).put("ok", eng.ok).put("mode", eng.chanMode).put("ch", eng.wantChannels())
                .put("flt", prefs.getBoolean("float", false)).put("file", eng.rec != Engine.IDLE && f != null ? f.getName() : "")
                .put("free", eng.freeSeconds()).put("marks", eng.markers.size()).put("lost", eng.lostMic).put("err", eng.error)
                .put("names", new JSONArray().put(prefs.getString("name1", "主持人")).put(prefs.getString("name2", "來賓")))
                .put("up", new JSONObject().put("name", upName).put("prog", upProg).put("left", ups.size()));
        synchronized (Prompt.class) {
            j.put("p", new JSONObject().put("seq", Prompt.seq).put("pos", Prompt.pos).put("run", Prompt.run).put("speed", Prompt.speed));
            j.put("rev", Prompt.rev);
            if (pcRev >= 0 && Prompt.rev > pcRev)
                j.put("script", new JSONObject().put("rev", Prompt.rev).put("title", Prompt.title).put("text", Prompt.text));
        }
        JSONObject r = post("/sync", j.toString().getBytes(StandardCharsets.UTF_8), 2500);
        pcRev = r.optLong("rev", 0);
        newApk = r.optInt("apk", 0);
        JSONObject s = r.optJSONObject("script");
        if (s != null && s.optLong("rev") > Prompt.rev) {
            Prompt.setScript(s.optString("title"), s.optString("text"), s.optLong("rev"));
            Prompt.save(prefs);
        }
        JSONObject p = r.optJSONObject("p");
        if (p != null) Prompt.adopt(p.optLong("seq"), p.optDouble("pos"), p.optBoolean("run"), (float) p.optDouble("speed", 4));
        JSONArray cmds = r.optJSONArray("cmds");
        if (cmds != null) for (int i = 0; i < cmds.length(); i++) {
            final JSONObject c = cmds.getJSONObject(i);
            main.post(() -> exec(c));
        }
    }

    private void exec(JSONObject c) {
        String k = c.optString("c");
        if (k.equals("start")) {
            if (c.has("title")) prefs.edit().putString("title", c.optString("title")).apply();
            eng.acquire();
            // 麥克風剛打開要一點時間
            main.postDelayed(() -> { eng.startRec(prefs.getString("title", "")); eng.release(); }, eng.ok ? 0 : 600);
        } else if (k.equals("stop")) eng.stopRec();
        else if (k.equals("pause")) eng.pauseRec();
        else if (k.equals("mark")) eng.mark(c.optString("label"));
        else if (k.equals("names")) {
            prefs.edit().putString("name1", c.optString("a", "主持人")).putString("name2", c.optString("b", "來賓")).apply();
            if (eng.onChange != null) eng.onChange.run();
        }
    }

    private void upLoop() {
        while (run) {
            try {
                File f = state == ON ? ups.peek() : null;
                if (f == null) { Thread.sleep(1000); continue; }
                if (f.equals(eng.recFile) && eng.rec != Engine.IDLE) { Thread.sleep(1000); continue; }
                if (!f.exists()) { ups.poll(); continue; }
                upName = f.getName();
                upProg = 0;
                upError = "";
                File js = Engine.jsonOf(f);
                if (js.exists()) send(js, false);
                send(f, true);
                JSONObject j = Engine.readJson(f);
                j.put("sent", true);
                Engine.writeJson(f, j);
                ups.poll();
                upName = "";
                upProg = 0;
                Runnable c = onChange;
                if (c != null) main.post(c);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                upError = "傳檔中斷，稍後自動再試";
                try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void send(File f, boolean progress) throws Exception {
        long total = f.length(), off = 0;
        byte[] buf = new byte[4 << 20];
        String name = URLEncoder.encode(f.getName(), "UTF-8");
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            do {
                int n = (int) Math.min(buf.length, total - off);
                in.seek(off);
                in.readFully(buf, 0, n);
                byte[] body = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                JSONObject r = post("/upload?name=" + name + "&off=" + off + "&total=" + total, body, 8000);
                if (!r.optBoolean("ok")) throw new Exception("upload");
                off += n;
                if (progress) upProg = total > 0 ? (float) off / total : 1;
            } while (off < total);
        }
    }
}
