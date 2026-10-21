(() => {
  (function(global) {
    "use strict";
    const Call = {
      state: "idle",
      // idle | ringing | active
      number: "",
      name: "",
      autoAnswer: false,
      _wired: false,
      nativeOn() {
        const n = global.ZY.Native;
        return !!(n && n.app && n.call && n.call.available());
      },
      /** Subscribe to native call events and enable auto-answer if the user set it. */
      init() {
        const st = global.ZY.store.get("settings") || {};
        Call.autoAnswer = st.callAutoAnswer !== false;
        if (!Call.nativeOn()) return false;
        global.ZY.Native.call.onEvent((e) => Call._onNative(e));
        try {
          global.ZY.Native.call.setAutoAnswer(Call.autoAnswer);
        } catch {
        }
        Call._wired = true;
        return true;
      },
      setAutoAnswer(on) {
        Call.autoAnswer = !!on;
        const st = global.ZY.store.get("settings") || {};
        st.callAutoAnswer = Call.autoAnswer;
        global.ZY.store.set("settings", st);
        try {
          global.ZY.Native.call.setAutoAnswer(Call.autoAnswer);
        } catch {
        }
        return Call.autoAnswer;
      },
      _onNative(e) {
        const st = e.state;
        if (st === "ringing") {
          Call.state = "ringing";
          Call.number = e.text || "";
          global.ZY.ui && global.ZY.ui.toast("\u0995\u09B2 \u0986\u09B8\u099B\u09C7 \u2014 \u0986\u09AE\u09BF \u09A7\u09B0\u099B\u09BF");
          global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: "ringing", number: Call.number });
        } else if (st === "active") {
          Call.state = "active";
          global.ZY.Native.call.speaker(true);
          global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: "active", number: Call.number });
          Call._greetCaller();
        } else if (st === "ended") {
          Call.state = "idle";
          global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: "ended", number: Call.number });
          Call.number = "";
          Call.name = "";
        }
      },
      /** Speak first when the call connects, then keep listening for the caller. */
      async _greetCaller() {
        const who = Call.name || Call.number || "\u098F\u0995\u099C\u09A8";
        try {
          global.ZY.VOICE.TTS.say(`\u09B9\u09CD\u09AF\u09BE\u09B2\u09CB, \u0986\u09AE\u09BF ZYRAXON \u09AC\u09B2\u099B\u09BF\u0964 ${who} \u0995\u09C7 \u09AC\u09B2\u099B\u09C7\u09A8?`);
        } catch {
        }
        try {
          global.ZY.VOICE.STT.start();
        } catch {
        }
        const api = global.ZY.Agent;
        if (api && api.send) {
          try {
            await api.send(`[\u0995\u09B2 \u099A\u09B2\u099B\u09C7] ${who} \u09AB\u09CB\u09A8\u09C7 \u0986\u099B\u09C7\u09A8\u0964 \u09A4\u09BE\u0995\u09C7 \u09AC\u09BE\u0982\u09B2\u09BE\u09AF\u09BC \u09B9\u09CD\u09AF\u09BE\u09B2\u09CB \u09AC\u09B2\u09CB \u098F\u09AC\u0982 \u099C\u09BF\u099C\u09CD\u099E\u09C7\u09B8 \u0995\u09B0\u09CB \u0995\u09C0 \u09A6\u09B0\u0995\u09BE\u09B0\u0964`);
          } catch {
          }
        }
      },
      /** A line for the system prompt so the model knows it can handle calls. */
      promptLine() {
        const lines = [
          "- \u09AB\u09CB\u09A8 \u0995\u09B2: \u09A4\u09C1\u09AE\u09BF \u09A8\u09BF\u099C\u09C7\u0987 \u0995\u09B2 \u09A7\u09B0\u09A4\u09C7 \u09AA\u09BE\u09B0\u09CB, \u0995\u09A5\u09BE \u09AC\u09B2\u09A4\u09C7 \u09AA\u09BE\u09B0\u09CB, \u0995\u09B2 \u0995\u09BE\u099F\u09A4\u09C7 \u09AA\u09BE\u09B0\u09CB, \u0986\u09B0 \u0995\u09B2 \u0995\u09B0\u09A4\u09C7 \u09AA\u09BE\u09B0\u09CB\u0964",
          "- \u0995\u09B2 \u0986\u09B8\u09B2\u09C7 user_ringing \u0987\u09AD\u09C7\u09A8\u09CD\u099F \u0986\u09B8\u09AC\u09C7; \u0995\u09B2 \u09A7\u09B0\u09BE\u09B0 \u09AA\u09B0 \u0995\u09B2\u09C7 \u09AF\u09BF\u09A8\u09BF \u0986\u099B\u09C7\u09A8 \u09A4\u09BE\u09B0 \u0995\u09A5\u09BE STT \u09B9\u09AF\u09BC\u09C7 \u09A4\u09CB\u09AE\u09BE\u09B0 \u0995\u09BE\u099B\u09C7 \u0986\u09B8\u09AC\u09C7 \u2014 \u09A4\u09BE\u0995\u09C7 \u09AC\u09BE\u0982\u09B2\u09BE\u09AF\u09BC \u0989\u09A4\u09CD\u09A4\u09B0 \u09A6\u09BE\u0993 (TTS-\u098F \u09B8\u09C7 \u09B6\u09C1\u09A8\u09AC\u09C7)\u0964",
          "- \u0995\u09B2 \u0995\u09BE\u099F\u09A4\u09C7 call_hangup, \u0995\u09B2 \u0995\u09B0\u09A4\u09C7 call_dial, \u098F\u0996\u09A8\u0995\u09BE\u09B0 \u0995\u09B2 \u0985\u09AC\u09B8\u09CD\u09A5\u09BE \u099C\u09BE\u09A8\u09A4\u09C7 call_state \u099F\u09C1\u09B2 \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0 \u0995\u09B0\u09CB\u0964",
          "- \u0995\u09B2 \u099A\u09B2\u09BE\u0995\u09BE\u09B2\u09C0\u09A8 \u099B\u09CB\u099F \u09AC\u09BE\u0995\u09CD\u09AF\u09C7 \u0995\u09A5\u09BE \u09AC\u09B2\u09CB, \u0995\u09BE\u09B0\u09A3 \u09A4\u09CB\u09AE\u09BE\u09B0 \u0995\u09A5\u09BE \u0995\u09B2\u09C7\u09B0 \u0985\u09AA\u09B0 \u09AA\u09CD\u09B0\u09BE\u09A8\u09CD\u09A4\u09C7 \u09B6\u09CB\u09A8\u09BE \u09AF\u09BE\u099A\u09CD\u099B\u09C7\u0964"
        ];
        if (!Call.nativeOn()) lines.push("- (\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7 \u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987 \u2014 \u0995\u09B2-\u099F\u09C1\u09B2 \u09B6\u09C1\u09A7\u09C1 APK-\u09A4\u09C7 \u0986\u09B8\u09B2 \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7, \u09B8\u09C7\u099F\u09BE \u09B8\u09CE\u09AD\u09BE\u09AC\u09C7 \u09AC\u09B2\u09CB\u0964)");
        return lines.join("\n");
      },
      /** Ask Android for the call permissions the bridge needs (one popup each). */
      async ensurePermissions() {
        const n = global.ZY.Native;
        if (!n) return false;
        n.permission.request("phone", "\u09AB\u09CB\u09A8 \u0995\u09B2 \u09A7\u09B0\u09BE\u09B0 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF");
        return true;
      },
      async answer() {
        if (!Call.nativeOn()) return { ok: false, error: "no-native", summary: "\u0995\u09B2 \u09A7\u09B0\u09BE\u09B0 \u099C\u09A8\u09CD\u09AF APK \u09A6\u09B0\u0995\u09BE\u09B0" };
        const ok = await global.ZY.Native.call.answer();
        return ok ? { ok: true, summary: "\u0995\u09B2 \u09A7\u09B0\u09BE \u09B9\u09B2\u09CB", data: { number: Call.number } } : { ok: false, error: "answer-failed", summary: "\u0995\u09B2 \u09A7\u09B0\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
      },
      async hangup() {
        if (!Call.nativeOn()) return { ok: false, error: "no-native", summary: "\u0995\u09B2 \u0995\u09BE\u099F\u09BE\u09B0 \u099C\u09A8\u09CD\u09AF APK \u09A6\u09B0\u0995\u09BE\u09B0" };
        const ok = await global.ZY.Native.call.end();
        return ok ? { ok: true, summary: "\u0995\u09B2 \u0995\u09C7\u099F\u09C7 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09B2\u09CB" } : { ok: false, error: "end-failed", summary: "\u0995\u09B2 \u0995\u09BE\u099F\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
      },
      async dial(number) {
        if (!number) return { ok: false, error: "number-required", summary: "\u09A8\u09AE\u09CD\u09AC\u09B0 \u09A6\u09BE\u0993" };
        if (Call.nativeOn()) {
          const ok = await global.ZY.Native.call.dial(number);
          return ok ? { ok: true, summary: `${number} \u09A8\u09BE\u09AE\u09CD\u09AC\u09BE\u09B0\u09C7 \u0995\u09B2 \u0995\u09B0\u09BE \u09B9\u09B2\u09CB` } : { ok: false, error: "dial-failed", summary: "\u0995\u09B2 \u0995\u09B0\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
        }
        const plat = global.ZY.Platform && global.ZY.Platform.data;
        if (plat && plat.isMobile) {
          try {
            location.href = "tel:" + encodeURIComponent(number);
            return { ok: true, summary: `${number} \u09A1\u09BE\u09AF\u09BC\u09BE\u09B2\u09BE\u09B0 \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB` };
          } catch {
          }
        }
        return { ok: false, error: "no-native", summary: "\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7 \u0995\u09B2 \u0995\u09B0\u09BE \u09B8\u09AE\u09CD\u09AD\u09AC \u09A8\u09AF\u09BC (APK \u09A6\u09B0\u0995\u09BE\u09B0)" };
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.Call = Call;
  })(window);
})();
