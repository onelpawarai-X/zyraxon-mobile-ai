(() => {
  (function(global) {
    "use strict";
    const $ = (id) => document.getElementById(id);
    const t = (k, v) => global.ZY.I18N.t(k, v);
    const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
    const linkify = (s) => {
      let out = esc(s);
      out = out.replace(
        /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
        (m, label, url) => `<a href="${url}" target="_blank" rel="noopener">${label}</a>`
      );
      out = out.replace(
        /(^|[\s(])(https?:\/\/[^\s<)]+)/g,
        (m, pre, url) => `${pre}<a href="${url}" target="_blank" rel="noopener">${url}</a>`
      );
      return out;
    };
    function labelFor(kind) {
      return {
        microphone: "Microphone",
        camera: "Camera",
        location: "Location",
        contacts: "Contacts",
        phone: "Phone calls",
        sms: "SMS",
        calendar: "Calendar",
        storage: "Storage",
        notifications: "Notifications",
        screen: "Read the screen",
        accessibility: "Accessibility service",
        overlay: "Display over other apps"
      }[kind] || kind;
    }
    const UI = {
      _el: {},
      _cur: null,
      _tools: 0,
      init() {
        const e = UI._el;
        e.chat = $("chat");
        e.stage = $("stage");
        e.taskboard = $("dock-todos");
        e.tbList = $("tb-list");
        e.tbCount = $("tb-count");
        e.tbProgress = $("tb-progress");
        e.toollog = $("dock-tools");
        e.tlList = $("tl-list");
        e.tlCount = $("tl-count");
        e.artifact = $("artifact");
        e.artBody = $("artifact-body");
        e.artTitle = $("artifact-title");
        e.codeOverlay = $("code-overlay");
        e.codeTitle = $("co-title");
        e.codeBody = $("co-code");
        e.live = $("live-text");
        e.badge = $("live-badge");
        e.think = $("think-strip");
        e.thinkText = $("think-text");
        e.drawer = $("drawer");
        e.drawerBody = $("drawer-body");
        e.drawerTitle = $("drawer-title");
        e.sheet = $("sheet-host");
        e.sheetTitle = $("sheet-title");
        e.sheetText = $("sheet-text");
        e.toast = $("toast-host");
        e.hall = $("voice-hall");
        e.modelMenu = $("model-menu");
        e.modelLabel = $("model-label");
        e.micBtn = $("orb-btn");
        e.speakBox = $("speak-box");
        e.speakText = $("speak-text");
        e.tray = $("attach-tray");
        e.attachBtn = $("btn-attach");
        e.proc = $("proc-strip");
        e.console = $("console");
        e.consoleBody = document.querySelector(".console-body");
        e.cbDot = $("cb-dot");
        UI.bindConsole();
        $("btn-menu") && $("btn-menu").addEventListener("click", () => UI.openDrawer("settings"));
        $("btn-settings").addEventListener("click", () => UI.openDrawer("settings"));
        $("drawer-close").addEventListener("click", UI.closeDrawer);
        e.drawer.addEventListener("click", (ev) => {
          if (ev.target === e.drawer) UI.closeDrawer();
        });
        $("artifact-close").addEventListener("click", () => e.artifact.hidden = true);
        $("artifact-copy").addEventListener("click", async () => {
          const txt = UI._artTop && UI._artTop.body || e.artBody.textContent;
          try {
            await navigator.clipboard.writeText(txt);
            UI.toast(t("toast.copied"));
          } catch {
            UI.toast(t("toast.copy_fail"));
          }
        });
        $("artifact-open").addEventListener("click", () => {
          const t2 = (UI._artTop && UI._artTop.body || e.artBody.textContent).trim();
          if (/^https?:\/\//.test(t2)) global.open(t2, "_blank");
          else {
            const b = new Blob([t2], { type: "text/plain" });
            global.open(URL.createObjectURL(b), "_blank");
          }
        });
        $("tb-toggle").addEventListener("click", () => UI.hidePanel("todos"));
        $("tl-toggle").addEventListener("click", () => UI.hidePanel("tools"));
        if (e.taskboard) UI.makeDraggable(e.taskboard);
        if (e.toollog) UI.makeDraggable(e.toollog);
        $("co-close").addEventListener("click", () => UI.closeCodeOverlay());
        $("co-min").addEventListener("click", () => UI.toggleCodeMin());
        $("model-btn").addEventListener("click", (ev) => {
          ev.stopPropagation();
          e.modelMenu.hidden = !e.modelMenu.hidden;
        });
        document.addEventListener("click", () => {
          e.modelMenu.hidden = true;
        });
        document.addEventListener("keydown", (ev) => {
          if (ev.key === "Escape") e.modelMenu.hidden = true;
        });
        e.modelMenu.addEventListener("click", (ev) => ev.stopPropagation());
      },
      /* ── chat ── */
      addMessage(role, text, meta = {}) {
        const div = document.createElement("div");
        div.className = "msg " + (role === "user" ? "user" : "ai");
        div.innerHTML = `<span class="body"></span>${meta.tools ? UI._chips(meta.tools) : ""}${UI._media(meta.attachments)}<span class="msg-time">${(/* @__PURE__ */ new Date()).toLocaleTimeString("bn-BD", { hour: "2-digit", minute: "2-digit" })}</span>`;
        const body = div.querySelector(".body");
        if (role === "user") body.textContent = text;
        else body.innerHTML = linkify(text);
        UI._el.chat.appendChild(div);
        UI._scroll(true);
        UI.consolePing();
        return div;
      },
      /** The media that was sent with a message, shown inside its bubble. */
      _media(atts) {
        if (!atts || !atts.length) return "";
        const one = (a) => a.kind === "image" ? `<img src="${a.dataUrl}" alt="" />` : a.kind === "video" ? `<video src="${a.dataUrl}" muted playsinline></video>` : `<span class="aud">\u{1F3B5} ${esc(a.name || "\u0985\u09A1\u09BF\u0993")}</span>`;
        return `<span class="msg-media">${atts.map(one).join("")}</span>`;
      },
      _chips(tools) {
        const items = tools.map((t2) => `<span class="chip ${t2.ok === false ? "err" : t2.url ? "art" : ""}">${esc(t2.name)}${t2.url ? " \u2197" : ""}</span>`).join("");
        return `<span class="msg-tools">${items}</span>`;
      },
      startStream() {
        UI.processing(false);
        UI.setMonitor("");
        UI._cur = document.createElement("div");
        UI._cur.className = "msg ai streaming";
        UI._cur.innerHTML = '<span class="body"></span>';
        UI._el.chat.appendChild(UI._cur);
        UI._streamText = "";
        UI.setBadge(t("status.writing"));
        UI._el.chat.parentElement && UI._el.chat.parentElement.classList;
      },
      /** Replace the whole streaming answer (used by the politeness filter). */
      setStream(full) {
        if (!UI._cur) UI.startStream();
        UI._streamText = full;
        UI._cur.querySelector(".body").innerHTML = linkify(full);
        UI.setLive(full);
        const fences = full.match(/```/g);
        if (fences && fences.length >= 1) UI.showStreamCode(full);
        UI._scroll();
      },
      streamToken(tok) {
        if (!UI._cur) UI.startStream();
        if (!UI._streamText && UI._thinkMsg) UI.hideThinking();
        UI._streamText += tok;
        UI._cur.querySelector(".body").innerHTML = linkify(UI._streamText);
        UI.setLive(UI._streamText);
        const fences = UI._streamText.match(/```/g);
        if (fences && fences.length >= 1) UI.showStreamCode(UI._streamText);
        UI._scroll();
      },
      /**
       * Pull every fenced block out of the text being streamed and show it in the
       * code panel, with the language of the first fence as the label.
       */
      showStreamCode(text) {
        const blocks = [...String(text).matchAll(/```([\w+.-]*)\n([\s\S]*?)(?:```|$)/g)];
        if (!blocks.length) return;
        const lang = (blocks[0][1] || "code").trim() || "code";
        const body = blocks.map((b) => b[2].replace(/\n$/, "")).join("\n\n");
        if (!body.trim()) return;
        UI.pushArtifact("Code \xB7 " + lang, body, "code");
        UI._codeLive = true;
        UI.showCodeOverlay("Writing code \xB7 " + lang, body);
      },
      /**
       * The live code overlay on top of the whole screen. The user asked to watch
       * the code being written line by line on the phone itself, so this is fed
       * the same streamed text and scrolls to the newest line as it arrives.
       */
      showCodeOverlay(title, code) {
        const e = UI._el;
        const nat = global.ZY.Native;
        if (nat && nat.app) {
          if (nat.settings.overlayAllowed()) nat.settings.overlayShow(title, String(code).slice(-4e3));
          else UI._overlayAskOnce();
        }
        if (!e.codeOverlay) return;
        e.codeTitle.textContent = title;
        e.codeBody.textContent = code;
        e.codeOverlay.hidden = false;
        e.codeOverlay.classList.toggle("min", !!UI._codeMin);
        const pre = e.codeBody.parentElement;
        if (pre) pre.scrollTop = pre.scrollHeight;
      },
      /**
       * Reveal a finished file line by line in the code box, so the user watches
       * it being written instead of seeing a wall of text appear at once. The
       * timer is kept so a second file restarts cleanly instead of interleaving.
       */
      typeCodeBox(title, code, lang) {
        const e = UI._el;
        if (!e.codeOverlay) return;
        clearInterval(UI._typeTimer);
        const lines = String(code == null ? "" : code).split("\n");
        e.codeTitle.textContent = title || "Writing code \xB7 " + (lang || "code");
        e.codeBody.textContent = "";
        e.codeOverlay.hidden = false;
        e.codeOverlay.classList.toggle("min", !!UI._codeMin);
        const per = global.ZY.Native && global.ZY.Native.app ? 1 : 2;
        let i = 0;
        const step = () => {
          const chunk = lines.slice(i, i + per).join("\n");
          const first = i === 0;
          i += per;
          e.codeBody.textContent += (first ? "" : "\n") + chunk;
          const pre = e.codeBody.parentElement;
          if (pre) pre.scrollTop = pre.scrollHeight;
          if (i >= lines.length) clearInterval(UI._typeTimer);
        };
        step();
        UI._typeTimer = setInterval(step, 28);
      },
      /** Shrink the code box to its title bar, or bring the code back. */
      toggleCodeMin() {
        const e = UI._el;
        UI._codeMin = !UI._codeMin;
        const nat = global.ZY.Native;
        if (nat && nat.app && nat.settings.overlayMinimize) nat.settings.overlayMinimize(UI._codeMin);
        if (e.codeOverlay) e.codeOverlay.classList.toggle("min", !!UI._codeMin);
      },
      /** Close the code box here and, on the phone, above other apps as well. */
      closeCodeOverlay() {
        const e = UI._el;
        UI._codeMin = false;
        if (e.codeOverlay) {
          e.codeOverlay.hidden = true;
          e.codeOverlay.classList.remove("min");
        }
        const nat = global.ZY.Native;
        if (nat && nat.app && nat.settings.overlayHide) {
          nat.settings.overlayMinimize(false);
          nat.settings.overlayHide();
        }
      },
      /** Ask for the overlay grant once, on the first code the assistant writes. */
      _overlayAskOnce() {
        if (UI._askedOverlay) return;
        UI._askedOverlay = true;
        UI.permissionSheet({ kind: "overlay", label: t("perm.overlay"), real: true });
      },
      endStream(keepBadge) {
        if (UI._cur) UI._cur.classList.remove("streaming");
        UI._cur = null;
        UI._codeLive = false;
        if (!keepBadge) UI.setBadge(t("status.ready"));
      },
      setLive(text) {
        const s = String(text || "");
        UI._el.live.textContent = s.slice(-320) || t("home.hint");
      },
      /**
       * The speech box under the orb: what the voice is saying right now. It is
       * fed one spoken chunk at a time from the TTS queue, so the box scrolls in
       * step with the voice instead of showing the whole answer at once — and it
       * stays attached to the chat, as the user asked.
       */
      setSpeak(text, on) {
        const e = UI._el;
        if (!e.speakBox) return;
        if (!on || !text) {
          e.speakBox.hidden = true;
          e.speakText.textContent = "";
          UI._speakLines = [];
          return;
        }
        UI._speakLines = UI._speakLines || [];
        const last = UI._speakLines[UI._speakLines.length - 1];
        if (last !== text) UI._speakLines.push(text);
        if (UI._speakLines.length > 6) UI._speakLines.shift();
        e.speakText.textContent = UI._speakLines.join(" ");
        e.speakBox.hidden = false;
      },
      setBadge(t2) {
        UI._el.badge.textContent = t2;
      },
      /** The camera button reflects the real stream state, on or off. */
      camState(on) {
        const b = document.getElementById("btn-cam");
        if (b) {
          b.classList.toggle("on", !!on);
          b.setAttribute("aria-pressed", on ? "true" : "false");
        }
      },
      /**
       * "Uploading / reading your media" state. It has to appear the instant send
       * is pressed — a base64 video takes a while to reach the model, and with no
       * feedback the user cannot tell a slow upload from a failed one.
       */
      processing(on, atts) {
        const el = UI._el.proc;
        if (!el) return;
        if (!on) {
          const left = 700 - (Date.now() - (UI._procAt || 0));
          clearTimeout(UI._procTimer);
          UI._procTimer = setTimeout(() => {
            el.hidden = true;
            document.body.classList.remove("proc-on");
          }, Math.max(0, left));
          return;
        }
        UI._procAt = Date.now();
        clearTimeout(UI._procTimer);
        const a = atts && atts[0] || {};
        const what = t("proc." + (a.kind === "video" ? "video" : a.kind === "audio" ? "audio" : a.kind === "image" ? "image" : "file"));
        el.querySelector(".proc-text").textContent = t("proc.sending", { what });
        el.hidden = false;
        document.body.classList.add("proc-on");
        UI.setBadge(t("status.processing"));
      },
      /** Mark attachments as actually handed over, then clear the tray. */
      markSent() {
        const tray = UI._el.tray;
        if (!tray || tray.hidden) return;
        tray.querySelectorAll(".att").forEach((d) => d.classList.add("sent"));
        UI._el.attachBtn && UI._el.attachBtn.classList.remove("has");
        setTimeout(() => {
          tray.hidden = true;
          tray.innerHTML = "";
        }, 450);
      },
      setThinking(text) {
        if (!text) {
          UI._el.think.hidden = true;
          if (UI._thinkMsg) {
            UI._thinkMsg.hidden = true;
            UI._thinkMsg.classList.remove("live");
          }
          return;
        }
        const last = text.trim().split("\n").filter(Boolean).slice(-1)[0] || t("home.thinking");
        UI._el.thinkText.textContent = last.slice(0, 160);
        UI._el.think.hidden = false;
        UI.setBadge(t("status.thinking"));
        global.ZY.ORB.setState("thinking");
        UI._thinkInline(text);
      },
      /**
       * The live "thinking" bubble inside the conversation. It is created on the
       * first reasoning token, updated as more arrive, and hidden the moment real
       * answer text starts streaming.
       */
      _thinkInline(text) {
        if (!UI._thinkMsg) {
          const div = document.createElement("div");
          div.className = "msg ai think live";
          div.innerHTML = '<span class="think-head"><i class="tdot"></i><i class="tdot"></i><i class="tdot"></i>' + esc(t("home.thinking")) + '</span><span class="body think-body"></span>';
          if (UI._cur) UI._el.chat.insertBefore(div, UI._cur);
          else UI._el.chat.appendChild(div);
          UI._thinkMsg = div;
        }
        UI._thinkMsg.hidden = false;
        const body = UI._thinkMsg.querySelector(".think-body");
        body.textContent = String(text || "").trim().slice(-1200);
        UI._scroll();
      },
      /**
       * Open the thinking bubble immediately, with an animated "thinking" header
       * and no reasoning text yet. Called the moment the user sends, so the wait
       * before the first token is visibly her thinking rather than dead air.
       */
      showThinkingNow() {
        UI._thinkInline("");
        UI.setBadge(t("status.thinking"));
        global.ZY.ORB.setState("thinking");
      },
      /** The answer is arriving — stop showing the thinking bubble. */
      hideThinking() {
        if (UI._thinkMsg) {
          UI._thinkMsg.hidden = true;
          UI._thinkMsg.classList.remove("live");
        }
        UI._el.think.hidden = true;
      },
      /* ── todos ── */
      renderTodos(list) {
        const e = UI._el;
        if (!list || !list.length) {
          e.tbList.innerHTML = "";
          e.tbCount.textContent = "0/0";
          e.tbProgress.style.width = "0%";
          UI.setTaskCount(0, 0);
          if (e.taskboard) e.taskboard.hidden = true;
          return;
        }
        if (e.taskboard) {
          UI.showPanel("todos");
          e.tbList.classList.remove("collapsed");
        }
        const done = list.filter((t2) => t2.status === "done").length;
        e.tbCount.textContent = `${done}/${list.length}`;
        e.tbProgress.style.width = done / list.length * 100 + "%";
        UI.setTaskCount(done, list.length);
        const html = list.map((t2) => `
        <li class="tb-item ${t2.status === "done" ? "done" : t2.status === "in_progress" ? "active" : ""}">
          <span class="tick">${t2.status === "done" ? '<svg viewBox="0 0 24 24"><path d="M4 12l5 5L20 6"/></svg>' : t2.status === "in_progress" ? "\u2022" : ""}</span>
          <span class="t">${esc(t2.title)}</span>
        </li>`).join("");
        if (html !== UI._tbHtml) {
          UI._tbHtml = html;
          e.tbList.innerHTML = html;
        }
      },
      /* ── tool log ── */
      addToolRow(name, args, state, res) {
        const e = UI._el;
        if (!e.toollog) return;
        UI.showPanel("tools");
        const argStr = Object.entries(args || {}).map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`).join(" ").slice(0, 60);
        if (state === "running") {
          const row = document.createElement("div");
          row.className = "tl-row";
          row.dataset.tool = name + ":" + argStr;
          row.innerHTML = `<span class="spinner"></span><span class="tl-name">${esc(name)}</span><span class="tl-arg">${esc(argStr)}</span><span class="tl-res">running\u2026</span>`;
          e.tlList.prepend(row);
          UI._tools++;
        } else {
          const row = e.tlList.querySelector(`.tl-row[data-tool="${CSS.escape(name + ":" + argStr)}"]`);
          if (row) {
            row.querySelector(".spinner") && row.querySelector(".spinner").remove();
            row.classList.toggle("err", !res.ok);
            const resEl = row.querySelector(".tl-res");
            const full = String(res.summary || "");
            const suffix = res.ms ? ` \xB7 ${res.ms}ms` : "";
            if (/https?:\/\//.test(full)) {
              resEl.innerHTML = linkify(full) + esc(suffix);
              resEl.classList.add("has-link");
            } else {
              resEl.textContent = full.slice(0, 70) + suffix;
            }
          }
        }
        e.tlCount.textContent = UI._tools;
        UI._scroll();
      },
      /* ── artifact box: a stack, so earlier code is never lost ── */
      /**
       * Every generated output used to replace the one before it, so a second
       * file wiped the first off the screen — the "আগের কোড লুট হয়ে যাচ্ছে"
       * complaint. Each result is now kept as a card in a scrollable stack, with
       * the newest first, and the panel reopens on the newest one.
       */
      showArtifact(title, body, kind) {
        UI.pushArtifact(title, body, kind);
      },
      pushArtifact(title, body, kind) {
        UI._arts = UI._arts || [];
        const top = UI._arts[0];
        const sig = title + "\0" + String(body).slice(0, 200);
        if (top && top.title === title) {
          top.body = body;
          top.kind = kind;
          top.sig = sig;
          top.ts = Date.now();
          if (top.node) {
            const code = top.node.querySelector("code");
            if (code) code.textContent = body;
            const pre = top.node.querySelector("pre");
            if (pre) pre.scrollTop = pre.scrollHeight;
            return;
          }
          UI.renderArtifacts();
          return;
        }
        UI._arts = UI._arts.filter((a) => a.sig !== sig);
        UI._arts.unshift({ title, body, kind, sig, ts: Date.now() });
        if (UI._arts.length > 12) UI._arts.length = 12;
        UI.renderArtifacts();
      },
      renderArtifacts() {
        const e = UI._el;
        const list = UI._arts || [];
        if (!list.length) {
          e.artifact.hidden = true;
          e.artBody.innerHTML = "";
          return;
        }
        e.artifact.hidden = false;
        e.artTitle.textContent = list[0].title;
        const wrap = document.createElement("div");
        wrap.className = "art-stack";
        list.forEach((a, i) => {
          const card = document.createElement("article");
          card.className = "art-card" + (i === 0 ? " newest" : "");
          const head = document.createElement("header");
          head.className = "art-card-head";
          head.innerHTML = `<b></b><span class="art-time"></span>`;
          head.querySelector("b").textContent = a.title;
          head.querySelector(".art-time").textContent = new Date(a.ts).toLocaleTimeString(void 0, { hour: "2-digit", minute: "2-digit" });
          const pre = document.createElement("pre");
          if (a.kind === "image" && /^data:image/.test(a.body)) {
            const img = document.createElement("img");
            img.src = a.body;
            img.style.maxWidth = "100%";
            img.style.borderRadius = "12px";
            pre.appendChild(img);
          } else {
            const code = document.createElement("code");
            code.textContent = a.body;
            pre.appendChild(code);
          }
          card.appendChild(head);
          card.appendChild(pre);
          wrap.appendChild(card);
          a.node = card;
        });
        e.artBody.innerHTML = "";
        e.artBody.appendChild(wrap);
        UI._artTop = list[0];
      },
      clearArtifacts() {
        UI._arts = [];
        UI.renderArtifacts();
      },
      /* ── model menu (fastest first) ── */
      renderModels(models) {
        const e = UI._el;
        const seq = !!(global.ZY.Agent && global.ZY.Agent.sequential);
        const seqRow = `
        <button class="model-item seq-item ${seq ? "active" : ""}" id="seq-toggle">
          <span class="mi-top">
            <span class="badge-fast">${seq ? "\u099A\u09BE\u09B2\u09C1" : "\u09B8\u09AC"}</span>
            <span>\u09B8\u09AC \u09AE\u09A1\u09C7\u09B2 \u098F\u0995\u09C7 \u098F\u0995\u09C7</span>
            <span class="badge-vis">sequential</span>
          </span>
          <span class="mi-desc">\u09AA\u09CD\u09B0\u09A4\u09BF\u099F\u09BF \u09A7\u09BE\u09AA\u09C7 \u09AA\u09B0\u09C7\u09B0 \u09AE\u09A1\u09C7\u09B2 \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7 \u2014 \u09B8\u09AC\u09BE\u0987 \u098F\u0995\u09B8\u09BE\u09A5\u09C7 \u098F\u0995\u0987 \u0995\u09BE\u099C\u09C7</span>
        </button>`;
        e.modelMenu.innerHTML = seqRow + models.map((m, i) => `
        <button class="model-item ${m.id === global.ZY.Agent.model ? "active" : ""}" data-id="${esc(m.id)}">
          <span class="mi-top">
            ${i === 0 ? '<span class="badge-fast">fastest</span>' : ""}
            <span>${esc(m.name || m.display_name || m.id)}</span>
            <span class="badge-vis">${(m.input_modalities || ["text"]).join(" \xB7 ")}</span>
          </span>
          <span class="mi-desc">${esc(m.description || "")}</span>
        </button>`).join("");
        e.modelMenu.querySelectorAll(".model-item").forEach((b) => b.addEventListener("click", () => {
          const id = b.dataset.id;
          global.ZY.Agent.setModel(id, true);
          const m = models.find((x) => x.id === id);
          e.modelLabel.textContent = m ? m.name || m.display_name : id;
          e.modelMenu.querySelectorAll(".model-item").forEach((x) => x.classList.toggle("active", x === b));
          e.modelMenu.hidden = true;
          UI.toast(t("toast.model") + e.modelLabel.textContent);
        }));
        const cur = models.find((m) => m.id === global.ZY.Agent.model) || models[0];
        if (cur) e.modelLabel.textContent = cur.name || cur.display_name || cur.id;
        const st = e.modelMenu.querySelector("#seq-toggle");
        if (st) st.addEventListener("click", (ev) => {
          ev.stopPropagation();
          const on = global.ZY.Agent.setSequential(!global.ZY.Agent.sequential);
          st.classList.toggle("active", on);
          const b = st.querySelector(".badge-fast");
          if (b) b.textContent = on ? "\u099A\u09BE\u09B2\u09C1" : "\u09B8\u09AC";
          UI.toast(on ? "\u09B8\u09AC \u09AE\u09A1\u09C7\u09B2 \u098F\u0995\u09C7 \u098F\u0995\u09C7 \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7" : "\u098F\u0995 \u09AE\u09A1\u09C7\u09B2\u09C7\u0987 \u099A\u09B2\u09AC\u09C7");
        });
      },
      /* ── console sheet: chat / todos / tools ── */
      bindConsole() {
        const e = UI._el;
        const open = (tab) => {
          e.console.hidden = false;
          UI._overlay(true);
          UI.consoleTab(tab || UI._consoleTab || "chat");
          if (e.cbDot) e.cbDot.hidden = true;
        };
        const close = () => {
          e.console.hidden = true;
          UI._overlay(false);
        };
        $("btn-console").addEventListener("click", () => open());
        $("console-close").addEventListener("click", close);
        document.querySelectorAll(".ctab").forEach((b) => b.addEventListener("click", () => UI.consoleTab(b.dataset.tab, true)));
        $("console-clear").addEventListener("click", () => {
          e.chat.innerHTML = "";
          global.ZY.Agent && (global.ZY.Agent.history = global.ZY.Agent.history.filter((m) => m.role === "system"));
          UI.toast(t("toast.chat_cleared"));
        });
        e.console.addEventListener("click", (ev) => {
          if (ev.target === e.console) close();
        });
        UI._consoleTab = "chat";
      },
      consoleTab(tab) {
        UI._consoleTab = tab;
        document.querySelectorAll(".ctab").forEach((b) => {
          if (b.dataset.tab === "chat") b.classList.toggle("on", tab === "chat");
        });
        document.querySelectorAll(".cview").forEach((v) => v.classList.toggle("on", v.dataset.view === "chat"));
        if (tab === "todos") UI.togglePanel("todos", true);
        else if (tab === "tools") UI.togglePanel("tools", true);
        else {
          UI.hidePanel("todos");
          UI.hidePanel("tools");
        }
        UI.syncPanelTabs();
        const body = UI._el.consoleBody;
        if (body) body.scrollTop = body.scrollHeight;
      },
      /** Reveal one panel without touching the other. */
      showPanel(which, force) {
        const e = UI._el;
        const el = which === "todos" ? e.taskboard : e.toollog;
        if (!el) return;
        UI._panelPref = UI._panelPref || {};
        if (force === true) UI._panelPref[which] = true;
        el.hidden = UI._panelPref[which] !== true;
        UI.syncPanelTabs();
      },
      /**
       * Tapping the tab itself flips the panel: open it if it was closed, close
       * it if it was open. The other panel is left exactly as it was, so the two
       * are never dragged open together.
       */
      togglePanel(which, fromTab) {
        const e = UI._el;
        const el = which === "todos" ? e.taskboard : e.toollog;
        if (!el) return;
        const wasOpen = !el.hidden;
        UI._panelPref = UI._panelPref || {};
        UI._panelPref[which] = !wasOpen;
        el.hidden = wasOpen;
        UI.syncPanelTabs();
      },
      hidePanel(which) {
        UI._panelPref = UI._panelPref || {};
        UI._panelPref[which] = false;
        const e = UI._el;
        const el = which === "todos" ? e.taskboard : e.toollog;
        if (el) el.hidden = true;
        UI.syncPanelTabs();
        if (UI._consoleTab === which) UI.consoleTab("chat");
      },
      /**
       * The Tasks/Tools tabs light up to mirror what is actually on screen, so a
       * tap and a visible change always agree — closing a panel by its own X
       * clears its tab too.
       */
      syncPanelTabs() {
        const e = UI._el;
        const open = (el) => !!el && !el.hidden;
        document.querySelectorAll(".ctab").forEach((b) => {
          if (b.dataset.tab === "todos") b.classList.toggle("on", open(e.taskboard));
          else if (b.dataset.tab === "tools") b.classList.toggle("on", open(e.toollog));
        });
      },
      /** Panels can be dragged by their header so they never block the chat. */
      makeDraggable(el) {
        const head = el.querySelector(".dock-head");
        if (!head || head.dataset.drag === "1") return;
        head.dataset.drag = "1";
        let sx = 0, sy = 0, ox = 0, oy = 0, dragging = false;
        const down = (ev) => {
          const p = ev.touches ? ev.touches[0] : ev;
          const r = el.getBoundingClientRect();
          dragging = true;
          sx = p.clientX;
          sy = p.clientY;
          ox = r.left;
          oy = r.top;
          el.style.left = ox + "px";
          el.style.top = oy + "px";
          el.style.right = "auto";
          el.style.bottom = "auto";
          ev.preventDefault();
        };
        const move = (ev) => {
          if (!dragging) return;
          const p = ev.touches ? ev.touches[0] : ev;
          const w = el.offsetWidth, h = el.offsetHeight;
          let x = Math.max(4, Math.min(innerWidth - w - 4, ox + p.clientX - sx));
          let y = Math.max(4, Math.min(innerHeight - h - 4, oy + p.clientY - sy));
          el.style.left = x + "px";
          el.style.top = y + "px";
        };
        const up = () => {
          dragging = false;
        };
        head.addEventListener("pointerdown", down);
        addEventListener("pointermove", move, { passive: true });
        addEventListener("pointerup", up);
        addEventListener("pointercancel", up);
      },
      /** Nudge the home button when something lands in the console unread. */
      consolePing() {
        const e = UI._el;
        if (!e.console || !e.console.hidden) return;
        if (e.cbDot) e.cbDot.hidden = false;
      },
      setTaskCount() {
      },
      /** The monitoring model's latest spoken line, shown above the chat. */
      setMonitor(line) {
        const bar = $("monitorbar");
        if (!bar) return;
        if (!line) {
          bar.hidden = true;
          return;
        }
        bar.hidden = false;
        $("mon-text").textContent = line;
      },
      /* ── drawer ── */
      openDrawer(pane) {
        const e = UI._el;
        e.drawer.hidden = false;
        UI._overlay(true);
        e.drawer.querySelectorAll(".pane").forEach((p) => p.hidden = p.dataset.pane !== pane);
        e.drawerTitle.textContent = t("set." + pane);
        if (pane === "permissions") UI.renderPermissions();
        if (pane === "memory") UI.renderMemory();
        if (pane === "characters") UI.renderChars();
        if (pane === "history") UI.renderHistory();
        if (pane === "skills") UI.renderSkills();
      },
      closeDrawer() {
        UI._el.drawer.hidden = true;
        UI._overlay(false);
      },
      /**
       * A full-screen modal sits on top of a playing 1080p clip, and every
       * backdrop-filter layer has to re-blur that clip on every frame — that is
       * what made the settings and chat panels crawl. While an overlay is up we
       * stop decoding the video and drop the modal blur; the panel's own glass
       * fill keeps the look. Closing restores both.
       */
      _overlay(on) {
        document.documentElement.classList.toggle("modal-open", !!on);
        const v = document.getElementById("bg-video");
        if (v && v.src && v.paused) {
          try {
            Promise.resolve(v.play()).catch(() => {
            });
          } catch {
          }
        }
      },
      renderPermissions() {
        const host = $("perm-list");
        const P = global.ZY.permissions;
        host.innerHTML = P.map((p) => `
        <div class="perm" data-kind="${p.id}">
          <span class="p-ico">${p.emoji}</span>
          <span class="p-txt"><b>${esc(p.label)}</b><i>${esc(p.why)}</i></span>
          <span class="p-state prompt" data-state>${esc(t("perm.ask"))}</span>
          <button data-ask="${p.id}" data-label="${esc(p.label)}">${esc(t("perm.allow"))}</button>
          <button class="p-revoke" data-revoke="${p.id}" data-i18n="perm.revoke">${esc(t("perm.revoke"))}</button>
        </div>`).join("");
        host.querySelectorAll("button[data-ask]").forEach((b) => b.addEventListener("click", async () => {
          const res = await UI.permissionSheet({ kind: b.dataset.ask, label: b.dataset.label, real: true });
          const row = host.querySelector(`.perm[data-kind="${b.dataset.ask}"]`);
          const st = row.querySelector("[data-state]");
          st.textContent = res ? t("perm.on") : t("perm.off");
          st.className = "p-state " + (res ? "granted" : "denied");
          if (res && b.dataset.ask === "accessibility" && global.ZY.store.get("screenWatch", true)) global.ZY.Screen.start();
          if (res && b.dataset.ask === "camera") {
            global.ZY.Screen.camStart("environment");
            UI.camState(true);
          }
        }));
        host.querySelectorAll("button[data-revoke]").forEach((b) => b.addEventListener("click", () => {
          const kind = b.dataset.revoke;
          const nat = global.ZY.Native;
          if (kind === "camera") {
            global.ZY.Screen.camStop();
            UI.camState(false);
          }
          if (kind === "screen" || kind === "accessibility") global.ZY.Screen.stop();
          if (nat && nat.app && nat.permission.revoke) nat.permission.revoke(kind);
          else global.ZY.ui.toast("Android \u09B8\u09C7\u099F\u09BF\u0982\u09B8 \u2192 \u0985\u09CD\u09AF\u09BE\u09AA \u2192 ZYRAXON \u2192 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A5\u09C7\u0995\u09C7 \u09AC\u09A8\u09CD\u09A7 \u0995\u09B0\u09CB");
          const row = host.querySelector(`.perm[data-kind="${kind}"]`);
          if (row) {
            const st = row.querySelector("[data-state]");
            st.textContent = t("perm.off");
            st.className = "p-state denied";
          }
        }));
        UI.refreshPermissionStates(host);
        if ("Notification" in global && Notification.permission !== "default") {
          const row = host.querySelector('.perm[data-kind="notifications"] [data-state]');
          if (row) {
            row.textContent = Notification.permission === "granted" ? t("perm.on") : t("perm.off");
            row.className = "p-state " + (Notification.permission === "granted" ? "granted" : "denied");
          }
        }
        const bridge = global.ZY.Tools.native.available();
        $("perm-note").textContent = bridge ? `Device bridge ON (${global.ZY.Tools.native.kind()}) \u2014 real app launch and screen control work.` : "No device bridge yet. In the browser the tools still do real work (clipboard, notifications, share, website publish, shell, files, URL reading). tap/swipe/type need the APK with Accessibility allowed.";
      },
      /**
       * Ask the device for the true state of each grant and paint the rows.
       * A row must never read "granted" because the user tapped Allow — it must
       * read what the operating system actually reports.
       */
      refreshPermissionStates(host) {
        const nat = global.ZY.Native;
        const setRow = (kind, granted) => {
          const row = (host || $("perm-list")).querySelector(`.perm[data-kind="${kind}"] [data-state]`);
          if (!row) return;
          row.textContent = granted ? t("perm.on") : t("perm.off");
          row.className = "p-state " + (granted ? "granted" : "denied");
        };
        if (!nat || !nat.app) return;
        ["microphone", "camera", "location", "contacts", "phone", "sms", "calendar", "storage", "notifications"].forEach((k) => {
          try {
            setRow(k, !!nat.permission.state(k).granted);
          } catch (e) {
          }
        });
        setRow("accessibility", nat.settings.accessibilityOn());
        if (nat.settings.overlayAllowed) setRow("overlay", nat.settings.overlayAllowed());
        if (nat.settings.canWriteSettings) setRow("write-settings", nat.settings.canWriteSettings());
        if (nat.device.watchingScreen) setRow("screen", nat.device.watchingScreen() || nat.settings.accessibilityOn());
      },
      /** Android-style "Allow" permission sheet — one tap, no digging in settings. */
      permissionSheet({ kind, label, real }) {
        const e = UI._el;
        return new Promise((resolve) => {
          e.sheet.hidden = false;
          e.sheetTitle.textContent = label || kind;
          e.sheetText.innerHTML = `Allow ZYRAXON to use <b>${esc(label || kind)}</b>?<br><small>${esc(t("perm.sheet_note"))}</small>`;
          const done = async (ok) => {
            e.sheet.hidden = true;
            $("sheet-allow").removeEventListener("click", onAllow);
            $("sheet-deny").removeEventListener("click", onDeny);
            if (ok && real) ok = await UI._reallyGrant(kind);
            if (ok) global.ZY.DB.remember("permission:" + kind, "granted", "permission");
            resolve(ok);
          };
          const onAllow = () => done(true);
          const onDeny = () => done(false);
          $("sheet-allow").addEventListener("click", onAllow);
          $("sheet-deny").addEventListener("click", onDeny);
        });
      },
      async _reallyGrant(kind) {
        const nat = global.ZY.Native;
        if (nat && nat.app) {
          const map = {
            microphone: "microphone",
            camera: "camera",
            location: "location",
            contacts: "contacts",
            phone: "phone",
            sms: "sms",
            calendar: "calendar",
            storage: "storage",
            notifications: "notifications"
          };
          const key = map[kind];
          if (key) {
            const st = nat.permission.state(key);
            if (st && st.granted) return true;
            return await new Promise((res) => {
              nat.permission.onResult((p) => {
                if (p.state === "granted" || p.state === "denied") res(p.state === "granted");
              });
              nat.permission.request(key, labelFor(kind));
              setTimeout(() => res(nat.permission.state(key).granted), 6e4);
            });
          }
          if (kind === "screen") {
            return nat.settings.accessibilityOn();
          }
          if (kind === "play-protect") {
            if (nat.settings.playProtect(true)) return true;
            nat.settings.open("write-settings");
            return false;
          }
          if (kind === "write-settings") {
            if (nat.settings.canWriteSettings()) return true;
            nat.settings.open("write-settings");
            return await new Promise((res) => setTimeout(() => res(nat.settings.canWriteSettings()), 2e4));
          }
          if (kind === "accessibility") {
            nat.settings.open("accessibility");
            return await new Promise((res) => {
              nat.device.onEvent((p) => {
                if (p.state === "accessibility") res(true);
              });
              setTimeout(() => res(nat.settings.accessibilityOn()), 45e3);
            });
          }
          if (kind === "overlay") {
            if (nat.settings.overlayAllowed && nat.settings.overlayAllowed()) return true;
            nat.settings.open("overlay");
            return await new Promise((res) => {
              const t0 = Date.now();
              const poll = setInterval(() => {
                if (nat.settings.overlayAllowed && nat.settings.overlayAllowed()) {
                  clearInterval(poll);
                  res(true);
                } else if (Date.now() - t0 > 45e3) {
                  clearInterval(poll);
                  res(false);
                }
              }, 700);
            });
          }
          if (kind === "install-apk") {
            nat.settings.open("install");
            return true;
          }
          if (kind === "storage") {
            nat.settings.open("app");
            return true;
          }
        }
        try {
          if (kind === "microphone" && navigator.mediaDevices && !global.ZY.VOICE.STT.available()) {
            const s = await navigator.mediaDevices.getUserMedia({ audio: true });
            s.getTracks().forEach((t2) => t2.stop());
          }
          if (kind === "camera" && navigator.mediaDevices) {
            const s = await navigator.mediaDevices.getUserMedia({ video: true });
            s.getTracks().forEach((t2) => t2.stop());
          }
          if (kind === "geolocation" && navigator.geolocation) {
            await new Promise((r) => navigator.geolocation.getCurrentPosition(r, r, { timeout: 8e3 }));
          }
          if (kind === "notifications" && "Notification" in global && Notification.permission === "default") {
            await Notification.requestPermission();
          }
          return true;
        } catch {
          return false;
        }
      },
      renderMemory() {
        global.ZY.DB.recall().then((rows) => {
          const host = $("memory-list");
          if (!rows.length) {
            host.innerHTML = '<p class="hint">' + esc(t("memory.empty")) + "</p>";
            return;
          }
          host.innerHTML = rows.map((m) => `<div class="mem-item"><button class="del" data-del="${esc(m.id)}">\u2715</button><b>${esc(m.key)}</b>${esc(m.value)}</div>`).join("");
          host.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => {
            await global.ZY.DB.del("memory", b.dataset.del);
            UI.renderMemory();
          }));
        });
      },
      renderChars() {
        const host = $("char-grid");
        host.innerHTML = global.ZY.charList().map((c) => `
        <button class="char-card ${c.id === global.ZY.Agent.character ? "active" : ""}" data-ch="${c.id}">
          <div class="cc-emoji">${c.emoji}</div><b>${esc(t("char." + c.id))}</b><i>${esc(t("char." + c.id + ".tag"))}</i>
        </button>`).join("");
        host.querySelectorAll("[data-ch]").forEach((b) => b.addEventListener("click", async () => {
          const ch = global.ZY.characters[b.dataset.ch];
          await UI.applyCharacter(ch);
          UI.renderChars();
          UI.closeDrawer();
          UI.toast(t("toast.char") + t("char." + ch.id));
        }));
      },
      async applyCharacter(ch, opts) {
        const silent = !!(opts && opts.silent);
        global.ZY.Agent.character = ch.id;
        global.ZY.store.set("character", ch.id);
        global.ZY.ORB.setCharacter(ch);
        $("char-chip-avatar").textContent = ch.emoji;
        $("char-chip-name").textContent = t("char." + ch.id);
        $("char-chip-mood").textContent = t("char." + ch.id + ".tag");
        document.documentElement.style.setProperty("--acc", ch.accent);
        global.ZY.VOICE.TTS.pitch = ch.voice.pitch;
        global.ZY.VOICE.TTS.rate = ch.voice.rate * (Number(global.ZY.store.get("rate", 1)) || 1);
        const v = global.ZY.VOICE.TTS.pickVoice(ch.voice.prefer);
        if (v) global.ZY.VOICE.TTS.voice = v;
        await global.ZY.DB.setKV("character", ch.id);
        await global.ZY.DB.remember("\u09B8\u0995\u09CD\u09B0\u09BF\u09AF\u09BC \u099A\u09B0\u09BF\u09A4\u09CD\u09B0", ch.name, "character");
        if (silent) return;
        global.ZY.Agent.history.push({ role: "user", content: `[character change] From now on you are the ${ch.id}. ${ch.rules}` });
        UI.addMessage("ai", `${ch.emoji} ${t("toast.char")}${t("char." + ch.id)}`);
        global.ZY.VOICE.TTS.say(t("toast.char") + t("char." + ch.id));
      },
      async renderHistory() {
        const rows = await global.ZY.DB.recentMessages(200);
        const host = $("hist-list");
        if (!rows.length) {
          host.innerHTML = '<p class="hint">' + esc(t("hist.empty")) + "</p>";
          return;
        }
        host.innerHTML = rows.map((m) => `<div class="hist-item"><i>${new Date(m.ts).toLocaleString()} \xB7 ${m.role === "user" ? "you" : "ZYRAXON"}</i><br>${esc((m.content || "").slice(0, 200))}</div>`).join("");
      },
      renderSkills() {
        $("skill-list").innerHTML = global.ZY.skills.map((s) => `
        <div class="skill-item">
          <b>${esc(s.name)}</b><span class="tag">${esc(s.tag)}</span>
          <div class="sk-code">${esc(s.code)}</div>
          <p>${esc(s.desc)}</p>
        </div>`).join("");
        $("play-note").textContent = global.ZY.playNote;
      },
      toast(msg) {
        const t2 = document.createElement("div");
        t2.className = "toast";
        t2.textContent = msg;
        UI._el.toast.appendChild(t2);
        setTimeout(() => t2.remove(), 2600);
      },
      _scroll(force) {
        const st = UI._el.stage;
        if (!st) return;
        if (UI._scrollPending) return;
        UI._scrollPending = true;
        requestAnimationFrame(() => {
          UI._scrollPending = false;
          const nearBottom = st.scrollHeight - st.scrollTop - st.clientHeight < 260;
          if (force || nearBottom) st.scrollTop = st.scrollHeight;
        });
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.ui = UI;
  })(window);
})();
