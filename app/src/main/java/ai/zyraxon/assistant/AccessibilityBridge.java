package ai.zyraxon.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Real phone control. When the user enables ZYRAXON in Accessibility settings,
 * this service can open apps, tap, type, scroll, read the screen and take
 * screenshots — the tools the assistant calls by name.
 */
public class AccessibilityBridge extends AccessibilityService {

    private static AccessibilityBridge self;

    /** The app the user is looking at right now, and a readable label. */
    private static String fgPkg = "";
    private static String fgLabel = "";
    private static long fgSince = 0;

    /** Background screen watch: a ticker that pushes frames to the web layer. */
    private static Handler watchHandler;
    private static Runnable watchTick;
    private static int watchMs = 2500;
    private static long lastFrameHash = 0;
    private static boolean watching = false;
    /** One capture in flight at a time — overlapping grabs stutter the phone. */
    private static boolean capturing = false;

    static boolean isOn() { return self != null; }

    static String foregroundPackage() { return fgPkg; }
    static String foregroundLabel() { return fgLabel; }
    static long foregroundSince() { return fgSince; }

    @Override
    public void onServiceConnected() {
        self = this;
        MainActivity.emit("native", "accessibility", "granted");
    }

    /**
     * Every window change tells us where the user is. This is what lets the
     * assistant always know which app is on screen without being asked, and
     * without any tool call.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        try {
            if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                CharSequence p = e.getPackageName();
                if (p == null) return;
                String pkg = p.toString();
                if (pkg.equals(fgPkg)) return;
                fgPkg = pkg;
                fgLabel = labelOf(pkg);
                fgSince = System.currentTimeMillis();
                MainActivity.emit("screen", "changed", pkg + "\u0001" + fgLabel);
                // A new screen is the moment worth capturing, even if the
                // ticker is off — the user asked to always know where they are.
                if (!watching && Build.VERSION.SDK_INT >= 30) screenShotBg(true);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        stopWatch();
        self = null;
        super.onDestroy();
    }

    /** Human-readable app name for a package, falling back to the package. */
    private static String labelOf(String pkg) {
        try {
            PackageManager pm = self != null ? self.getPackageManager() : MainActivity.instance.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    /**
     * Start pushing a screenshot to the assistant roughly every `ms`, in the
     * background. Nothing is written to the gallery and nothing is shown on
     * screen: the frame goes straight to the model as context. Identical
     * frames are dropped so an idle phone does not spam the model.
     */
    static void startWatch(int ms) {
        if (self == null || Build.VERSION.SDK_INT < 30) return;
        watchMs = Math.max(500, ms);
        if (watching) return;
        watching = true;
        if (watchHandler == null) watchHandler = new Handler(Looper.getMainLooper());
        watchTick = new Runnable() {
            @Override public void run() {
                if (!watching) return;
                screenShotBg(false);
                watchHandler.postDelayed(this, watchMs);
            }
        };
        watchHandler.postDelayed(watchTick, watchMs);
    }

    static void stopWatch() {
        watching = false;
        if (watchHandler != null && watchTick != null) watchHandler.removeCallbacks(watchTick);
        watchTick = null;
    }

    static boolean isWatching() { return watching; }

    /**
     * One background frame. `force` sends it even when it looks the same as the
     * last one (used right after the user switches to a new screen).
     */
    static void screenShotBg(final boolean force) {
        if (self == null || Build.VERSION.SDK_INT < 30) {
            // Below API 30 there is no screenshot API. The window text is the
            // honest fallback and still tells the assistant where the user is.
            if (force) MainActivity.emit("screen", "text", windowText());
            return;
        }
        // Nothing to learn from our own screen: the user is looking at the chat.
        // Capturing it every tick was a full-resolution grab, a copy, a hash and
        // a JPEG encode per interval — pure battery burn on a phone for a frame
        // that only ever showed this app. A real screen change still forces one.
        if (!force && "ai.zyraxon.assistant".equals(fgPkg)) return;
        // One capture at a time. The ticker and a window change can collide, and
        // two concurrent full-screen grabs are what made the phone stutter.
        if (capturing) return;
        capturing = true;
        try {
            self.takeScreenshot(Display.DEFAULT_DISPLAY, self.getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    try {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        Bitmap soft = bmp != null ? bmp.copy(Bitmap.Config.ARGB_8888, false) : null;
                        result.getHardwareBuffer().close();
                        if (soft == null) return;
                        long hash = sampleHash(soft);
                        if (!force && hash == lastFrameHash) return;
                        lastFrameHash = hash;
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        // A smaller frame is plenty to recognise a screen, and it
                        // keeps the per-second stream light on the model.
                        Bitmap small = Bitmap.createScaledBitmap(soft,
                                Math.max(1, soft.getWidth() / 3), Math.max(1, soft.getHeight() / 3), true);
                        soft.recycle();
                        small.compress(Bitmap.CompressFormat.JPEG, 45, out);
                        small.recycle();
                        String b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
                        MainActivity.emit("shot", "bg", b64);
                    } catch (Exception ignored) {
                    } finally { capturing = false; }
                }
                @Override public void onFailure(int errorCode) { capturing = false; }
            });
        } catch (Exception e) { capturing = false; }
    }

