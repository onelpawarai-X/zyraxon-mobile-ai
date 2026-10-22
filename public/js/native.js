(() => {
  (function(global) {
    "use strict";
    const N = global.ZYNative && typeof global.ZYNative === "object" ? global.ZYNative : null;
    const Native = {
      app: !!N,
      info: { app: false, sdk: 0, release: "", model: "", tts: false, stt: false, accessibility: false },
      _cbQueue: {},
      init() {
        if (!N) return;
        try {
          Native.info = JSON.parse(N.platform());
        } catch {
          Native.info = { app: true };
        }
        global.ZYNativeCB = (kind, payload, extra) => {
          let p = payload;
          if (extra !== void 0 && typeof payload === "string") {
            p = JSON.stringify({ state: payload, text: String(extra) });
          }
          Native._dispatch(kind, p);
        };
        if (global.ZYDesktop && typeof global.ZYDesktop.bind === "function") {
          try {
            global.ZYDesktop.bind(global.ZYNativeCB);
          } catch {
          }
        }
        Native.on("tts", (p) => {
          if (!p || !p.text) return;
          const TTS = global.ZY.VOICE && global.ZY.VOICE.TTS;
          if (!TTS) return;
          if (p.state === "done" && TTS._natId === p.text) {
            const fn = TTS._natDone;
            if (fn) fn();
          } else if (p.state === "error" && TTS._natId === p.text) {
            const fn = TTS._natDone;
            if (fn) fn();
          }
        });
      },
      on(kind, fn) {
        (Native._cbQueue[kind] = Native._cbQueue[kind] || []).push(fn);
      },
      _dispatch(kind, raw) {
        let p = raw;
        if (typeof raw === "string") {
          try {
            p = JSON.parse(raw);
          } catch {
          }
        }
        (Native._cbQueue[kind] || []).forEach((fn) => {
          try {
            fn(p);
          } catch {
          }
        });
      },
      /* ───────── voice out: native TTS, any language ───────── */
      tts: {
        available() {
          return !!(N && Native.info.tts);
        },
        languages() {
          if (!N) return [];
          try {
            return JSON.parse(N.ttsLanguages());
          } catch {
            return [];
          }
        },
        setLanguage(tag) {
          return !!(N && N.ttsSetLanguage(tag));
        },
        setVoice(name) {
          if (N) N.ttsSetVoice(name);
        },
        setRate(r) {
          if (N) N.ttsSetRate(r);
        },
        setPitch(p) {
          if (N) N.ttsSetPitch(p);
        },
        speak(text, lang) {
          if (!N) return null;
          return N.ttsSpeak(text, lang || "");
        },
        stop() {
          if (N) N.ttsStop();
        }
      },
      /* ───────── voice in: native STT, any language ───────── */
      stt: {
        available() {
          return !!(N && Native.info.stt);
        },
        // An empty tag is a real request: "listen in whatever language is spoken".
        // Forcing 'bn-BD' here (the old default) overrode the auto setting and
        // pinned the recogniser to Bangla even when the user chose another one.
        start(lang) {
          if (!N) return false;
          return N.sttStart(lang == null ? "" : String(lang));
        },
        stop() {
          if (N) N.sttStop();
        },
        /** Pin the mic open natively so it restarts itself between utterances. */
        keepAlive(on) {
          if (!N || !N.sttKeepAlive) return false;
          return N.sttKeepAlive(!!on);
        },
        onResult(fn) {
          Native.on("stt", fn);
        }
      },
      /* ───────── permissions: one tap on Allow ───────── */
      permission: {
        state(name) {
          if (!N) return { granted: false };
          try {
            return JSON.parse(N.permissionState(name));
          } catch {
            return { granted: false };
          }
        },
        request(name, label) {
          if (N) N.requestPermission(name, label || name);
        },
        /** Hand back a grant — Android requires its own screen for this. */
        revoke(name) {
          return !!(N && N.revokePermission && N.revokePermission(name));
        },
        onResult(fn) {
          Native.on("perm", fn);
        }
      },
      settings: {
        open(kind) {
          if (N) N.openSystemSettings(kind);
        },
        accessibilityOn() {
          return !!(N && N.accessibilityOn());
        },
        serverBase() {
          return N ? N.serverBase() : "";
        },
        setServerBase(u) {
          if (N) N.setServerBase(u);
        },
        /** The version baked into this APK, straight from the package manager. */
        appVersion() {
          return N && N.appVersion ? N.appVersion() : "";
        },
        /** Turn Play Protect's installer verification off (true) or on (false). */
        playProtect(off) {
          return !!(N && N.playProtect && N.playProtect(!!off));
        },
        canWriteSettings() {
          const st = Native.permission.state("write-settings");
          return !!(st && st.granted);
        },
        /** Draw the live code box above other apps (needs the overlay grant). */
        overlayAllowed() {
          return !!(N && N.overlayAllowed && N.overlayAllowed());
        },
        overlayShow(title, body) {
          return !!(N && N.overlayShow && N.overlayShow(title, body));
        },
        overlayHide() {
          if (N && N.overlayHide) N.overlayHide();
        },
        overlayMinimize(min) {
          if (N && N.overlayMinimize) N.overlayMinimize(!!min);
        },
        overlayMinimized() {
          return !!(N && N.overlayMinimized && N.overlayMinimized());
        }
      },
      /* ───────── real device control ───────── */
      device: {
        openApp(nameOrPkg) {
          if (N) N.openApp(nameOrPkg);
        },
        /** Does this app really exist here? Never claim an app that isn't installed. */
        resolveApp(nameOrPkg) {
          if (!N || !N.resolveApp) return { found: false, package: "", label: "" };
          try {
            return JSON.parse(N.resolveApp(nameOrPkg));
          } catch {
            return { found: false, package: "", label: "" };
          }
        },
        launchUrl(url) {
          return !!(N && N.launchUrl(url));
        },
        tap(x, y) {
          return !!(N && N.tap(x, y));
        },
        swipe(x1, y1, x2, y2) {
          return !!(N && N.swipe(x1, y1, x2, y2));
        },
        tapText(t) {
          return !!(N && N.tapText(t));
        },
        typeText(t) {
          return !!(N && N.typeText(t));
        },
        scroll(forward) {
          return !!(N && N.scroll(!!forward));
        },
        action(a) {
          return !!(N && N.globalAction(a));
        },
        readScreen() {
          if (!N) return [];
          try {
            return JSON.parse(N.readScreen());
          } catch {
            return [];
          }
        },
        screenShot() {
          if (N) N.screenShot();
        },
        listApps() {
          if (!N) return [];
          try {
            return JSON.parse(N.listApps());
          } catch {
            return [];
          }
        },
        onShot(fn) {
          Native.on("shot", fn);
        },
        onEvent(fn) {
          Native.on("native", fn);
        },
        /** Background screen feed — on/off, no tool call involved. */
        watchScreen(on, everyMs) {
          return !!(N && N.watchScreen && N.watchScreen(!!on, Number(everyMs) || 0));
        },
        watchingScreen() {
          return !!(N && N.watchingScreen && N.watchingScreen());
        },
        foregroundApp() {
          if (!N || !N.foregroundApp) return { pkg: "", label: "" };
          try {
            return JSON.parse(N.foregroundApp());
          } catch {
            return { pkg: "", label: "" };
          }
        }
      },
      misc: {
        notify(t, b) {
          if (N) N.notifyMsg(t, b);
        },
        vibrate(ms) {
          if (N) N.vibrate(ms || 30);
        },
        clipboardRead() {
          return N ? N.clipboardRead() : "";
        },
        clipboardWrite(t) {
          if (N) N.clipboardWrite(t);
        },
        deviceInfo() {
          if (!N) return {};
          try {
            return JSON.parse(N.deviceInfo());
          } catch {
            return {};
          }
        }
      },
      /* ───────── real commands on the phone itself ───────── */
      shell: {
        available() {
          return !!(N && N.shellExec);
        },
        /** Run `command` in the app sandbox; returns { code, stdout, stderr }. */
        exec(command, cwd, timeoutMs) {
          if (!N || !N.shellExec) return null;
          try {
            return JSON.parse(N.shellExec(String(command), cwd || "", Number(timeoutMs) || 0));
          } catch (e) {
            return { error: String(e) };
          }
        }
      },
      /* ───────── real phone calls ───────── */
      call: {
        available() {
          return !!(N && N.callState);
        },
        state() {
          if (!N) return { inCall: false, ringing: false };
          try {
            return JSON.parse(N.callState());
          } catch {
            return { inCall: false, ringing: false };
          }
        },
        answer() {
          return !!(N && N.answerCall());
        },
        end() {
          return !!(N && N.endCall());
        },
        dial(num) {
          return !!(N && N.dialNumber(String(num)));
        },
        setAutoAnswer(on) {
          if (N && N.setCallAutoAnswer) N.setCallAutoAnswer(!!on);
        },
        permissionsOk() {
          return !!(N && N.callPermissionsOk && N.callPermissionsOk());
        },
        speaker(on) {
          if (N && N.setSpeaker) N.setSpeaker(!!on);
        },
        onEvent(fn) {
          Native.on("call", fn);
        }
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.Native = Native;
  })(window);
})();
