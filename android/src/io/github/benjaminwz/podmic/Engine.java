package io.github.benjaminwz.podmic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** 收音核心：一條執行緒一直讀麥克風 → 音量、波形、測量用的緩衝；錄音時同時寫檔。 */
final class Engine {
    private static Engine I;

    static synchronized Engine get(Context c) {
        if (I == null) I = new Engine(c.getApplicationContext());
        return I;
    }

    static final int RATE = 48000, IDLE = 0, REC = 1, PAUSE = 2;
    static final long SPLIT_BYTES = 3_900_000_000L;

    final Context app;
    final SharedPreferences prefs;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Thread thread;
    private int users;
    private volatile boolean run, reopen;

    // 輸入裝置
    volatile String devName = "";
    volatile boolean usb, dji, ok;
    volatile String error = "";
    volatile int chanMode;

    // 音量（線性）。peak 由畫面讀走後歸零
    final float[] peak = new float[2], rms = new float[2];
    final float[] peak2 = new float[2];   // 給電腦連線用的另一份
    volatile long clipAt;
    private double sLL, sRR, sLR;

    // 測量用：最近約 21 秒的單聲道（第 1 軌）
    final float[] ring = new float[1 << 20];
    volatile long ringPos;
    // 波形條：每 25 ms 一格的峰值
    final float[] wave = new float[4096];
    volatile int wavePos;

    // 錄音
    volatile int rec = IDLE;
    volatile long recFrames, recStart;
    volatile File recFile;
    volatile boolean lostMic;
    private Wav.Writer writer;
    private int[] pick = {0, 1};
    private int part;
    private String baseName = "";
    final ArrayList<Wav.Marker> markers = new ArrayList<>();
    Runnable onChange;      // 狀態變了（畫面更新用）
    Runnable onFinished;    // 一個檔案寫完了

