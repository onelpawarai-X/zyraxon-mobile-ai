/* ══════════════════════════════════════════════════════════════
   ZYRAXON App — bootstrap & wiring.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  // Baseline order = measured benchmark speed (fastest first, as the user asked).
  // Measured on the live provider: three probe rounds each, ranked by
  // "never dropped a call" first and raw speed second. Apex answers in ~0.35s
  // and never missed; Matrix is last only because it took ~19s. The list is
  // refreshed from /api/model-health at runtime; this is the cold-start order.
  const FAST_INDEX = ['zyraxon-apex', 'zyraxon-visionary', 'zyraxon-titan', 'zyraxon-pulse', 'zyraxon-codex', 'zyraxon-neural', 'zyraxon-quantum', 'zyraxon-cyber', 'zyraxon-genesis', 'zyraxon-matrix'];
  const $ = (id: string): any => document.getElementById(id);
  const store = () => global.ZY.store;

  const App: any = {
    models: [],
    async boot() {
      global.ZY.Native.init();
      await global.ZY.Platform.init();     // know where we are before anything else
      await global.ZY.DB.init();
      // UI language first: every label below is rendered through it.
      // ZYRAXON is built for a Bangla speaker, so Bangla is the default. The
      // old default followed the phone's locale, which on an English phone
      // pulled the whole session into English — the "কথা ইংরেজিতে বলতে"
      // complaint. The user can still switch it in Settings.
      global.ZY.I18N.init(store().get('uiLang', 'bn'));
      // The mic is NOT tied to the interface language: the user speaks many
      // languages and the recogniser must follow them, not the menus.
      global.ZY.VOICE.STT.setLang(store().get('sttLang', ''));
      global.ZY.ui.init();
      global.ZY.BG.init();
      global.ZY.ORB.init('aura');
      global.ZY.Call.init();               // watch for incoming calls from boot
      await global.ZY.Agent.init();
      App.bindNative();
      // Background screen awareness: if the user already granted accessibility
      // (and wants the watch on), it starts itself — no tool call, no tap. The
      // assistant simply always knows where the user is from now on.
      if (store().get('screenWatch', true)) global.ZY.Screen.start();

      // character
      const ch = global.ZY.characters[global.ZY.Agent.character] || global.ZY.characters.girlfriend;
      await global.ZY.ui.applyCharacter(ch, { silent: true });   // restore, do not re-speak

      // background
      const bg = store().get('bg', 'galaxy');
      $('set-bg').innerHTML = global.ZY.BG.scenes.map((s) => `<option value="${s.id}">${s.name}</option>`).join('');
      $('set-bg').value = bg;
      global.ZY.BG.set(bg, store().get('bgUrl', ''));
      global.ZY.BG.setSpeed(Number(store().get('bgSpeed', 1)));
      global.ZY.BG.setSound(!!store().get('bgSound', false));

      App.bindSettings();
      App.bindComposer();
      App.bindVoice();
      App.bindQuickbar();
      App.showVersion();            // corner badge, filled from the real version
      await App.restore();          // past conversation first — the user must see it immediately
      await App.loadModels();
      global.ZY.Tools.native.available() && global.ZY.ui.toast('ডিভাইস ব্রিজ পাওয়া গেছে ✓');

      // Greet once, in the interface language, and speak it too — the user
      // asked for the assistant to actually talk, not just show text.
      const seen = store().get('greeted', false);
      if (!seen) {
        store().set('greeted', true);
        const chName = global.ZY.t('char.' + ch.id);
        const hi = global.ZY.I18N.code === 'bn'
          ? `হ্যালো! আমি ZYRAXON — তোমার ${chName}। বলো, শুরু করি।`
          : `Hi, I am ZYRAXON — your ${chName}. Tell me what to do and I will start.`;
        global.ZY.ui.addMessage('ai', hi);
        global.ZY.VOICE.TTS.say(hi);
        await global.ZY.DB.addMessage({ role: 'assistant', content: hi, ts: Date.now() });
      }
      // The microphone opens on its own at boot and stays open — the user asked
      // never to have to tap the orb to start talking.
      if (global.ZY.VOICE.STT.available()) {
        global.ZY.VOICE.STT.init();
        if (store().get('alwaysMic', true)) App.armMic();
      }

      // Register the SW for the web/PWA build only. Inside the APK the assets
      // are already local, and a stale SW cache there would shadow app updates.
      if ('serviceWorker' in navigator && !global.ZY.Native.app) {
        navigator.serviceWorker.register('/sw.js').catch(() => {});
      } else if ('serviceWorker' in navigator) {
        navigator.serviceWorker.getRegistrations().then((rs) => rs.forEach((r) => r.unregister())).catch(() => {});
      }
    },

    /** Rebuild the visible conversation and the task panel from the local DB.
     *  The user's whole history must survive app restarts and cloud loss. */
    async restore() {
      const rows = await global.ZY.DB.recentMessages(120);
      const chat = $('chat');
      const shown = rows.filter((m) => (m.role === 'user' || m.role === 'assistant') && (m.content || '').trim());
      if (shown.length) {
        chat.innerHTML = '';
        // keep the persisted order, oldest first
        for (const m of shown) {
          const div = document.createElement('div');
          div.className = 'msg ' + (m.role === 'user' ? 'user' : 'ai') + ' restored';
          // Tool names ride in their own field, so an old bubble never shows
          // "[টুল: …]" and a fresh one hints at what ran without repeating it.
          div.innerHTML = `<span class="body"></span>${m.tools && m.tools.length ? global.ZY.ui._chips(m.tools.map((n) => ({ name: n }))) : ''}<span class="msg-time">${new Date(m.ts || Date.now()).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>`;
          // Older rows kept the tool names inside the words; strip that suffix
          // so a past conversation reads as plain speech again.
          div.querySelector('.body').textContent = String(m.content || '').replace(/\n?\[টুল:[^\]]*\]\s*$/g, '').trim();
          chat.appendChild(div);
        }
        global.ZY.ui._scroll(true);
      }
      // the todo board is part of the memory too
      const todos = await global.ZY.DB.loadTodos();
      if (todos && todos.length) {
        global.ZY.store.set('todos', todos);
        global.ZY.ui.renderTodos(todos);
      }
      const n = shown.length;
      if (n) global.ZY.ui.toast(`${n}টি আগের কথা মনে পড়েছে ✓`);
      return n;
    },

    async loadModels() {
      let list = [];
      try {
        const r = await fetch('/api/models');
        const j = await r.json();
        list = j.data || [];
      } catch {
        try { const r = await fetch('https://zyraxonai.lovable.app/api/public/zyraxon/v1/models'); list = (await r.json()).data || []; } catch {}
      }
      if (!list.length) {
        list = [{ id: 'zyraxon-titan', name: 'ZYRAXON Titan', description: 'দ্রুত ও শক্তিশালী', input_modalities: ['text', 'image', 'video'] }];
      }

      // Baseline order = measured benchmark speed (fastest first, as the user asked).
      const rank = (id) => { const i = FAST_INDEX.indexOf(id); return i === -1 ? 99 : i; };

      list.sort((a, b) => rank(a.id) - rank(b.id));
      App.models = list;
      App._applyModels(list);
      // Live pool health is slow (it probes every model). Never block the first
      // paint on it — refresh the order in the background instead.
      App.refreshHealth();
    },

    /** Re-sort the model list by live responsiveness once the probe finishes. */
    async refreshHealth() {
      let liveOrder = [];
      try {
        const hr = await fetch('/api/model-health');
        const hj = await hr.json();
        liveOrder = hj.order || [];
        global.ZY.Agent.healthOrder = liveOrder;
        global.ZY.Agent.stalled = new Set((hj.results || []).filter((x) => !x.ok).map((x) => x.model));
      } catch { return; }
      if (!liveOrder.length || !App.models) return;
      const rank = (id) => { const i = FAST_INDEX.indexOf(id); return i === -1 ? 99 : i; };
      const rankLive = (id) => { const i = liveOrder.indexOf(id); return i === -1 ? rank(id) : i; };
      const sorted = App.models.slice().sort((a, b) => rankLive(a.id) - rankLive(b.id) || rank(a.id) - rank(b.id));
      App._applyModels(sorted);
    },

    _applyModels(list) {
      App.models = list;
      // If the user explicitly picked a model, respect it; otherwise the default
      // is the fastest *benchmarked* model that the live probe has not marked
      // stalled. Ranking by live latency alone can promote a model that answers
      // quickly but never emits tool calls, which breaks the agent.
      const picked = global.ZY.store.get('userModel', '');
      global.ZY.ui.renderModels(list);
      if (picked && list.some((m) => m.id === picked)) {
        global.ZY.Agent.setModel(picked, false);
      } else {
        const stalled = global.ZY.Agent.stalled || new Set();
        const healthy = list.filter((m) => !stalled.has(m.id));
        const rank = (id) => { const i = FAST_INDEX.indexOf(id); return i === -1 ? 99 : i; };
        const preferred = healthy.slice().sort((a, b) => rank(a.id) - rank(b.id))[0] || list[0];
        global.ZY.Agent.setModel(preferred.id, false);
      }
      $('model-label').textContent = (list.find((m) => m.id === global.ZY.Agent.model) || list[0]).name;
    },

    bindComposer() {
      const input = $('input');
      const grow = () => { input.style.height = 'auto'; input.style.height = Math.min(120, input.scrollHeight) + 'px'; };
      input.addEventListener('input', grow);
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey && innerWidth > 720) { e.preventDefault(); App.submit(input.value); }
      });
      $('btn-send').addEventListener('click', () => App.submit(input.value));
      $('btn-stop').addEventListener('click', () => { global.ZY.Agent.stop(); global.ZY.ui.endStream(); App.setBusy(false); });
    },
    async submit(text) {
      text = (text || '').trim();
      const atts = global.ZY.Agent.attachments || [];
      // Media alone is a complete message. Requiring text meant a user who
      // picked a video and pressed send saw nothing happen at all.
      if (!text && !atts.length) return;
      if (!text && atts.length) {
        text = atts.some((a) => a.kind === 'video') ? 'এই ভিডিওটা দেখো'
          : atts.some((a) => a.kind === 'audio') ? 'এই অডিওটা শোনো'
            : 'এই ছবিটা দেখো';
      }
      $('input').value = ''; $('input').style.height = 'auto';
      // If the agent is mid-task, Agent.send queues the words instead of
      // starting a second turn — don't flip the UI back to idle in that case.
      if (global.ZY.Agent.busy) {
        $('artifact').hidden = true;
        await global.ZY.Agent.send(text);
        App.setBusy(false);
        return;
      }
      $('artifact').hidden = true;
      global.ZY.VOICE.TTS.stop();
      App.setBusy(true);
      global.ZY.ORB.setState('thinking');
      // Show the wait immediately: the upload + model read is the slow part and
      // the user needs to see that the media really left the device.
      global.ZY.ui.processing(true, atts);
      try {
        await global.ZY.Agent.send(text);
      } finally {
        global.ZY.ui.processing(false);
        App.setBusy(false);
      }
    },
    setBusy(b) {
      // While working, keep BOTH buttons: stop (to abort) and send (to inject a
      // new instruction mid-task). Hiding send would make the user unable to
      // steer the agent — the exact behaviour the user asked us to remove.
      $('btn-send').hidden = false;
      $('btn-stop').hidden = !b;
      document.querySelector('.composer-inner').classList.toggle('busy', b);
    },

    bindNative() {
      const n = global.ZY.Native;
      if (!n || !n.app) return;
      document.documentElement.classList.add('is-app');
      const info = n.info || {};
      global.ZY.ui.toast(`Android ${info.release || '?'} • TTS ${info.tts ? '✓' : '✗'} • STT ${info.stt ? '✓' : '✗'}`);

      // surface real device events into the chat as they happen
      n.device.onEvent((p) => {
        if (p.state === 'accessibility') { global.ZY.ui.toast('Accessibility on ✓'); App.pushSystem('⚙️ Accessibility service started — taps, typing and app launches are real now.'); }
        if (p.state === 'appOpened') App.pushSystem(`📱 App opened: ${p.text}`);
        if (p.state === 'appMissing') App.pushSystem(`❓ App not found: ${p.text}`);
      });
      // The background screen watch reports where the user went on its own
      // channel. This never reaches the screen as a bubble — model context only.
      n.on('screen', (p) => {
        if (p.state === 'changed') {
          const [pkg, label] = String(p.text || '').split('\u0001');
          global.ZY.Screen.onScreenChanged(pkg, label);
        } else if (p.state === 'text') {
          global.ZY.Screen.onScreenText(p.text || '');
        }
      });
      // screenshots come back asynchronously — hand them to the visual model.
      // A "bg" frame is the silent background watch: it goes into the model's
      // context and is never shown, never saved to the gallery.
      n.device.onShot((p) => {
        if (p.state === 'ok' && p.text) App.showScreenshot(p.text);
        else if (p.state === 'bg' && p.text) global.ZY.Screen.onFrame(p.text);
      });
      // Re-arm the watch when the app comes back to the front, so awareness
      // survives being backgrounded.
      document.addEventListener('visibilitychange', () => {
        if (!document.hidden && global.ZY.Screen.available()) global.ZY.Screen.start();
      });
      n.on('perm', (p) => {
        if (p.state === 'granted') global.ZY.ui.toast(global.ZY.t('perm.granted'));
      });
    },

    pushSystem(text) {
      global.ZY.ui.addToolRow('system', {}, 'ok', { ok: true, summary: text });
      global.ZY.DB.remember('event:' + Date.now(), text, 'event');
    },

    showScreenshot(b64) {
      try {
        const url = 'data:image/jpeg;base64,' + b64;
        const host = document.getElementById('artifact-body');
        if (host) {
          host.innerHTML = '';
          const img = document.createElement('img');
          img.src = url;
          img.style.maxWidth = '100%';
          host.appendChild(img);
          document.getElementById('artifact-title').textContent = 'Screenshot';
          document.getElementById('artifact').hidden = false;
        }
        global.ZY.Agent.lastShot = url;
      } catch {}
    },

    /** Open the mic and keep it open. Called at boot and after every reply. */
    armMic() {
      const stt = global.ZY.VOICE.STT;
      if (!stt.available()) return;
      if (store().get('alwaysMic', true)) stt.start();
    },

    bindVoice() {
      const btn = $('orb-btn');
      const stt = global.ZY.VOICE.STT;
      const TTS = global.ZY.VOICE.TTS;

      stt.onState = (s) => {
        btn.classList.toggle('live', s === 'listening');
        $('voice-hall').classList.toggle('listening', s === 'listening');
        const badge = $('live-badge');
        badge.textContent = s === 'listening' ? global.ZY.t('status.listening')
          : s === 'denied' ? global.ZY.t('status.mic_denied')
            : s === 'thinking' ? global.ZY.t('status.thinking')
              : global.ZY.t('status.ready');
        if (s === 'denied') global.ZY.ui.toast(global.ZY.t('toast.mic_blocked'));
      };
      // What the user says must be visible as it arrives. Interim words go into
      // the composer so the user sees their own speech appear while they talk,
      // and the caption under the orb mirrors it. Echo is filtered *before*
      // anything is written, which is what stops the assistant's own voice from
      // appearing in the chat box and sending itself.
      stt.onPartial = (t) => {
        const text = String(t || '');
        if (TTS.isEcho(text)) { global.ZY.ui.setLive(''); return; }
        global.ZY.ui.setLive(text);
        const box = $('input');
        // Never clobber words the user is typing by hand.
        if (box && document.activeElement !== box) {
          box.value = text;
          box.dispatchEvent(new Event('input', { bubbles: true }));
        }
      };
      stt.onFinal = (t) => {
        const text = (t || '').trim();
        // Drop the assistant's own voice instead of posting it back as a prompt.
        if (TTS.isEcho(text)) { global.ZY.ui.setLive(''); return; }
        global.ZY.ui.setLive(text || '…');
        // The user's own voice does not cut her off here. Whatever she is
        // reading finishes until the reply to these new words actually starts;
        // Agent.send sets that hand-off. Only echo (her own voice) is dropped.
        if (text) App.submit(text);
      };

      // Tapping the orb no longer toggles the mic on and off. It is already on;
      // a tap just re-arms it if the OS ever reclaimed it.
      btn.addEventListener('click', async () => {
        if (!stt.available()) {
          await global.ZY.ui.permissionSheet({ kind: 'microphone', label: 'Microphone', real: true });
          return;
        }
        stt.start();
        global.ZY.ui.toast(global.ZY.t('toast.mic_on'));
      });

      // speaker
      $('voice-hall').addEventListener('dblclick', () => TTS.stop());
      $('char-chip').addEventListener('click', () => global.ZY.ui.openDrawer('characters'));
      $('live-caption').addEventListener('click', () => TTS.stop());
      $('speak-box').addEventListener('click', () => TTS.stop());
      global.ZY.ORB.onState((s) => {
        const h = $('voice-hall');
        h.classList.toggle('speaking', s === 'speaking');
        h.classList.toggle('thinking', s === 'thinking');
      });
    },

    bindQuickbar() {
      document.querySelectorAll('.quickbar button').forEach((b) => {
        if (b.id === 'btn-cam') return;   // camera has its own handler below
        b.addEventListener('click', () => App.submit(b.dataset.q));
      });
      // The camera button grabs a real frame and sends it as an attachment, so
      // the multimodal model looks at it itself — "what am I eating?" works.
      const cam = $('btn-cam');
      if (cam) cam.addEventListener('click', () => App.shootCamera());
    },

    /**
     * The camera is not a tool call. The user asked for exactly this: once they
     * allow the camera, it simply turns on and its frames ride along with the
     * conversation — she can see what the phone sees without anyone calling a
     * tool. Pressing the button again turns the eye off.
     */
    async shootCamera() {
      const ui = global.ZY.ui;
      const S = global.ZY.Screen;
      if (S.cam.on) {
        S.camStop();
        ui.toast('ক্যামেরা বন্ধ করলাম');
        ui.camState && ui.camState(false);
        return;
      }
      // One tap here is the permission request; there is nothing else to grant.
      const ok = await ui.permissionSheet({ kind: 'camera', label: global.ZY.t('perm.camera'), real: true });
      if (!ok) return;
      const on = await S.camStart('environment');
      ui.camState && ui.camState(on);
      if (on) {
        ui.toast('ক্যামেরা চালু — আমি এখন যা দেখছি সেটাই দেখতে পাচ্ছি');
        App.submit('ক্যামেরায় এখন যা দেখছ সেটা দেখে বলো কী আছে');
      } else {
        ui.toast('ক্যামেরা চালু হলো না — অনুমতি দাও নি মনে হয়');
      }
    },

    /** Attach images / video / audio and send them to the model as real parts. */
    bindAttachments() {
      const tray = $('attach-tray');
      const fileInput = $('file-input');
      const render = () => {
        const atts = global.ZY.Agent.attachments || [];
        tray.hidden = !atts.length;
        tray.innerHTML = atts.map((a, i) => `
          <div class="att" data-i="${i}">
            ${a.kind === 'image' ? `<img src="${a.dataUrl}" alt="" />` : a.kind === 'video' ? `<video src="${a.dataUrl}" muted></video>` : '🎵'}
            <button class="x" data-rm="${i}">✕</button>
          </div>`).join('');
        $('btn-attach').classList.toggle('has', !!atts.length);
        tray.querySelectorAll('[data-rm]').forEach((b) => b.addEventListener('click', () => {
          global.ZY.Agent.attachments.splice(Number(b.dataset.rm), 1);
          render();
        }));
      };
      $('btn-attach').addEventListener('click', () => fileInput.click());
      fileInput.addEventListener('change', async () => {
        for (const f of fileInput.files) {
          if (f.size > 24 * 1024 * 1024) { global.ZY.ui.toast(global.ZY.t('toast.file_big')); continue; }
          const dataUrl = await new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(f); });
          const kind = /^image\//.test(f.type) ? 'image' : /^video\//.test(f.type) ? 'video' : 'audio';
          const fmt = kind === 'audio' ? (f.type.split('/')[1] || 'wav').split(';')[0] : undefined;
          global.ZY.Agent.attachments.push({ kind, dataUrl, name: f.name, format: fmt });
        }
        fileInput.value = '';
        render();
        global.ZY.ui.toast(global.ZY.t('toast.attached'));
      });
      // Let the camera button refresh the same tray without duplicating it.
      App._renderAttachTray = render;
    },

    /** Fill the bottom-left badge with the version this build really is.
     *  On Android the package manager is authoritative (the APK cannot lie about
     *  itself); on desktop/web the server answers. Never blocks boot, and stays
     *  quiet if neither knows — a wrong version is worse than none. */
    showVersion() {
      const el = $('app-version');
      if (!el) return;
      const paint = (v) => { if (v) el.textContent = 'v' + String(v).replace(/^v/i, ''); };
      const native = global.ZY.Native.settings.appVersion && global.ZY.Native.settings.appVersion();
      if (native) return paint(native);
      fetch('/api/version').then((r) => (r.ok ? r.json() : null)).then((d) => d && paint(d.version)).catch(() => {});
    },

    bindSettings() {
      const s = store();
      const bind = (id, key, kind) => {
        const el = $(id);
        if (!el) return;
        if (kind === 'check') { el.checked = !!s.get(key, el.checked); el.addEventListener('change', () => s.set(key, el.checked)); }
        else if (kind === 'range') { el.value = s.get(key, el.value); el.addEventListener('input', () => s.set(key, Number(el.value))); }
        else { el.value = s.get(key, el.value); el.addEventListener('change', () => s.set(key, el.value)); }
      };
      bind('set-tts', 'tts', 'check');
      bind('set-alwaysmic', 'alwaysMic', 'check');
      bind('set-autosend', 'autosend', 'check');
      bind('set-rate', 'rate', 'range');
      bind('set-pitch', 'pitch', 'range');
      bind('set-glass', 'glass', 'range');
      bind('set-bg-speed', 'bgSpeed', 'range');
      bind('set-bg-sound', 'bgSound', 'check');
      bind('set-bg-vol', 'bgVol', 'range');
      bind('set-bg-url', 'bgUrl', 'text');
      bind('set-base', 'base', 'text');
      bind('set-proxy', 'proxy', 'text');
      bind('set-brand', 'brand', 'text');

      // Always-on mic: turning it off is the only way the microphone closes.
      $('set-alwaysmic').addEventListener('change', () => {
        if ($('set-alwaysmic').checked) App.armMic();
        else global.ZY.VOICE.STT.stop();
      });

      // App language + recognition language, both built from the i18n table.
      const langs = global.ZY.I18N.langs;
      $('set-ui-lang').innerHTML = langs.map((l) => `<option value="${l.code}">${l.name}</option>`).join('');
      $('set-ui-lang').value = global.ZY.I18N.code;
      $('set-ui-lang').addEventListener('change', () => {
        const code = $('set-ui-lang').value;
        s.set('uiLang', code);
        global.ZY.I18N.init(code);
        // The mic is NOT tied to the interface language: the user speaks many
      // languages and the recogniser must follow them, not the menus.
      global.ZY.VOICE.STT.setLang(store().get('sttLang', ''));
        $('set-stt-lang').value = global.ZY.VOICE.STT.lang;
        global.ZY.ui.renderChars();
        global.ZY.ui.toast(global.ZY.t('toast.saved'));
      });

      $('set-stt-lang').innerHTML = '<option value="">' + global.ZY.t('set.auto') + '</option>'
        + langs.map((l) => `<option value="${l.stt}">${l.name}</option>`).join('');
      $('set-stt-lang').value = store().get('sttLang', '');
      $('set-stt-lang').addEventListener('change', () => {
        const tag = $('set-stt-lang').value;
        s.set('sttLang', tag);
        global.ZY.VOICE.STT.setLang(tag);
        global.ZY.ui.toast(global.ZY.t('toast.saved'));
      });

      global.ZY.VOICE.TTS.enabled = !!s.get('tts', true);
      $('set-tts').addEventListener('change', () => { global.ZY.VOICE.TTS.enabled = $('set-tts').checked; if (!global.ZY.VOICE.TTS.enabled) global.ZY.VOICE.TTS.stop(); });
      $('set-rate').addEventListener('input', () => { global.ZY.VOICE.TTS.rate = Number($('set-rate').value); });
      $('set-pitch').addEventListener('input', () => { global.ZY.VOICE.TTS.pitch = Number($('set-pitch').value); });
      $('set-glass').addEventListener('input', () => document.documentElement.style.setProperty('--glass-blur', $('set-glass').value + 'px'));
      document.documentElement.style.setProperty('--glass-blur', (s.get('glass', 14)) + 'px');
      $('set-bg').addEventListener('change', () => global.ZY.BG.set($('set-bg').value, $('set-bg-url').value));
      $('set-bg-url').addEventListener('change', () => { s.set('bg', 'custom'); $('set-bg').value = 'custom'; global.ZY.BG.set('custom', $('set-bg-url').value); });
      $('set-bg-speed').addEventListener('input', () => global.ZY.BG.setSpeed(Number($('set-bg-speed').value)));
      global.ZY.BG.setVolume(Number(s.get('bgVol', 0.55)));
      $('set-bg-vol').addEventListener('input', () => global.ZY.BG.setVolume(Number($('set-bg-vol').value)));
      $('set-bg-sound').addEventListener('change', () => {
        global.ZY.BG.setSound($('set-bg-sound').checked);
        if ($('set-bg-sound').checked) global.ZY.ui.toast(global.ZY.t('toast.bg_sound'));
      });
      $('set-base').addEventListener('change', () => global.ZY.ui.toast(global.ZY.t('toast.settings_saved')));
      $('set-brand').addEventListener('change', () => { const v = $('set-brand').value; document.title = v || 'ZYRAXON'; });

      // orb animation style picker — the user asked for several to choose from
      const orbSel = $('set-orb');
      orbSel.innerHTML = global.ZY.ORB.styles.map((st) => `<option value="${st.id}">${st.name}</option>`).join('');
      // Default must be a style that exists: a stale 'halo' default left the
      // picker blank and setStyle() a no-op, so the orb never changed look.
      orbSel.value = s.get('orbStyle', 'pink');
      global.ZY.ORB.setStyle(orbSel.value);
      orbSel.addEventListener('change', () => { s.set('orbStyle', orbSel.value); global.ZY.ORB.setStyle(orbSel.value); });
      $('set-orb-cycle').addEventListener('click', () => {
        const styles = global.ZY.ORB.styles;
        const i = styles.findIndex((st) => st.id === global.ZY.ORB.style);
        const next = styles[(i + 1) % styles.length];
        orbSel.value = next.id; s.set('orbStyle', next.id); global.ZY.ORB.setStyle(next.id);
        global.ZY.ui.toast(global.ZY.t('toast.style') + next.name);
      });
      App.bindAttachments();

      // voice list + test
      const fillVoices = () => {
        const vs = global.ZY.VOICE.TTS.load();
        const sel = $('set-voice');
        if (!vs.length) { sel.innerHTML = '<option>' + global.ZY.t('set.auto') + '</option>'; return; }
        sel.innerHTML = vs.map((v) => `<option value="${v.name}">${v.name} (${v.lang})</option>`).join('');
        const cur = global.ZY.VOICE.TTS.voice || global.ZY.VOICE.TTS.pickVoice();
        if (cur) sel.value = cur.name;
      };
      fillVoices();
      if ('speechSynthesis' in global) speechSynthesis.onvoiceschanged = fillVoices;
      $('set-voice').addEventListener('change', () => global.ZY.VOICE.TTS.setVoiceByName($('set-voice').value));
      $('set-voice-test').addEventListener('click', () => global.ZY.VOICE.TTS.test());

      // "open in the browser" — the same page is already served on a local
      // port, so the user's own Chrome can run it with their own voices and
      // can outlive this window. In a browser already, a new tab is enough.
      $('set-open-browser').addEventListener('click', async () => {
        const here = location.origin;
        if (!global.ZY.Native || !global.ZY.Native.app) {
          global.open(here, '_blank', 'noopener');
          return;
        }
        const base = (global.ZY.store.get('base') || here).replace(/\/$/, '');
        global.ZY.ui.toast(global.ZY.t('toast.browser_open'));
        try {
          const r = await fetch(base + '/api/open-browser', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: base }),
          });
          const j = await r.json();
          if (!j.ok) global.ZY.ui.toast(global.ZY.t('toast.browser_none'));
        } catch {
          global.open(here, '_blank', 'noopener');
        }
      });

      // memory pane actions
      $('mem-add').addEventListener('click', async () => {
        const k = $('mem-key').value.trim(), v = $('mem-val').value.trim();
        if (!k || !v) return global.ZY.ui.toast(global.ZY.t('toast.mem_need'));
        await global.ZY.DB.remember(k, v, 'manual'); $('mem-key').value = ''; $('mem-val').value = '';
        global.ZY.ui.renderMemory();
      });
      $('mem-export').addEventListener('click', async () => {
        const data = await global.ZY.DB.exportAll();
        const b = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
        const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = 'zyraxon-memory.json'; a.click();
      });
      $('mem-clear').addEventListener('click', async () => { await global.ZY.DB.clear('memory'); global.ZY.ui.renderMemory(); global.ZY.ui.toast(global.ZY.t('toast.mem_cleared')); });
      $('hist-clear').addEventListener('click', async () => { await global.ZY.DB.clear('messages'); $('chat').innerHTML = ''; global.ZY.ui.toast(global.ZY.t('toast.chat_cleared')); });

      // extra drawer entries injected into topbar menu button
      $('btn-menu').addEventListener('click', () => {
        global.ZY.ui.openDrawer('settings');
      });
      App.injectMenuShortcuts();
    },

    injectMenuShortcuts() {
      const body = $('drawer-body');
      const nav = document.createElement('div');
      nav.className = 'group';
      nav.style.flexDirection = 'row';
      nav.style.flexWrap = 'wrap';
      nav.innerHTML = ['settings', 'permissions', 'characters', 'memory', 'history', 'skills']
        .map((k) => `<button class="ghost-btn" data-nav="${k}">${global.ZY.t('set.' + (k === 'skills' ? 'skills' : k))}</button>`).join('');
      body.prepend(nav);
      nav.querySelectorAll('[data-nav]').forEach((b) => b.addEventListener('click', () => global.ZY.ui.openDrawer(b.dataset.nav)));
    },

    /** Build the user message, folding in any attached media as real parts. */
    _buildUserMessage(text) { return global.ZY.Agent._buildUserMessage(text); },
  };

  global.ZY.App = App;
  document.addEventListener('DOMContentLoaded', () => App.boot());
})(window);
