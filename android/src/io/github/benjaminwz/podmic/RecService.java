package io.github.benjaminwz.podmic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** 錄音時的前景服務：螢幕關掉、切到別的 App 都繼續錄。 */
public class RecService extends Service {
    private PowerManager.WakeLock wake;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Engine e = Engine.get(this);
        if (intent != null && "stop".equals(intent.getAction())) {
            e.stopRec();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rec", "錄音中", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "rec")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("錄音中")
                .setContentText(e.recFile != null ? e.recFile.getName() : "")
                .setOngoing(true)
                .setUsesChronometer(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(1, n);
        } catch (Exception ex) {
            // 極少數情況（App 不在前景時被遙控開始）系統不給前景服務：錄音照樣進行，只是可能被系統回收
        }
        if (wake == null) {
            wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podmic:rec");
            wake.acquire(12 * 60 * 60 * 1000L);
        }
        if (e.rec == Engine.IDLE) stopSelf();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }
}
