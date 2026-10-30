(() => {
  (function(global) {
    "use strict";
    const FAST_INDEX = ["zyraxon-quantum", "zyraxon-genesis", "zyraxon-cyber", "zyraxon-apex", "zyraxon-titan", "zyraxon-visionary", "zyraxon-neural", "zyraxon-codex", "zyraxon-pulse", "zyraxon-matrix"];
    const $ = (id) => document.getElementById(id);
    const store = () => global.ZY.store;
    const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
    const App = {
      models: [],
      async boot() {
        global.ZY.Native.init();
        await global.ZY.Platform.init();
        await global.ZY.DB.init();
        global.ZY.I18N.init(store().get("uiLang", "bn"));
        global.ZY.VOICE.STT.setLang(store().get("sttLang", ""));
        global.ZY.ui.init();
        global.ZY.BG.init();
        global.ZY.ORB.init("aura");
        global.ZY.Call.init();
        await global.ZY.Agent.init();
        App.bindNative();
        if (store().get("screenWatch", true)) global.ZY.Screen.start();
        const ch = global.ZY.characters[global.ZY.Agent.character] || global.ZY.characters.girlfriend;
        await global.ZY.ui.applyCharacter(ch, { silent: true });
        const bg = store().get("bg", "galaxy");
        $("set-bg").innerHTML = global.ZY.BG.scenes.map((s) => `<option value="${s.id}">${s.name}</option>`).join("");
        $("set-bg").value = bg;
        global.ZY.BG.set(bg, store().get("bgUrl", ""));
        global.ZY.BG.setSpeed(Number(store().get("bgSpeed", 1)));
        global.ZY.BG.setSound(!!store().get("bgSound", false));
        App.bindSettings();
        App.bindComposer();
        App.bindVoice();
        App.bindQuickbar();
        App.showVersion();
        await App.restore();
        await App.loadModels();
        global.ZY.Tools.native.available() && global.ZY.ui.toast("\u09A1\u09BF\u09AD\u09BE\u0987\u09B8 \u09AC\u09CD\u09B0\u09BF\u099C \u09AA\u09BE\u0993\u09AF\u09BC\u09BE \u0997\u09C7\u099B\u09C7 \u2713");
        await App.waitVoiceReady(6e3);
        const seen = store().get("greeted", false);
        if (!seen) {
          store().set("greeted", true);
          const chName = global.ZY.t("char." + ch.id);
          const hi = global.ZY.I18N.code === "bn" ? `\u09B9\u09CD\u09AF\u09BE\u09B2\u09CB! \u0986\u09AE\u09BF ZYRAXON \u2014 \u09A4\u09CB\u09AE\u09BE\u09B0 ${chName}\u0964 \u09AC\u09B2\u09CB, \u09B6\u09C1\u09B0\u09C1 \u0995\u09B0\u09BF\u0964` : `Hi, I am ZYRAXON \u2014 your ${chName}. Tell me what to do and I will start.`;
          global.ZY.ui.addMessage("ai", hi);
          global.ZY.VOICE.TTS.say(hi);
          await global.ZY.DB.addMessage({ role: "assistant", content: hi, ts: Date.now() });
        }
        if (global.ZY.VOICE.STT.available()) {
          global.ZY.VOICE.STT.init();
          if (store().get("alwaysMic", true)) App.armMic();
        }
        if ("serviceWorker" in navigator && !global.ZY.Native.app) {
          navigator.serviceWorker.register("/sw.js").catch(() => {
          });
        } else if ("serviceWorker" in navigator) {
          navigator.serviceWorker.getRegistrations().then((rs) => rs.forEach((r) => r.unregister())).catch(() => {
          });
        }
      },
      /** Rebuild the visible conversation and the task panel from the local DB.
       *  The user's whole history must survive app restarts and cloud loss. */
      async restore() {
        const rows = await global.ZY.DB.recentMessages(120);
        const chat = $("chat");
        const shown = rows.filter((m) => (m.role === "user" || m.role === "assistant") && (m.content || "").trim());
        if (shown.length) {
          chat.innerHTML = "";
          for (const m of shown) {
            const div = document.createElement("div");
            div.className = "msg " + (m.role === "user" ? "user" : "ai") + " restored";
            div.innerHTML = `<span class="body"></span>${m.tools && m.tools.length ? global.ZY.ui._chips(m.tools.map((n2) => ({ name: n2 }))) : ""}<span class="msg-time">${new Date(m.ts || Date.now()).toLocaleTimeString(void 0, { hour: "2-digit", minute: "2-digit" })}</span>`;
            div.querySelector(".body").textContent = String(m.content || "").replace(/\n?\[টুল:[^\]]*\]\s*$/g, "").trim();
            chat.appendChild(div);
          }
          global.ZY.ui._scroll(true);
        }
        const todos = await global.ZY.DB.loadTodos();
        if (todos && todos.length) {
          global.ZY.store.set("todos", todos);
          global.ZY.ui.renderTodos(todos);
        }
        const n = shown.length;
        if (n) global.ZY.ui.toast(`${n}\u099F\u09BF \u0986\u0997\u09C7\u09B0 \u0995\u09A5\u09BE \u09AE\u09A8\u09C7 \u09AA\u09A1\u09BC\u09C7\u099B\u09C7 \u2713`);
        return n;
      },
      async loadModels(forceRefresh = false) {
        let list = [];
        const hasSrv = !!(global.ZY.Platform && global.ZY.Platform.hasServer && global.ZY.Platform.hasServer());
        const modelsUrl = global.ZY.Platform && global.ZY.Platform.provider ? global.ZY.Platform.provider.models : "/api/public/zyraxon/v1/models";
        if (global.ZY.PROVIDERS) {
          try {
            list = await global.ZY.PROVIDERS.models(forceRefresh);
          } catch (e) {
            console.warn("Error calling PROVIDERS.models:", e);
          }
        }
        if (!list || !list.length) {
          const sources = hasSrv ? ["/api/models", modelsUrl] : [modelsUrl, "/api/models"];
          for (const u of sources) {
            try {
              const ctrl = new AbortController();
              const to = setTimeout(() => ctrl.abort(), 6e3);
              const r = await fetch(u, { cache: "no-store", signal: ctrl.signal }).finally(() => clearTimeout(to));
              if (!r.ok) continue;
              const j = await r.json();
              list = j.data || [];
              if (list.length) break;
            } catch {
            }
          }
        }
        if (!list || !list.length) {
          list = FAST_INDEX.map((id) => ({
            id,
            name: "ZYRAXON " + id.replace("zyraxon-", "").replace(/^./, (c) => c.toUpperCase()),
            description: "দ্রুত ও শক্তিশালী",
            input_modalities: ["text", "image", "video", "audio"]
          }));
        }
        const rank = (id) => {
          const i = FAST_INDEX.indexOf(id);
          return i === -1 ? 99 : i;
        };
        list.sort((a, b) => {
          const ap = a.provider === "zyraxon" ? 0 : (a.provider === "opencode" ? 1 : 2);
          const bp = b.provider === "zyraxon" ? 0 : (b.provider === "opencode" ? 1 : 2);
          if (ap !== bp) return ap - bp;
          return rank(a.id) - rank(b.id);
        });
        App.models = list;
        App._applyModels(list);
        App.refreshHealth();
        return list;
      },
      /** Re-sort the model list by live responsiveness once the probe finishes. */
      async refreshHealth() {
        let liveOrder = [];
        try {
          const hr = await fetch("/api/model-health");
          const hj = await hr.json();
          liveOrder = hj.order || [];
          global.ZY.Agent.healthOrder = liveOrder;
          global.ZY.Agent.stalled = new Set((hj.results || []).filter((x) => !x.ok).map((x) => x.model));
        } catch {
          return;
        }
        if (!liveOrder.length || !App.models) return;
        const rank = (id) => {
          const i = FAST_INDEX.indexOf(id);
          return i === -1 ? 99 : i;
        };
        const rankLive = (id) => {
          const i = liveOrder.indexOf(id);
          return i === -1 ? rank(id) : i;
        };
        const sorted = App.models.slice().sort((a, b) => rankLive(a.id) - rankLive(b.id) || rank(a.id) - rank(b.id));
        App._applyModels(sorted);
      },
      /** The provider cards in Settings. Each connected one gets a Remove button;
       *  each unconnected one gets a key box and a Save button that really tests
       *  the key against the provider before declaring it connected. */
      renderProviders() {
        const host = $("prov-list");
        if (!host || !global.ZY.PROVIDERS) return;
        const P = global.ZY.PROVIDERS;
        host.innerHTML = P.list.map((p) => {
          const on = P.connected(p.id);
          const active = P.active() === p.id;
          return `
        <div class="prov-card ${on ? "on" : ""}" data-prov="${p.id}">
          <div class="prov-head">
            <b>${esc(p.name)}</b>
            <span class="prov-state">${on ? active ? "\u099A\u09BE\u09B2\u09C1" : "\u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1" : "\u09A8\u09C7\u0987"}</span>
          </div>
          <p class="hint">${esc(p.hint)}</p>
          ${p.needsKey ? `<input type="password" class="prov-key" placeholder="${on ? "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" : "API key \u09AA\u09C7\u09B8\u09CD\u099F \u0995\u09B0\u09CB"}" autocomplete="off" />` : ""}
          <div class="prov-actions">
            ${p.needsKey ? `<button class="primary-btn prov-save" data-prov="${p.id}">${on ? "\u0986\u09AA\u09A1\u09C7\u099F" : "\u09B8\u09C7\u09AD \u0993 \u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F"}</button>` : ""}
            ${on && p.needsKey ? `<button class="ghost-btn prov-remove" data-prov="${p.id}">\u09AE\u09C1\u099B\u09CB</button>` : ""}
            ${on ? `<button class="ghost-btn prov-use" data-prov="${p.id}" ${active ? "disabled" : ""}>${active ? "\u09AC\u09CD\u09AF\u09AC\u09B9\u09C3\u09A4 \u09B9\u099A\u09CD\u099B\u09C7" : "\u098F\u099F\u09BE\u0987 \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0 \u0995\u09B0\u09CB"}</button>` : ""}
          </div>
        </div>`;
        }).join("");
        host.querySelectorAll(".prov-save").forEach((b) => b.addEventListener("click", async () => {
          const id = b.dataset.prov;
          const inp = host.querySelector(`.prov-card[data-prov="${id}"] .prov-key`);
          const v = String(inp && inp.value || "").trim();
          if (!v || /^•+$/.test(v)) return global.ZY.ui.toast("\u0986\u0997\u09C7 API key \u09AA\u09C7\u09B8\u09CD\u099F \u0995\u09B0\u09CB");
          b.textContent = "\u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u099B\u09BF\u2026";
          P.setKey(id, v);
          const list = await P.models().catch(() => []);
          const mine = list.filter((m) => m.provider === id);
          if (!mine.length) {
            P.setKey(id, "");
            App.renderProviders();
            return global.ZY.ui.toast("\u0995\u09C0 \u0995\u09BE\u099C \u0995\u09B0\u09B2 \u09A8\u09BE \u2014 \u0986\u09AC\u09BE\u09B0 \u09A6\u09C7\u0996\u09CB");
          }
          P.setActive(id);
          App.renderProviders();
          App.loadModels();
          global.ZY.ui.toast(`${P.list.find((x) => x.id === id).name} \u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1 (${mine.length}\u099F\u09BF \u09AE\u09A1\u09C7\u09B2)`);
        }));
        host.querySelectorAll(".prov-remove").forEach((b) => b.addEventListener("click", () => {
          const id = b.dataset.prov;
          P.setKey(id, "");
          if (P.active() === id) P.setActive("opencode");
          App.renderProviders();
          App.loadModels();
          global.ZY.ui.toast("\u0995\u09C0 \u09AE\u09C1\u099B\u09C7 \u09AB\u09C7\u09B2\u09BE \u09B9\u09B2\u09CB");
        }));
        host.querySelectorAll(".prov-use").forEach((b) => b.addEventListener("click", () => {
          P.setActive(b.dataset.prov);
          App.renderProviders();
          App.loadModels();
          global.ZY.ui.toast("\u09AA\u09CD\u09B0\u09CB\u09AD\u09BE\u0987\u09A1\u09BE\u09B0 \u09AC\u09A6\u09B2\u09BE\u09A8\u09CB \u09B9\u09B2\u09CB");
        }));
      },
      _applyModels(list) {
        App.models = list;
        let picked = global.ZY.store.get("userModel", "");
        if (picked && (picked.includes("gemini-2.0-flash") && picked.includes("opencode") || picked.includes("qwen-2.5-coder") || (picked.includes("llama-3.3-70b") && picked.includes("opencode")))) {
          picked = "";
          global.ZY.store.set("userModel", "");
        }
        global.ZY.ui.renderModels(list);
        if (picked && list.some((m) => m.id === picked)) {
          global.ZY.Agent.setModel(picked, false);
        } else {
          const stalled = global.ZY.Agent.stalled || /* @__PURE__ */ new Set();
          const healthy = list.filter((m) => !stalled.has(m.id));
          const rank = (id) => {
            const i = FAST_INDEX.indexOf(id);
            return i === -1 ? 99 : i;
          };
          const preferred = healthy.slice().sort((a, b) => rank(a.id) - rank(b.id))[0] || list[0];
          global.ZY.Agent.setModel(preferred.id, false);
        }
        $("model-label").textContent = (list.find((m) => m.id === global.ZY.Agent.model) || list[0]).name;
      },
      bindComposer() {
        const input = $("input");
        const grow = () => {
          input.style.height = "auto";
          input.style.height = Math.min(120, input.scrollHeight) + "px";
        };
        input.addEventListener("input", grow);
        input.addEventListener("keydown", (e) => {
          if (e.key === "Enter" && !e.shiftKey && innerWidth > 720) {
            e.preventDefault();
            App.submit(input.value);
          }
        });
        $("btn-send").addEventListener("click", () => App.submit(input.value));
        $("btn-stop").addEventListener("click", () => {
          global.ZY.Agent.stop();
          global.ZY.ui.endStream();
          App.setBusy(false);
        });
      },
      async submit(text) {
        text = (text || "").trim();
        const atts = global.ZY.Agent.attachments || [];
        if (!text && !atts.length) return;
        if (!text && atts.length) {
          text = atts.some((a) => a.kind === "video") ? "\u098F\u0987 \u09AD\u09BF\u09A1\u09BF\u0993\u099F\u09BE \u09A6\u09C7\u0996\u09CB" : atts.some((a) => a.kind === "audio") ? "\u098F\u0987 \u0985\u09A1\u09BF\u0993\u099F\u09BE \u09B6\u09CB\u09A8\u09CB" : "\u098F\u0987 \u099B\u09AC\u09BF\u099F\u09BE \u09A6\u09C7\u0996\u09CB";
        }
        $("input").value = "";
        $("input").style.height = "auto";
        if (global.ZY.Agent.busy) {
          $("artifact").hidden = true;
          await global.ZY.Agent.send(text);
          App.setBusy(false);
          return;
        }
        $("artifact").hidden = true;
        global.ZY.VOICE.TTS.stop();
        App.setBusy(true);
        global.ZY.ORB.setState("thinking");
        global.ZY.ui.processing(true, atts);
        try {
          await global.ZY.Agent.send(text);
        } finally {
          global.ZY.ui.processing(false);
          App.setBusy(false);
        }
      },
      setBusy(b) {
        $("btn-send").hidden = false;
        $("btn-stop").hidden = !b;
        document.querySelector(".composer-inner").classList.toggle("busy", b);
      },
      bindNative() {
        const n = global.ZY.Native;
        if (!n || !n.app) return;
        document.documentElement.classList.add("is-app");
        const info = n.info || {};
        global.ZY.ui.toast(`Android ${info.release || "?"} \u2022 TTS ${info.tts ? "\u2713" : "\u2717"} \u2022 STT ${info.stt ? "\u2713" : "\u2717"}`);
        n.device.onEvent((p) => {
          if (p.state === "accessibility") {
            global.ZY.ui.toast("Accessibility on \u2713");
            App.pushSystem("\u2699\uFE0F Accessibility service started \u2014 taps, typing and app launches are real now.");
          }
          if (p.state === "appOpened") App.pushSystem(`\u{1F4F1} App opened: ${p.text}`);
          if (p.state === "appMissing") App.pushSystem(`\u2753 App not found: ${p.text}`);
        });
        n.on("screen", (p) => {
          if (p.state === "changed") {
            const [pkg, label] = String(p.text || "").split("");
            global.ZY.Screen.onScreenChanged(pkg, label);
          } else if (p.state === "text") {
            global.ZY.Screen.onScreenText(p.text || "");
          }
        });
        n.device.onShot((p) => {
          if (p.state === "ok" && p.text) App.showScreenshot(p.text);
          else if (p.state === "bg" && p.text) global.ZY.Screen.onFrame(p.text);
        });
        document.addEventListener("visibilitychange", () => {
          if (!document.hidden && global.ZY.Screen.available()) global.ZY.Screen.start();
        });
        n.on("perm", (p) => {
          if (p.state === "granted") global.ZY.ui.toast(global.ZY.t("perm.granted"));
        });
      },
      pushSystem(text) {
        global.ZY.ui.addToolRow("system", {}, "ok", { ok: true, summary: text });
        global.ZY.DB.remember("event:" + Date.now(), text, "event");
      },
      showScreenshot(b64) {
        try {
          const url = "data:image/jpeg;base64," + b64;
          const host = document.getElementById("artifact-body");
          if (host) {
            host.innerHTML = "";
            const img = document.createElement("img");
            img.src = url;
            img.style.maxWidth = "100%";
            host.appendChild(img);
            document.getElementById("artifact-title").textContent = "Screenshot";
            document.getElementById("artifact").hidden = false;
          }
          global.ZY.Agent.lastShot = url;
        } catch {
        }
      },
      /**
       * Wait (bounded) until the native voice engines report ready.
       *
       * On a real phone the TTS engine binds a second or two after the page, and
       * the speech recogniser appears a moment after that. Returning immediately
       * on the first probe is what made the assistant mute and the microphone
       * dead on a real device while it worked fine on the emulator image (which
       * has no engines at all, so the wait simply times out).
       */
      async waitVoiceReady(ms) {
        const nat = global.ZY.Native;
        if (!nat || !nat.app) return true;
        const until = Date.now() + (ms || 6e3);
        for (; ; ) {
          const info = nat.refresh(true) || {};
          if (info.tts && info.stt) return true;
          if (Date.now() >= until) return !!(info.tts || info.stt);
          await new Promise((r) => setTimeout(r, 250));
        }
      },
      /** Open the mic and keep it open. Called at boot and after every reply. */
      armMic() {
        const stt = global.ZY.VOICE.STT;
        if (!stt.available()) return;
        if (store().get("alwaysMic", true)) stt.start();
      },
      bindVoice() {
        const btn = $("orb-btn");
        const stt = global.ZY.VOICE.STT;
        const TTS = global.ZY.VOICE.TTS;
        stt.onState = (s) => {
          btn.classList.toggle("live", s === "listening");
          $("voice-hall").classList.toggle("listening", s === "listening");
          const badge = $("live-badge");
          badge.textContent = s === "listening" ? global.ZY.t("status.listening") : s === "denied" ? global.ZY.t("status.mic_denied") : s === "thinking" ? global.ZY.t("status.thinking") : global.ZY.t("status.ready");
          if (s === "denied") global.ZY.ui.toast(global.ZY.t("toast.mic_blocked"));
        };
        stt.onPartial = (t) => {
          const text = String(t || "");
          if (TTS.isEcho(text)) {
            global.ZY.ui.setLive("");
            return;
          }
          if (text) App._lastHeard = text;
          global.ZY.ui.setLive(text);
          const box = $("input");
          if (box && document.activeElement !== box) {
            box.value = text;
            box.dispatchEvent(new Event("input", { bubbles: true }));
          }
        };
        stt.onFinal = (t) => {
          const text = String(t || "").trim() || String(App._lastHeard || "").trim();
          App._lastHeard = "";
          if (TTS.isEcho(text)) {
            global.ZY.ui.setLive("");
            return;
          }
          global.ZY.ui.setLive(text || "\u2026");
          if (text) App.submit(text);
        };
        btn.addEventListener("click", async () => {
          const nat = global.ZY.Native;
          const granted = nat && nat.app && nat.permission ? nat.permission.state("microphone").granted : true;
          if (!stt.available() && !granted) {
            const ok = await global.ZY.ui.permissionSheet({ kind: "microphone", label: "Microphone", real: true });
            if (ok) stt.start();
            return;
          }
          stt.start();
          global.ZY.ui.toast(global.ZY.t("toast.mic_on"));
        });
        $("voice-hall").addEventListener("dblclick", () => TTS.stop());
        $("char-chip").addEventListener("click", () => global.ZY.ui.openDrawer("characters"));
        $("live-caption").addEventListener("click", () => TTS.stop());
        $("speak-box").addEventListener("click", () => TTS.stop());
        global.ZY.ORB.onState((s) => {
          const h = $("voice-hall");
          h.classList.toggle("speaking", s === "speaking");
          h.classList.toggle("thinking", s === "thinking");
        });
      },
      bindQuickbar() {
        document.querySelectorAll(".quickbar button").forEach((b) => {
          if (b.id === "btn-cam") return;
          b.addEventListener("click", () => App.submit(b.dataset.q));
        });
        const cam = $("btn-cam");
        if (cam) cam.addEventListener("click", () => App.shootCamera());
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
          ui.toast("\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE \u09AC\u09A8\u09CD\u09A7 \u0995\u09B0\u09B2\u09BE\u09AE");
          ui.camState && ui.camState(false);
          return;
        }
        const ok = await ui.permissionSheet({ kind: "camera", label: global.ZY.t("perm.camera"), real: true });
        if (!ok) return;
        const on = await S.camStart("environment");
        ui.camState && ui.camState(on);
        if (on) {
          ui.toast("\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE \u099A\u09BE\u09B2\u09C1 \u2014 \u0986\u09AE\u09BF \u098F\u0996\u09A8 \u09AF\u09BE \u09A6\u09C7\u0996\u099B\u09BF \u09B8\u09C7\u099F\u09BE\u0987 \u09A6\u09C7\u0996\u09A4\u09C7 \u09AA\u09BE\u099A\u09CD\u099B\u09BF");
          App.submit("\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE\u09AF\u09BC \u098F\u0996\u09A8 \u09AF\u09BE \u09A6\u09C7\u0996\u099B \u09B8\u09C7\u099F\u09BE \u09A6\u09C7\u0996\u09C7 \u09AC\u09B2\u09CB \u0995\u09C0 \u0986\u099B\u09C7");
        } else {
          ui.toast("\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE \u099A\u09BE\u09B2\u09C1 \u09B9\u09B2\u09CB \u09A8\u09BE \u2014 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09BE\u0993 \u09A8\u09BF \u09AE\u09A8\u09C7 \u09B9\u09AF\u09BC");
        }
      },
      /** Attach images / video / audio and send them to the model as real parts. */
      bindAttachments() {
        const tray = $("attach-tray");
        const fileInput = $("file-input");
        const render = () => {
          const atts = global.ZY.Agent.attachments || [];
          tray.hidden = !atts.length;
          tray.innerHTML = atts.map((a, i) => `
          <div class="att" data-i="${i}" title="${a.name || a.kind}">
            ${a.kind === "image" ? `<img src="${a.dataUrl}" alt="" />` : a.kind === "video" ? `<video src="${a.dataUrl}" muted></video>` : a.kind === "pdf" ? '<span class="att-ico">\u{1F4C4}</span>' : a.kind === "file" ? '<span class="att-ico">\u{1F4CE}</span>' : '<span class="att-ico">\u{1F3B5}</span>'}
            <button class="x" data-rm="${i}">\u2715</button>
          </div>`).join("");
          $("btn-attach").classList.toggle("has", !!atts.length);
          tray.querySelectorAll("[data-rm]").forEach((b) => b.addEventListener("click", () => {
            global.ZY.Agent.attachments.splice(Number(b.dataset.rm), 1);
            render();
          }));
        };
        $("btn-attach").addEventListener("click", () => fileInput.click());
        fileInput.addEventListener("change", async () => {
          for (const f of fileInput.files) {
            if (f.size > 24 * 1024 * 1024) {
              global.ZY.ui.toast(global.ZY.t("toast.file_big"));
              continue;
            }
            const dataUrl = await new Promise((res) => {
              const r = new FileReader();
              r.onload = () => res(r.result);
              r.readAsDataURL(f);
            });
            const kind = App._kindOf(f.type, f.name);
            const format = kind === "audio" ? App._audioFormat(f.type, f.name) : void 0;
            global.ZY.Agent.attachments.push({ kind, dataUrl, name: f.name, format, mime: f.type });
          }
          fileInput.value = "";
          render();
          global.ZY.ui.toast(global.ZY.t("toast.attached"));
        });
        App._renderAttachTray = render;
      },
      /** What this file really is. A PDF is not audio, and a document is not a
       *  picture — classifying by name as well as MIME is what keeps them apart. */
      _kindOf(mime, name) {
        const m = String(mime || "").toLowerCase();
        const n = String(name || "").toLowerCase();
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("audio/")) return "audio";
        if (m === "application/pdf" || n.endsWith(".pdf")) return "pdf";
        if (/\.(png|jpe?g|gif|webp|bmp|heic|avif)$/.test(n)) return "image";
        if (/\.(mp4|webm|mov|mkv|avi|m4v)$/.test(n)) return "video";
        if (/\.(mp3|wav|m4a|aac|ogg|oga|opus|flac|amr|3gp)$/.test(n)) return "audio";
        return "file";
      },
      /** The container name the provider expects (`mp3`, not `mpeg`). */
      _audioFormat(mime, name) {
        const m = String(mime || "").toLowerCase();
        const n = String(name || "").toLowerCase();
        if (/mpeg|mp3/.test(m) || n.endsWith(".mp3")) return "mp3";
        if (/mp4|m4a|x-m4a/.test(m) || /\.(m4a|mp4)$/.test(n)) return "m4a";
        if (/wav|x-wav/.test(m) || n.endsWith(".wav")) return "wav";
        if (/ogg|opus/.test(m) || /\.(ogg|oga|opus)$/.test(n)) return "ogg";
        if (/webm/.test(m) || n.endsWith(".webm")) return "webm";
        if (/aac/.test(m) || n.endsWith(".aac")) return "aac";
        if (/flac/.test(m) || n.endsWith(".flac")) return "flac";
        const ext = n.split(".").pop();
        return ext && ext.length <= 5 ? ext : "mp3";
      },
      /** Fill the bottom-left badge with the version this build really is.
       *  On Android the package manager is authoritative (the APK cannot lie about
       *  itself); on desktop/web the server answers. Never blocks boot, and stays
       *  quiet if neither knows — a wrong version is worse than none. */
      showVersion() {
        const el = $("app-version");
        if (!el) return;
        const paint = (v) => {
          if (v) el.textContent = "v" + String(v).replace(/^v/i, "");
        };
        const native = global.ZY.Native.settings.appVersion && global.ZY.Native.settings.appVersion();
        if (native) return paint(native);
        fetch("/api/version").then((r) => r.ok ? r.json() : null).then((d) => d && paint(d.version)).catch(() => {
        });
      },
      bindSettings() {
        const s = store();
        const bind = (id, key, kind) => {
          const el = $(id);
          if (!el) return;
          if (kind === "check") {
            el.checked = !!s.get(key, el.checked);
            el.addEventListener("change", () => s.set(key, el.checked));
          } else if (kind === "range") {
            el.value = s.get(key, el.value);
            el.addEventListener("input", () => s.set(key, Number(el.value)));
          } else {
            el.value = s.get(key, el.value);
            el.addEventListener("change", () => s.set(key, el.value));
          }
        };
        bind("set-tts", "tts", "check");
        bind("set-alwaysmic", "alwaysMic", "check");
        bind("set-autosend", "autosend", "check");
        bind("set-rate", "rate", "range");
        bind("set-pitch", "pitch", "range");
        bind("set-glass", "glass", "range");
        bind("set-bg-speed", "bgSpeed", "range");
        bind("set-bg-sound", "bgSound", "check");
        bind("set-bg-vol", "bgVol", "range");
        bind("set-bg-url", "bgUrl", "text");
        bind("set-base", "base", "text");
        bind("set-proxy", "proxy", "text");
        bind("set-brand", "brand", "text");
        $("set-alwaysmic").addEventListener("change", () => {
          if ($("set-alwaysmic").checked) App.armMic();
          else global.ZY.VOICE.STT.stop();
        });
        const langs = global.ZY.I18N.langs;
        $("set-ui-lang").innerHTML = langs.map((l) => `<option value="${l.code}">${l.name}</option>`).join("");
        $("set-ui-lang").value = global.ZY.I18N.code;
        $("set-ui-lang").addEventListener("change", () => {
          const code = $("set-ui-lang").value;
          s.set("uiLang", code);
          global.ZY.I18N.init(code);
          global.ZY.VOICE.STT.setLang(store().get("sttLang", ""));
          $("set-stt-lang").value = global.ZY.VOICE.STT.lang;
          global.ZY.ui.renderChars();
          global.ZY.ui.toast(global.ZY.t("toast.saved"));
        });
        $("set-stt-lang").innerHTML = '<option value="">' + global.ZY.t("set.auto") + "</option>" + langs.map((l) => `<option value="${l.stt}">${l.name}</option>`).join("");
        $("set-stt-lang").value = store().get("sttLang", "");
        $("set-stt-lang").addEventListener("change", () => {
          const tag = $("set-stt-lang").value;
          s.set("sttLang", tag);
          global.ZY.VOICE.STT.setLang(tag);
          global.ZY.ui.toast(global.ZY.t("toast.saved"));
        });
        global.ZY.VOICE.TTS.enabled = !!s.get("tts", true);
        $("set-tts").addEventListener("change", () => {
          global.ZY.VOICE.TTS.enabled = $("set-tts").checked;
          if (!global.ZY.VOICE.TTS.enabled) global.ZY.VOICE.TTS.stop();
        });
        $("set-rate").addEventListener("input", () => {
          global.ZY.VOICE.TTS.rate = Number($("set-rate").value);
        });
        $("set-pitch").addEventListener("input", () => {
          global.ZY.VOICE.TTS.pitch = Number($("set-pitch").value);
        });
        $("set-glass").addEventListener("input", () => document.documentElement.style.setProperty("--glass-blur", $("set-glass").value + "px"));
        document.documentElement.style.setProperty("--glass-blur", s.get("glass", 14) + "px");
        $("set-bg").addEventListener("change", () => global.ZY.BG.set($("set-bg").value, $("set-bg-url").value));
        $("set-bg-url").addEventListener("change", () => {
          s.set("bg", "custom");
          $("set-bg").value = "custom";
          global.ZY.BG.set("custom", $("set-bg-url").value);
        });
        $("set-bg-speed").addEventListener("input", () => global.ZY.BG.setSpeed(Number($("set-bg-speed").value)));
        global.ZY.BG.setVolume(Number(s.get("bgVol", 0.55)));
        $("set-bg-vol").addEventListener("input", () => global.ZY.BG.setVolume(Number($("set-bg-vol").value)));
        $("set-bg-sound").addEventListener("change", () => {
          global.ZY.BG.setSound($("set-bg-sound").checked);
          if ($("set-bg-sound").checked) global.ZY.ui.toast(global.ZY.t("toast.bg_sound"));
        });
        $("set-base").addEventListener("change", () => global.ZY.ui.toast(global.ZY.t("toast.settings_saved")));
        $("set-brand").addEventListener("change", () => {
          const v = $("set-brand").value;
          document.title = v || "ZYRAXON";
        });
        App.renderProviders();
        const ghStatus = (text) => {
          const el = $("set-gh-status");
          if (el) el.textContent = text;
        };
        $("set-gh-token").value = global.ZY.GH.token() ? "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" : "";
        ghStatus(global.ZY.GH.connected() ? "\u099F\u09CB\u0995\u09C7\u09A8 \u09B8\u09C7\u09AD \u0995\u09B0\u09BE \u0986\u099B\u09C7" : "\u099F\u09CB\u0995\u09C7\u09A8 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09A8\u09BF");
        $("set-gh-save").addEventListener("click", async () => {
          const v = String($("set-gh-token").value || "").trim();
          if (!v || /^•+$/.test(v)) return global.ZY.ui.toast("\u0986\u0997\u09C7 \u099F\u09CB\u0995\u09C7\u09A8 \u09AA\u09C7\u09B8\u09CD\u099F \u0995\u09B0\u09CB");
          global.ZY.GH.setToken(v);
          $("set-gh-token").value = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
          ghStatus("\u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09BE \u09B9\u099A\u09CD\u099B\u09C7\u2026");
          const a = await global.ZY.GH.account();
          ghStatus(a.ok ? `\u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1: ${a.login} (${a.repos.length}\u099F\u09BF \u09B0\u09BF\u09AA\u09CB)` : `\u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: ${a.message || a.error}`);
          global.ZY.ui.toast(a.ok ? `\u0997\u09BF\u099F\u09B9\u09BE\u09AC \u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1: ${a.login}` : "\u099F\u09CB\u0995\u09C7\u09A8 \u09AF\u09BE\u099A\u09BE\u0987 \u09B9\u09AF\u09BC\u09A8\u09BF");
        });
        $("set-gh-check").addEventListener("click", async () => {
          ghStatus("\u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09BE \u09B9\u099A\u09CD\u099B\u09C7\u2026");
          const a = await global.ZY.GH.account();
          ghStatus(a.ok ? `\u0995\u09BE\u09A8\u09C7\u0995\u09CD\u099F\u09C7\u09A1: ${a.login} (${a.repos.length}\u099F\u09BF \u09B0\u09BF\u09AA\u09CB)` : `\u09AC\u09CD\u09AF\u09B0\u09CD\u09A5: ${a.message || a.error}`);
        });
        $("set-gh-clear").addEventListener("click", () => {
          global.ZY.GH.setToken("");
          $("set-gh-token").value = "";
          ghStatus("\u099F\u09CB\u0995\u09C7\u09A8 \u09AE\u09C1\u099B\u09C7 \u09AB\u09C7\u09B2\u09BE \u09B9\u09B2\u09CB");
        });
        const orbSel = $("set-orb");
        orbSel.innerHTML = global.ZY.ORB.styles.map((st) => `<option value="${st.id}">${st.name}</option>`).join("");
        orbSel.value = s.get("orbStyle", "pink");
        global.ZY.ORB.setStyle(orbSel.value);
        orbSel.addEventListener("change", () => {
          s.set("orbStyle", orbSel.value);
          global.ZY.ORB.setStyle(orbSel.value);
        });
        $("set-orb-cycle").addEventListener("click", () => {
          const styles = global.ZY.ORB.styles;
          const i = styles.findIndex((st) => st.id === global.ZY.ORB.style);
          const next = styles[(i + 1) % styles.length];
          orbSel.value = next.id;
          s.set("orbStyle", next.id);
          global.ZY.ORB.setStyle(next.id);
          global.ZY.ui.toast(global.ZY.t("toast.style") + next.name);
        });
        App.bindAttachments();
        const fillVoices = () => {
          const vs = global.ZY.VOICE.TTS.load();
          const sel = $("set-voice");
          if (!vs.length) {
            sel.innerHTML = "<option>" + global.ZY.t("set.auto") + "</option>";
            return;
          }
          sel.innerHTML = vs.map((v) => `<option value="${v.name}">${v.name} (${v.lang})</option>`).join("");
          const cur = global.ZY.VOICE.TTS.voice || global.ZY.VOICE.TTS.pickVoice();
          if (cur) sel.value = cur.name;
        };
        fillVoices();
        if ("speechSynthesis" in global) speechSynthesis.onvoiceschanged = fillVoices;
        $("set-voice").addEventListener("change", () => global.ZY.VOICE.TTS.setVoiceByName($("set-voice").value));
        $("set-voice-test").addEventListener("click", () => global.ZY.VOICE.TTS.test());
        $("set-open-browser").addEventListener("click", async () => {
          const here = location.origin;
          if (!global.ZY.Native || !global.ZY.Native.app) {
            global.open(here, "_blank", "noopener");
            return;
          }
          const base = (global.ZY.store.get("base") || here).replace(/\/$/, "");
          global.ZY.ui.toast(global.ZY.t("toast.browser_open"));
          try {
            const r = await fetch(base + "/api/open-browser", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ url: base })
            });
            const j = await r.json();
            if (!j.ok) global.ZY.ui.toast(global.ZY.t("toast.browser_none"));
          } catch {
            global.open(here, "_blank", "noopener");
          }
        });
        $("mem-add").addEventListener("click", async () => {
          const k = $("mem-key").value.trim(), v = $("mem-val").value.trim();
          if (!k || !v) return global.ZY.ui.toast(global.ZY.t("toast.mem_need"));
          await global.ZY.DB.remember(k, v, "manual");
          $("mem-key").value = "";
          $("mem-val").value = "";
          global.ZY.ui.renderMemory();
        });
        $("mem-export").addEventListener("click", async () => {
          const data = await global.ZY.DB.exportAll();
          const b = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
          const a = document.createElement("a");
          a.href = URL.createObjectURL(b);
          a.download = "zyraxon-memory.json";
          a.click();
        });
        $("mem-clear").addEventListener("click", async () => {
          await global.ZY.DB.clear("memory");
          global.ZY.ui.renderMemory();
          global.ZY.ui.toast(global.ZY.t("toast.mem_cleared"));
        });
        $("hist-clear").addEventListener("click", async () => {
          await global.ZY.DB.clear("messages");
          $("chat").innerHTML = "";
          global.ZY.ui.toast(global.ZY.t("toast.chat_cleared"));
        });
        $("btn-menu").addEventListener("click", () => {
          global.ZY.ui.openDrawer("settings");
        });
        App.injectMenuShortcuts();
      },
      injectMenuShortcuts() {
        const body = $("drawer-body");
        const nav = document.createElement("div");
        nav.className = "group";
        nav.style.flexDirection = "row";
        nav.style.flexWrap = "wrap";
        nav.innerHTML = ["settings", "permissions", "characters", "memory", "history", "skills"].map((k) => `<button class="ghost-btn" data-nav="${k}">${global.ZY.t("set." + (k === "skills" ? "skills" : k))}</button>`).join("");
        body.prepend(nav);
        nav.querySelectorAll("[data-nav]").forEach((b) => b.addEventListener("click", () => global.ZY.ui.openDrawer(b.dataset.nav)));
      },
      /** Build the user message, folding in any attached media as real parts. */
      _buildUserMessage(text) {
        return global.ZY.Agent._buildUserMessage(text);
      }
    };
    global.ZY.App = App;
    document.addEventListener("DOMContentLoaded", () => App.boot());
  })(window);
})();
