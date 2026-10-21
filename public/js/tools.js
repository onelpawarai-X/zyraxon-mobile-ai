/* ══════════════════════════════════════════════════════════════
   ZYRAXON Tools — REAL execution, no simulation.

   Two layers:
     • WEB layer   — everything a PWA can really do (clipboard,
                     notifications, share, URL fetch, website
                     publish, shell, files, memory, capture).
     • NATIVE layer— a thin bridge to the Android shell. Works with
                     Capacitor, a raw addJavascriptInterface object,
                     or postMessage. When no bridge is present the
                     tool says so honestly and offers the closest
                     real alternative — it never pretends.

   Every tool returns { ok, summary, data } and is verified by the
   caller before a todo is marked done.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const API = () => (global.ZY.store.get('base') || location.origin).replace(/\/$/, '');
  const BRIDGE_TIMEOUT = 12000;

  /** One call shape for every filesystem operation (server does the real work). */
  async function fsCall(body) {
    try {
      const r = await fetch(`${API()}/api/fs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return await r.json();
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  }

  /* ─────────────────── native bridge ─────────────────── */
  const Native = {
    // ZY. Native is the real Android bridge injected by the APK (native.js).
    _zy() { return (global.ZY && global.ZY.Native && global.ZY.Native.app) ? global.ZY.Native : null; },
    available() { return !!(this._zy() || this._cap() || this._js() || this._msg()); },
    kind() {
      if (this._zy()) return 'zyraxon-native';
      return this._cap() ? 'capacitor' : this._js() ? 'javascript-interface' : this._msg() ? 'postmessage' : 'none';
    },
    _cap() { return global.Capacitor && global.Capacitor.Plugins && (global.Capacitor.Plugins.ZYRAXON || global.Capacitor.Plugins.Zyraxon); },
    _js() { return global.ZYRAXONAndroid || global.ZYRAXONBridge || null; },
    _msg() { return global.ReactNativeWebView || (global.ZYRAXON || {}).native ? true : null; },

    async call(method, params = {}) {
      const zy = this._zy();
      if (zy) return Native._zyCall(zy, method, params);
      const cap = this._cap();
      if (cap) {
        const plugin = cap.ZYRAXON || cap.Zyraxon;
        try { if (plugin[method]) return await plugin[method](params); } catch (e) { return { error: String(e) }; }
      }
      const js = this._js();
      if (js) {
        try {
          const raw = js[method] ? js[method](JSON.stringify(params)) : js.invoke(method, JSON.stringify(params));
          const out = typeof raw === 'string' ? JSON.parse(raw) : raw;
          return out && out.__promise ? await out.__promise : out;
        } catch (e) { return { error: String(e) }; }
      }
      if (global.ReactNativeWebView && global.ReactNativeWebView.postMessage) {
        return await new Promise((resolve) => {
          const id = 'zy' + Math.random().toString(36).slice(2);
          const handler = (ev) => {
            try { const m = JSON.parse(ev.data); if (m && m.id === id) { removeEventListener('message', handler); resolve(m.result); } } catch {}
          };
          addEventListener('message', handler);
          global.ReactNativeWebView.postMessage(JSON.stringify({ id, method, params }));
          setTimeout(() => { removeEventListener('message', handler); resolve({ error: 'native timeout' }); }, BRIDGE_TIMEOUT);
        });
      }
      return { error: 'no-native-bridge' };
    },

    /** Map the tool vocabulary onto the concrete Android bridge calls. */
    _zyCall(zy, method, p) {
      const d = zy.device;
      switch (method) {
        case 'openApp':
          d.openApp(p.package || p.name || '');
          return { ok: true, via: 'zyraxon-native' };
        case 'launchUrl':
          return { ok: d.launchUrl(p.url || '') };
        case 'tap':
          return { ok: d.tap(p.x || 0, p.y || 0) };
        case 'tapText':
          return { ok: d.tapText(p.text || '') };
        case 'typeText':
          return { ok: d.typeText(p.text || '') };
        case 'swipe':
          return { ok: d.swipe ? d.swipe(p.x1 || 0, p.y1 || 0, p.x2 || 0, p.y2 || 0) : false };
        case 'scroll':
          return { ok: d.scroll(p.forward !== false) };
        case 'pressKey':
          return { ok: d.action(p.key || p.action || 'back') };
        case 'readScreen': {
          const lines = d.readScreen();
          return { ok: true, lines, accessibility: zy.settings.accessibilityOn() };
        }
        case 'screenShot':
          d.screenShot();
          return { ok: true, pending: true };
        case 'listApps':
          return { ok: true, apps: d.listApps() };
        default:
          return { error: 'unknown-native-method:' + method };
      }
    },
  };

  /* ─────────────────── permission helper ─────────────────── */
  async function askPermission(kind, label) {
    if (global.ZY.ui && global.ZY.ui.permissionSheet) {
      return global.ZY.ui.permissionSheet({ kind, label });
    }
    return true;
  }

  async function ensureWebPerm(name) {
    if (name === 'notifications' && 'Notification' in global) {
      if (Notification.permission === 'granted') return true;
      if (Notification.permission === 'denied') return false;
      if (!(await askPermission('notifications', 'নোটিফিকেশন'))) return false;
      return (await Notification.requestPermission()) === 'granted';
    }
    if (name === 'geolocation' && navigator.geolocation) return true;
    return true;
  }

  /* ─────────────────── tool implementations ─────────────────── */
  const impl = {
    async todo_write(args) {
      const todos = Array.isArray(args.todos) ? args.todos : [];
      global.ZY.store.set('todos', todos);
      await global.ZY.DB.saveTodos(todos);
      if (global.ZY.ui) global.ZY.ui.renderTodos(todos);
      const done = todos.filter((t) => t.status === 'done').length;
      return { ok: true, summary: `${done}/${todos.length} tasks done`, data: { todos } };
    },

    async open_app(args) {
      const pkg = args.package || '';
      const name = args.name || pkg;
      const map = {
        facebook: 'com.facebook.katana', messenger: 'com.facebook.orca', whatsapp: 'com.whatsapp',
        instagram: 'com.instagram.android', youtube: 'com.google.android.youtube',
        camera: 'com.android.camera2', chrome: 'com.android.chrome', settings: 'com.android.settings',
        gmail: 'com.google.android.gm', maps: 'com.google.android.apps.maps', playstore: 'com.android.vending',
        telegram: 'org.telegram.messenger', tiktok: 'com.zhiliaoapp.musically',
      };
      const target = pkg || map[String(name).toLowerCase()] || String(name).toLowerCase();

      if (Native.available()) {
        // Ask the phone first. Claiming an app opened when it did not is the
        // exact failure the user complained about: "a video that isn't there
        // yet it gets opened". Only a resolved package may be reported open.
        // This must be the real injected bridge (global.ZY.Native) — the local
        // Native shim above has no `device`, so using it here threw a TypeError
        // and open_app could never succeed on a device.
        const dev = global.ZY.Native && global.ZY.Native.device;
        const res = (dev && dev.resolveApp(target)) || {};
        if (!res.found) {
          return {
            ok: false,
            error: 'app-not-installed',
            summary: `"${name}" এই ফোনে ইনস্টল করা নেই — খোলা হয়নি। অন্য অ্যাপের নাম বলো, অথবা Play Store থেকে ইনস্টল করতে বলো।`,
            data: { package: target, found: false },
          };
        }
        dev.openApp(res.package || target);
        return { ok: true, summary: `${res.label || name} খোলা হয়েছে (native)`, data: { via: 'native', package: res.package || target, label: res.label || name } };
      }
      // Android Chrome understands intent: URLs — this really launches the app.
      const ua = navigator.userAgent;
      if (/Android/i.test(ua) && target.includes('.')) {
        const intent = `intent://#Intent;package=${target};end`;
        const a = document.createElement('a'); a.href = intent; a.style.display = 'none';
        document.body.appendChild(a); a.click(); a.remove();
        return { ok: true, summary: `${name} লঞ্চ করার চেষ্টা হলো (intent)`, data: { via: 'intent', package: target } };
      }
      const web = { facebook: 'https://m.facebook.com', messenger: 'https://m.me', whatsapp: 'https://web.whatsapp.com', youtube: 'https://m.youtube.com', instagram: 'https://www.instagram.com', telegram: 'https://web.telegram.org' };
      const url = web[String(name).toLowerCase()];
      if (url) { impl.launch_url({ url, new_tab: true }); return { ok: true, summary: `${name} ওয়েব ভার্সনে খোলা হলো`, data: { via: 'web', url } }; }
      return { ok: false, error: `${name} খোলা যায়নি — ডিভাইস ব্রিজ নেই`, summary: `“${name}” ওপেন করা যায়নি (native ব্রিজ লাগবে)` };
    },

    /**
     * Open a URL — but never pretend a dead link opened. A HEAD/GET check runs
     * first so a broken or missing video/page is reported honestly instead of
     * launching the browser at nothing, which is what the user complained about.
     */
    async launch_url(args) {
      const url = args.url || 'https://www.google.com';
      if (!/^https?:\/\//i.test(url)) {
        return { ok: false, error: 'bad-url', summary: `"${url}" একটি বৈধ লিংক নয় — খোলা হয়নি।` };
      }
      // Verify the link really resolves before opening it. Local/relative links
      // skip the check (they are ours), everything on the web is checked.
      let reachable = true, status = 0;
      try {
        const r = await fetch(`${API()}/api/check-url`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) });
        const j = await r.json();
        reachable = !!j.ok; status = j.status || 0;
      } catch (e) { reachable = true; }   // server unreachable: do not block the user
      if (!reachable) {
        return {
          ok: false,
          error: 'url-unreachable',
          summary: `${url} খোলা যাচ্ছে না — লিংকটি নেই বা কাজ করছে না (status ${status || '?'})। খোলা হয়নি।`,
          data: { url, status },
        };
      }
      const w = args.new_tab !== false ? global.open(url, '_blank') : (location.href = url);
      return { ok: true, summary: `${url} খোলা হলো${status ? ` (${status})` : ''}`, data: { url, popup: !!w, status } };
    },

    async read_screen() {
      if (Native.available()) {
        const r = await Native.call('readScreen', {});
        if (r && !r.error) return { ok: true, summary: 'স্ক্রিন পড়া হয়েছে (native)', data: r };
      }
      if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
        const granted = await askPermission('screen', 'স্ক্রিন ক্যাপচার');
        if (granted) {
          try {
            const stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 2 }, audio: false });
            const track = stream.getVideoTracks()[0];
            const vid = document.createElement('video'); vid.srcObject = stream; vid.muted = true; await vid.play();
            await new Promise((r) => setTimeout(r, 350));
            const cv = document.createElement('canvas'); cv.width = vid.videoWidth || 720; cv.height = vid.videoHeight || 1280;
            cv.getContext('2d').drawImage(vid, 0, 0);
            const dataUrl = cv.toDataURL('image/jpeg', 0.7);
            track.stop();
            return { ok: true, summary: 'স্ক্রিন ক্যাপচার হয়েছে', data: { dataUrl, width: cv.width, height: cv.height, note: 'ছবি সংযুক্ত হয়েছে — multimodal মডেল দেখতে পারবে' } };
          } catch (e) {
            return { ok: false, error: 'screen-capture-denied', summary: 'স্ক্রিন অনুমতি পাওয়া যায়নি', data: { hint: 'উপরের Allow চাপো, অথবা অ্যাপে অ্যাক্সেসিবিলিটি সার্ভিস চালু করো' } };
          }
        }
      }
      return { ok: false, error: 'no-screen-access', summary: 'এই ডিভাইসে স্ক্রিন অ্যাক্সেস নেই (APK + Accessibility দরকার)', data: { hint: 'APK-এ অ্যাক্সেসিবিলিটি সার্ভিস দিলে সরাসরি স্ক্রিন পড়া যাবে' } };
    },

    /**
     * Look through the phone's camera. The user asked to point the camera at
     * something — food, a sign, a screen — and have her say what she sees. The
     * frame goes back as a data URL so the multimodal model looks at it itself.
     */
    async look_camera(args) {
      const facing = args && args.facing === 'front' ? 'user' : 'environment';
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        return { ok: false, error: 'no-camera-api', summary: 'এই ডিভাইসে ক্যামেরা API নেই', data: { hint: 'APK ইনস্টল করলে ক্যামেরা কাজ করবে' } };
      }
      const granted = await askPermission('camera', 'ক্যামেরা');
      if (!granted) return { ok: false, error: 'camera-denied', summary: 'ক্যামেরার অনুমতি দেওয়া হয়নি', data: { hint: 'উপরের Allow চাপো' } };
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: facing }, audio: false });
      } catch (e) {
        return { ok: false, error: 'camera-denied', summary: 'ক্যামেরা খোলা যায়নি', data: { hint: String(e) } };
      }
      try {
        const vid = document.createElement('video');
        vid.srcObject = stream; vid.muted = true; vid.playsInline = true;
        await vid.play();
        // The sensor needs a moment to expose the first real frame.
        await new Promise((r) => setTimeout(r, 450));
        const cv = document.createElement('canvas');
        cv.width = vid.videoWidth || 720;
        cv.height = vid.videoHeight || 1280;
        cv.getContext('2d').drawImage(vid, 0, 0, cv.width, cv.height);
        const dataUrl = cv.toDataURL('image/jpeg', 0.8);
        return { ok: true, summary: `${facing === 'user' ? 'সামনের' : 'পিছনের'} ক্যামেরার ছবি নেওয়া হয়েছে`, data: { dataUrl, modality: 'image', facing, width: cv.width, height: cv.height } };
      } finally {
        stream.getTracks().forEach((t) => t.stop());
      }
    },

    async list_apps() {
      const nat = global.ZY.Native;
      if (nat && nat.app) {
        const apps = nat.device.listApps();
        return { ok: apps.length > 0, summary: `${apps.length}টি অ্যাপ পাওয়া গেছে`, data: { apps } };
      }
      return { ok: false, error: 'no-native-bridge', summary: 'অ্যাপের তালিকা শুধু APK-তে পাওয়া যায়' };
    },

    /**
     * Is this image effectively blank (all one colour)? The user hit "white
     * screenshot" after opening an app: the frame was captured before the other
     * app had painted. A blank frame is not a usable look at the screen, so it is
     * retried rather than reported as success.
     */
    async _blankImage(dataUrl) {
      try {
        const img = new Image();
        await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = dataUrl; });
        const w = Math.min(160, img.naturalWidth || 160);
        const h = Math.min(320, img.naturalHeight || 320);
        const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
        const ctx = cv.getContext('2d');
        ctx.drawImage(img, 0, 0, w, h);
        const d = ctx.getImageData(0, 0, w, h).data;
        const first = [d[0], d[1], d[2]];
        for (let i = 4; i < d.length; i += 16) {
          if (Math.abs(d[i] - first[0]) > 6 || Math.abs(d[i + 1] - first[1]) > 6 || Math.abs(d[i + 2] - first[2]) > 6) return false;
        }
        return true;
      } catch { return false; }
    },

    async screen_shot() {
      const nat = global.ZY.Native;
      const grab = () => new Promise((res) => {
        let settled = false;
        nat.device.onShot((p) => {
          if (settled) return;
          if (p.state === 'ok') { settled = true; res({ ok: true, summary: 'স্ক্রিনশট নেওয়া হয়েছে', data: { dataUrl: 'data:image/jpeg;base64,' + p.text, modality: 'image' } }); }
          else if (p.state === 'error') { settled = true; res({ ok: false, error: p.text, summary: 'স্ক্রিনশট ব্যর্থ: ' + p.text }); }
        });
        nat.device.screenShot();
        setTimeout(() => { if (!settled) { settled = true; res({ ok: false, error: 'timeout', summary: 'স্ক্রিনশট সময় শেষ' }); } }, 12000);
      });
      if (nat && nat.app && nat.info.sdk >= 30) {
        // Give the newly opened app a moment to paint, then retry while blank.
        for (let i = 0; i < 4; i++) {
          const r = await grab();
          if (!r.ok) return r;
          if (!(await impl._blankImage(r.data.dataUrl))) return r;
          await new Promise((res) => setTimeout(res, 400 + i * 300));
        }
        return { ok: false, error: 'blank-screen', summary: 'স্ক্রিন এখনো সাদা — অ্যাপ পুরোপুরি খোলেনি', data: { hint: 'অ্যাপ খোলার পর একটু অপেক্ষা করে আবার screen_shot করো, অথবা read_screen দিয়ে টেক্সট পড়ো' } };
      }
      return impl.read_screen();
    },

    async _nativeTouch(args, method) {
      if (!Native.available()) return { ok: false, error: 'no-native-bridge', summary: 'ট্যাপ/স্বাইপের জন্য ডিভাইস ব্রিজ (APK) দরকার', data: { hint: 'অ্যাপ ইনস্টল করে অ্যাক্সেসিবিলিটি Allow করলে এটা রিয়েলি কাজ করবে' } };
      const r = await Native.call(method, args);
      if (r && !r.error) return { ok: true, summary: `${method} done`, data: r };
      return { ok: false, error: r && r.error, summary: `${method} ব্যর্থ` };
    },
    tap(args) { return impl._nativeTouch(args, 'tap'); },
    swipe(args) { return impl._nativeTouch(args, 'swipe'); },
    type_text(args) { return impl._nativeTouch(args, 'typeText'); },
    press_key(args) { return impl._nativeTouch(args, 'pressKey'); },

    async notify(args) {
      const title = args.title || 'ZYRAXON';
      const body = args.body || '';
      if (await ensureWebPerm('notifications')) {
        try { new Notification(title, { body, icon: '/assets/icon.svg', badge: '/assets/icon.svg' }); return { ok: true, summary: 'নোটিফিকেশন পাঠানো হলো', data: { title, body } }; }
        catch (e) { return { ok: false, error: String(e), summary: 'নোটিফিকেশন ব্যর্থ' }; }
      }
      return { ok: false, error: 'denied', summary: 'নোটিফিকেশন অনুমতি দেওয়া হয়নি' };
    },

    async clipboard_read() {
      try { const t = await navigator.clipboard.readText(); return { ok: true, summary: `ক্লিপবোর্ড পড়া হলো (${t.length} অক্ষর)`, data: { text: t } }; }
      catch { return { ok: false, error: 'clipboard-denied', summary: 'ক্লিপবোর্ড পড়া যায়নি (অনুমতি দরকার)' }; }
    },
    async clipboard_write(args) {
      try { await navigator.clipboard.writeText(args.text || ''); return { ok: true, summary: 'ক্লিপবোর্ডে লেখা হলো', data: { text: args.text } }; }
      catch { return { ok: false, error: 'clipboard-denied', summary: 'ক্লিপবোর্ডে লেখা যায়নি' }; }
    },

    async share(args) {
      const payload = { title: args.title || 'ZYRAXON', text: args.text || '', url: args.url || undefined };
      if (navigator.share) {
        try { await navigator.share(payload); return { ok: true, summary: 'শেয়ার শিট খোলা হলো', data: payload }; }
        catch (e) { if (String(e).includes('Abort')) return { ok: true, summary: 'শেয়ার বাতিল হয়েছে', data: payload }; return { ok: false, error: String(e), summary: 'শেয়ার ব্যর্থ' }; }
      }
      if (payload.url) { impl.launch_url({ url: payload.url }); return { ok: true, summary: 'লিংক ব্রাউজারে খোলা হলো', data: payload }; }
      return { ok: false, error: 'no-web-share', summary: 'এই ব্রাউজারে শেয়ার নেই' };
    },

    /**
     * Prove a link is real before you open it or tell the user about it. Call
     * this for any video, page or download URL you are about to use — it is the
     * difference between "খোলা হলো" and "লিংকটি নেই".
     */
    async verify_url(args) {
      const url = args.url || '';
      if (!/^https?:\/\//i.test(url)) return { ok: false, error: 'bad-url', summary: `"${url}" বৈধ http(s) লিংক নয়।` };
      try {
        const r = await fetch(`${API()}/api/check-url`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) });
        const j = await r.json();
        if (j.ok) return { ok: true, summary: `লিংক ঠিক আছে: ${url} (${j.status}${j.contentType ? ', ' + j.contentType : ''})`, data: j };
        return { ok: false, error: 'url-unreachable', summary: `লিংকটি নেই বা কাজ করছে না: ${url} (status ${j.status || '?'})`, data: j };
      } catch (e) {
        return { ok: false, error: String(e), summary: `লিংক যাচাই করা গেল না: ${url}` };
      }
    },

    async read_url(args) {
      const r = await fetch(`${API()}/api/fetch-url`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: args.url }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: `URL পড়া যায়নি: ${args.url}` };
      const text = (j.text || '').slice(0, args.max_chars || 12000);
      return { ok: true, summary: `${args.url} পড়া হলো (${(j.text || '').length} অক্ষর)`, data: { status: j.status, contentType: j.contentType, text } };
    },

    async read_media(args) {
      const r = await fetch(`${API()}/api/fetch-url`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: args.url }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: `মিডিয়া পড়া যায়নি: ${args.url}` };
      if (j.dataUrl) {
        const modality = args.modality || (/^audio/.test(j.contentType) ? 'audio' : /^video/.test(j.contentType) ? 'video' : /pdf/.test(j.contentType) ? 'pdf' : 'image');
        global.ZY._pendingMedia = { url: args.url, dataUrl: j.dataUrl, modality, part: args.part };
        return { ok: true, summary: `${modality} পড়া হলো (${(j.bytes / 1024).toFixed(0)}KB) — মডেলে পাঠানোর জন্য তৈরি`, data: { modality, bytes: j.bytes, part: args.part, note: 'পরের টার্নে এটি মডেলের ইনপুটে যুক্ত হবে' } };
      }
      return { ok: true, summary: `${args.url} টেক্সট হিসেবে পড়া হলো`, data: { text: (j.text || '').slice(0, 12000) } };
    },

    async generate_file(args) {
      const r = await fetch(`${API()}/api/write-file`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ path: args.path, content: args.content, mode: args.mode }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: `ফাইল লেখা যায়নি: ${args.path}` };
      return { ok: true, summary: `${args.path} লেখা হলো (${j.bytes} বাইট)`, data: j };
    },

    async publish_website(args) {
      const r = await fetch(`${API()}/api/publish`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: args.name, html: args.html, files: args.files }) });
      const j = await r.json().catch(() => ({}));
      if (!j.ok) return { ok: false, error: j.error || 'publish-failed', summary: 'পাবলিশ ব্যর্থ — সাইটটি সত্যিই সার্ভ হচ্ছে না (status ' + (j.status || '?') + ')। আবার চেষ্টা করো।', data: j };
      global.ZY.store.set('lastSite', j.url);
      return { ok: true, summary: `ওয়েবসাইট পাবলিশ হয়েছে ও ভেরিফাই হয়েছে: ${j.url}`, data: j };
    },

    async run_shell(args) {
      // A command is the universal fallback: when no dedicated tool fits, this
      // still does the job. On the phone it runs in the app's own Android
      // sandbox; everywhere else it runs on the machine hosting the server.
      const P = global.ZY.Platform;
      const nativeShell = global.ZY.Native && global.ZY.Native.shell && global.ZY.Native.shell.available();
      const preferNative = nativeShell && !(P && P.data && P.data.isDesktop && P.data.host);

      if (preferNative) {
        const j = global.ZY.Native.shell.exec(args.command, args.cwd, args.timeout_ms) || {};
        if (j.error) return { ok: false, error: j.error, summary: `ফোনে কমান্ড চালানো যায়নি: ${j.error}`, data: j };
        const ok = j.code === 0;
        const tail = String(j.stdout || '').trim().split('\n').slice(-6).join('\n').slice(0, 400);
        return { ok, summary: `[ফোন/Android sandbox] $ ${args.command} → exit ${j.code}${tail ? ' | ' + tail : ''}`, data: j };
      }

      const r = await fetch(`${API()}/api/shell`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ command: args.command, cwd: args.cwd, shell: args.shell, timeout_ms: args.timeout_ms }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: 'কমান্ড চালানো যায়নি' };
      const ok = j.code === 0;
      const tail = (j.stdout || '').trim().split('\n').slice(-6).join('\n').slice(0, 400);
      const where = (P && P.data && P.data.host && P.data.host.osName) ? P.data.host.osName : 'host';
      return { ok, summary: `[হোস্ট (${where})/${j.shell || 'shell'}] $ ${args.command} → exit ${j.code}${tail ? ' | ' + tail : ''}`, data: { code: j.code, stdout: j.stdout, stderr: j.stderr, shell: j.shell, cwd: j.cwd } };
    },

    /* ── filesystem: real reads, writes and in-place edits ── */
    async read_file(args) {
      const j = await fsCall({ op: 'read', path: args.path, from_line: args.from_line, to_line: args.to_line });
      if (!j.ok) return { ok: false, error: j.error, summary: `ফাইল পড়া যায়নি: ${args.path} (${j.error})` };
      if (j.text) {
        global.ZY._pendingMedia = { url: args.path, dataUrl: j.dataUrl, modality: 'auto' };
        return { ok: true, summary: `${args.path} একটি বাইনারি ফাইল (${j.bytes} বাইট) — মডেলের ইনপুটে যুক্ত হলো`, data: { bytes: j.bytes, binary: true } };
      }
      return { ok: true, summary: `${args.path} পড়া হলো — ${j.lines} লাইন, ${j.bytes} বাইট`, data: { path: j.path, lines: j.lines, bytes: j.bytes, content: j.content } };
    },

    async write_file(args) {
      const j = await fsCall({ op: 'write', path: args.path, content: args.content, append: args.mode === 'append' });
      if (!j.ok) return { ok: false, error: j.error, summary: `ফাইল লেখা যায়নি: ${args.path} (${j.error})` };
      return { ok: true, summary: `${args.path} ${j.created ? 'তৈরি' : 'আপডেট'} হলো (${j.bytes} বাইট)`, data: j };
    },

    async edit_file(args) {
      const j = await fsCall({ op: 'edit', path: args.path, find: args.find, replace: args.replace, all: args.all });
      if (!j.ok) return { ok: false, error: j.error, occurrences: j.occurrences, summary: `এডিট হলো না: ${args.path} — ${j.error}` };
      return { ok: true, summary: `${args.path} এডিট হলো (${j.replaced} জায়গায়)`, data: j };
    },

    async list_dir(args) {
      const j = await fsCall({ op: 'list', path: args.path || '.' });
      if (!j.ok) return { ok: false, error: j.error, summary: `ফোল্ডার পড়া যায়নি: ${args.path} (${j.error})` };
      const names = j.entries.slice(0, 80).map((e) => (e.dir ? e.name + '/' : e.name)).join(', ');
      return { ok: true, summary: `${j.path} — ${j.count}টি আইটেম: ${names}`, data: j };
    },

    async find_files(args) {
      const j = await fsCall({ op: 'glob', path: args.path || '.', pattern: args.pattern || '*' });
      if (!j.ok) return { ok: false, error: j.error, summary: `খোঁজা যায়নি: ${j.error}` };
      return { ok: true, summary: `${j.pattern} → ${j.count}টি ফাইল মিলেছে`, data: j };
    },

    async grep_files(args) {
      const j = await fsCall({ op: 'grep', path: args.path || '.', query: args.query, regex: args.regex, case_sensitive: args.case_sensitive });
      if (!j.ok) return { ok: false, error: j.error, summary: `grep ব্যর্থ: ${j.error}` };
      return { ok: true, summary: `${j.count}টি লাইনে "${args.query}" পাওয়া গেল`, data: j };
    },

    async delete_file(args) {
      const j = await fsCall({ op: 'delete', path: args.path, recursive: args.recursive });
      if (!j.ok) return { ok: false, error: j.error, summary: `মোছা যায়নি: ${args.path} (${j.error})` };
      return { ok: true, summary: `${args.path} মোছা হলো`, data: j };
    },

    /* ── web: real search and generic HTTP ── */
    async web_search(args) {
      const r = await fetch(`${API()}/api/web-search`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: args.query, count: args.count }) });
      const j = await r.json();
      if (!j.ok || !j.results || !j.results.length) return { ok: false, error: j.error || 'no-results', summary: `"${args.query}" — কিছু পাওয়া যায়নি` };
      const top = j.results.slice(0, 5).map((x, i) => `${i + 1}. ${x.title} — ${x.url}`).join('\n');
      return { ok: true, summary: `${j.results.length}টি ফলাফল (${j.engine}):\n${top}`, data: j };
    },

    async http_request(args) {
      const r = await fetch(`${API()}/api/http`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: args.url, method: args.method, headers: args.headers, body: args.body }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: `${args.method || 'GET'} ${args.url} ব্যর্থ: ${j.error}` };
      const brief = typeof j.data === 'string' ? j.data.slice(0, 200) : JSON.stringify(j.data).slice(0, 200);
      return { ok: j.ok, summary: `${args.method || 'GET'} ${args.url} → ${j.status} (${j.bytes} বাইট) ${brief}`, data: j };
    },

    /* ── desktop / host system ── */
    async sys_info() {
      const r = await fetch(`${API()}/api/sysinfo`);
      const j = await r.json();
      return { ok: true, summary: `${j.osName} ${j.release} ${j.arch}, CPU ${j.cpus && j.cpus.length ? j.cpus[j.cpus.length - 1] : '?'}, RAM ${j.freeMemMB}/${j.totalMemMB}MB ফাঁকা${j.disk ? `, ডিস্ক ${j.disk.freeGB}GB ফাঁকা` : ''}`, data: j };
    },

    async platform_info() {
      const p = global.ZY.Platform.data || global.ZY.Platform.detect();
      return { ok: true, summary: `আমি চলছি ${p.osName} (${p.kind}) — শেল ${(p.host && p.host.defaultShell) || p.shell}, নেটিভ ব্রিজ ${p.nativeBridge ? 'আছে' : 'নেই'}`, data: p };
    },

    async download_file(args) {
      const r = await fetch(`${API()}/api/http`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: args.url }) });
      const j = await r.json();
      if (j.error) return { ok: false, error: j.error, summary: `${args.url} নামানো যায়নি: ${j.error}` };
      const fallbackName = (args.url.split('/').pop() || 'file').split('?')[0] || 'file';
      const target = args.path || ('downloads/' + fallbackName);
      let wr;
      if (j.dataUrl) {
        // binary: the server hands back base64 in a data URL
        const b64 = j.dataUrl.split(',')[1] || '';
        wr = await fsCall({ op: 'write', path: target, content: b64 });
        if (wr.ok) return { ok: true, summary: `${args.url} → ${wr.path} (${Math.round(b64.length * 0.75 / 1024)}KB, base64)`, data: { ...wr, encoding: 'base64', bytes: Math.round(b64.length * 0.75) } };
      } else if (typeof j.data === 'string') {
        wr = await fsCall({ op: 'write', path: target, content: j.data });
        if (wr.ok) return { ok: true, summary: `${args.url} → ${wr.path} (${j.bytes} বাইট টেক্সট)`, data: { ...wr, encoding: 'utf8' } };
      } else {
        wr = await fsCall({ op: 'write', path: target, content: JSON.stringify(j.data) });
        if (wr.ok) return { ok: true, summary: `${args.url} → ${wr.path} (JSON)`, data: { ...wr, encoding: 'json' } };
      }
      return { ok: false, error: (wr && wr.error) || 'save-failed', summary: `সেভ করা যায়নি: ${(wr && wr.error) || 'unknown'}` };
    },

    async process_list() {
      const r = await fetch(`${API()}/api/sysinfo`);
      const j = await r.json();
      return { ok: true, summary: `চলমান প্রসেস (${(j.processes || []).length})`, data: { processes: j.processes || [] } };
    },

    /* ── phone calls: the assistant speaks to the caller ── */
    async call_state() {
      const c = global.ZY.Call;
      if (!c || !c.nativeOn()) return { ok: false, error: 'no-native', summary: 'এই ডিভাইসে কল-ব্রিজ নেই (APK লাগবে)' };
      const st = global.ZY.Native.call.state();
      const label = st.ringing ? 'কল আসছে' : st.inCall ? 'কল চলছে' : 'কোনো কল নেই';
      return { ok: true, summary: `${label}${st.number ? ' — ' + st.number : ''}`, data: st };
    },

    async call_answer() {
      const c = global.ZY.Call;
      if (!c) return { ok: false, error: 'no-native', summary: 'কল-ব্রিজ নেই' };
      await c.ensurePermissions();
      return await c.answer();
    },

    async call_hangup() {
      const c = global.ZY.Call;
      if (!c) return { ok: false, error: 'no-native', summary: 'কল-ব্রিজ নেই' };
      return await c.hangup();
    },

    async call_dial(args) {
      const c = global.ZY.Call;
      if (!c) return { ok: false, error: 'no-native', summary: 'কল-ব্রিজ নেই' };
      await c.ensurePermissions();
      return await c.dial(args.number);
    },

    async call_auto_answer(args) {
      const c = global.ZY.Call;
      if (!c) return { ok: false, error: 'no-native', summary: 'কল-ব্রিজ নেই' };
      const on = c.setAutoAnswer(args.on !== false);
      return { ok: true, summary: on ? 'অটো-কল-রিসিভ চালু — কল এলেই আমি ধরে ফেলব' : 'অটো-কল-রিসিভ বন্ধ', data: { autoAnswer: on } };
    },

    async memory_write(args) {
      await global.ZY.DB.remember(args.key, args.value, args.tag || 'fact');
      global.ZY.ui && global.ZY.ui.toast(`মনে রাখলাম: ${args.key}`);
      return { ok: true, summary: `মনে রাখা হলো — ${args.key}`, data: { key: args.key, value: args.value } };
    },

    async memory_read(args) {
      const rows = await global.ZY.DB.recall(args.query);
      return { ok: true, summary: `${rows.length}টি স্মৃতি পাওয়া গেল`, data: { rows } };
    },

    async geolocate() {
      if (!(await askPermission('geolocation', 'লোকেশন'))) return { ok: false, error: 'denied', summary: 'লোকেশন অনুমতি নেই' };
      return await new Promise((resolve) => {
        navigator.geolocation.getCurrentPosition(
          (p) => resolve({ ok: true, summary: `লোকেশন পাওয়া গেল (${p.coords.latitude.toFixed(4)}, ${p.coords.longitude.toFixed(4)})`, data: { lat: p.coords.latitude, lon: p.coords.longitude, acc: p.coords.accuracy } }),
          (e) => resolve({ ok: false, error: e.message, summary: 'লোকেশন পাওয়া যায়নি' }), { enableHighAccuracy: true, timeout: 12000 });
      });
    },

    /* ── GitHub: real repositories, real Pages, real Actions builds ── */
    async github_account() {
      const j = await fetch(`${API()}/api/github/info`).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
      if (!j.ok) return { ok: false, error: j.error || 'not-connected', summary: `গিটহাব অ্যাকাউন্ট পাওয়া যায়নি${j.message ? ': ' + j.message : ''}` };
      return { ok: true, summary: `গিটহাব কানেক্টেড: ${j.login} (${j.repos.length}টি রিপোজিটরি)`, data: j };
    },

    async github_publish(args) {
      const files = args.files && typeof args.files === 'object' ? args.files : {};
      if (args.html && !files['index.html']) files['index.html'] = args.html;
      const j = await fetch(`${API()}/api/github/publish`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: args.name, files, description: args.description, private: args.private, pages: args.pages !== false, owner: args.owner, branch: args.branch }),
      }).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
      if (!j.ok) return { ok: false, error: j.error || 'github-failed', summary: `গিটহাবে পাবলিশ ব্যর্থ${j.message ? ': ' + j.message : ''}`, data: j };
      const lines = [`রিপোজিটরি ${j.created ? 'তৈরি হয়েছে' : 'আগেই ছিল'}: ${j.repo}`, `${j.committed.length}টি ফাইল কমিট হয়েছে`];
      // Only claim a live site when the exact content we pushed was served.
      if (j.pages && j.pages.live) lines.push(`লাইভ সাইট (যাচাই করা, নতুন কনটেন্টসহ): ${j.pages.url}`);
      else if (j.pages && j.pages.url && j.pages.verified === false) lines.push(`Pages বিল্ড এখনো propagate হচ্ছে (status ${j.pages.status}): ${j.pages.url} — কিছুক্ষণ পর read_url দিয়ে যাচাই করো`);
      else if (j.pages && j.pages.url) lines.push(`Pages সাইট: ${j.pages.url} (status ${j.pages.status})`);
      if (j.failed && j.failed.length) lines.push(`${j.failed.length}টি ফাইল ব্যর্থ`);
      return { ok: true, summary: lines.join(' | '), data: j };
    },

    async github_workflow(args) {
      const j = await fetch(`${API()}/api/github/workflow`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repo: args.repo, content: args.content, path: args.path, branch: args.branch }),
      }).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
      return j.ok
        ? { ok: true, summary: `ওয়ার্কফ্লো লেখা হলো: ${j.path}`, data: j }
        : { ok: false, error: j.error || 'failed', summary: `ওয়ার্কফ্লো লেখা যায়নি${j.message ? ': ' + j.message : ''}`, data: j };
    },

    async github_actions(args) {
      const j = await fetch(`${API()}/api/github/actions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repo: args.repo, workflow: args.workflow, ref: args.ref, files: args.files, timeout_ms: args.timeout_ms }),
      }).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
      if (!j.ok) return { ok: false, error: j.error || 'build-failed', summary: `বিল্ড ব্যর্থ${j.conclusion ? ' (' + j.conclusion + ')' : ''}${j.stage ? ' — ' + j.stage : ''}`, data: j };
      const jobs = (j.jobs || []).map((x) => `${x.name}=${x.conclusion}`).join(', ');
      const arts = (j.artifacts || []).map((a) => a.name).join(', ');
      return { ok: true, summary: `বিল্ড সফল — ${jobs}${arts ? ' | আর্টিফ্যাক্ট: ' + arts : ''}`, data: j };
    },
  };

  /* ─────────────────── JSON schemas sent to the model ─────────────────── */
  const S = (props, req, extra = {}) => ({ type: 'object', properties: props, required: req, additionalProperties: false, ...extra });
  const Schemas = [
    { name: 'todo_write', description: 'Create or update the live task board. Call it after every tool result. Never give a final answer while a task is unfinished.', parameters: S({ todos: { type: 'array', items: S({ id: { type: 'string' }, title: { type: 'string' }, status: { type: 'string', enum: ['pending', 'in_progress', 'done'] } }, ['id', 'title', 'status']) } }, ['todos']) },
    { name: 'open_app', description: 'Open a real app on the phone (by package or by name).', parameters: S({ package: { type: 'string' }, name: { type: 'string' } }, []) },
    { name: 'launch_url', description: 'Open a URL in the browser. The URL is verified first; a dead link is reported, never opened.', parameters: S({ url: { type: 'string' }, new_tab: { type: 'boolean' } }, ['url']) },
    { name: 'verify_url', description: 'Check that a link really exists (video, page, file) before you open it or tell the user it works. Returns the real HTTP status.', parameters: S({ url: { type: 'string' } }, ['url']) },
    { name: 'read_screen', description: 'Look at the current screen (screenshot/OCR) — use it to verify what happened after opening an app.', parameters: S({}, []) },
    { name: 'look_camera', description: 'Look through the phone camera and see what is really in front of the user (food, a sign, a screen). Returns the photo so your vision reads it directly. Use facing:"front" for selfie view.', parameters: S({ facing: { type: 'string', enum: ['back', 'front'] } }, []) },
    { name: 'tap', description: 'Tap a point on the screen (x,y pixels).', parameters: S({ x: { type: 'number' }, y: { type: 'number' } }, ['x', 'y']) },
    { name: 'swipe', description: 'Swipe on the screen (scroll, back gesture).', parameters: S({ x1: { type: 'number' }, y1: { type: 'number' }, x2: { type: 'number' }, y2: { type: 'number' }, ms: { type: 'number' } }, ['x1', 'y1', 'x2', 'y2']) },
    { name: 'type_text', description: 'Type into the focused input for real.', parameters: S({ text: { type: 'string' }, enter: { type: 'boolean' } }, ['text']) },
    { name: 'press_key', description: 'Press a key: home, back, recents, enter, volume_up, volume_down.', parameters: S({ key: { type: 'string' } }, ['key']) },
    { name: 'list_apps', description: 'List every app installed on the phone (name + package) — use it to find which app to launch.', parameters: S({}, []) },
    { name: 'screen_shot', description: 'Take a real Android screenshot — you can then look at it with your vision.', parameters: S({}, []) },
    { name: 'notify', description: 'Post an Android notification.', parameters: S({ title: { type: 'string' }, body: { type: 'string' } }, ['body']) },
    { name: 'clipboard_read', description: 'Read the clipboard.', parameters: S({}, []) },
    { name: 'clipboard_write', description: 'Write to the clipboard.', parameters: S({ text: { type: 'string' } }, ['text']) },
    { name: 'share', description: 'Open the share sheet (text / link / file).', parameters: S({ text: { type: 'string' }, url: { type: 'string' }, title: { type: 'string' } }, []) },
    { name: 'read_url', description: 'Read a web page as text. Use it for research and verification.', parameters: S({ url: { type: 'string' }, max_chars: { type: 'number' } }, ['url']) },
    { name: 'read_media', description: 'Read an image / audio / video / PDF from a URL — the multimodal model processes it. You can name a specific part.', parameters: S({ url: { type: 'string' }, modality: { type: 'string', enum: ['image', 'audio', 'video', 'pdf', 'auto'] }, part: { type: 'string', description: 'e.g. "01:20" or "page 3"' } }, ['url']) },
    { name: 'generate_file', description: 'Write a real file into the site workspace (index.html, app.js, ...).', parameters: S({ path: { type: 'string' }, content: { type: 'string' }, mode: { type: 'string', enum: ['overwrite', 'append'] } }, ['path', 'content']) },
    { name: 'publish_website', description: 'Publish a real website and get the live URL back (it verifies the URL itself).', parameters: S({ name: { type: 'string' }, html: { type: 'string' }, files: { type: 'object', additionalProperties: { type: 'string' } } }, ['name']) },
    { name: 'run_shell', description: 'Run a real command on the host machine (Linux/macOS: bash/zsh, Windows: PowerShell/cmd). Without shell, the platform default is used.', parameters: S({ command: { type: 'string' }, cwd: { type: 'string' }, shell: { type: 'string', enum: ['auto', 'bash', 'zsh', 'sh', 'fish', 'powershell', 'pwsh', 'cmd'] }, timeout_ms: { type: 'number' } }, ['command']) },
    { name: 'read_file', description: 'Read any file (text or binary — for image/audio/video/PDF the model looks at it itself). A line range is allowed.', parameters: S({ path: { type: 'string' }, from_line: { type: 'number' }, to_line: { type: 'number' } }, ['path']) },
    { name: 'write_file', description: 'Write any file (create it or replace it whole). Missing folders are created.', parameters: S({ path: { type: 'string' }, content: { type: 'string' }, mode: { type: 'string', enum: ['overwrite', 'append'] } }, ['path', 'content']) },
    { name: 'edit_file', description: 'Find and replace text inside a file (all occurrences or one).', parameters: S({ path: { type: 'string' }, find: { type: 'string' }, replace: { type: 'string' }, all: { type: 'boolean' } }, ['path', 'find', 'replace']) },
    { name: 'list_dir', description: 'List every file and folder inside a directory (name, size).', parameters: S({ path: { type: 'string' } }, []) },
    { name: 'find_files', description: 'Find files by name pattern (e.g. **/*.js, *.md, config.*).', parameters: S({ path: { type: 'string' }, pattern: { type: 'string' } }, ['pattern']) },
    { name: 'grep_files', description: 'Search text/regex inside files (which file, which line).', parameters: S({ path: { type: 'string' }, query: { type: 'string' }, regex: { type: 'boolean' }, case_sensitive: { type: 'boolean' } }, ['query']) },
    { name: 'delete_file', description: 'Delete a file or folder (recursive:true deletes the contents too).', parameters: S({ path: { type: 'string' }, recursive: { type: 'boolean' } }, ['path']) },
    { name: 'web_search', description: 'Do a real web search (DuckDuckGo) — for research, news and answering questions.', parameters: S({ query: { type: 'string' }, count: { type: 'number' } }, ['query']) },
    { name: 'http_request', description: 'Any HTTP request (GET/POST/PUT/DELETE) with headers and body — for calling APIs.', parameters: S({ url: { type: 'string' }, method: { type: 'string', enum: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'] }, headers: { type: 'object', additionalProperties: { type: 'string' } }, body: { type: 'string' } }, ['url']) },
    { name: 'download_file', description: 'Download a file from the web and save it (image/video/document).', parameters: S({ url: { type: 'string' }, path: { type: 'string' } }, ['url']) },
    { name: 'sys_info', description: 'The real state of this machine: OS, CPU, RAM, disk, network, running processes.', parameters: S({}, []) },
    { name: 'platform_info', description: 'Where you are really running (mobile/desktop, OS, shell) — never issue another platform\'s commands.', parameters: S({}, []) },
    { name: 'process_list', description: 'See which processes are running right now.', parameters: S({}, []) },
    { name: 'call_state', description: 'Check whether a call is ringing or in progress (with the number).', parameters: S({}, []) },
    { name: 'call_answer', description: 'Answer the incoming call for real (you then speak on the call).', parameters: S({}, []) },
    { name: 'call_hangup', description: 'Hang up the current call.', parameters: S({}, []) },
    { name: 'call_dial', description: 'Place a call yourself.', parameters: S({ number: { type: 'string' } }, ['number']) },
    { name: 'call_auto_answer', description: 'Turn auto-answer on or off.', parameters: S({ on: { type: 'boolean' } }, []) },
    { name: 'memory_write', description: 'Write a durable fact about the user into the local DB (remembered forever).', parameters: S({ key: { type: 'string' }, value: { type: 'string' }, tag: { type: 'string' } }, ['key', 'value']) },
    { name: 'memory_read', description: 'Look up a memory in the local DB.', parameters: S({ query: { type: 'string' } }, []) },
    { name: 'geolocate', description: 'Get the current location.', parameters: S({}, []) },

    /* ── GitHub: the user's own account, with the user's own token ── */
    { name: 'github_account', description: 'Check which GitHub account is connected and list its repositories. Call this first whenever the user mentions GitHub or asks you to publish code.', parameters: S({}, []) },
    { name: 'github_publish', description: 'Create a real GitHub repository (or reuse one) and commit files to it. Optionally turn on GitHub Pages so the site is live on the web. Returns the real repository URL and the live page URL.', parameters: S({ name: { type: 'string' }, files: { type: 'object', additionalProperties: { type: 'string' }, description: 'path -> file content' }, description: { type: 'string' }, private: { type: 'boolean' }, pages: { type: 'boolean' }, owner: { type: 'string' }, branch: { type: 'string' } }, ['name', 'files']) },
    { name: 'github_workflow', description: 'Put a GitHub Actions workflow file into a repository (for example .github/workflows/build.yml). Use this to let Actions build and test anything.', parameters: S({ repo: { type: 'string', description: 'owner/name' }, content: { type: 'string' }, path: { type: 'string' }, branch: { type: 'string' } }, ['repo', 'content']) },
    { name: 'github_actions', description: 'Run a GitHub Actions workflow in the user\'s own account, wait for it to finish, and report the real result of every job and the build artifacts it produced. This is a real Windows / Linux / macOS builder — use it to compile applications for all three platforms.', parameters: S({ repo: { type: 'string', description: 'owner/name' }, workflow: { type: 'string' }, ref: { type: 'string' }, files: { type: 'object', additionalProperties: { type: 'string' } }, timeout_ms: { type: 'number' } }, ['repo']) },
  ];

  const Tools = {
    schemas: Schemas,
    /** OpenAI-format tools array for the API (sorted fastest-first by caller). */
    apiTools() { return Schemas.map((s) => ({ type: 'function', function: { name: s.name, description: s.description, parameters: s.parameters } })); },
    names() { return Schemas.map((s) => s.name); },
    native: Native,

    async execute(name, args) {
      const fn = impl[name];
      if (!fn) return { ok: false, error: 'unknown-tool', summary: `অজানা টুল: ${name}` };
      let a = args;
      if (typeof a === 'string') { try { a = JSON.parse(a); } catch { a = {}; } }
      try {
        const t0 = performance.now();
        const r = await fn(a || {});
        r.ms = Math.round(performance.now() - t0);
        return r;
      } catch (e) {
        return { ok: false, error: String(e), summary: `টুল ব্যর্থ: ${name}` };
      }
    },
  };

  global.ZY = global.ZY || {};
  global.ZY.Tools = Tools;
})(window);