    private Engine(Context c) {
        app = c;
        prefs = c.getSharedPreferences("podmic", Context.MODE_PRIVATE);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { changed(d, "麥克風接上"); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { changed(d, "麥克風中斷"); }
        }, main);
    }

    private void changed(AudioDeviceInfo[] ds, String what) {
        boolean in = false;
        for (AudioDeviceInfo d : ds) if (d.isSource() && isUsb(d)) in = true;
        if (!in || !run) return;
        if (rec != IDLE) {
            mark(what);
            if (what.equals("麥克風中斷")) lostMic = true;
        }
        reopen = true;
    }

    static boolean isUsb(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    File dir() {
        File d = new File(app.getExternalFilesDir(null), "recordings");
        d.mkdirs();
        return d;
    }

    long freeBytes() {
        try { return new StatFs(dir().getPath()).getAvailableBytes(); } catch (Exception e) { return 0; }
    }

    /** 還能錄幾秒（用目前的格式算）。 */
    long freeSeconds() {
        int ch = wantChannels();
        return freeBytes() / ((long) RATE * ch * (prefs.getBoolean("float", false) ? 4 : 3));
    }

    int wantChannels() {
        String c = prefs.getString("chan", "auto");
        if (c.equals("mono")) return 1;
        if (c.equals("stereo")) return 2;
        return (chanMode == Dsp.M_SINGLE || chanMode == Dsp.M_DUAL || chanMode == Dsp.M_NONE) ? 1 : 2;
    }

    // ---------- 開關 ----------

    synchronized void acquire() {
        users++;
        if (thread == null) {
            run = true;
            thread = new Thread(this::loop, "podmic-audio");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }
    }

    synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0 && rec == IDLE) stopThread();
    }

    private void stopThread() {
        run = false;
        Thread t = thread;
        thread = null;
        if (t != null) try { t.join(800); } catch (InterruptedException ignored) {}
        ok = false;
    }

    void restart() { reopen = true; }

    private AudioRecord open() {
        AudioDeviceInfo pickDev = null;
        String src = prefs.getString("src", "auto");
        if (!src.equals("phone")) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) if (isUsb(d)) { pickDev = d; break; }
        }
        boolean raw = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = raw ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int s : sources) {
            try {
                AudioFormat f = new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
                AudioRecord r = new AudioRecord.Builder().setAudioSource(s).setAudioFormat(f)
                        .setBufferSizeInBytes(Math.max(min, RATE * 8 / 2)).build();   // 約 0.5 秒：畫面卡一下也不掉音
                if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); continue; }
                if (pickDev != null) r.setPreferredDevice(pickDev);
                r.startRecording();
                if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue; }
                AudioDeviceInfo d = pickDev;
                usb = d != null;
                String n = d != null && d.getProductName() != null ? d.getProductName().toString() : "";
                dji = usb && (n.toUpperCase(Locale.ROOT).contains("DJI") || n.toUpperCase(Locale.ROOT).contains("MIC"));
                devName = usb ? (n.isEmpty() ? "USB 麥克風" : n) : "手機麥克風";
                error = "";
                return r;
            } catch (SecurityException e) {
                error = "還沒允許使用麥克風";
                return null;
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
        }
        if (error.isEmpty()) error = "麥克風打不開";
        return null;
    }

    private void loop() {
        float[] buf = new float[960 * 2];
        AudioRecord r = null;
        int waveN = 0;
        float waveMax = 0;
        long modeN = 0;
        while (run) {
            if (r == null || reopen) {
                if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
                reopen = false;
                r = open();
                ok = r != null;
                ping();
                if (r == null) { try { Thread.sleep(700); } catch (InterruptedException e) { break; } continue; }
            }
            int n = r.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
            if (n <= 0) { reopen = true; try { Thread.sleep(200); } catch (InterruptedException e) { break; } continue; }
            int frames = n / 2;
            float pl = 0, pr = 0;
            double sl = 0, sr = 0, lr = 0;
            long rp = ringPos;
            for (int i = 0; i < frames; i++) {
                float a = buf[2 * i], b = buf[2 * i + 1];
                float aa = Math.abs(a), ab = Math.abs(b);
                if (aa > pl) pl = aa;
                if (ab > pr) pr = ab;
                sl += a * a; sr += b * b; lr += a * b;
                ring[(int) (rp++ & (ring.length - 1))] = a;
                float m = Math.max(aa, ab);
                if (m > waveMax) waveMax = m;
                if (++waveN >= 1200) {
                    wave[wavePos & (wave.length - 1)] = rec == REC ? waveMax : -waveMax;   // 負值 = 沒在錄（畫成灰色）
                    wavePos++;
                    waveN = 0;
                    waveMax = 0;
                }
            }
            ringPos = rp;
            synchronized (peak) {
                if (pl > peak[0]) peak[0] = pl;
                if (pr > peak[1]) peak[1] = pr;
                if (pl > peak2[0]) peak2[0] = pl;
                if (pr > peak2[1]) peak2[1] = pr;
                rms[0] = (float) Math.sqrt(sl / frames);
                rms[1] = (float) Math.sqrt(sr / frames);
            }
            if (pl >= 0.999f || pr >= 0.999f) clipAt = System.currentTimeMillis();
            // 約 2 秒的滑動統計 → 判斷接收器的輸出模式
            double k = 0.99;
            sLL = sLL * k + sl; sRR = sRR * k + sr; sLR = sLR * k + lr;
            if (++modeN % 25 == 0) {
                int m = Dsp.mode(sLL / frames / 100, sRR / frames / 100, sLR / frames / 100);
                if (m != Dsp.M_NONE && m != chanMode) { chanMode = m; ping(); }
            }
            if (rec == REC) {
                synchronized (lock) {
                    if (writer != null) {
                        try {
                            writer.write(buf, frames, 2, pick);
                            recFrames += frames;
                            if (writer.dataBytes() > SPLIT_BYTES) nextPart();
                        } catch (Exception e) {
                            error = "寫檔失敗：" + e.getMessage();
                            main.post(this::stopRec);
                        }
                    }
                }
            }
        }
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
    }

    private void ping() {
        Runnable c = onChange;
        if (c != null) main.post(c);
    }

    // ---------- 錄音 ----------

    static String clean(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
    }

    boolean startRec(String title) {
        if (rec != IDLE || !ok) return false;
        synchronized (lock) {
            try {
                String t = clean(title == null ? "" : title);
                baseName = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + (t.isEmpty() ? "" : "_" + t);
                part = 1;
                pick = wantChannels() == 1 ? new int[]{0} : new int[]{0, 1};
                markers.clear();
                recFrames = 0;
                lostMic = false;
                openWriter();
                recStart = System.currentTimeMillis();
                rec = REC;
            } catch (Exception e) {
                error = "開檔失敗：" + e.getMessage();
                return false;
            }
        }
        acquire();   // 錄音中就算畫面關掉也繼續收音
        try {
            app.startForegroundService(new Intent(app, RecService.class));
        } catch (Exception ignored) {}
        ping();
        return true;
    }

    private void openWriter() throws Exception {
        File f = new File(dir(), baseName + (part > 1 ? "_" + part : "") + ".wav");
        for (int i = 2; f.exists(); i++) f = new File(dir(), baseName + "(" + i + ")" + (part > 1 ? "_" + part : "") + ".wav");
        writer = new Wav.Writer(f, RATE, pick.length, prefs.getBoolean("float", false));
        recFile = f;
    }

    /** 快到 4 GB（WAV 上限）→ 無縫接下一個檔。 */
    private void nextPart() throws Exception {
        finishFile();
        part++;
        markers.clear();
        openWriter();
    }

    private void finishFile() throws Exception {
        Wav.Writer w = writer;
        writer = null;
        if (w == null) return;
        ArrayList<Wav.Marker> ms = new ArrayList<>(markers);
        w.close(ms);
        JSONObject j = new JSONObject();
        j.put("device", devName).put("dji", dji).put("mode", chanMode).put("rate", RATE).put("channels", w.channels)
                .put("float", w.flt).put("frames", w.frames).put("started", recStart)
                .put("names", new JSONArray().put(prefs.getString("name1", "主持人")).put(prefs.getString("name2", "來賓")));
        JSONArray a = new JSONArray();
        for (Wav.Marker m : ms) a.put(new JSONObject().put("t", m.frame / (double) RATE).put("label", m.label));
        j.put("markers", a);
        writeJson(w.file, j);
        final File done = w.file;
        main.post(() -> { Link.get(app).queue(done, false); if (onFinished != null) onFinished.run(); });
    }

    static File jsonOf(File wav) {
        String n = wav.getName();
        return new File(wav.getParentFile(), n.substring(0, n.length() - 4) + ".json");
    }

    static void writeJson(File wav, JSONObject j) {
        try (FileOutputStream o = new FileOutputStream(jsonOf(wav))) {
            o.write(j.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    static JSONObject readJson(File wav) {
        try {
            File f = jsonOf(wav);
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int o = 0, n;
                while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
            }
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    void pauseRec() {
        if (rec == REC) { mark("暫停"); rec = PAUSE; }
        else if (rec == PAUSE) rec = REC;
        ping();
    }

    void mark(String label) {
        if (rec == IDLE) return;
        synchronized (lock) {
            if (writer == null) return;
            String l = label == null || label.isEmpty() ? "標記 " + (markers.size() + 1) : label;
            markers.add(new Wav.Marker(writer.frames, l));
        }
        ping();
    }

    void stopRec() {
        if (rec == IDLE) return;
        rec = IDLE;
        synchronized (lock) {
            try { finishFile(); } catch (Exception e) { error = "存檔失敗：" + e.getMessage(); }
        }
        app.stopService(new Intent(app, RecService.class));
        release();
        ping();
    }

    double recSeconds() { return recFrames / (double) RATE; }

    /** 複製最近 n 格（單聲道）出來做測量。 */
    float[] recent(int n) {
        n = Math.min(n, ring.length);
        float[] out = new float[n];
        long end = ringPos;
        for (int i = 0; i < n; i++) out[i] = ring[(int) ((end - n + i) & (ring.length - 1))];
        return out;
    }
}
