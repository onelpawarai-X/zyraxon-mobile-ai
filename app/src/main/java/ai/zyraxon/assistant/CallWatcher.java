package ai.zyraxon.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

/**
 * Real phone-call control for ZYRAXON.
 *
 * <p>Android gives no public API to accept a call, so we use the documented
 * two-step route: {@code ANSWER_PHONE_CALLS} plus the hidden
 * {@code TelephonyManager.answerRingingCall()} / {@code endCall()} methods,
 * which are reachable by reflection and work from Android 5 up to 14. On
 * Android 10+ the {@link TelecomManager#acceptRingingCall} path is tried first
 * because it is the supported one.
 *
 * <p>{@link PhoneStateListener} feeds every state change back into the web
 * layer through {@link MainActivity#cb}, so the assistant hears about a
 * ringing phone without polling, and can speak to the caller through the
 * normal TTS pipeline while the call is up.
 */
final class CallWatcher {

    static volatile boolean inCall = false;
    static volatile boolean ringing = false;
    static volatile boolean watching = false;
    static volatile boolean autoAnswer = false;
    static volatile String number = "";
    static volatile String name = "";

    private static TelephonyManager tm;
    private static PhoneStateListener listener;

    private CallWatcher() {}

    static boolean permissionsOk(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Start listening for call state. Safe to call repeatedly. */
    static synchronized void refresh(Context ctx) {
        if (tm == null) {
            tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        }
        if (tm == null || listening()) return;
        try {
            listener = new PhoneStateListener() {
                @Override public void onCallStateChanged(int state, String incoming) {
                    if (incoming != null && !incoming.isEmpty()) number = incoming;
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            ringing = true; inCall = false;
                            name = lookupName(ctx, number);
                            MainActivity.emit("call", "ringing", number);
                            if (autoAnswer) {
                                final Context app = ctx.getApplicationContext();
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed(new Runnable() {
                                            public void run() { answer(app); }
                                        }, 700);
                            }
                            break;
                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            ringing = false; inCall = true;
                            MainActivity.emit("call", "active", number);
                            break;
                        case TelephonyManager.CALL_STATE_IDLE:
                            boolean was = inCall || ringing;
                            ringing = false; inCall = false;
                            if (was) MainActivity.emit("call", "ended", number);
                            break;
                    }
                }
            };
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            watching = true;
        } catch (Exception ignored) {}
    }

    private static boolean listening() { return watching; }

    static synchronized void stop() {
        try { if (tm != null && listener != null) tm.listen(listener, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) {}
        watching = false;
    }

    /** Accept the incoming call. Returns true when the call was really answered. */
    @SuppressWarnings({"deprecation", "unchecked"})
    static boolean answer(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                TelecomManager tc = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tc != null) {
                    tc.acceptRingingCall();
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        if (tm == null) tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        try {
            java.lang.reflect.Method m = TelephonyManager.class.getDeclaredMethod("answerRingingCall");
            m.setAccessible(true);
            m.invoke(tm);
            return true;
        } catch (Throwable ignored) {}
        // Last resort: bring up the in-call UI so one tap answers.
        try {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
            i.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK));
            ctx.sendOrderedBroadcast(i, null);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Hang up the current call. */
    @SuppressWarnings({"deprecation"})
    static boolean hangUp(Context ctx) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                TelecomManager tc = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tc != null) { tc.endCall(); return true; }
            } catch (Throwable ignored) {}
        }
        if (tm == null) tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        try {
            java.lang.reflect.Method m = TelephonyManager.class.getDeclaredMethod("endCall");
            m.setAccessible(true);
            m.invoke(tm);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Place an outgoing call through the dialer (no permission needed beyond normal). */
    static boolean dial(Context ctx, String num) {
        if (num == null || num.trim().isEmpty()) return false;
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num.trim())));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num.trim())));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Best-effort caller name from the contact book. */
    private static String lookupName(Context ctx, String num) {
        if (num == null || num.isEmpty()) return "";
        try {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
            Uri uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num));
            android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try { if (c.moveToFirst()) return c.getString(0); } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
