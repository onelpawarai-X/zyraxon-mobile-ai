package ai.zyraxon.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Android shell for ZYRAXON.
 *
 * The Bengalified web UI is bundled in assets and served from a secure local
 * origin (https://appassets.androidplatform.net) so getUserMedia / SSE / service
 * workers all behave exactly as on the web. Everything the web layer cannot do
 * on its own — real speech-to-text, any-language TTS, runtime permission
 * popups, driving other apps through the accessibility service — is exposed
 * through the {@code ZYNative} JavaScript bridge below.
 */
public class MainActivity extends Activity {

    static MainActivity instance;
    private WebView web;
    private FrameLayout root;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer sr;
    private Intent srIntent;
    private boolean listening = false;
    /** Mic stays open for the whole session unless the user explicitly stops it. */
    private volatile boolean micAlwaysOn = true;
    private volatile boolean micRequested = false;
    /**
     * Languages to rotate through when the user has not pinned one. Google's
     * recogniser is much more accurate when it knows the language, but the user
     * mixes Bangla and English in the same sentence — so we try them in turn and
     * keep whichever one is actually producing words.
     */
    private static final String[] AUTO_LANGS = { "bn-BD", "en-US" };
    private volatile int langIdx = 0;
    private volatile String pinnedLang = "";
    private final android.os.Handler micHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final int REQ_PERM = 4711;
    private static final String ASSET_HOST = "https://appassets.androidplatform.net";

    /** Permission name -> Android permission, so JS can ask by friendly name. */
    private static final Map<String, String[]> PERMS = new HashMap<>();
    static {
        PERMS.put("microphone", new String[]{Manifest.permission.RECORD_AUDIO});
        PERMS.put("camera", new String[]{Manifest.permission.CAMERA});
        PERMS.put("location", new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        PERMS.put("contacts", new String[]{Manifest.permission.READ_CONTACTS});
        PERMS.put("phone", new String[]{Manifest.permission.CALL_PHONE});
        PERMS.put("sms", new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS});
        PERMS.put("calendar", new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR});
        PERMS.put("storage", Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        PERMS.put("notifications", Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.POST_NOTIFICATIONS}
                : new String[0]);
        // Special-access permissions: no runtime dialog exists, they are granted
        // on a system screen. Listed so permissionState() can report them.
        PERMS.put("write-settings", new String[]{Manifest.permission.WRITE_SETTINGS});
        // Overlay (SYSTEM_ALERT_WINDOW) has no runtime dialog — it is granted
        // from its own screen. Listed so permissionState() reports the truth
        // instead of always claiming it is on.
        PERMS.put("overlay", new String[]{Manifest.permission.SYSTEM_ALERT_WINDOW});
    }

    /** WRITE_SETTINGS cannot be granted by a dialog — only from its own screen. */
    private boolean canWriteSettings() {
        return Settings.System.canWrite(this);
    }

    /**
     * Turn Google Play Protect's installer verification off (or on) for the
     * user. Needs WRITE_SETTINGS; returns false so the caller can send the user
     * to the one-tap system screen instead of failing silently.
     */
    @JavascriptInterface
    public boolean playProtect(boolean off) {
        if (!canWriteSettings()) return false;
        try {
            Settings.Global.putInt(getContentResolver(), "package_verifier_enable", off ? 0 : 1);
            Settings.Global.putInt(getContentResolver(), "verifier_verify_adb_installs", off ? 0 : 1);
            Settings.Global.putInt(getContentResolver(), "package_verifier_user_consent", off ? -1 : 1);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        getWindow().setStatusBarColor(0xFF05060F);
        getWindow().setNavigationBarColor(0xFF05060F);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05060F);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " ZYRAXONApp/1.0");

        web.setWebViewClient(new LocalClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // The web page already asked our own sheet; grant here so the
                // browser-level gate never blocks the microphone.
                runOnUiThread(new Runnable() {
                    public void run() {
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                    || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                request.grant(request.getResources());
                                return;
                            }
                        }
                        request.deny();
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "ZYNative");

        startTts();
        startStt();
        KeepAliveService.start(this);
        // Start watching for calls right away so a ringing phone reaches the
        // assistant even before the user opens the chat.
        if (CallWatcher.permissionsOk(this)) CallWatcher.refresh(this);

        String url = ASSET_HOST + "/assets/index.html";
        String extra = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (extra != null && extra.startsWith("http")) url = extra;
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String extra = intent.getStringExtra("url");
        if (extra != null && web != null) web.loadUrl(extra);
    }

    /* ───────────────────────── local asset serving ───────────────────────── */

    private class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u != null && ASSET_HOST.equals(u.getScheme() + "://" + u.getHost())) {
                String path = u.getPath();
                if (path != null && path.startsWith("/assets/")) path = path.substring(8);
                else if (path != null && path.startsWith("/")) path = path.substring(1);
                if (path == null || path.isEmpty()) path = "index.html";
                try {
                    InputStream in = getAssets().open(path);
                    String mime = mimeOf(path);
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private String mimeOf(String p) {
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js")) return "text/javascript";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".webm")) return "video/webm";
            if (p.endsWith(".mp4")) return "video/mp4";
            if (p.endsWith(".mp3")) return "audio/mpeg";
            if (p.endsWith(".woff2")) return "font/woff2";
            return "application/octet-stream";
        }
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() {
                try { web.evaluateJavascript(code, null); } catch (Exception ignored) {}
            }
        });
    }

    /* ───────────────────────────── TTS ───────────────────────────── */

    private void startTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        // These go through emit(), which sends {state, text}. The
                        // old 3-argument ZYNativeCB(...) call dropped the utterance
                        // id on the floor — JS never saw "done", so every sentence
                        // waited for the 6s safety timer and the voice arrived
                        // 15-30 seconds late. This is the realtime fix.
                        @Override public void onStart(String id) { emit("tts", "start", id); }
                        @Override public void onDone(String id) {
                            emit("tts", "done", id);
                            // The JS layer closes the microphone for the whole
                            // duration of speech (STT.pause), so there is nothing
                            // to restart here — restarting it was what put the mic
                            // back on the loudspeaker mid-sentence. The JS gate
                            // reopens it the moment the queue drains.
                            // (Kept disabled on purpose; see STT.pause/resume.)
                        }
                        @Override public void onError(String id) {
                            emit("tts", "error", id);
                            if (micAlwaysOn && micRequested) scheduleRestart(220);
                        }
                    });
                }
            }
        });
    }

    private void startStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        srIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        srIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        srIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        srIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Google's recogniser handles code-switching poorly when pinned to one
        // language: Bangla speech is missed while the engine listens in English.
        // In auto mode we rotate between the languages the user actually mixes,
        // and once one of them returns words we stay on it.
        srIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        srIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);

        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { listening = true; cb("stt", "listening", ""); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) { js("window.ZYNativeCB&&ZYNativeCB('stt','level','"+(int)(v*10)+"')"); }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { cb("stt", "processing", ""); }
            @Override public void onError(int code) {
                listening = false;
                cb("stt", "error", String.valueOf(code));
                // A recogniser that hears nothing because it is listening in the
                // wrong language is the classic "mic never picks up my Bangla".
                // In auto mode, rotate to the next language before retrying.
                if (pinnedLang.isEmpty() && (code == SpeechRecognizer.ERROR_NO_MATCH
                        || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    langIdx = (langIdx + 1) % AUTO_LANGS.length;
                    String next = AUTO_LANGS[langIdx];
                    srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, next);
                    srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, next);
                }
                // Android's recogniser ends itself after every utterance, on
                // silence and on "no match". With the mic pinned open we simply
                // start the next session, so the user never has to tap again.
                if (micAlwaysOn && micRequested && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    scheduleRestart(code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900 : 220);
                }
            }
            @Override public void onResults(Bundle r) {
                listening = false;
                ArrayList<String> out = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                cb("stt", "final", out != null && !out.isEmpty() ? out.get(0) : "");
                if (micAlwaysOn && micRequested) scheduleRestart(140);
            }
            @Override public void onPartialResults(Bundle r) {
                ArrayList<String> out = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (out != null && !out.isEmpty()) cb("stt", "partial", out.get(0));
            }
            @Override public void onEvent(int t, Bundle p) {}
        });
    }

    /** Start a recognition session on the main thread, cancelling any old one. */
    private boolean openMic() {
        final boolean[] ok = { false };
        Runnable r = () -> {
            try {
                if (sr != null && listening) { try { sr.cancel(); } catch (Exception ignored) {} }
                sr.startListening(srIntent);
                listening = true;
                ok[0] = true;
            } catch (Exception ignored) {}
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            micHandler.post(() -> { try { r.run(); } finally { latch.countDown(); } });
            try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
        return ok[0];
    }

    /** Restart after the recogniser closed itself, but only while pinned open. */
    private void scheduleRestart(long delayMs) {
        micHandler.removeCallbacksAndMessages(null);
        micHandler.postDelayed(() -> {
            if (micAlwaysOn && micRequested) openMic();
        }, delayMs);
    }

    private void cb(String kind, String state, String payload) {
        emit(kind, state, payload);
    }

    /** Read a stream to the end into a builder; never throws to the caller. */
    private static void drain(InputStream in, StringBuilder sb) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.length() > 200000) break;
            }
        } catch (Exception ignored) {}
        finally { try { in.close(); } catch (Exception ignored) {} }
    }

    /** Keep tool output bounded so one huge command cannot stall the bridge. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[truncated]";
    }

    /* ─────────────────────────── JS bridge ─────────────────────────── */

    public class Bridge {

        @JavascriptInterface
        public String platform() {
            JSONObject o = new JSONObject();
            try {
                o.put("app", true);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", Build.VERSION.RELEASE);
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("tts", ttsReady);
                o.put("stt", SpeechRecognizer.isRecognitionAvailable(MainActivity.this));
                o.put("accessibility", AccessibilityBridge.isOn());
                o.put("apps", AccessibilityBridge.isOn());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String serverBase() {
            return prefs().getString("server", BuildConfig.SERVER_BASE);
        }

        @JavascriptInterface
        public void setServerBase(String base) {
            prefs().edit().putString("server", base).apply();
        }

        /* ---- voice out (TTS, any language) ---- */

        @JavascriptInterface
        public String ttsLanguages() {
            JSONArray arr = new JSONArray();
            if (ttsReady && Build.VERSION.SDK_INT >= 21) {
                try {
                    for (Voice v : tts.getVoices()) {
                        if (v.getLocale() == null) continue;
                        JSONObject o = new JSONObject();
                        o.put("lang", v.getLocale().toLanguageTag());
                        o.put("name", v.getName());
                        arr.put(o);
                    }
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean ttsSetLanguage(String tag) {
            if (!ttsReady) return false;
            try {
                Locale l = Locale.forLanguageTag(tag);
                int r = tts.setLanguage(l);
                return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void ttsSetVoice(String name) {
            if (!ttsReady || Build.VERSION.SDK_INT < 21) return;
            try {
                for (Voice v : tts.getVoices()) if (v.getName().equals(name)) { tts.setVoice(v); return; }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void ttsSetRate(float rate) { if (ttsReady) tts.setSpeechRate(rate); }

        @JavascriptInterface
        public void ttsSetPitch(float p) { if (ttsReady) tts.setPitch(p); }

        @JavascriptInterface
        public String ttsSpeak(String text, String lang) {
            if (!ttsReady) return "no-tts";
            try {
                // Never silently read Bangla with an English voice. The engine
                // usually has no Bengali data, and the old fallback to
                // Locale.getDefault() is exactly why she "spoke English": the
                // text was Bangla but the voice was not. Report the gap so the
                // caller can speak it through the real Bangla TTS proxy instead.
                if (lang != null && !lang.isEmpty()) {
                    int ok = tts.setLanguage(Locale.forLanguageTag(lang));
                    if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) {
                        String base = lang.split("-")[0].toLowerCase();
                        boolean latin = base.matches("en|es|fr|pt|id|tr|de|it|nl|pl|vi|ms|sv|da|no|fi|cs|ro|hu");
                        // A Latin language can fall back to the device default
                        // and still be understood; anything else cannot.
                        if (latin) { tts.setLanguage(Locale.getDefault()); }
                        else { return "lang-missing"; }
                    }
                }
                String id = "u" + System.currentTimeMillis();
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
                return id;
            } catch (Exception e) { return "err"; }
        }

        @JavascriptInterface
        public void ttsStop() {
            if (ttsReady) tts.stop();
            emit("tts", "stopped", "");
        }

        /* ---- voice in (native STT, any language) ---- */

        @JavascriptInterface
        public boolean sttAvailable() {
            return sr != null;
        }

        @JavascriptInterface
        public boolean sttStart(String lang) {
            if (sr == null) return false;
            // An empty tag means "whatever language the user speaks". Setting an
            // empty EXTRA_LANGUAGE / PREFERENCE makes some recognisers refuse the
            // session outright (that was the deaf microphone), so only pin a
            // language when the user actually chose one.
            if (lang != null && !lang.isEmpty()) {
                pinnedLang = lang;
                langIdx = 0;
                srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            } else {
                pinnedLang = "";
                srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AUTO_LANGS[langIdx % AUTO_LANGS.length]);
                srIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, AUTO_LANGS[langIdx % AUTO_LANGS.length]);
            }
            srIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
            srIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            micRequested = true;
            micAlwaysOn = true;
            return openMic();
        }

        /** Pin the microphone open; it restarts itself for the whole session. */
        @JavascriptInterface
        public boolean sttKeepAlive(boolean on) {
            micAlwaysOn = !!on;
            if (micAlwaysOn) {
                micRequested = true;
                // Never cancel a session that is already listening. The JS
                // watchdog calls this every second or so; cancelling and
                // restarting each time meant the recogniser was torn down
                // before it could ever finish a sentence — the "mic stays on
                // but never hears me" bug. Only (re)open when it is idle.
                if (!listening) return openMic();
                return true;
            }
            micRequested = false;
            micHandler.removeCallbacksAndMessages(null);
            return true;
        }

        @JavascriptInterface
        public void sttStop() {
            micRequested = false;
            listening = false;
            micHandler.removeCallbacksAndMessages(null);
            if (sr != null) { try { sr.cancel(); } catch (Exception ignored) {} }
        }

        /* ---- real command execution on the phone itself ---- */

        /**
         * Run a command in the app's own process sandbox and return the real
         * exit code, stdout and stderr. This is the last-resort capability:
         * when no dedicated tool exists for something, a command still works.
         *
         * The sandbox is Android's, so `/sdcard` and the app's private data are
         * reachable but the system partition is read-only — a command that the
         * OS forbids fails honestly instead of being faked.
         */
        @JavascriptInterface
        public String shellExec(String command, String cwd, int timeoutMs) {
            JSONObject o = new JSONObject();
            if (command == null || command.trim().isEmpty()) {
                try { o.put("error", "empty-command"); } catch (Exception ignored) {}
                return o.toString();
            }
            int limit = timeoutMs > 0 ? Math.min(timeoutMs, 120000) : 30000;
            File workDir = (cwd == null || cwd.isEmpty()) ? getFilesDir() : new File(cwd);
            if (!workDir.exists()) workDir = getFilesDir();
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                pb.directory(workDir);
                pb.redirectErrorStream(false);
                Map<String, String> env = pb.environment();
                env.put("HOME", workDir.getAbsolutePath());
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                // Android's linker refuses to exec binaries from app-writable
                // storage, so the private native dir is the only usable place.
                env.put("PATH", getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin:/vendor/bin");
                p = pb.start();
                final Process proc = p;
                final StringBuilder out = new StringBuilder();
                final StringBuilder err = new StringBuilder();
                Thread tOut = new Thread(new Runnable() { public void run() { drain(proc.getInputStream(), out); } });
                Thread tErr = new Thread(new Runnable() { public void run() { drain(proc.getErrorStream(), err); } });
                tOut.start(); tErr.start();
                boolean done = p.waitFor(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
                int code;
                if (!done) {
                    code = -1;
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                } else {
                    code = p.exitValue();
                }
                tOut.join(1500); tErr.join(1500);
                o.put("code", code);
                o.put("timedOut", !done);
                o.put("stdout", clip(out.toString(), 40000));
                o.put("stderr", clip(err.toString(), 8000));
                o.put("cwd", workDir.getAbsolutePath());
                o.put("shell", "sh");
                o.put("where", "android-app-sandbox");
            } catch (Exception e) {
                try { o.put("error", "exec-failed: " + e.getMessage()); } catch (Exception ignored) {}
            } finally {
                if (p != null) { try { p.destroy(); } catch (Exception ignored) {} }
            }
            return o.toString();
        }

        /* ---- permissions: native popup, one tap on Allow ---- */

        @JavascriptInterface
        public String permissionState(String name) {
            String[] need = PERMS.get(name);
            JSONObject o = new JSONObject();
            try {
                boolean granted = true;
                if ("write-settings".equals(name)) {
                    granted = canWriteSettings();
                } else if ("overlay".equals(name)) {
                    // SYSTEM_ALERT_WINDOW is never in the runtime list, so the
                    // checkSelfPermission loop below would always say "denied".
                    granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
                } else if (need != null) {
                    for (String p : need)
                        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) granted = false;
                }
                o.put("granted", granted);
                o.put("name", name);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void requestPermission(final String name, final String label) {
            runOnUiThread(new Runnable() {
                public void run() { askPermission(name, label == null ? name : label); }
            });
        }

        /**
         * Take a permission back. Android never lets an app revoke its own
         * runtime grant, so the honest route is the one screen that can: the
         * app's own permission page. The user flips the toggle there and comes
         * straight back — nothing to search for.
         */
        @JavascriptInterface
        public boolean revokePermission(String name) {
            Intent i;
            if ("overlay".equals(name) && Build.VERSION.SDK_INT >= 23) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
            } else if ("accessibility".equals(name)) {
                i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else if (Build.VERSION.SDK_INT >= 30) {
                // The per-app permission screen: every grant for this app, one tap each.
                i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            } else {
                i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean openSystemSettings(String kind) {
            Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            if ("accessibility".equals(kind)) {
                i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else if ("overlay".equals(kind) && Build.VERSION.SDK_INT >= 23) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
            } else if ("app".equals(kind)) {
                i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            } else if ("install".equals(kind) && Build.VERSION.SDK_INT >= 26) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
            } else if ("write-settings".equals(kind) && Build.VERSION.SDK_INT >= 23) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            } else if ("play-protect".equals(kind)) {
                // Play Protect's own toggle lives inside the Play Store. Its
                // documented deep link opens the Play Protect screen directly;
                // the app-details page is only the fallback when no handler
                // claims the link, so the user never lands on a dead screen.
                try {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/playprotect"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:com.android.vending"));
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean accessibilityOn() { return AccessibilityBridge.isOn(); }

        /* ---- background screen awareness: no tool call needed ---- */

        /**
         * Turn the per-second background screen feed on or off. Frames go to the
         * assistant only — never to the gallery, never shown on screen.
         */
        @JavascriptInterface
        public boolean watchScreen(boolean on, int everyMs) {
            if (on) AccessibilityBridge.startWatch(everyMs > 0 ? everyMs : 1200);
            else AccessibilityBridge.stopWatch();
            return AccessibilityBridge.isWatching();
        }

        @JavascriptInterface
        public boolean watchingScreen() { return AccessibilityBridge.isWatching(); }

        @JavascriptInterface
        public String foregroundApp() {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", AccessibilityBridge.foregroundPackage());
                o.put("label", AccessibilityBridge.foregroundLabel());
                o.put("since", AccessibilityBridge.foregroundSince());
            } catch (Exception ignored) {}
            return o.toString();
        }

        /* ---- a real system overlay: code/status above other apps ---- */

        private android.view.View overlayView;
        private android.widget.TextView overlayText;

        /**
         * Show the live code/status box on top of everything — including other
         * apps — using the system overlay window. This is what lets the user
         * watch her write while they are in Facebook or Chrome. Requires the
         * "display over other apps" permission; if it is missing we report it
         * so the caller can open the one-tap system screen.
         */
        @JavascriptInterface
        public boolean overlayShow(final String title, final String body) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) return false;
            micHandler.post(() -> {
                try {
                    if (overlayView == null) {
                        android.widget.LinearLayout box = new android.widget.LinearLayout(MainActivity.this);
                        box.setOrientation(android.widget.LinearLayout.VERTICAL);
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                        bg.setColor(0xE60B0F1E);
                        bg.setCornerRadius(28f);
                        bg.setStroke(2, 0x66FFFFFF);
                        box.setBackground(bg);
                        int pad = (int) (14 * getResources().getDisplayMetrics().density);
                        box.setPadding(pad, pad, pad, pad);

                        android.widget.TextView head = new android.widget.TextView(MainActivity.this);
                        head.setTextColor(0xFF7CE7FF);
                        head.setTextSize(12f);
                        head.setId(android.R.id.title);
                        box.addView(head);

                        overlayText = new android.widget.TextView(MainActivity.this);
                        overlayText.setTextColor(0xFFEAF2FF);
                        overlayText.setTextSize(11f);
                        overlayText.setTypeface(android.graphics.Typeface.MONOSPACE);
                        overlayText.setMaxLines(18);
                        box.addView(overlayText);

                        int flags = Build.VERSION.SDK_INT >= 26
                                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : android.view.WindowManager.LayoutParams.TYPE_PHONE;
                        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                                flags,
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        lp.gravity = android.view.Gravity.TOP;
                        lp.y = (int) (60 * getResources().getDisplayMetrics().density);
                        android.view.WindowManager wm =
                                (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        wm.addView(box, lp);
                        overlayView = box;
                    }
                    android.widget.TextView head = overlayView.findViewById(android.R.id.title);
                    if (head != null) head.setText(title);
                    if (overlayText != null) overlayText.setText(body);
                } catch (Exception ignored) {}
            });
            return true;
        }

        @JavascriptInterface
        public void overlayHide() {
            micHandler.post(() -> {
                try {
                    if (overlayView != null) {
                        android.view.WindowManager wm =
                                (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        wm.removeView(overlayView);
                    }
                } catch (Exception ignored) {}
                overlayView = null; overlayText = null;
            });
        }

        @JavascriptInterface
        public boolean overlayAllowed() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
        }

        /* ---- real device control through the accessibility service ---- */

        @JavascriptInterface
        public void openApp(String pkgOrName) {
            AccessibilityBridge.openApp(MainActivity.this, pkgOrName);
        }

        /** Truth about whether an app exists here — never claim an app that isn't. */
        @JavascriptInterface
        public String resolveApp(String pkgOrName) {
            return AccessibilityBridge.resolveApp(MainActivity.this, pkgOrName);
        }

        @JavascriptInterface
        public boolean launchUrl(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean tap(float x, float y) { return AccessibilityBridge.tap(x, y); }

        @JavascriptInterface
        public boolean swipe(float x1, float y1, float x2, float y2) { return AccessibilityBridge.swipe(x1, y1, x2, y2); }

        @JavascriptInterface
        public boolean tapText(String text) { return AccessibilityBridge.tapText(text); }

        @JavascriptInterface
        public boolean typeText(String text) { return AccessibilityBridge.typeText(text); }

        @JavascriptInterface
        public boolean scroll(boolean forward) { return AccessibilityBridge.scroll(forward); }

        @JavascriptInterface
        public boolean globalAction(String action) { return AccessibilityBridge.globalAction(action); }

        @JavascriptInterface
        public String readScreen() { return AccessibilityBridge.readScreen(); }

        @JavascriptInterface
        public void screenShot() { AccessibilityBridge.screenShot(); }

        @JavascriptInterface
        public String listApps() { return AccessibilityBridge.listApps(MainActivity.this); }

        /* ---- misc native bits ---- */

        @JavascriptInterface
        public void notifyMsg(String title, String body) {
            KeepAliveService.notify(MainActivity.this, title, body);
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String clipboardRead() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                return d != null && d.getItemCount() > 0 ? String.valueOf(d.getItemAt(0).coerceToText(MainActivity.this)) : "";
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void clipboardWrite(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("zyraxon", text));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String deviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("model", Build.MODEL);
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("locale", Locale.getDefault().toLanguageTag());
            } catch (Exception ignored) {}
            return o.toString();
        }

        /* ---- phone calls: real answer / hang up / dial ---- */

        @JavascriptInterface
        public String callState() {
            JSONObject o = new JSONObject();
            try {
                CallWatcher.refresh(MainActivity.this);
                o.put("inCall", CallWatcher.inCall);
                o.put("ringing", CallWatcher.ringing);
                o.put("number", CallWatcher.number == null ? "" : CallWatcher.number);
                o.put("name", CallWatcher.name == null ? "" : CallWatcher.name);
                o.put("watching", CallWatcher.watching);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public boolean answerCall() {
            boolean ok = CallWatcher.answer(MainActivity.this);
            cb("call", ok ? "answered" : "answer-failed", CallWatcher.number == null ? "" : CallWatcher.number);
            return ok;
        }

        @JavascriptInterface
        public boolean endCall() {
            boolean ok = CallWatcher.hangUp(MainActivity.this);
            cb("call", ok ? "ended" : "end-failed", "");
            return ok;
        }

        @JavascriptInterface
        public boolean dialNumber(String number) {
            return CallWatcher.dial(MainActivity.this, number);
        }

        /** Auto-answer mode: the assistant picks up any incoming call itself. */
        @JavascriptInterface
        public void setCallAutoAnswer(boolean on) {
            CallWatcher.autoAnswer = on;
            CallWatcher.refresh(MainActivity.this);
        }

        @JavascriptInterface
        public boolean callPermissionsOk() {
            return CallWatcher.permissionsOk(MainActivity.this);
        }

        /** Hands-free loudspeaker so the assistant's TTS is audible to the caller. */
        @JavascriptInterface
        public void setSpeaker(boolean on) {
            try {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setSpeakerphoneOn(on);
            } catch (Exception ignored) {}
        }
    }

    /* ───────────────────── permission popups ───────────────────── */

    private void askPermission(String name, String label) {
        String[] need = PERMS.get(name);
        if (need == null || need.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("অনুমতি")
                    .setMessage(label + " — এই ফোনে আলাদা অনুমতি লাগে না।")
                    .setPositiveButton("ঠিক আছে", null)
                    .show();
            return;
        }
        boolean all = true;
        final List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { all = false; missing.add(p); }
        }
        if (all) { cb("perm", "granted", name); return; }

        // One tap: our popup explains, then Android's own dialog confirms.
        new AlertDialog.Builder(this)
                .setTitle("অনুমতি দরকার")
                .setMessage(label + " ব্যবহার করতে অনুমতি দিতে হবে। \"Allow\" চাপো, ব্যস।")
                .setPositiveButton("Allow", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        requestPermissions(missing.toArray(new String[0]), REQ_PERM);
                    }
                })
                .setNegativeButton("না", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        cb("perm", "denied", name);
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM) return;
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        cb("perm", ok ? "granted" : "denied", "");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        KeepAliveService.stop(this);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("zyraxon", Context.MODE_PRIVATE);
    }

    /** Push a native event into the web layer (window.ZYNativeCB). */
    static void emit(String kind, String state, String payload) {
        MainActivity a = instance;
        if (a == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("text", payload == null ? "" : payload);
            String arg = JSONObject.quote(o.toString());
            a.js("window.ZYNativeCB&&window.ZYNativeCB('" + kind + "'," + arg + ")");
        } catch (Exception ignored) {}
    }
}
