package ai.zyraxon.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

/**
 * Public doorway to the device capabilities.
 *
 * {@link AccessibilityBridge} and {@link CallWatcher} are package-private on
 * purpose, so the tool layer — which lives in {@code ai.zyraxon.assistant.ai} —
 * cannot reach them directly. This class is the single, explicit surface it is
 * allowed to use.
 */
public final class DeviceBridge {

    private DeviceBridge() {}

    public static boolean accessibilityOn() { return AccessibilityBridge.isOn(); }

    public static String foregroundLabel() { return AccessibilityBridge.foregroundLabel(); }

    public static String foregroundPackage() { return AccessibilityBridge.foregroundPackage(); }

    public static String readScreen() { return AccessibilityBridge.readScreen(); }

    public static void screenShot() { AccessibilityBridge.screenShot(); }

    /** Returns the current screen as base64 JPEG, or "" when it cannot be read. */
    public static String screenShotBase64(long timeoutMs) {
        return AccessibilityBridge.screenShotBlocking(timeoutMs);
    }

    public static boolean tap(float x, float y) { return AccessibilityBridge.tap(x, y); }

    public static boolean tapText(String text) { return AccessibilityBridge.tapText(text); }

    public static boolean swipe(float x1, float y1, float x2, float y2) {
        return AccessibilityBridge.swipe(x1, y1, x2, y2);
    }

    public static boolean typeText(String text) { return AccessibilityBridge.typeText(text); }

    public static boolean pressKey(String key) { return AccessibilityBridge.globalAction(key); }

    public static String listApps(Context ctx) { return AccessibilityBridge.listApps(ctx); }

    /** Returns the package name of an installed app, or "" when there is none. */
    public static String resolvePackage(Context ctx, String pkgOrName) {
        try {
            JSONObject o = new JSONObject(AccessibilityBridge.resolveApp(ctx, pkgOrName));
            return o.optBoolean("found") ? o.optString("package") : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static void openApp(Context ctx, String pkgOrName) {
        AccessibilityBridge.openApp(ctx, pkgOrName);
    }

    public static String callState() {
        if (CallWatcher.inCall) return "active";
        if (CallWatcher.ringing) return "ringing";
        return "idle";
    }

    public static String callNumber() { return CallWatcher.number; }

    public static boolean answerCall(Context ctx) { return CallWatcher.answer(ctx); }

    public static boolean hangUpCall(Context ctx) { return CallWatcher.hangUp(ctx); }

    public static boolean dial(Context ctx, String number) { return CallWatcher.dial(ctx, number); }

    /** Post a real notification, so the user sees the assistant act. */
    public static void notify(Context ctx, String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channel = "zyraxon_ai";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channel, "ZYRAXON", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, channel)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title == null || title.isEmpty() ? "ZYRAXON" : title)
                    .setContentText(body == null ? "" : body)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Exception ignored) {}
    }
}