    /**
     * A cheap fingerprint of a frame, used only to skip identical screens.
     * Reads the pixels in one bulk call: getPixel() in a loop is a virtual call
     * per pixel and was a measurable part of the per-tick cost.
     */
    private static long sampleHash(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight();
        if (w < 2 || hh < 2) return 0;
        int step = Math.max(1, Math.min(w, hh) / 24);
        int cols = Math.max(1, w / step), rows = Math.max(1, hh / step);
        int[] row = new int[cols];
        long h = 17;
        for (int y = 0; y < hh; y += step) {
            b.getPixels(row, 0, cols, 0, y, cols, 1);
            for (int i = 0; i < cols; i++) h = h * 31 + row[i];
        }
        return h;
    }

    /** The visible text of the current window — the honest pre-API-30 view. */
    static String windowText() {
        if (self == null) return "";
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        collectText(root, sb, 0);
        return sb.toString();
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 25 || sb.length() > 4000) return;
        try {
            if (n.getText() != null) sb.append(n.getText()).append('\n');
            else if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append('\n');
            for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
        } catch (Exception ignored) {}
    }

    /* ------------------------------ tools ------------------------------ */

    static boolean tap(float x, float y) {
        if (self == null || Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        return self.dispatchGesture(gd, null, null);
    }

    /** A real drag gesture — scrolling, going back, dismissing sheets. */
    static boolean swipe(float x1, float y1, float x2, float y2) {
        if (self == null || Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 260);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        return self.dispatchGesture(gd, null, null);
    }

    static boolean tapText(String text) {
        if (self == null) return false;
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo n : nodes) {
            AccessibilityNodeInfo clickable = n;
            while (clickable != null) {
                if (clickable.isClickable()) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
                Rect r = new Rect();
                clickable.getBoundsInScreen(r);
                if (r.width() > 0) return tap(r.centerX(), r.centerY());
                clickable = clickable.getParent();
            }
        }
        return false;
    }

    static boolean typeText(String text) {
        if (self == null) return false;
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) focus = root;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    static boolean scroll(boolean forward) {
        if (self == null) return false;
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        if (root == null) return false;
        return root.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    static boolean globalAction(String action) {
        if (self == null) return false;
        String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
        int code;
        switch (a) {
            case "back": code = GLOBAL_ACTION_BACK; break;
            case "home": code = GLOBAL_ACTION_HOME; break;
            case "recents":
            case "recent": code = GLOBAL_ACTION_RECENTS; break;
            case "notifications": code = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": code = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": code = GLOBAL_ACTION_LOCK_SCREEN; break;
            default: return false;
        }
        return self.performGlobalAction(code);
    }

    /** Flatten the visible screen into readable text lines with positions. */
    static String readScreen() {
        if (self == null) return "[\"accessibility service বন্ধ\"]";
        JSONArray arr = new JSONArray();
        try {
            AccessibilityNodeInfo root = self.getRootInActiveWindow();
            collect(root, arr, 0);
        } catch (Exception ignored) {}
        return arr.toString();
    }

    private static void collect(AccessibilityNodeInfo n, JSONArray arr, int depth) {
        if (n == null || depth > 24 || arr.length() > 400) return;
        try {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            CharSequence label = (t != null && t.length() > 0) ? t : d;
            if (label != null && label.length() > 0) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                JSONObject o = new JSONObject();
                o.put("text", label.toString());
                o.put("x", r.centerX());
                o.put("y", r.centerY());
                if (n.isClickable()) o.put("click", true);
                if (n.isEditable()) o.put("editable", true);
                arr.put(o);
            }
            for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), arr, depth + 1);
        } catch (Exception ignored) {}
    }

    /** API 30+ real screenshot, pushed back into the web layer as base64. */
    static void screenShot() {
        screenShot(0);
    }

    /** Holds the newest frame so a caller can wait for one instead of guessing. */
    private static final java.util.concurrent.atomic.AtomicReference<String> lastShot =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * Takes a screenshot and returns it, blocking the caller until the frame is
     * ready. A tool that wants to look at the screen needs the pixels in the same
     * turn, and the async push alone only delivered them on the next one.
     */
    static String screenShotBlocking(final long timeoutMs) {
        lastShot.set(null);
        if (self == null || Build.VERSION.SDK_INT < 30) return "";
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                self.takeScreenshot(Display.DEFAULT_DISPLAY, self.getMainExecutor(),
                        new TakeScreenshotCallback() {
                            @Override public void onSuccess(ScreenshotResult result) {
                                try {
                                    Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                                    Bitmap soft = bmp != null ? bmp.copy(Bitmap.Config.ARGB_8888, false) : null;
                                    result.getHardwareBuffer().close();
                                    if (soft == null) return;
                                    // Scale down: a full-resolution PNG as base64 is
                                    // megabytes and would stall the request.
                                    int w = Math.max(1, soft.getWidth() / 2);
                                    int h = Math.max(1, soft.getHeight() / 2);
                                    Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
                                    soft.recycle();
                                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                                    small.compress(Bitmap.CompressFormat.JPEG, 62, out);
                                    small.recycle();
                                    lastShot.set(android.util.Base64.encodeToString(
                                            out.toByteArray(), android.util.Base64.NO_WRAP));
                                } catch (Exception ignored) {
                                } finally {
                                    gate.countDown();
                                }
                            }
                            @Override public void onFailure(int errorCode) {
                                gate.countDown();
                            }
                        });
            } catch (Exception e) {
                gate.countDown();
            }
        });
        try {
            gate.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return lastShot.get() == null ? "" : lastShot.get();
    }

    /**
     * Take a real screenshot. `attempt` is used internally: a display that was
     * just switched to another app can return a frame that is not painted yet
     * (an all-white or all-black bitmap), which is exactly the "white screenshot"
     * the user hit. A blank frame is retried a few times with a short delay
     * instead of being handed back as a successful capture.
     */
    static void screenShot(final int attempt) {
        if (self == null) {
            MainActivity.emit("shot", "error", "accessibility-off");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            MainActivity.emit("shot", "error", "sdk<30");
            return;
        }
        self.takeScreenshot(Display.DEFAULT_DISPLAY,
                self.getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                            Bitmap soft = bmp != null ? bmp.copy(Bitmap.Config.ARGB_8888, false) : null;
                            result.getHardwareBuffer().close();
                            if (soft == null) { MainActivity.emit("shot", "error", "null"); return; }
                            if (isBlank(soft) && attempt < 4) {
                                // The window has not painted yet — wait and retry.
                                new Handler(Looper.getMainLooper()).postDelayed(
                                        () -> screenShot(attempt + 1), 220 + attempt * 180);
                                return;
                            }
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            soft.compress(Bitmap.CompressFormat.JPEG, 70, out);
                            String b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
                            MainActivity.emit("shot", "ok", b64);
                        } catch (Exception e) { MainActivity.emit("shot", "error", String.valueOf(e)); }
                    }
                    @Override public void onFailure(int errorCode) {
                        // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT is transient;
                        // retrying is the right answer, not reporting a failure.
                        if (attempt < 3) {
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    () -> screenShot(attempt + 1), 320 + attempt * 200);
                            return;
                        }
                        MainActivity.emit("shot", "error", "code:" + errorCode);
                    }
                });
    }

    /**
     * A frame with no usable content: every sampled pixel is the same colour.
     * Sample on a grid so the check is cheap even for a 1440p screen.
     */
    private static boolean isBlank(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w < 2 || h < 2) return true;
            int first = b.getPixel(w / 4, h / 4);
            int step = Math.max(1, Math.min(w, h) / 24);
            for (int y = 2; y < h - 2; y += step) {
                for (int x = 2; x < w - 2; x += step) {
                    if (b.getPixel(x, y) != first) return false;
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    static String listApps(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                JSONObject o = new JSONObject();
                o.put("label", String.valueOf(ri.loadLabel(pm)));
                o.put("pkg", ri.activityInfo.packageName);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    /** Resolve an app by label or package and launch its main activity. */
    static void openApp(Context ctx, String pkgOrName) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String target = pkgOrName == null ? "" : pkgOrName.trim();
            Intent launch = pm.getLaunchIntentForPackage(target);
            if (launch == null) {
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                    if (label.equals(target.toLowerCase(Locale.ROOT))
                            || label.contains(target.toLowerCase(Locale.ROOT))) {
                        launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                        break;
                    }
                }
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                MainActivity.emit("native", "appOpened", target);
            } else {
                MainActivity.emit("native", "appMissing", target);
            }
        } catch (Exception e) {
            MainActivity.emit("native", "appError", String.valueOf(e));
        }
    }

    /**
     * Does this app really exist on this phone? openApp() used to fire an
     * intent and report success even when nothing matched, so the assistant
     * told the user an app was open when it was not. The tool layer calls this
     * first and only claims success for a package that resolves.
     *
     * Returns JSON: { found, package, label }.
     */
    static String resolveApp(Context ctx, String pkgOrName) {
        JSONObject o = new JSONObject();
        try {
            PackageManager pm = ctx.getPackageManager();
            String target = pkgOrName == null ? "" : pkgOrName.trim();
            String pkg = null;
            if (pm.getLaunchIntentForPackage(target) != null) {
                pkg = target;
            } else {
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                String want = target.toLowerCase(Locale.ROOT);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                    if (label.equals(want) || label.contains(want)) {
                        pkg = ri.activityInfo.packageName;
                        break;
                    }
                }
            }
            o.put("found", pkg != null);
            o.put("package", pkg == null ? "" : pkg);
            if (pkg != null) {
                try {
                    o.put("label", String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
                } catch (Exception e) { o.put("label", pkg); }
            }
        } catch (Exception e) {
            // org.json's put() declares a checked JSONException, so the recovery
            // write needs its own guard — otherwise this file does not compile.
            try { o.put("found", false); } catch (JSONException ignored) {}
        }
        return o.toString();
    }
}
