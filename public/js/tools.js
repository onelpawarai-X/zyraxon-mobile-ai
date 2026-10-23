(() => {
  (function(global) {
    "use strict";
    const API = () => (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
    const BRIDGE_TIMEOUT = 12e3;
    async function netFetch(url, opts = {}) {
      const nat = global.ZY.Native;
      const hdr = Object.assign({}, opts.headers || {});
      if (nat && nat.app && nat.http && nat.http.available()) {
        const r = nat.http.fetch({
          url,
          method: opts.method || "GET",
          headers: hdr,
          body: typeof opts.body === "string" ? opts.body : opts.body == null ? "" : JSON.stringify(opts.body),
          timeoutMs: opts.timeoutMs || 2e4,
          maxBytes: opts.maxBytes || 4e5,
          asDataUrl: !!opts.asDataUrl
        });
        if (r && !r.error) {
          return {
            ok: !!r.ok,
            status: Number(r.status) || 0,
            headers: r.headers || {},
            text: String(r.body == null ? "" : r.body),
            dataUrl: r.dataUrl || null,
            contentType: r.contentType || (r.headers && (r.headers["Content-Type"] || r.headers["content-type"]) || ""),
            bytes: Number(r.bytes) || 0,
            truncated: !!r.truncated,
            viaBridge: true,
            // Callers await this and some chain `.catch()`, so it must be a
            // promise exactly like the browser path below.
            json() {
              try {
                return Promise.resolve(JSON.parse(String(r.body == null ? "" : r.body)));
              } catch {
                return Promise.resolve(null);
              }
            }
          };
        }
      }
      const res = await fetch(url, opts);
      const text = await res.text();
      const headers = {};
      try {
        res.headers.forEach((v, k) => {
          headers[k] = v;
        });
      } catch {
      }
      return {
        ok: res.ok,
        status: res.status,
        headers,
        text,
        dataUrl: null,
        contentType: headers["content-type"] || "",
        bytes: text.length,
        truncated: false,
        viaBridge: false,
        json() {
          try {
            return Promise.resolve(JSON.parse(text));
          } catch {
            return Promise.resolve(null);
          }
        }
      };
    }
    async function fsCall(body) {
      const nat = global.ZY.Native;
      if (nat && nat.fs && nat.fs.available()) {
        const j = nat.fs.op(body);
        if (j && !j.error) return j;
        if (j && j.error && !/unknown op|not available/i.test(String(j.error))) return j;
      }
      try {
        const r = await fetch(`${API()}/api/fs`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        return await r.json();
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    }
    const Native = {
      // ZY. Native is the real Android bridge injected by the APK (native.js).
      _zy() {
        return global.ZY && global.ZY.Native && global.ZY.Native.app ? global.ZY.Native : null;
      },
      available() {
        return !!(this._zy() || this._cap() || this._js() || this._msg());
      },
      kind() {
        if (this._zy()) return "zyraxon-native";
        return this._cap() ? "capacitor" : this._js() ? "javascript-interface" : this._msg() ? "postmessage" : "none";
      },
      _cap() {
        return global.Capacitor && global.Capacitor.Plugins && (global.Capacitor.Plugins.ZYRAXON || global.Capacitor.Plugins.Zyraxon);
      },
      _js() {
        return global.ZYRAXONAndroid || global.ZYRAXONBridge || null;
      },
      _msg() {
        return global.ReactNativeWebView || (global.ZYRAXON || {}).native ? true : null;
      },
      async call(method, params = {}) {
        const zy = this._zy();
        if (zy) return Native._zyCall(zy, method, params);
        const cap = this._cap();
        if (cap) {
          const plugin = cap.ZYRAXON || cap.Zyraxon;
          try {
            if (plugin[method]) return await plugin[method](params);
          } catch (e) {
            return { error: String(e) };
          }
        }
        const js = this._js();
        if (js) {
          try {
            const raw = js[method] ? js[method](JSON.stringify(params)) : js.invoke(method, JSON.stringify(params));
            const out = typeof raw === "string" ? JSON.parse(raw) : raw;
            return out && out.__promise ? await out.__promise : out;
          } catch (e) {
            return { error: String(e) };
          }
        }
        if (global.ReactNativeWebView && global.ReactNativeWebView.postMessage) {
          return await new Promise((resolve) => {
            const id = "zy" + Math.random().toString(36).slice(2);
            const handler = (ev) => {
              try {
                const m = JSON.parse(ev.data);
                if (m && m.id === id) {
                  removeEventListener("message", handler);
                  resolve(m.result);
                }
              } catch {
              }
            };
            addEventListener("message", handler);
            global.ReactNativeWebView.postMessage(JSON.stringify({ id, method, params }));
            setTimeout(() => {
              removeEventListener("message", handler);
              resolve({ error: "native timeout" });
            }, BRIDGE_TIMEOUT);
          });
        }
        return { error: "no-native-bridge" };
      },
      /** Map the tool vocabulary onto the concrete Android bridge calls. */
      _zyCall(zy, method, p) {
        const d = zy.device;
        switch (method) {
          case "openApp":
            d.openApp(p.package || p.name || "");
            return { ok: true, via: "zyraxon-native" };
          case "launchUrl":
            return { ok: d.launchUrl(p.url || "") };
          case "tap":
            return { ok: d.tap(p.x || 0, p.y || 0) };
          case "tapText":
            return { ok: d.tapText(p.text || "") };
          case "typeText":
            return { ok: d.typeText(p.text || "") };
          case "swipe":
            return { ok: d.swipe ? d.swipe(p.x1 || 0, p.y1 || 0, p.x2 || 0, p.y2 || 0) : false };
          case "scroll":
            return { ok: d.scroll(p.forward !== false) };
          case "pressKey":
            return { ok: d.action(p.key || p.action || "back") };
          case "readScreen": {
            const lines = d.readScreen();
            return { ok: true, lines, accessibility: zy.settings.accessibilityOn() };
          }
          case "screenShot":
            d.screenShot();
            return { ok: true, pending: true };
          case "listApps":
            return { ok: true, apps: d.listApps() };
          default:
            return { error: "unknown-native-method:" + method };
        }
      }
    };
    async function askPermission(kind, label) {
      if (global.ZY.ui && global.ZY.ui.permissionSheet) {
        return global.ZY.ui.permissionSheet({ kind, label });
      }
      return true;
    }
    async function ensureWebPerm(name) {
      if (name === "notifications" && "Notification" in global) {
        if (Notification.permission === "granted") return true;
        if (Notification.permission === "denied") return false;
        if (!await askPermission("notifications", "\u09A8\u09CB\u099F\u09BF\u09AB\u09BF\u0995\u09C7\u09B6\u09A8")) return false;
        return await Notification.requestPermission() === "granted";
      }
      if (name === "geolocation" && navigator.geolocation) return true;
      return true;
    }
    const impl = {
      /**
       * Show the live code box, and close it when the file is finished. The user
       * asked for the assistant to open it herself before writing and shut it
       * down when done, so this is a real tool, not a UI side effect.
       */
      async code_box(args) {
        const ui = global.ZY.ui;
        const action = String(args.action || "open");
        if (!ui) return { ok: false, summary: "UI \u09A8\u09C7\u0987 \u2014 \u0995\u09CB\u09A1 \u09AC\u0995\u09CD\u09B8 \u099A\u09BE\u09B2\u09BE\u09A8\u09CB \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
        if (action === "close") {
          ui.closeCodeOverlay && ui.closeCodeOverlay();
          return { ok: true, summary: "\u0995\u09CB\u09A1 \u09AC\u0995\u09CD\u09B8 \u09AC\u09A8\u09CD\u09A7 \u0995\u09B0\u09BE \u09B9\u09B2\u09CB", data: { action } };
        }
        if (action === "minimize") {
          if (!ui._codeMin) ui.toggleCodeMin && ui.toggleCodeMin();
          return { ok: true, summary: "\u0995\u09CB\u09A1 \u09AC\u0995\u09CD\u09B8 \u099B\u09CB\u099F \u0995\u09B0\u09BE \u09B9\u09B2\u09CB", data: { action } };
        }
        if (action === "restore") {
          if (ui._codeMin) ui.toggleCodeMin && ui.toggleCodeMin();
          return { ok: true, summary: "\u0995\u09CB\u09A1 \u09AC\u0995\u09CD\u09B8 \u0986\u09AC\u09BE\u09B0 \u09AC\u09A1\u09BC \u0995\u09B0\u09BE \u09B9\u09B2\u09CB", data: { action } };
        }
        const title = args.title || "Writing code\u2026";
        const body = args.code || "";
        ui.showCodeOverlay(title, body);
        return { ok: true, summary: `\u0995\u09CB\u09A1 \u09AC\u0995\u09CD\u09B8 \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB \u2014 ${title}`, data: { action: "open" } };
      },
      async todo_write(args) {
        const todos = Array.isArray(args.todos) ? args.todos : [];
        global.ZY.store.set("todos", todos);
        await global.ZY.DB.saveTodos(todos);
        if (global.ZY.ui) global.ZY.ui.renderTodos(todos);
        const done = todos.filter((t) => t.status === "done").length;
        return { ok: true, summary: `${done}/${todos.length} tasks done`, data: { todos } };
      },
      async open_app(args) {
        const pkg = args.package || "";
        const name = args.name || pkg;
        const map = {
          facebook: "com.facebook.katana",
          messenger: "com.facebook.orca",
          whatsapp: "com.whatsapp",
          instagram: "com.instagram.android",
          youtube: "com.google.android.youtube",
          camera: "com.android.camera2",
          chrome: "com.android.chrome",
          settings: "com.android.settings",
          gmail: "com.google.android.gm",
          maps: "com.google.android.apps.maps",
          playstore: "com.android.vending",
          telegram: "org.telegram.messenger",
          tiktok: "com.zhiliaoapp.musically"
        };
        const target = pkg || map[String(name).toLowerCase()] || String(name).toLowerCase();
        if (Native.available()) {
          const dev = global.ZY.Native && global.ZY.Native.device;
          const res = dev && dev.resolveApp(target) || {};
          if (!res.found) {
            return {
              ok: false,
              error: "app-not-installed",
              summary: `"${name}" \u098F\u0987 \u09AB\u09CB\u09A8\u09C7 \u0987\u09A8\u09B8\u09CD\u099F\u09B2 \u0995\u09B0\u09BE \u09A8\u09C7\u0987 \u2014 \u0996\u09CB\u09B2\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF\u0964 \u0985\u09A8\u09CD\u09AF \u0985\u09CD\u09AF\u09BE\u09AA\u09C7\u09B0 \u09A8\u09BE\u09AE \u09AC\u09B2\u09CB, \u0985\u09A5\u09AC\u09BE Play Store \u09A5\u09C7\u0995\u09C7 \u0987\u09A8\u09B8\u09CD\u099F\u09B2 \u0995\u09B0\u09A4\u09C7 \u09AC\u09B2\u09CB\u0964`,
              data: { package: target, found: false }
            };
          }
          dev.openApp(res.package || target);
          return { ok: true, summary: `${res.label || name} \u0996\u09CB\u09B2\u09BE \u09B9\u09AF\u09BC\u09C7\u099B\u09C7 (native)`, data: { via: "native", package: res.package || target, label: res.label || name } };
        }
        const ua = navigator.userAgent;
        if (/Android/i.test(ua) && target.includes(".")) {
          const intent = `intent://#Intent;package=${target};end`;
          const a = document.createElement("a");
          a.href = intent;
          a.style.display = "none";
          document.body.appendChild(a);
          a.click();
          a.remove();
          return { ok: true, summary: `${name} \u09B2\u099E\u09CD\u099A \u0995\u09B0\u09BE\u09B0 \u099A\u09C7\u09B7\u09CD\u099F\u09BE \u09B9\u09B2\u09CB (intent)`, data: { via: "intent", package: target } };
        }
        const web = { facebook: "https://m.facebook.com", messenger: "https://m.me", whatsapp: "https://web.whatsapp.com", youtube: "https://m.youtube.com", instagram: "https://www.instagram.com", telegram: "https://web.telegram.org" };
        const url = web[String(name).toLowerCase()];
        if (url) {
          impl.launch_url({ url, new_tab: true });
          return { ok: true, summary: `${name} \u0993\u09AF\u09BC\u09C7\u09AC \u09AD\u09BE\u09B0\u09CD\u09B8\u09A8\u09C7 \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB`, data: { via: "web", url } };
        }
        return { ok: false, error: `${name} \u0996\u09CB\u09B2\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF \u2014 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8 \u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987`, summary: `\u201C${name}\u201D \u0993\u09AA\u09C7\u09A8 \u0995\u09B0\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF (native \u09AC\u09CD\u09B0\u09BF\u099C \u09B2\u09BE\u0997\u09AC\u09C7)` };
      },
      /**
       * Open a URL — but never pretend a dead link opened. A HEAD/GET check runs
       * first so a broken or missing video/page is reported honestly instead of
       * launching the browser at nothing, which is what the user complained about.
       */
      async launch_url(args) {
        const url = args.url || "https://www.google.com";
        if (!/^https?:\/\//i.test(url)) {
          return { ok: false, error: "bad-url", summary: `"${url}" \u098F\u0995\u099F\u09BF \u09AC\u09C8\u09A7 \u09B2\u09BF\u0982\u0995 \u09A8\u09AF\u09BC \u2014 \u0996\u09CB\u09B2\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF\u0964` };
        }
        let reachable = true, status = 0;
        try {
          const r = await netFetch(`${API()}/api/check-url`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url }) });
          const j = await r.json();
          reachable = !!j.ok;
          status = j.status || 0;
        } catch (e) {
          reachable = true;
        }
        if (!reachable) {
          return {
            ok: false,
            error: "url-unreachable",
            summary: `${url} \u0996\u09CB\u09B2\u09BE \u09AF\u09BE\u099A\u09CD\u099B\u09C7 \u09A8\u09BE \u2014 \u09B2\u09BF\u0982\u0995\u099F\u09BF \u09A8\u09C7\u0987 \u09AC\u09BE \u0995\u09BE\u099C \u0995\u09B0\u099B\u09C7 \u09A8\u09BE (status ${status || "?"})\u0964 \u0996\u09CB\u09B2\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF\u0964`,
            data: { url, status }
          };
        }
        const w = args.new_tab !== false ? global.open(url, "_blank") : location.href = url;
        return { ok: true, summary: `${url} \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB${status ? ` (${status})` : ""}`, data: { url, popup: !!w, status } };
      },
      async read_screen() {
        if (Native.available()) {
          const r = await Native.call("readScreen", {});
          if (r && !r.error) return { ok: true, summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u09AA\u09A1\u09BC\u09BE \u09B9\u09AF\u09BC\u09C7\u099B\u09C7 (native)", data: r };
        }
        if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
          const granted = await askPermission("screen", "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u0995\u09CD\u09AF\u09BE\u09AA\u099A\u09BE\u09B0");
          if (granted) {
            try {
              const stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 2 }, audio: false });
              const track = stream.getVideoTracks()[0];
              const vid = document.createElement("video");
              vid.srcObject = stream;
              vid.muted = true;
              await vid.play();
              await new Promise((r) => setTimeout(r, 350));
              const cv = document.createElement("canvas");
              cv.width = vid.videoWidth || 720;
              cv.height = vid.videoHeight || 1280;
              cv.getContext("2d").drawImage(vid, 0, 0);
              const dataUrl = cv.toDataURL("image/jpeg", 0.7);
              track.stop();
              return { ok: true, summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u0995\u09CD\u09AF\u09BE\u09AA\u099A\u09BE\u09B0 \u09B9\u09AF\u09BC\u09C7\u099B\u09C7", data: { dataUrl, width: cv.width, height: cv.height, note: "\u099B\u09AC\u09BF \u09B8\u0982\u09AF\u09C1\u0995\u09CD\u09A4 \u09B9\u09AF\u09BC\u09C7\u099B\u09C7 \u2014 multimodal \u09AE\u09A1\u09C7\u09B2 \u09A6\u09C7\u0996\u09A4\u09C7 \u09AA\u09BE\u09B0\u09AC\u09C7" } };
            } catch (e) {
              return { ok: false, error: "screen-capture-denied", summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF", data: { hint: "\u0989\u09AA\u09B0\u09C7\u09B0 Allow \u099A\u09BE\u09AA\u09CB, \u0985\u09A5\u09AC\u09BE \u0985\u09CD\u09AF\u09BE\u09AA\u09C7 \u0985\u09CD\u09AF\u09BE\u0995\u09CD\u09B8\u09C7\u09B8\u09BF\u09AC\u09BF\u09B2\u09BF\u099F\u09BF \u09B8\u09BE\u09B0\u09CD\u09AD\u09BF\u09B8 \u099A\u09BE\u09B2\u09C1 \u0995\u09B0\u09CB" } };
            }
          }
        }
        return { ok: false, error: "no-screen-access", summary: "\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7 \u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u0985\u09CD\u09AF\u09BE\u0995\u09CD\u09B8\u09C7\u09B8 \u09A8\u09C7\u0987 (APK + Accessibility \u09A6\u09B0\u0995\u09BE\u09B0)", data: { hint: "APK-\u098F \u0985\u09CD\u09AF\u09BE\u0995\u09CD\u09B8\u09C7\u09B8\u09BF\u09AC\u09BF\u09B2\u09BF\u099F\u09BF \u09B8\u09BE\u09B0\u09CD\u09AD\u09BF\u09B8 \u09A6\u09BF\u09B2\u09C7 \u09B8\u09B0\u09BE\u09B8\u09B0\u09BF \u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AC\u09C7" } };
      },
      /**
       * Look through the phone's camera. The user asked to point the camera at
       * something — food, a sign, a screen — and have her say what she sees. The
       * frame goes back as a data URL so the multimodal model looks at it itself.
       */
      async look_camera(args) {
        const facing = args && args.facing === "front" ? "user" : "environment";
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
          return { ok: false, error: "no-camera-api", summary: "\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7 \u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE API \u09A8\u09C7\u0987", data: { hint: "APK \u0987\u09A8\u09B8\u09CD\u099F\u09B2 \u0995\u09B0\u09B2\u09C7 \u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7" } };
        }
        const granted = await askPermission("camera", "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE");
        if (!granted) return { ok: false, error: "camera-denied", summary: "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE\u09B0 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF", data: { hint: "\u0989\u09AA\u09B0\u09C7\u09B0 Allow \u099A\u09BE\u09AA\u09CB" } };
        let stream;
        try {
          stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: facing }, audio: false });
        } catch (e) {
          return { ok: false, error: "camera-denied", summary: "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE \u0996\u09CB\u09B2\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF", data: { hint: String(e) } };
        }
        try {
          const vid = document.createElement("video");
          vid.srcObject = stream;
          vid.muted = true;
          vid.playsInline = true;
          await vid.play();
          await new Promise((r) => setTimeout(r, 450));
          const cv = document.createElement("canvas");
          cv.width = vid.videoWidth || 720;
          cv.height = vid.videoHeight || 1280;
          cv.getContext("2d").drawImage(vid, 0, 0, cv.width, cv.height);
          const dataUrl = cv.toDataURL("image/jpeg", 0.8);
          return { ok: true, summary: `${facing === "user" ? "\u09B8\u09BE\u09AE\u09A8\u09C7\u09B0" : "\u09AA\u09BF\u099B\u09A8\u09C7\u09B0"} \u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE\u09B0 \u099B\u09AC\u09BF \u09A8\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09C7\u099B\u09C7`, data: { dataUrl, modality: "image", facing, width: cv.width, height: cv.height } };
        } finally {
          stream.getTracks().forEach((t) => t.stop());
        }
      },
      async list_apps() {
        const nat = global.ZY.Native;
        if (nat && nat.app) {
          const apps = nat.device.listApps();
          return { ok: apps.length > 0, summary: `${apps.length}\u099F\u09BF \u0985\u09CD\u09AF\u09BE\u09AA \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u099B\u09C7`, data: { apps } };
        }
        return { ok: false, error: "no-native-bridge", summary: "\u0985\u09CD\u09AF\u09BE\u09AA\u09C7\u09B0 \u09A4\u09BE\u09B2\u09BF\u0995\u09BE \u09B6\u09C1\u09A7\u09C1 APK-\u09A4\u09C7 \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC" };
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
          await new Promise((res, rej) => {
            img.onload = res;
            img.onerror = rej;
            img.src = dataUrl;
          });
          const w = Math.min(160, img.naturalWidth || 160);
          const h = Math.min(320, img.naturalHeight || 320);
          const cv = document.createElement("canvas");
          cv.width = w;
          cv.height = h;
          const ctx = cv.getContext("2d");
          ctx.drawImage(img, 0, 0, w, h);
          const d = ctx.getImageData(0, 0, w, h).data;
          const first = [d[0], d[1], d[2]];
          for (let i = 4; i < d.length; i += 16) {
            if (Math.abs(d[i] - first[0]) > 6 || Math.abs(d[i + 1] - first[1]) > 6 || Math.abs(d[i + 2] - first[2]) > 6) return false;
          }
          return true;
        } catch {
          return false;
        }
      },
      async screen_shot() {
        const nat = global.ZY.Native;
        const grab = () => new Promise((res) => {
          let settled = false;
          nat.device.onShot((p) => {
            if (settled) return;
            if (p.state === "ok") {
              settled = true;
              res({ ok: true, summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09B6\u099F \u09A8\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09C7\u099B\u09C7", data: { dataUrl: "data:image/jpeg;base64," + p.text, modality: "image" } });
            } else if (p.state === "error") {
              settled = true;
              res({ ok: false, error: p.text, summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09B6\u099F \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: " + p.text });
            }
          });
          nat.device.screenShot();
          setTimeout(() => {
            if (!settled) {
              settled = true;
              res({ ok: false, error: "timeout", summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09B6\u099F \u09B8\u09AE\u09AF\u09BC \u09B6\u09C7\u09B7" });
            }
          }, 12e3);
        });
        if (nat && nat.app && nat.info.sdk >= 30) {
          for (let i = 0; i < 4; i++) {
            const r = await grab();
            if (!r.ok) return r;
            if (!await impl._blankImage(r.data.dataUrl)) return r;
            await new Promise((res) => setTimeout(res, 400 + i * 300));
          }
          return { ok: false, error: "blank-screen", summary: "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8 \u098F\u0996\u09A8\u09CB \u09B8\u09BE\u09A6\u09BE \u2014 \u0985\u09CD\u09AF\u09BE\u09AA \u09AA\u09C1\u09B0\u09CB\u09AA\u09C1\u09B0\u09BF \u0996\u09CB\u09B2\u09C7\u09A8\u09BF", data: { hint: "\u0985\u09CD\u09AF\u09BE\u09AA \u0996\u09CB\u09B2\u09BE\u09B0 \u09AA\u09B0 \u098F\u0995\u099F\u09C1 \u0985\u09AA\u09C7\u0995\u09CD\u09B7\u09BE \u0995\u09B0\u09C7 \u0986\u09AC\u09BE\u09B0 screen_shot \u0995\u09B0\u09CB, \u0985\u09A5\u09AC\u09BE read_screen \u09A6\u09BF\u09AF\u09BC\u09C7 \u099F\u09C7\u0995\u09CD\u09B8\u099F \u09AA\u09A1\u09BC\u09CB" } };
        }
        return impl.read_screen();
      },
      async _nativeTouch(args, method) {
        if (!Native.available()) return { ok: false, error: "no-native-bridge", summary: "\u099F\u09CD\u09AF\u09BE\u09AA/\u09B8\u09CD\u09AC\u09BE\u0987\u09AA\u09C7\u09B0 \u099C\u09A8\u09CD\u09AF \u09A1\u09BF\u09AD\u09BE\u0987\u09B8 \u09AC\u09CD\u09B0\u09BF\u099C (APK) \u09A6\u09B0\u0995\u09BE\u09B0", data: { hint: "\u0985\u09CD\u09AF\u09BE\u09AA \u0987\u09A8\u09B8\u09CD\u099F\u09B2 \u0995\u09B0\u09C7 \u0985\u09CD\u09AF\u09BE\u0995\u09CD\u09B8\u09C7\u09B8\u09BF\u09AC\u09BF\u09B2\u09BF\u099F\u09BF Allow \u0995\u09B0\u09B2\u09C7 \u098F\u099F\u09BE \u09B0\u09BF\u09AF\u09BC\u09C7\u09B2\u09BF \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7" } };
        const r = await Native.call(method, args);
        if (r && !r.error) return { ok: true, summary: `${method} done`, data: r };
        return { ok: false, error: r && r.error, summary: `${method} \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5` };
      },
      tap(args) {
        return impl._nativeTouch(args, "tap");
      },
      swipe(args) {
        return impl._nativeTouch(args, "swipe");
      },
      type_text(args) {
        return impl._nativeTouch(args, "typeText");
      },
      press_key(args) {
        return impl._nativeTouch(args, "pressKey");
      },
      async notify(args) {
        const title = args.title || "ZYRAXON";
        const body = args.body || "";
        if (await ensureWebPerm("notifications")) {
          try {
            new Notification(title, { body, icon: "/assets/icon.svg", badge: "/assets/icon.svg" });
            return { ok: true, summary: "\u09A8\u09CB\u099F\u09BF\u09AB\u09BF\u0995\u09C7\u09B6\u09A8 \u09AA\u09BE\u09A0\u09BE\u09A8\u09CB \u09B9\u09B2\u09CB", data: { title, body } };
          } catch (e) {
            return { ok: false, error: String(e), summary: "\u09A8\u09CB\u099F\u09BF\u09AB\u09BF\u0995\u09C7\u09B6\u09A8 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5" };
          }
        }
        return { ok: false, error: "denied", summary: "\u09A8\u09CB\u099F\u09BF\u09AB\u09BF\u0995\u09C7\u09B6\u09A8 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF" };
      },
      async clipboard_read() {
        try {
          const t = await navigator.clipboard.readText();
          return { ok: true, summary: `\u0995\u09CD\u09B2\u09BF\u09AA\u09AC\u09CB\u09B0\u09CD\u09A1 \u09AA\u09A1\u09BC\u09BE \u09B9\u09B2\u09CB (${t.length} \u0985\u0995\u09CD\u09B7\u09B0)`, data: { text: t } };
        } catch {
          return { ok: false, error: "clipboard-denied", summary: "\u0995\u09CD\u09B2\u09BF\u09AA\u09AC\u09CB\u09B0\u09CD\u09A1 \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF (\u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09B0\u0995\u09BE\u09B0)" };
        }
      },
      async clipboard_write(args) {
        try {
          await navigator.clipboard.writeText(args.text || "");
          return { ok: true, summary: "\u0995\u09CD\u09B2\u09BF\u09AA\u09AC\u09CB\u09B0\u09CD\u09A1\u09C7 \u09B2\u09C7\u0996\u09BE \u09B9\u09B2\u09CB", data: { text: args.text } };
        } catch {
          return { ok: false, error: "clipboard-denied", summary: "\u0995\u09CD\u09B2\u09BF\u09AA\u09AC\u09CB\u09B0\u09CD\u09A1\u09C7 \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
        }
      },
      async share(args) {
        const payload = { title: args.title || "ZYRAXON", text: args.text || "", url: args.url || void 0 };
        if (navigator.share) {
          try {
            await navigator.share(payload);
            return { ok: true, summary: "\u09B6\u09C7\u09AF\u09BC\u09BE\u09B0 \u09B6\u09BF\u099F \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB", data: payload };
          } catch (e) {
            if (String(e).includes("Abort")) return { ok: true, summary: "\u09B6\u09C7\u09AF\u09BC\u09BE\u09B0 \u09AC\u09BE\u09A4\u09BF\u09B2 \u09B9\u09AF\u09BC\u09C7\u099B\u09C7", data: payload };
            return { ok: false, error: String(e), summary: "\u09B6\u09C7\u09AF\u09BC\u09BE\u09B0 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5" };
          }
        }
        if (payload.url) {
          impl.launch_url({ url: payload.url });
          return { ok: true, summary: "\u09B2\u09BF\u0982\u0995 \u09AC\u09CD\u09B0\u09BE\u0989\u099C\u09BE\u09B0\u09C7 \u0996\u09CB\u09B2\u09BE \u09B9\u09B2\u09CB", data: payload };
        }
        return { ok: false, error: "no-web-share", summary: "\u098F\u0987 \u09AC\u09CD\u09B0\u09BE\u0989\u099C\u09BE\u09B0\u09C7 \u09B6\u09C7\u09AF\u09BC\u09BE\u09B0 \u09A8\u09C7\u0987" };
      },
      /**
       * Prove a link is real before you open it or tell the user about it. Call
       * this for any video, page or download URL you are about to use — it is the
       * difference between "খোলা হলো" and "লিংকটি নেই".
       */
      async verify_url(args) {
        const url = args.url || "";
        if (!/^https?:\/\//i.test(url)) return { ok: false, error: "bad-url", summary: `"${url}" \u09AC\u09C8\u09A7 http(s) \u09B2\u09BF\u0982\u0995 \u09A8\u09AF\u09BC\u0964` };
        try {
          let r = await netFetch(url, { method: "HEAD", timeoutMs: 12e3 });
          if (!r.status || r.status >= 400) {
            r = await netFetch(url, { method: "GET", headers: { Range: "bytes=0-0" }, timeoutMs: 12e3 });
          }
          const ct = String(r.contentType || r.headers && (r.headers["Content-Type"] || r.headers["content-type"]) || "");
          const ok = r.status >= 200 && r.status < 400;
          const j = { ok, status: r.status, contentType: ct };
          if (ok) return { ok: true, summary: `\u09B2\u09BF\u0982\u0995 \u09A0\u09BF\u0995 \u0986\u099B\u09C7: ${url} (${r.status}${ct ? ", " + ct : ""})`, data: j };
          return { ok: false, error: "url-unreachable", summary: `\u09B2\u09BF\u0982\u0995\u099F\u09BF \u09A8\u09C7\u0987 \u09AC\u09BE \u0995\u09BE\u099C \u0995\u09B0\u099B\u09C7 \u09A8\u09BE: ${url} (status ${r.status || "?"})`, data: j };
        } catch (e) {
          return { ok: false, error: String(e), summary: `\u09B2\u09BF\u0982\u0995 \u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09BE \u0997\u09C7\u09B2 \u09A8\u09BE: ${url}` };
        }
      },
      async read_url(args) {
        const url = String(args.url || "");
        if (!/^https?:\/\//i.test(url)) return { ok: false, error: "bad-url", summary: `"${url}" \u09AC\u09C8\u09A7 \u09B2\u09BF\u0982\u0995 \u09A8\u09AF\u09BC` };
        try {
          const r = await netFetch(url, { timeoutMs: 3e4, maxBytes: 2e6 });
          const text = typeof r.text === "function" ? await r.text() : r.text;
          const ct = String(r.contentType || r.headers && (r.headers["Content-Type"] || r.headers["content-type"]) || "");
          const isHtml = /html/i.test(ct) || /<html/i.test(String(text).slice(0, 500));
          const clean = isHtml ? String(text).replace(/<script[\s\S]*?<\/script>/gi, " ").replace(/<style[\s\S]*?<\/style>/gi, " ").replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/[ \t]+/g, " ").replace(/\n\s*\n\s*\n+/g, "\n\n").trim() : String(text);
          const out = clean.slice(0, args.max_chars || 12e3);
          return { ok: r.ok, summary: `${url} \u09AA\u09A1\u09BC\u09BE \u09B9\u09B2\u09CB (${clean.length} \u0985\u0995\u09CD\u09B7\u09B0)`, data: { status: r.status, contentType: ct, text: out } };
        } catch (e) {
          return { ok: false, error: String(e), summary: `URL \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${url} (${e})` };
        }
      },
      async read_media(args) {
        const url = String(args.url || "");
        if (!/^https?:\/\//i.test(url)) return { ok: false, error: "bad-url", summary: `"${url}" \u09AC\u09C8\u09A7 \u09B2\u09BF\u0982\u0995 \u09A8\u09AF\u09BC` };
        try {
          const r = await netFetch(url, { timeoutMs: 6e4, maxBytes: 12 * 1024 * 1024, asDataUrl: true });
          if (!r.ok) return { ok: false, error: `http-${r.status}`, summary: `\u09AE\u09BF\u09A1\u09BF\u09AF\u09BC\u09BE \u0986\u09A8\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${url} (status ${r.status})` };
          const ct = String(r.contentType || "");
          if (r.dataUrl) {
            const modality2 = args.modality || (/^audio/.test(ct) ? "audio" : /^video/.test(ct) ? "video" : /pdf/.test(ct) ? "pdf" : "image");
            global.ZY._pendingMedia = { url, dataUrl: r.dataUrl, modality: modality2, part: args.part };
            return { ok: true, summary: `${modality2} \u09AA\u09A1\u09BC\u09BE \u09B9\u09B2\u09CB (${Math.round((r.bytes || 0) / 1024)}KB) \u2014 \u09AE\u09A1\u09C7\u09B2\u09C7 \u09AA\u09BE\u09A0\u09BE\u09A8\u09CB\u09B0 \u099C\u09A8\u09CD\u09AF \u09A4\u09C8\u09B0\u09BF`, data: { modality: modality2, bytes: r.bytes, part: args.part, note: "\u09AA\u09B0\u09C7\u09B0 \u099F\u09BE\u09B0\u09CD\u09A8\u09C7 \u098F\u099F\u09BF \u09AE\u09A1\u09C7\u09B2\u09C7\u09B0 \u0987\u09A8\u09AA\u09C1\u099F\u09C7 \u09AF\u09C1\u0995\u09CD\u09A4 \u09B9\u09AC\u09C7" } };
          }
          const blob = await (await fetch(url)).blob();
          const dataUrl = await new Promise((res) => {
            const fr = new FileReader();
            fr.onload = () => res(String(fr.result));
            fr.readAsDataURL(blob);
          });
          const modality = args.modality || (/^audio/.test(blob.type) ? "audio" : /^video/.test(blob.type) ? "video" : /pdf/.test(blob.type) ? "pdf" : "image");
          global.ZY._pendingMedia = { url, dataUrl, modality, part: args.part };
          return { ok: true, summary: `${modality} \u09AA\u09A1\u09BC\u09BE \u09B9\u09B2\u09CB (${Math.round(blob.size / 1024)}KB) \u2014 \u09AE\u09A1\u09C7\u09B2\u09C7 \u09AA\u09BE\u09A0\u09BE\u09A8\u09CB\u09B0 \u099C\u09A8\u09CD\u09AF \u09A4\u09C8\u09B0\u09BF`, data: { modality, bytes: blob.size, part: args.part, note: "\u09AA\u09B0\u09C7\u09B0 \u099F\u09BE\u09B0\u09CD\u09A8\u09C7 \u098F\u099F\u09BF \u09AE\u09A1\u09C7\u09B2\u09C7\u09B0 \u0987\u09A8\u09AA\u09C1\u099F\u09C7 \u09AF\u09C1\u0995\u09CD\u09A4 \u09B9\u09AC\u09C7" } };
        } catch (e) {
          return { ok: false, error: String(e), summary: `\u09AE\u09BF\u09A1\u09BF\u09AF\u09BC\u09BE \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${url} (${e})` };
        }
      },
      async generate_file(args) {
        const j = await fsCall({ op: "write", path: args.path, content: args.content, append: args.mode === "append" });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u09AB\u09BE\u0987\u09B2 \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${args.path} (${j.error})` };
        return { ok: true, summary: `${args.path} \u09B2\u09C7\u0996\u09BE \u09B9\u09B2\u09CB (${j.bytes} \u09AC\u09BE\u0987\u099F)`, data: j };
      },
      /**
       * Put a site on the real web. With a server it goes to the server's static
       * host; with no server (the APK) the user's own GitHub Pages is the real
       * host, and the result is verified before it is called live. Files are
       * always written locally first, so nothing is lost if publishing fails.
       */
      async publish_website(args) {
        const name = String(args.name || "zyraxon-site").replace(/[^A-Za-z0-9._-]/g, "-");
        const files = args.files && typeof args.files === "object" ? { ...args.files } : {};
        if (args.html && !files["index.html"]) files["index.html"] = args.html;
        if (!Object.keys(files).length) return { ok: false, error: "no-files", summary: "\u09AA\u09CD\u09B0\u0995\u09BE\u09B6 \u0995\u09B0\u09BE\u09B0 \u099C\u09A8\u09CD\u09AF \u0995\u09CB\u09A8\u09CB \u09AB\u09BE\u0987\u09B2 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF" };
        for (const [rel, content] of Object.entries(files)) {
          await fsCall({ op: "write", path: `sites/${name}/${String(rel).replace(/^\/+/, "")}`, content: String(content) });
        }
        const hasSrv = !!(global.ZY.Platform && global.ZY.Platform.hasServer && global.ZY.Platform.hasServer());
        if (hasSrv) {
          const r = await netFetch(`${API()}/api/publish`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name, html: args.html, files }) });
          const j = await r.json().catch(() => ({}));
          if (j.ok) {
            global.ZY.store.set("lastSite", j.url);
            return { ok: true, summary: `\u0993\u09AF\u09BC\u09C7\u09AC\u09B8\u09BE\u0987\u099F \u09AA\u09BE\u09AC\u09B2\u09BF\u09B6 \u09B9\u09AF\u09BC\u09C7\u099B\u09C7 \u0993 \u09AD\u09C7\u09B0\u09BF\u09AB\u09BE\u0987 \u09B9\u09AF\u09BC\u09C7\u099B\u09C7: ${j.url}`, data: j };
          }
        }
        const GH = global.ZY.GH;
        if (GH && GH.connected()) {
          const j = await GH.publish({ name, files, pages: true });
          if (j.ok) {
            const url = j.pages && j.pages.url;
            if (url) global.ZY.store.set("lastSite", url);
            if (j.pages && j.pages.live) return { ok: true, summary: `\u09B2\u09BE\u0987\u09AD \u0993\u09AF\u09BC\u09C7\u09AC\u09B8\u09BE\u0987\u099F (\u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09BE): ${url} \u2014 \u09B0\u09BF\u09AA\u09CB ${j.repo}`, data: j };
            return { ok: true, summary: `GitHub Pages-\u098F \u09AA\u09CD\u09B0\u0995\u09BE\u09B6 \u0995\u09B0\u09BE \u09B9\u09B2\u09CB: ${url || j.repo} \u2014 \u09AC\u09BF\u09B2\u09CD\u09A1 propagate \u09B9\u099A\u09CD\u099B\u09C7, \u0995\u09BF\u099B\u09C1\u0995\u09CD\u09B7\u09A3 \u09AA\u09B0 verify_url \u09A6\u09BF\u09AF\u09BC\u09C7 \u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09CB`, data: j };
          }
          return { ok: false, error: j.error || "publish-failed", summary: `\u09AA\u09CD\u09B0\u0995\u09BE\u09B6 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5${j.message ? ": " + j.message : ""}`, data: j };
        }
        return { ok: false, error: "not-connected", summary: "\u09B8\u09BE\u0987\u099F\u099F\u09BF \u09AB\u09CB\u09A8\u09C7 \u09B8\u09C7\u09AD \u09B9\u09AF\u09BC\u09C7\u099B\u09C7 (sites/" + name + "), \u0995\u09BF\u09A8\u09CD\u09A4\u09C1 \u09B2\u09BE\u0987\u09AD \u0995\u09B0\u09A4\u09C7 \u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09B0\u0995\u09BE\u09B0 \u2014 \u09B8\u09C7\u099F\u09BF\u0982\u09B8\u09C7 \u099F\u09CB\u0995\u09C7\u09A8 \u09AC\u09B8\u09BE\u0993, \u09A4\u09BE\u09B0\u09AA\u09B0 \u0986\u09AC\u09BE\u09B0 publish_website \u09A1\u09BE\u0995\u09CB" };
      },
      async run_shell(args) {
        const P = global.ZY.Platform;
        const nativeShell = global.ZY.Native && global.ZY.Native.shell && global.ZY.Native.shell.available();
        const preferNative = nativeShell && !(P && P.data && P.data.isDesktop && P.data.host);
        if (preferNative) {
          const j2 = global.ZY.Native.shell.exec(args.command, args.cwd, args.timeout_ms) || {};
          if (j2.error) return { ok: false, error: j2.error, summary: `\u09AB\u09CB\u09A8\u09C7 \u0995\u09AE\u09BE\u09A8\u09CD\u09A1 \u099A\u09BE\u09B2\u09BE\u09A8\u09CB \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${j2.error}`, data: j2 };
          const ok2 = j2.code === 0;
          const tail2 = String(j2.stdout || "").trim().split("\n").slice(-6).join("\n").slice(0, 400);
          return { ok: ok2, summary: `[\u09AB\u09CB\u09A8/Android sandbox] $ ${args.command} \u2192 exit ${j2.code}${tail2 ? " | " + tail2 : ""}`, data: j2 };
        }
        const r = await netFetch(`${API()}/api/shell`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ command: args.command, cwd: args.cwd, shell: args.shell, timeout_ms: args.timeout_ms }) });
        const j = await r.json();
        if (j.error) return { ok: false, error: j.error, summary: "\u0995\u09AE\u09BE\u09A8\u09CD\u09A1 \u099A\u09BE\u09B2\u09BE\u09A8\u09CB \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" };
        const ok = j.code === 0;
        const tail = (j.stdout || "").trim().split("\n").slice(-6).join("\n").slice(0, 400);
        const where = P && P.data && P.data.host && P.data.host.osName ? P.data.host.osName : "host";
        return { ok, summary: `[\u09B9\u09CB\u09B8\u09CD\u099F (${where})/${j.shell || "shell"}] $ ${args.command} \u2192 exit ${j.code}${tail ? " | " + tail : ""}`, data: { code: j.code, stdout: j.stdout, stderr: j.stderr, shell: j.shell, cwd: j.cwd } };
      },
      /* ── filesystem: real reads, writes and in-place edits ── */
      async read_file(args) {
        const j = await fsCall({ op: "read", path: args.path, from_line: args.from_line, to_line: args.to_line });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u09AB\u09BE\u0987\u09B2 \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${args.path} (${j.error})` };
        if (j.text) {
          global.ZY._pendingMedia = { url: args.path, dataUrl: j.dataUrl, modality: "auto" };
          return { ok: true, summary: `${args.path} \u098F\u0995\u099F\u09BF \u09AC\u09BE\u0987\u09A8\u09BE\u09B0\u09BF \u09AB\u09BE\u0987\u09B2 (${j.bytes} \u09AC\u09BE\u0987\u099F) \u2014 \u09AE\u09A1\u09C7\u09B2\u09C7\u09B0 \u0987\u09A8\u09AA\u09C1\u099F\u09C7 \u09AF\u09C1\u0995\u09CD\u09A4 \u09B9\u09B2\u09CB`, data: { bytes: j.bytes, binary: true } };
        }
        return { ok: true, summary: `${args.path} \u09AA\u09A1\u09BC\u09BE \u09B9\u09B2\u09CB \u2014 ${j.lines} \u09B2\u09BE\u0987\u09A8, ${j.bytes} \u09AC\u09BE\u0987\u099F`, data: { path: j.path, lines: j.lines, bytes: j.bytes, content: j.content } };
      },
      async write_file(args) {
        const j = await fsCall({ op: "write", path: args.path, content: args.content, append: args.mode === "append" });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u09AB\u09BE\u0987\u09B2 \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${args.path} (${j.error})` };
        return { ok: true, summary: `${args.path} ${j.created ? "\u09A4\u09C8\u09B0\u09BF" : "\u0986\u09AA\u09A1\u09C7\u099F"} \u09B9\u09B2\u09CB (${j.bytes} \u09AC\u09BE\u0987\u099F)`, data: j };
      },
      async edit_file(args) {
        const j = await fsCall({ op: "edit", path: args.path, find: args.find, replace: args.replace, all: args.all });
        if (!j.ok) return { ok: false, error: j.error, occurrences: j.occurrences, summary: `\u098F\u09A1\u09BF\u099F \u09B9\u09B2\u09CB \u09A8\u09BE: ${args.path} \u2014 ${j.error}` };
        return { ok: true, summary: `${args.path} \u098F\u09A1\u09BF\u099F \u09B9\u09B2\u09CB (${j.replaced} \u099C\u09BE\u09AF\u09BC\u0997\u09BE\u09AF\u09BC)`, data: j };
      },
      async list_dir(args) {
        const j = await fsCall({ op: "list", path: args.path || "." });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u09AB\u09CB\u09B2\u09CD\u09A1\u09BE\u09B0 \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${args.path} (${j.error})` };
        const names = j.entries.slice(0, 80).map((e) => e.dir ? e.name + "/" : e.name).join(", ");
        return { ok: true, summary: `${j.path} \u2014 ${j.count}\u099F\u09BF \u0986\u0987\u099F\u09C7\u09AE: ${names}`, data: j };
      },
      async find_files(args) {
        const j = await fsCall({ op: "glob", path: args.path || ".", pattern: args.pattern || "*" });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u0996\u09CB\u0981\u099C\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${j.error}` };
        return { ok: true, summary: `${j.pattern} \u2192 ${j.count}\u099F\u09BF \u09AB\u09BE\u0987\u09B2 \u09AE\u09BF\u09B2\u09C7\u099B\u09C7`, data: j };
      },
      async grep_files(args) {
        const j = await fsCall({ op: "grep", path: args.path || ".", query: args.query, regex: args.regex, case_sensitive: args.case_sensitive });
        if (!j.ok) return { ok: false, error: j.error, summary: `grep \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: ${j.error}` };
        return { ok: true, summary: `${j.count}\u099F\u09BF \u09B2\u09BE\u0987\u09A8\u09C7 "${args.query}" \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u09B2`, data: j };
      },
      async delete_file(args) {
        const j = await fsCall({ op: "delete", path: args.path, recursive: args.recursive });
        if (!j.ok) return { ok: false, error: j.error, summary: `\u09AE\u09CB\u099B\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${args.path} (${j.error})` };
        return { ok: true, summary: `${args.path} \u09AE\u09CB\u099B\u09BE \u09B9\u09B2\u09CB`, data: j };
      },
      /* ── web: real search and generic HTTP ── */
      async web_search(args) {
        const q = String(args.query || "").trim();
        if (!q) return { ok: false, error: "query-required", summary: "\u0996\u09CB\u0981\u099C\u09BE\u09B0 \u09B6\u09AC\u09CD\u09A6 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF" };
        const n = Math.min(Math.max(Number(args.count) || 8, 1), 25);
        const strip = (s) => String(s || "").replace(/<[^>]+>/g, "").replace(/&amp;/g, "&").replace(/&#x27;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/\s+/g, " ").trim();
        const parseDDG = (html) => {
          const out = [];
          const rx = /<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:class="result__snippet"[^>]*>([\s\S]*?)<\/a>)?/g;
          let m;
          while ((m = rx.exec(html)) && out.length < n) {
            let url = m[1];
            const red = /uddg=([^&]+)/.exec(url);
            if (red) {
              try {
                url = decodeURIComponent(red[1]);
              } catch {
              }
            }
            if (/^https?:/.test(url)) out.push({ title: strip(m[2]), url, snippet: strip(m[3]) });
          }
          return out;
        };
        const attempts = [
          `https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`,
          `https://lite.duckduckgo.com/lite/?q=${encodeURIComponent(q)}`
        ];
        for (const u of attempts) {
          try {
            const r = await netFetch(u, { headers: { Accept: "text/html" }, timeoutMs: 15e3, maxBytes: 6e5 });
            if (!r.ok) continue;
            const html = typeof r.text === "function" ? await r.text() : r.text;
            let results = parseDDG(html);
            if (!results.length) {
              const rx = /<a[^>]+href="(https?:\/\/[^"]+)"[^>]*>([^<]{3,120})<\/a>/g;
              let m;
              while ((m = rx.exec(html)) && results.length < n) {
                if (/duckduckgo|javascript/.test(m[1])) continue;
                results.push({ title: strip(m[2]), url: m[1], snippet: "" });
              }
            }
            if (results.length) {
              const top = results.slice(0, 5).map((x, i) => `${i + 1}. ${x.title} \u2014 ${x.url}`).join("\n");
              return { ok: true, summary: `${results.length}\u099F\u09BF \u09AB\u09B2\u09BE\u09AB\u09B2 (duckduckgo):
${top}`, data: { ok: true, engine: "duckduckgo", query: q, results } };
            }
          } catch {
          }
        }
        try {
          const r = await netFetch(`https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(q)}&format=json&srlimit=${n}`, { timeoutMs: 15e3 });
          const j = await r.json();
          const results = (j && j.query && j.query.search || []).map((s) => ({ title: s.title, url: `https://en.wikipedia.org/wiki/${encodeURIComponent(String(s.title).replace(/ /g, "_"))}`, snippet: strip(s.snippet) }));
          if (results.length) {
            const top = results.slice(0, 5).map((x, i) => `${i + 1}. ${x.title} \u2014 ${x.url}`).join("\n");
            return { ok: true, summary: `${results.length}\u099F\u09BF \u09AB\u09B2\u09BE\u09AB\u09B2 (wikipedia):
${top}`, data: { ok: true, engine: "wikipedia", query: q, results } };
          }
        } catch {
        }
        return { ok: false, error: "no-results", summary: `"${q}" \u2014 \u0995\u09BF\u099B\u09C1 \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF` };
      },
      async http_request(args) {
        try {
          const r = await netFetch(args.url, {
            method: (args.method || "GET").toUpperCase(),
            headers: args.headers || {},
            body: args.body,
            timeoutMs: 25e3,
            maxBytes: 4e5
          });
          const bodyText = typeof r.text === "function" ? await r.text() : r.text;
          const j = { ok: r.ok, status: r.status, bytes: r.bytes != null ? r.bytes : bodyText.length, contentType: r.headers && (r.headers["Content-Type"] || r.headers["content-type"]) || "", data: bodyText.slice(0, 2e5) };
          if (!j.ok && !j.status) return { ok: false, error: "request-failed", summary: `${args.method || "GET"} ${args.url} \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5`, data: j };
          const brief = String(j.data).slice(0, 200);
          return { ok: j.ok, summary: `${args.method || "GET"} ${args.url} \u2192 ${j.status} (${j.bytes} \u09AC\u09BE\u0987\u099F) ${brief}`, data: j };
        } catch (e) {
          return { ok: false, error: String(e), summary: `${args.method || "GET"} ${args.url} \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: ${e}` };
        }
      },
      /* ── desktop / host system ── */
      async sys_info() {
        const P = global.ZY.Platform;
        const nat = global.ZY.Native;
        if (nat && nat.app && nat.device && nat.device.info) {
          const d = nat.device.info() || {};
          return { ok: true, summary: `${d.model || "Android"} (Android ${d.release || "?"}), SDK ${d.sdk || "?"}, \u09AC\u09CD\u09AF\u09BE\u099F\u09BE\u09B0\u09BF ${d.battery != null ? d.battery + "%" : "?"}`, data: d };
        }
        const r = await netFetch(`${API()}/api/sysinfo`);
        const j = await r.json();
        if (!j || j.error) return { ok: false, error: "sysinfo-unavailable", summary: "\u09B8\u09BF\u09B8\u09CD\u099F\u09C7\u09AE \u09A4\u09A5\u09CD\u09AF \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u09B2 \u09A8\u09BE", data: j };
        return { ok: true, summary: `${j.osName} ${j.release} ${j.arch}, CPU ${j.cpus && j.cpus.length ? j.cpus[j.cpus.length - 1] : "?"}, RAM ${j.freeMemMB}/${j.totalMemMB}MB \u09AB\u09BE\u0981\u0995\u09BE${j.disk ? `, \u09A1\u09BF\u09B8\u09CD\u0995 ${j.disk.freeGB}GB \u09AB\u09BE\u0981\u0995\u09BE` : ""}`, data: j };
      },
      async platform_info() {
        const p = global.ZY.Platform.data || global.ZY.Platform.detect();
        return { ok: true, summary: `\u0986\u09AE\u09BF \u099A\u09B2\u099B\u09BF ${p.osName} (${p.kind}) \u2014 \u09B6\u09C7\u09B2 ${p.host && p.host.defaultShell || p.shell}, \u09A8\u09C7\u099F\u09BF\u09AD \u09AC\u09CD\u09B0\u09BF\u099C ${p.nativeBridge ? "\u0986\u099B\u09C7" : "\u09A8\u09C7\u0987"}`, data: p };
      },
      async download_file(args) {
        const url = String(args.url || "");
        if (!/^https?:\/\//i.test(url)) return { ok: false, error: "bad-url", summary: `"${url}" \u09AC\u09C8\u09A7 \u09B2\u09BF\u0982\u0995 \u09A8\u09AF\u09BC` };
        const fallbackName = (url.split("/").pop() || "file").split("?")[0] || "file";
        const target = args.path || "downloads/" + fallbackName;
        try {
          const r = await netFetch(url, { timeoutMs: 6e4, maxBytes: 12 * 1024 * 1024 });
          const text = typeof r.text === "function" ? await r.text() : r.text;
          if (!r.ok) return { ok: false, error: `http-${r.status}`, summary: `${url} \u09A8\u09BE\u09AE\u09BE\u09A8\u09CB \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF (status ${r.status})` };
          const viaBridge = !!r.viaBridge;
          const ct = String(r.headers && (r.headers["Content-Type"] || r.headers["content-type"]) || "");
          const isText = /^(text\/|application\/(json|xml|javascript|x-javascript|yaml)|)/.test(ct) && !/image|video|audio|pdf|octet/.test(ct);
          const wr = isText ? await fsCall({ op: "write", path: target, content: text }) : await fsCall({ op: "write", path: target, content: viaBridge ? text : btoa(unescape(encodeURIComponent(text))) });
          if (!wr.ok) return { ok: false, error: wr.error || "save-failed", summary: `\u09B8\u09C7\u09AD \u0995\u09B0\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${wr.error || "unknown"}` };
          return { ok: true, summary: `${url} \u2192 ${wr.path} (${wr.bytes} \u09AC\u09BE\u0987\u099F${isText ? "" : ", base64"})`, data: { ...wr, contentType: ct, encoding: isText ? "utf8" : "base64" } };
        } catch (e) {
          return { ok: false, error: String(e), summary: `${url} \u09A8\u09BE\u09AE\u09BE\u09A8\u09CB \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF: ${e}` };
        }
      },
      async process_list() {
        const nat = global.ZY.Native;
        if (nat && nat.shell && nat.shell.available()) {
          const j2 = nat.shell.exec("ps -A -o PID,NAME 2>/dev/null || ps 2>/dev/null", "", 8e3) || {};
          const lines = String(j2.stdout || "").trim().split("\n").filter(Boolean).slice(0, 120);
          return { ok: true, summary: `\u099A\u09B2\u09AE\u09BE\u09A8 \u09AA\u09CD\u09B0\u09B8\u09C7\u09B8 (${Math.max(0, lines.length - 1)})`, data: { processes: lines } };
        }
        const r = await netFetch(`${API()}/api/sysinfo`);
        const j = await r.json();
        if (!j || j.error) return { ok: false, error: "unavailable", summary: "\u09AA\u09CD\u09B0\u09B8\u09C7\u09B8 \u09A4\u09BE\u09B2\u09BF\u0995\u09BE \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u09B2 \u09A8\u09BE", data: j };
        return { ok: true, summary: `\u099A\u09B2\u09AE\u09BE\u09A8 \u09AA\u09CD\u09B0\u09B8\u09C7\u09B8 (${(j.processes || []).length})`, data: { processes: j.processes || [] } };
      },
      /* ── phone calls: the assistant speaks to the caller ── */
      async call_state() {
        const c = global.ZY.Call;
        if (!c || !c.nativeOn()) return { ok: false, error: "no-native", summary: "\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7 \u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987 (APK \u09B2\u09BE\u0997\u09AC\u09C7)" };
        const st = global.ZY.Native.call.state();
        const label = st.ringing ? "\u0995\u09B2 \u0986\u09B8\u099B\u09C7" : st.inCall ? "\u0995\u09B2 \u099A\u09B2\u099B\u09C7" : "\u0995\u09CB\u09A8\u09CB \u0995\u09B2 \u09A8\u09C7\u0987";
        return { ok: true, summary: `${label}${st.number ? " \u2014 " + st.number : ""}`, data: st };
      },
      async call_answer() {
        const c = global.ZY.Call;
        if (!c) return { ok: false, error: "no-native", summary: "\u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987" };
        await c.ensurePermissions();
        return await c.answer();
      },
      async call_hangup() {
        const c = global.ZY.Call;
        if (!c) return { ok: false, error: "no-native", summary: "\u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987" };
        return await c.hangup();
      },
      async call_dial(args) {
        const c = global.ZY.Call;
        if (!c) return { ok: false, error: "no-native", summary: "\u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987" };
        await c.ensurePermissions();
        return await c.dial(args.number);
      },
      async call_auto_answer(args) {
        const c = global.ZY.Call;
        if (!c) return { ok: false, error: "no-native", summary: "\u0995\u09B2-\u09AC\u09CD\u09B0\u09BF\u099C \u09A8\u09C7\u0987" };
        const on = c.setAutoAnswer(args.on !== false);
        return { ok: true, summary: on ? "\u0985\u099F\u09CB-\u0995\u09B2-\u09B0\u09BF\u09B8\u09BF\u09AD \u099A\u09BE\u09B2\u09C1 \u2014 \u0995\u09B2 \u098F\u09B2\u09C7\u0987 \u0986\u09AE\u09BF \u09A7\u09B0\u09C7 \u09AB\u09C7\u09B2\u09AC" : "\u0985\u099F\u09CB-\u0995\u09B2-\u09B0\u09BF\u09B8\u09BF\u09AD \u09AC\u09A8\u09CD\u09A7", data: { autoAnswer: on } };
      },
      async memory_write(args) {
        await global.ZY.DB.remember(args.key, args.value, args.tag || "fact");
        global.ZY.ui && global.ZY.ui.toast(`\u09AE\u09A8\u09C7 \u09B0\u09BE\u0996\u09B2\u09BE\u09AE: ${args.key}`);
        return { ok: true, summary: `\u09AE\u09A8\u09C7 \u09B0\u09BE\u0996\u09BE \u09B9\u09B2\u09CB \u2014 ${args.key}`, data: { key: args.key, value: args.value } };
      },
      async memory_read(args) {
        const rows = await global.ZY.DB.recall(args.query);
        return { ok: true, summary: `${rows.length}\u099F\u09BF \u09B8\u09CD\u09AE\u09C3\u09A4\u09BF \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u09B2`, data: { rows } };
      },
      async geolocate() {
        if (!await askPermission("geolocation", "\u09B2\u09CB\u0995\u09C7\u09B6\u09A8")) return { ok: false, error: "denied", summary: "\u09B2\u09CB\u0995\u09C7\u09B6\u09A8 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A8\u09C7\u0987" };
        return await new Promise((resolve) => {
          navigator.geolocation.getCurrentPosition(
            (p) => resolve({ ok: true, summary: `\u09B2\u09CB\u0995\u09C7\u09B6\u09A8 \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u09B2 (${p.coords.latitude.toFixed(4)}, ${p.coords.longitude.toFixed(4)})`, data: { lat: p.coords.latitude, lon: p.coords.longitude, acc: p.coords.accuracy } }),
            (e) => resolve({ ok: false, error: e.message, summary: "\u09B2\u09CB\u0995\u09C7\u09B6\u09A8 \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF" }),
            { enableHighAccuracy: true, timeout: 12e3 }
          );
        });
      },
      /* ── GitHub: the user's own account, with the user's own token ── */
      async github_account() {
        const GH = global.ZY.GH;
        if (!GH) return { ok: false, error: "no-module", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u09AE\u09A1\u09BF\u0989\u09B2 \u09B2\u09CB\u09A1 \u09B9\u09AF\u09BC\u09A8\u09BF" };
        if (!GH.connected()) return { ok: false, error: "not-connected", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF \u2014 \u09B8\u09C7\u099F\u09BF\u0982\u09B8\u09C7 \u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09AC\u09B8\u09BE\u0993, \u09A4\u09BE\u09B0\u09AA\u09B0 \u0986\u09AC\u09BE\u09B0 \u099A\u09C7\u09B7\u09CD\u099F\u09BE \u0995\u09B0\u09CB" };
        const j = await GH.account();
        if (!j.ok) return { ok: false, error: j.error || "not-connected", summary: `\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u0985\u09CD\u09AF\u09BE\u0995\u09BE\u0989\u09A8\u09CD\u099F \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF${j.message ? ": " + j.message : ""}`, data: j };
        return { ok: true, summary: `\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1: ${j.login} (${j.repos.length}\u099F\u09BF \u09B0\u09BF\u09AA\u09CB\u099C\u09BF\u099F\u09B0\u09BF)`, data: j };
      },
      async github_publish(args) {
        const GH = global.ZY.GH;
        if (!GH) return { ok: false, error: "no-module", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u09AE\u09A1\u09BF\u0989\u09B2 \u09B2\u09CB\u09A1 \u09B9\u09AF\u09BC\u09A8\u09BF" };
        if (!GH.connected()) return { ok: false, error: "not-connected", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF \u2014 \u09B8\u09C7\u099F\u09BF\u0982\u09B8\u09C7 \u099F\u09CB\u0995\u09C7\u09A8 \u09AC\u09B8\u09BE\u09B2\u09C7 \u09B8\u09A4\u09CD\u09AF\u09BF\u0995\u09BE\u09B0\u09C7\u09B0 \u09B0\u09BF\u09AA\u09CB\u099C\u09BF\u099F\u09B0\u09BF \u09A4\u09C8\u09B0\u09BF \u09B9\u09AC\u09C7" };
        const files = args.files && typeof args.files === "object" ? { ...args.files } : {};
        if (args.html && !files["index.html"]) files["index.html"] = args.html;
        const j = await GH.publish({
          name: args.name,
          files,
          description: args.description,
          private: args.private,
          pages: args.pages !== false,
          owner: args.owner,
          branch: args.branch
        });
        if (!j.ok) return { ok: false, error: j.error || "github-failed", summary: `\u0997\u09BF\u099F\u09B9\u09BE\u09AC\u09C7 \u09AA\u09BE\u09AC\u09B2\u09BF\u09B6 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5${j.message ? ": " + j.message : ""}`, data: j };
        const lines = [`\u09B0\u09BF\u09AA\u09CB\u099C\u09BF\u099F\u09B0\u09BF ${j.created ? "\u09A4\u09C8\u09B0\u09BF \u09B9\u09AF\u09BC\u09C7\u099B\u09C7" : "\u0986\u0997\u09C7\u0987 \u099B\u09BF\u09B2"}: ${j.repo}`, `${j.committed.length}\u099F\u09BF \u09AB\u09BE\u0987\u09B2 \u0995\u09AE\u09BF\u099F \u09B9\u09AF\u09BC\u09C7\u099B\u09C7`];
        if (j.pages && j.pages.live) lines.push(`\u09B2\u09BE\u0987\u09AD \u09B8\u09BE\u0987\u099F (\u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09BE, \u09A8\u09A4\u09C1\u09A8 \u0995\u09A8\u099F\u09C7\u09A8\u09CD\u099F\u09B8\u09B9): ${j.pages.url}`);
        else if (j.pages && j.pages.url && j.pages.verified === false) lines.push(`Pages \u09AC\u09BF\u09B2\u09CD\u09A1 \u098F\u0996\u09A8\u09CB propagate \u09B9\u099A\u09CD\u099B\u09C7 (status ${j.pages.status}): ${j.pages.url} \u2014 \u0995\u09BF\u099B\u09C1\u0995\u09CD\u09B7\u09A3 \u09AA\u09B0 read_url \u09A6\u09BF\u09AF\u09BC\u09C7 \u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09CB`);
        else if (j.pages && j.pages.url) lines.push(`Pages \u09B8\u09BE\u0987\u099F: ${j.pages.url} (status ${j.pages.status})`);
        if (j.failed && j.failed.length) lines.push(`${j.failed.length}\u099F\u09BF \u09AB\u09BE\u0987\u09B2 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5`);
        return { ok: true, summary: lines.join(" | "), data: j };
      },
      async github_workflow(args) {
        const GH = global.ZY.GH;
        if (!GH || !GH.connected()) return { ok: false, error: "not-connected", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF" };
        const j = await GH.workflow(args);
        return j.ok ? { ok: true, summary: `\u0993\u09AF\u09BC\u09BE\u09B0\u09CD\u0995\u09AB\u09CD\u09B2\u09CB \u09B2\u09C7\u0996\u09BE \u09B9\u09B2\u09CB: ${j.path}`, data: j } : { ok: false, error: j.error || "failed", summary: `\u0993\u09AF\u09BC\u09BE\u09B0\u09CD\u0995\u09AB\u09CD\u09B2\u09CB \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AF\u09BC\u09A8\u09BF${j.message ? ": " + j.message : ""}`, data: j };
      },
      async github_actions(args) {
        const GH = global.ZY.GH;
        if (!GH || !GH.connected()) return { ok: false, error: "not-connected", summary: "\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF" };
        const j = await GH.actions(args);
        if (!j.ok) return { ok: false, error: j.error || "build-failed", summary: `\u09AC\u09BF\u09B2\u09CD\u09A1 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5${j.conclusion ? " (" + j.conclusion + ")" : ""}${j.stage ? " \u2014 " + j.stage : ""}`, data: j };
        const jobs = (j.jobs || []).map((x) => `${x.name}=${x.conclusion}`).join(", ");
        const arts = (j.artifacts || []).map((a) => a.name).join(", ");
        return { ok: true, summary: `\u09AC\u09BF\u09B2\u09CD\u09A1 \u09B8\u09AB\u09B2 \u2014 ${jobs}${arts ? " | \u0986\u09B0\u09CD\u099F\u09BF\u09AB\u09CD\u09AF\u09BE\u0995\u09CD\u099F: " + arts : ""}`, data: j };
      }
    };
    const S = (props, req, extra = {}) => ({ type: "object", properties: props, required: req, additionalProperties: false, ...extra });
    const Schemas = [
      { name: "code_box", description: 'Show or close the live code panel on screen. Call it with action:"open" (and optionally title/code) before you write a file, then action:"close" when the file is finished. Use action:"minimize"/"restore" to shrink it out of the way without losing what was written.', parameters: S({ action: { type: "string", enum: ["open", "close", "minimize", "restore"] }, title: { type: "string" }, code: { type: "string" } }, ["action"]) },
      { name: "todo_write", description: "Create or update the live task board. Call it after every tool result. Never give a final answer while a task is unfinished.", parameters: S({ todos: { type: "array", items: S({ id: { type: "string" }, title: { type: "string" }, status: { type: "string", enum: ["pending", "in_progress", "done"] } }, ["id", "title", "status"]) } }, ["todos"]) },
      { name: "open_app", description: "Open a real app on the phone (by package or by name).", parameters: S({ package: { type: "string" }, name: { type: "string" } }, []) },
      { name: "launch_url", description: "Open a URL in the browser. The URL is verified first; a dead link is reported, never opened.", parameters: S({ url: { type: "string" }, new_tab: { type: "boolean" } }, ["url"]) },
      { name: "verify_url", description: "Check that a link really exists (video, page, file) before you open it or tell the user it works. Returns the real HTTP status.", parameters: S({ url: { type: "string" } }, ["url"]) },
      { name: "read_screen", description: "Look at the current screen (screenshot/OCR) \u2014 use it to verify what happened after opening an app.", parameters: S({}, []) },
      { name: "look_camera", description: 'Look through the phone camera and see what is really in front of the user (food, a sign, a screen). Returns the photo so your vision reads it directly. Use facing:"front" for selfie view.', parameters: S({ facing: { type: "string", enum: ["back", "front"] } }, []) },
      { name: "tap", description: "Tap a point on the screen (x,y pixels).", parameters: S({ x: { type: "number" }, y: { type: "number" } }, ["x", "y"]) },
      { name: "swipe", description: "Swipe on the screen (scroll, back gesture).", parameters: S({ x1: { type: "number" }, y1: { type: "number" }, x2: { type: "number" }, y2: { type: "number" }, ms: { type: "number" } }, ["x1", "y1", "x2", "y2"]) },
      { name: "type_text", description: "Type into the focused input for real.", parameters: S({ text: { type: "string" }, enter: { type: "boolean" } }, ["text"]) },
      { name: "press_key", description: "Press a key: home, back, recents, enter, volume_up, volume_down.", parameters: S({ key: { type: "string" } }, ["key"]) },
      { name: "list_apps", description: "List every app installed on the phone (name + package) \u2014 use it to find which app to launch.", parameters: S({}, []) },
      { name: "screen_shot", description: "Take a real Android screenshot \u2014 you can then look at it with your vision.", parameters: S({}, []) },
      { name: "notify", description: "Post an Android notification.", parameters: S({ title: { type: "string" }, body: { type: "string" } }, ["body"]) },
      { name: "clipboard_read", description: "Read the clipboard.", parameters: S({}, []) },
      { name: "clipboard_write", description: "Write to the clipboard.", parameters: S({ text: { type: "string" } }, ["text"]) },
      { name: "share", description: "Open the share sheet (text / link / file).", parameters: S({ text: { type: "string" }, url: { type: "string" }, title: { type: "string" } }, []) },
      { name: "read_url", description: "Read a web page as text. Use it for research and verification.", parameters: S({ url: { type: "string" }, max_chars: { type: "number" } }, ["url"]) },
      { name: "read_media", description: "Read an image / audio / video / PDF from a URL \u2014 the multimodal model processes it. You can name a specific part.", parameters: S({ url: { type: "string" }, modality: { type: "string", enum: ["image", "audio", "video", "pdf", "auto"] }, part: { type: "string", description: 'e.g. "01:20" or "page 3"' } }, ["url"]) },
      { name: "generate_file", description: "Write a real file into the site workspace (index.html, app.js, ...).", parameters: S({ path: { type: "string" }, content: { type: "string" }, mode: { type: "string", enum: ["overwrite", "append"] } }, ["path", "content"]) },
      { name: "publish_website", description: "Publish a real website and get the live URL back (it verifies the URL itself).", parameters: S({ name: { type: "string" }, html: { type: "string" }, files: { type: "object", additionalProperties: { type: "string" } } }, ["name"]) },
      { name: "run_shell", description: "Run a real command on the host machine (Linux/macOS: bash/zsh, Windows: PowerShell/cmd). Without shell, the platform default is used.", parameters: S({ command: { type: "string" }, cwd: { type: "string" }, shell: { type: "string", enum: ["auto", "bash", "zsh", "sh", "fish", "powershell", "pwsh", "cmd"] }, timeout_ms: { type: "number" } }, ["command"]) },
      { name: "read_file", description: "Read any file (text or binary \u2014 for image/audio/video/PDF the model looks at it itself). A line range is allowed.", parameters: S({ path: { type: "string" }, from_line: { type: "number" }, to_line: { type: "number" } }, ["path"]) },
      { name: "write_file", description: "Write any file (create it or replace it whole). Missing folders are created.", parameters: S({ path: { type: "string" }, content: { type: "string" }, mode: { type: "string", enum: ["overwrite", "append"] } }, ["path", "content"]) },
      { name: "edit_file", description: "Find and replace text inside a file (all occurrences or one).", parameters: S({ path: { type: "string" }, find: { type: "string" }, replace: { type: "string" }, all: { type: "boolean" } }, ["path", "find", "replace"]) },
      { name: "list_dir", description: "List every file and folder inside a directory (name, size).", parameters: S({ path: { type: "string" } }, []) },
      { name: "find_files", description: "Find files by name pattern (e.g. **/*.js, *.md, config.*).", parameters: S({ path: { type: "string" }, pattern: { type: "string" } }, ["pattern"]) },
      { name: "grep_files", description: "Search text/regex inside files (which file, which line).", parameters: S({ path: { type: "string" }, query: { type: "string" }, regex: { type: "boolean" }, case_sensitive: { type: "boolean" } }, ["query"]) },
      { name: "delete_file", description: "Delete a file or folder (recursive:true deletes the contents too).", parameters: S({ path: { type: "string" }, recursive: { type: "boolean" } }, ["path"]) },
      { name: "web_search", description: "Do a real web search (DuckDuckGo) \u2014 for research, news and answering questions.", parameters: S({ query: { type: "string" }, count: { type: "number" } }, ["query"]) },
      { name: "http_request", description: "Any HTTP request (GET/POST/PUT/DELETE) with headers and body \u2014 for calling APIs.", parameters: S({ url: { type: "string" }, method: { type: "string", enum: ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"] }, headers: { type: "object", additionalProperties: { type: "string" } }, body: { type: "string" } }, ["url"]) },
      { name: "download_file", description: "Download a file from the web and save it (image/video/document).", parameters: S({ url: { type: "string" }, path: { type: "string" } }, ["url"]) },
      { name: "sys_info", description: "The real state of this machine: OS, CPU, RAM, disk, network, running processes.", parameters: S({}, []) },
      { name: "platform_info", description: "Where you are really running (mobile/desktop, OS, shell) \u2014 never issue another platform's commands.", parameters: S({}, []) },
      { name: "process_list", description: "See which processes are running right now.", parameters: S({}, []) },
      { name: "call_state", description: "Check whether a call is ringing or in progress (with the number).", parameters: S({}, []) },
      { name: "call_answer", description: "Answer the incoming call for real (you then speak on the call).", parameters: S({}, []) },
      { name: "call_hangup", description: "Hang up the current call.", parameters: S({}, []) },
      { name: "call_dial", description: "Place a call yourself.", parameters: S({ number: { type: "string" } }, ["number"]) },
      { name: "call_auto_answer", description: "Turn auto-answer on or off.", parameters: S({ on: { type: "boolean" } }, []) },
      { name: "memory_write", description: "Write a durable fact about the user into the local DB (remembered forever).", parameters: S({ key: { type: "string" }, value: { type: "string" }, tag: { type: "string" } }, ["key", "value"]) },
      { name: "memory_read", description: "Look up a memory in the local DB.", parameters: S({ query: { type: "string" } }, []) },
      { name: "geolocate", description: "Get the current location.", parameters: S({}, []) },
      /* ── GitHub: the user's own account, with the user's own token ── */
      { name: "github_account", description: "Check which GitHub account is connected and list its repositories. Call this first whenever the user mentions GitHub or asks you to publish code.", parameters: S({}, []) },
      { name: "github_publish", description: "Create a real GitHub repository (or reuse one) and commit files to it. Optionally turn on GitHub Pages so the site is live on the web. Returns the real repository URL and the live page URL.", parameters: S({ name: { type: "string" }, files: { type: "object", additionalProperties: { type: "string" }, description: "path -> file content" }, description: { type: "string" }, private: { type: "boolean" }, pages: { type: "boolean" }, owner: { type: "string" }, branch: { type: "string" } }, ["name", "files"]) },
      { name: "github_workflow", description: "Put a GitHub Actions workflow file into a repository (for example .github/workflows/build.yml). Use this to let Actions build and test anything.", parameters: S({ repo: { type: "string", description: "owner/name" }, content: { type: "string" }, path: { type: "string" }, branch: { type: "string" } }, ["repo", "content"]) },
      { name: "github_actions", description: "Run a GitHub Actions workflow in the user's own account, wait for it to finish, and report the real result of every job and the build artifacts it produced. This is a real Windows / Linux / macOS builder \u2014 use it to compile applications for all three platforms.", parameters: S({ repo: { type: "string", description: "owner/name" }, workflow: { type: "string" }, ref: { type: "string" }, files: { type: "object", additionalProperties: { type: "string" } }, timeout_ms: { type: "number" } }, ["repo"]) }
    ];
    const Tools = {
      schemas: Schemas,
      /** OpenAI-format tools array for the API (sorted fastest-first by caller). */
      apiTools() {
        return Schemas.map((s) => ({ type: "function", function: { name: s.name, description: s.description, parameters: s.parameters } }));
      },
      names() {
        return Schemas.map((s) => s.name);
      },
      native: Native,
      async execute(name, args) {
        const fn = impl[name];
        if (!fn) return { ok: false, error: "unknown-tool", summary: `\u0985\u099C\u09BE\u09A8\u09BE \u099F\u09C1\u09B2: ${name}` };
        let a = args;
        if (typeof a === "string") {
          try {
            a = JSON.parse(a);
          } catch {
            a = {};
          }
        }
        try {
          const t0 = performance.now();
          const r = await fn(a || {});
          r.ms = Math.round(performance.now() - t0);
          return r;
        } catch (e) {
          return { ok: false, error: String(e), summary: `\u099F\u09C1\u09B2 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: ${name}` };
        }
      }
    };
    global.ZY = global.ZY || {};
    Tools._netFetch = netFetch;
    global.ZY.Tools = Tools;
  })(window);
})();
