/* ══════════════════════════════════════════════════════════════
   ZYRAXON Call — the assistant answers the phone itself.

   Android has no single public API for "pick up now", so the native side
   (CallWatcher.java) uses TelecomManager plus the reflection fallbacks that
   work from Android 5 to 14. This layer:

     • watches for an incoming call and pushes it into the chat as a real
       turn, so the assistant greets the caller without the user typing;
     • pipes the caller's speech (Android's own call audio → our STT) into
       the normal agent loop;
     • speaks the reply back through TTS with the loudspeaker on, so the
       assistant talks to the caller and the user never has to;
     • can place calls and hang up on request.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const Call: any = {
    state: 'idle',        // idle | ringing | active
    number: '',
    name: '',
    autoAnswer: false,
    _wired: false,

    nativeOn() {
      const n = global.ZY.Native;
      return !!(n && n.app && n.call && n.call.available());
    },

    /** Subscribe to native call events and enable auto-answer if the user set it. */
    init() {
      const st = global.ZY.store.get('settings') || {};
      Call.autoAnswer = st.callAutoAnswer !== false; // default: on, that is the ask
      if (!Call.nativeOn()) return false;
      global.ZY.Native.call.onEvent((e) => Call._onNative(e));
      try { global.ZY.Native.call.setAutoAnswer(Call.autoAnswer); } catch {}
      Call._wired = true;
      return true;
    },

    setAutoAnswer(on) {
      Call.autoAnswer = !!on;
      const st = global.ZY.store.get('settings') || {};
      st.callAutoAnswer = Call.autoAnswer;
      global.ZY.store.set('settings', st);
      try { global.ZY.Native.call.setAutoAnswer(Call.autoAnswer); } catch {}
      return Call.autoAnswer;
    },

    _onNative(e) {
      const st = e.state;
      if (st === 'ringing') {
        Call.state = 'ringing';
        Call.number = e.text || '';
        global.ZY.ui && global.ZY.ui.toast('কল আসছে — আমি ধরছি');
        global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: 'ringing', number: Call.number });
      } else if (st === 'active') {
        Call.state = 'active';
        global.ZY.Native.call.speaker(true);
        global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: 'active', number: Call.number });
        // The assistant opens the conversation — the user does not have to.
        Call._greetCaller();
      } else if (st === 'ended') {
        Call.state = 'idle';
        global.ZY.ui && global.ZY.ui.addCallCard && global.ZY.ui.addCallCard({ state: 'ended', number: Call.number });
        Call.number = ''; Call.name = '';
      }
    },

    /** Speak first when the call connects, then keep listening for the caller. */
    async _greetCaller() {
      const who = Call.name || Call.number || 'একজন';
      try {
        global.ZY.VOICE.TTS.say(`হ্যালো, আমি ZYRAXON বলছি। ${who} কে বলছেন?`);
      } catch {}
      // Re-arm the mic so the caller's voice lands in the chat box.
      try { global.ZY.VOICE.STT.start(); } catch {}
      const api = global.ZY.Agent;
      if (api && api.send) {
        try { await api.send(`[কল চলছে] ${who} ফোনে আছেন। তাকে বাংলায় হ্যালো বলো এবং জিজ্ঞেস করো কী দরকার।`); } catch {}
      }
    },

    /** A line for the system prompt so the model knows it can handle calls. */
    promptLine() {
      const lines = [
        '- ফোন কল: তুমি নিজেই কল ধরতে পারো, কথা বলতে পারো, কল কাটতে পারো, আর কল করতে পারো।',
        '- কল আসলে user_ringing ইভেন্ট আসবে; কল ধরার পর কলে যিনি আছেন তার কথা STT হয়ে তোমার কাছে আসবে — তাকে বাংলায় উত্তর দাও (TTS-এ সে শুনবে)।',
        '- কল কাটতে call_hangup, কল করতে call_dial, এখনকার কল অবস্থা জানতে call_state টুল ব্যবহার করো।',
        '- কল চলাকালীন ছোট বাক্যে কথা বলো, কারণ তোমার কথা কলের অপর প্রান্তে শোনা যাচ্ছে।',
      ];
      if (!Call.nativeOn()) lines.push('- (এই ডিভাইসে কল-ব্রিজ নেই — কল-টুল শুধু APK-তে আসল কাজ করবে, সেটা সৎভাবে বলো।)');
      return lines.join('\n');
    },

    /** Ask Android for the call permissions the bridge needs (one popup each). */
    async ensurePermissions() {
      const n = global.ZY.Native;
      if (!n) return false;
      n.permission.request('phone', 'ফোন কল ধরার অনুমতি');
      return true;
    },

    async answer() {
      if (!Call.nativeOn()) return { ok: false, error: 'no-native', summary: 'কল ধরার জন্য APK দরকার' };
      const ok = await global.ZY.Native.call.answer();
      return ok ? { ok: true, summary: 'কল ধরা হলো', data: { number: Call.number } } : { ok: false, error: 'answer-failed', summary: 'কল ধরা যায়নি' };
    },

    async hangup() {
      if (!Call.nativeOn()) return { ok: false, error: 'no-native', summary: 'কল কাটার জন্য APK দরকার' };
      const ok = await global.ZY.Native.call.end();
      return ok ? { ok: true, summary: 'কল কেটে দেওয়া হলো' } : { ok: false, error: 'end-failed', summary: 'কল কাটা যায়নি' };
    },

    async dial(number) {
      if (!number) return { ok: false, error: 'number-required', summary: 'নম্বর দাও' };
      if (Call.nativeOn()) {
        const ok = await global.ZY.Native.call.dial(number);
        return ok ? { ok: true, summary: `${number} নাম্বারে কল করা হলো` } : { ok: false, error: 'dial-failed', summary: 'কল করা যায়নি' };
      }
      // Browser fallback only where a dialer can exist — on a desktop host
      // there is none, and pretending otherwise would be a lie.
      const plat = global.ZY.Platform && global.ZY.Platform.data;
      if (plat && plat.isMobile) {
        try { location.href = 'tel:' + encodeURIComponent(number); return { ok: true, summary: `${number} ডায়ালার খোলা হলো` }; }
        catch { /* fall through */ }
      }
      return { ok: false, error: 'no-native', summary: 'এই ডিভাইসে কল করা সম্ভব নয় (APK দরকার)' };
    },
  };

  global.ZY = global.ZY || {};
  global.ZY.Call = Call;
})(window);
