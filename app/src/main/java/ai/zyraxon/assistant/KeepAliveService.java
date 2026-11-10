package ai.zyraxon.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the assistant firmly alive: a low-priority foreground service plus
 * notification channel so the mic and SSE stream survive screen-off and
 * Android's background trimming.
 */
public class KeepAliveService extends Service {

    private static final String CHAN = "zyraxon";
    private static final int NOTE_ID = 9001;
    private PowerManager.WakeLock lock;

    static void start(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            // startService(), not startForegroundService(). The service is only
            // ever started from MainActivity, which is in the foreground, where a
            // plain background service is legal. startForegroundService() carries
            // a hard deadline — the service must reach startForeground() within
            // ~5s or the system kills the whole app — and on a cold start the main
            // thread is still busy with the first Compose frame, so that deadline
            // was missed every launch ("খুলেই বন্ধ হয়ে যায়"). The service still
            // promotes itself to foreground in onStartCommand, which is allowed
            // and has no deadline; only the promotion can fail, and it fails
            // safely.
            c.startService(i);
        } catch (Exception ignored) {}
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Exception ignored) {}
    }

    static void notify(Context c, String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHAN, "ZYRAXON", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n = new Notification.Builder(c)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(false)
                    .setContentIntent(pi)
                    .build();
            if (Build.VERSION.SDK_INT >= 26) n = new Notification.Builder(c, CHAN)
                    .setContentTitle(title).setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi).build();
            nm.notify(NOTE_ID, n);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The system gives a service started with startForegroundService() only a
        // few seconds to call startForeground() before it kills the *whole app*
        // ("did not then call Service.startForeground()"). On a cold start the
        // main thread is still busy loading the first Compose frame, so this call
        // is made first and unconditionally — everything else waits. If it fails,
        // the service stops itself rather than leaving the system waiting and
        // taking the app down with it.
        if (!promoteToForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zyraxon:alive");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignored) {}
        return START_STICKY;
    }

    /** Promotes to foreground, returning false when the platform refused. */
    private boolean promoteToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CHAN, "ZYRAXON", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(this, CHAN)
                        .setContentTitle("জাইরাক্সন প্রস্তুত")
                        .setContentText("সবসময় তোমার কথা শোনার জন্য অনলাইন আছি")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .build();
                startForeground(NOTE_ID, n);
            } else {
                Notification n = new Notification.Builder(this)
                        .setContentTitle("জাইরাক্সন প্রস্তুত")
                        .setContentText("সবসময় তোমার কথা শোনার জন্য অনলাইন আছি")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .build();
                startForeground(NOTE_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
