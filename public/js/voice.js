(() => {
  (function(global) {
    "use strict";
    const ORB_STYLES = [
      { id: "pink", name: "\u0997\u09CB\u09B2\u09BE\u09AA\u09BF \u0986\u09AD\u09BE (\u09A1\u09BF\u09AB\u09B2\u09CD\u099F)" },
      { id: "rose", name: "\u09B0\u09CB\u099C \u0986\u09AD\u09BE" },
      { id: "aurora", name: "\u0985\u09B0\u09CB\u09B0\u09BE \u0986\u09AD\u09BE" },
      { id: "mint", name: "\u09AE\u09BF\u09A8\u09CD\u099F \u0986\u09AD\u09BE" },
      { id: "ocean", name: "\u09B8\u09BE\u0997\u09B0 \u0986\u09AD\u09BE" },
      { id: "ember", name: "\u0985\u0999\u09CD\u0997\u09BE\u09B0 \u0986\u09AD\u09BE" },
      { id: "sunset", name: "\u09B8\u09C2\u09B0\u09CD\u09AF\u09BE\u09B8\u09CD\u09A4 \u0986\u09AD\u09BE" },
      { id: "galaxy", name: "\u0997\u09CD\u09AF\u09BE\u09B2\u09BE\u0995\u09CD\u09B8\u09BF \u0986\u09AD\u09BE" },
      { id: "cyber", name: "\u09B8\u09BE\u0987\u09AC\u09BE\u09B0 \u0986\u09AD\u09BE" }
    ];
    const ORB = {
      state: "idle",
      // idle | listening | speaking | thinking
      level: 0,
      // 0..1 speaking amplitude
      accent: "#ff5cae",
      accent2: "#38e8ff",
      emoji: "\u{1F49C}",
      style: "pink",
      t: 0,
      _mouth: 0,
      init(el) {
        ORB.el = (typeof el === "string" ? document.getElementById(el) : el) || document.getElementById("aura");
        ORB.dom = !(ORB.el && ORB.el.tagName === "CANVAS");
        if (ORB.dom) {
          ORB.hall = document.getElementById("voice-hall");
          ORB._paintState(ORB.state);
          return;
        }
        ORB.c = ORB.el;
        ORB.x = ORB.el.getContext("2d");
        ORB._resize();
        addEventListener("resize", ORB._resize);
        requestAnimationFrame(ORB._loop);
      },
      _paintState(s) {
        if (!ORB.hall) return;
        ORB.hall.classList.toggle("listening", s === "listening");
        ORB.hall.classList.toggle("speaking", s === "speaking");
        ORB.hall.classList.toggle("thinking", s === "thinking");
        ORB.hall.dataset.orbState = s;
      },
      _resize() {
        const dpr = Math.min(devicePixelRatio || 1, 2);
        const w = ORB.c.clientWidth || 240;
        ORB.c.width = w * dpr;
        ORB.c.height = w * dpr;
        ORB.x.setTransform(dpr, 0, 0, dpr, 0, 0);
        ORB.S = w;
      },
      setCharacter(ch) {
        ORB.accent = ch.accent || "#ff5cae";
        ORB.emoji = ch.emoji || "\u{1F49C}";
        if (ORB.dom && ORB.el) ORB.el.style.setProperty("--aura-accent", ORB.accent);
      },
      setStyle(id) {
        if (!ORB_STYLES.some((s) => s.id === id)) return;
        ORB.style = id;
        if (ORB.dom && ORB.hall) ORB.hall.dataset.auraStyle = id;
      },
      styles: ORB_STYLES,
      setState(s) {
        if (ORB.state !== s) {
          ORB.state = s;
          if (ORB.dom) ORB._paintState(s);
          try {
            global.ZY.BG && global.ZY.BG.duck && global.ZY.BG.duck(s === "speaking");
          } catch {
          }
          ORB._onState && ORB._onState(s);
        }
      },
      setLevel(v) {
        ORB.level = Math.max(0, Math.min(1, v));
      },
      onState(fn) {
        ORB._onState = fn;
      },
      /**
       * Drive the amplitude from a deterministic envelope shaped by the text while
       * the phone's own TTS engine speaks (the engine exposes no samples to the
       * WebView). Syllable timing follows real speech cadence, not noise.
       */
      talkEnvelope(text) {
        const syllables = Math.max(1, Math.round(String(text).length / 3.2));
        const dur = Math.min(9e3, 320 + syllables * 105);
        const t0 = performance.now();
        const timer = setInterval(() => {
          const u = (performance.now() - t0) / dur;
          if (u >= 1) {
            clearInterval(timer);
            ORB.setLevel(0);
            return;
          }
          const syllable = Math.abs(Math.sin(u * syllables * Math.PI));
          const decay = 1 - u * 0.55;
          ORB.setLevel(0.18 + syllable * decay * 0.8);
        }, 60);
        return () => {
          clearInterval(timer);
          ORB.setLevel(0);
        };
      },
      /**
       * Real amplitude: tee the playing <audio> through an AnalyserNode and read
       * the true waveform each frame. Falls back to nothing if the graph fails.
       */
      trackAudio(audioEl) {
        let raf = 0, ctx = null, src = null;
        try {
          const AC = global.AudioContext || global.webkitAudioContext;
          if (!AC) return () => {
          };
          ctx = new AC();
          src = ctx.createMediaElementSource(audioEl);
          const an = ctx.createAnalyser();
          an.fftSize = 512;
          src.connect(an);
          an.connect(ctx.destination);
          const buf = new Uint8Array(an.frequencyBinCount);
          const tick = () => {
            an.getByteTimeDomainData(buf);
            let sum = 0;
            for (let i = 0; i < buf.length; i++) {
              const v = (buf[i] - 128) / 128;
              sum += v * v;
            }
            ORB.setLevel(Math.min(1, Math.sqrt(sum / buf.length) * 3.4));
            raf = requestAnimationFrame(tick);
          };
          tick();
        } catch {
          return () => {
          };
        }
        return () => {
          cancelAnimationFrame(raf);
          try {
            src && src.disconnect();
          } catch {
          }
          try {
            ctx && ctx.close();
          } catch {
          }
          ORB.setLevel(0);
        };
      },
      /** Energy 0..1 for the current frame — the single value every style draws from. */
      _energy() {
        const base = ORB.state === "speaking" ? 0.3 + ORB.level * 0.7 : ORB.state === "listening" ? 0.24 : ORB.state === "thinking" ? 0.18 : 0.1;
        const target = base + Math.sin(ORB.t * 1.6) * 0.04;
        ORB._mouth += (target - ORB._mouth) * 0.22;
        return Math.max(0.04, Math.min(1, ORB._mouth));
      },
      _loop(now) {
        if (document.documentElement.classList.contains("modal-open") || document.hidden) {
          requestAnimationFrame(ORB._loop);
          return;
        }
        ORB.t = (now || performance.now()) / 1e3;
        const x = ORB.x, S = ORB.S || 240, cx = S / 2, cy = S / 2;
        const e = ORB._energy();
        const A = ORB.accent, A2 = ORB.accent2;
        const bob = Math.sin(ORB.t * 1.1) * S * 0.012;
        x.clearRect(0, 0, S, S);
        x.save();
        x.translate(cx, cy + bob);
        const halo = x.createRadialGradient(0, 0, S * 0.05, 0, 0, S * 0.5);
        halo.addColorStop(0, hexA(A, 0.3 + e * 0.25));
        halo.addColorStop(1, hexA(A, 0));
        x.fillStyle = halo;
        x.beginPath();
        x.arc(0, 0, S * 0.5, 0, 7);
        x.fill();
        const R = S * (0.16 + e * 0.1);
        const style = ORB.style;
        if (style === "pulse") {
          for (let k = 0; k < 4; k++) {
            const p = (ORB.t * (0.45 + e * 0.9) + k / 4) % 1;
            x.beginPath();
            x.arc(0, 0, R * (1 + p * 2.4), 0, 7);
            x.strokeStyle = hexA(k % 2 ? A2 : A, 0.42 * (1 - p));
            x.lineWidth = 2;
            x.stroke();
          }
          const g = x.createRadialGradient(-R * 0.3, -R * 0.3, R * 0.1, 0, 0, R * 1.2);
          g.addColorStop(0, "#ffffff");
          g.addColorStop(0.45, hexA(A2, 0.95));
          g.addColorStop(1, hexA(A, 0.85));
          x.beginPath();
          x.arc(0, 0, R * (1 + e * 0.22), 0, 7);
          x.fillStyle = g;
          x.fill();
        } else if (style === "orbit") {
          x.beginPath();
          x.arc(0, 0, R * 1.5, 0, 7);
          x.strokeStyle = hexA("#ffffff", 0.18);
          x.lineWidth = 1.4;
          x.stroke();
          x.beginPath();
          x.arc(0, 0, R * 2.1, 0, 7);
          x.strokeStyle = hexA("#ffffff", 0.1);
          x.lineWidth = 1;
          x.stroke();
          const g = x.createRadialGradient(0, 0, R * 0.1, 0, 0, R);
          g.addColorStop(0, "#ffffff");
          g.addColorStop(1, hexA(A, 0.9));
          x.beginPath();
          x.arc(0, 0, R, 0, 7);
          x.fillStyle = g;
          x.fill();
          for (let k = 0; k < 6; k++) {
            const a = ORB.t * (0.9 + k * 0.12) + k / 6 * Math.PI * 2;
            const rr = R * (1.5 + k % 2 * 0.6) + Math.sin(ORB.t * 2 + k) * S * 0.02;
            x.beginPath();
            x.arc(Math.cos(a) * rr, Math.sin(a) * rr, 2.6 + e * 2.4, 0, 7);
            x.fillStyle = k % 2 ? A2 : A;
            x.fill();
          }
        } else if (style === "wave") {
          x.beginPath();
          x.arc(0, 0, R, 0, 7);
          const g = x.createRadialGradient(0, -R * 0.4, R * 0.1, 0, 0, R * 1.1);
          g.addColorStop(0, "#ffffff");
          g.addColorStop(1, hexA(A, 0.85));
          x.fillStyle = g;
          x.fill();
          for (let ring = 0; ring < 3; ring++) {
            x.beginPath();
            const rr = R * (1.25 + ring * 0.32);
            for (let i = 0; i <= 64; i++) {
              const th = i / 64 * Math.PI * 2;
              const wob = Math.sin(th * (3 + ring) + ORB.t * 3.2) * (S * 0.02 + e * S * 0.045);
              const px = Math.cos(th) * (rr + wob), py = Math.sin(th) * (rr + wob);
              i ? x.lineTo(px, py) : x.moveTo(px, py);
            }
            x.closePath();
            x.strokeStyle = hexA(ring % 2 ? A2 : A, 0.5 - ring * 0.1);
            x.lineWidth = 1.8;
            x.stroke();
          }
        } else if (style === "bloom") {
          const petals = 9;
          for (let k = 0; k < petals; k++) {
            const a = k / petals * Math.PI * 2 + ORB.t * 0.28;
            const len = R * (1.5 + e * 0.9) + Math.sin(ORB.t * 2.1 + k) * S * 0.03;
            x.beginPath();
            x.moveTo(0, 0);
            x.quadraticCurveTo(Math.cos(a - 0.32) * len * 0.6, Math.sin(a - 0.32) * len * 0.6, Math.cos(a) * len, Math.sin(a) * len);
            x.quadraticCurveTo(Math.cos(a + 0.32) * len * 0.6, Math.sin(a + 0.32) * len * 0.6, 0, 0);
            x.fillStyle = hexA(k % 2 ? A : A2, 0.14 + e * 0.12);
            x.fill();
          }
          const g = x.createRadialGradient(0, 0, R * 0.05, 0, 0, R * 1.05);
          g.addColorStop(0, "#ffffff");
          g.addColorStop(1, hexA(A, 0.92));
          x.beginPath();
          x.arc(0, 0, R, 0, 7);
          x.fillStyle = g;
          x.fill();
        } else {
          x.beginPath();
          x.arc(0, 0, R * 1.28, 0, 7);
          x.strokeStyle = hexA(A, 0.55);
          x.lineWidth = 2.4;
          x.stroke();
          x.beginPath();
          x.arc(0, 0, R * 1.62, 0, 7);
          x.strokeStyle = hexA(A2, 0.34);
          x.lineWidth = 1.4;
          x.stroke();
          const g = x.createRadialGradient(-R * 0.35, -R * 0.4, R * 0.08, 0, 0, R * 1.15);
          g.addColorStop(0, "#ffffff");
          g.addColorStop(0.5, hexA(A2, 0.9));
          g.addColorStop(1, hexA(A, 0.88));
          x.beginPath();
          x.arc(0, 0, R * (1 + e * 0.16), 0, 7);
          x.fillStyle = g;
          x.fill();
        }
        if (ORB.state === "listening") {
          for (let k = 0; k < 2; k++) {
            const p = (ORB.t * 1.2 + k * 0.5) % 1;
            x.beginPath();
            x.arc(0, 0, S * (0.2 + p * 0.28), 0, 7);
            x.strokeStyle = hexA(A2, 0.5 * (1 - p));
            x.lineWidth = 2;
            x.stroke();
          }
        }
        if (ORB.state === "thinking") {
          for (let i = 0; i < 8; i++) {
            const a = ORB.t * 1.2 + i / 8 * Math.PI * 2;
            const rr = S * 0.36 + Math.sin(ORB.t * 3 + i) * 6;
            x.beginPath();
            x.arc(Math.cos(a) * rr, Math.sin(a) * rr, 3.1, 0, 7);
            x.fillStyle = hexA(i % 2 ? A2 : A, 0.9);
            x.fill();
          }
        }
        x.restore();
        requestAnimationFrame(ORB._loop);
      }
    };
    function sim(a, b) {
      if (!a || !b) return 0;
      if (a === b) return 1;
      const grams = (s) => {
        const t = " " + s.replace(/\s+/g, " ") + " ";
        const g = /* @__PURE__ */ new Set();
        for (let i = 0; i < t.length - 1; i++) g.add(t.slice(i, i + 2));
        return g;
      };
      const A = grams(a), B = grams(b);
      if (!A.size || !B.size) return 0;
      let inter = 0;
      for (const g of A) if (B.has(g)) inter++;
      return 2 * inter / (A.size + B.size);
    }
    function hexA(hex, a) {
      const h = hex.replace("#", "");
      const n = parseInt(h.length === 3 ? h.split("").map((c) => c + c).join("") : h, 16);
      return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`;
    }
    function shade(hex, amt) {
      const h = hex.replace("#", "");
      const n = parseInt(h.length === 3 ? h.split("").map((c) => c + c).join("") : h, 16);
      const f = (v) => Math.max(0, Math.min(255, Math.round(v + (amt > 0 ? (255 - v) * amt : v * amt))));
      return `rgb(${f(n >> 16 & 255)},${f(n >> 8 & 255)},${f(n & 255)})`;
    }
    const TTS = {
      enabled: true,
      rate: 1,
      pitch: 1.05,
      voice: null,
      _queue: [],
      _speaking: false,
      _guard: null,
      lang: "bn-BD",
      voices: [],
      /** Latest content of the streaming answer — speech only ever reaches here. */
      live: "",
      /** Reasoner text already spoken, so a growing reasoning block is not repeated. */
      _reasonSaid: "",
      load() {
        if (!("speechSynthesis" in global)) return [];
        TTS.voices = speechSynthesis.getVoices();
        return TTS.voices;
      },
      pickVoice(prefer) {
        const vs = TTS.load();
        const prefs = (prefer || ["bn", "ban"]).map((p) => p.toLowerCase());
        const byLang = vs.find((v) => /bn|bangla|bengali/i.test(v.lang) || /bn|bangla|bengali/i.test(v.name));
        if (byLang) return byLang;
        for (const p of prefs) {
          const f = vs.find((v) => v.name.toLowerCase().includes(p) || v.lang.toLowerCase().includes(p));
          if (f) return f;
        }
        return vs[0] || null;
      },
      setVoiceByName(name) {
        TTS.voice = TTS.load().find((v) => v.name === name) || null;
      },
      /**
       * Everything the assistant says goes through here. Code is stripped (code
       * must never be read aloud) and only newly-arrived complete sentences are
       * queued, so speech starts while the answer is still streaming.
       */
      feed(text) {
        const safe = stripCode(String(text || ""));
        TTS.live = safe;
        const upto = lastEnd(safe);
        if (upto <= TTS._spokenUpto) {
          const pending = safe.slice(TTS._spokenUpto);
          if (pending.length < 110) return 0;
          const cut = pending.lastIndexOf(" ", pending.length - 20);
          const at = TTS._spokenUpto + (cut > 40 ? cut : pending.length - 20);
          const fresh2 = safe.slice(TTS._spokenUpto, at);
          TTS._spokenUpto = at;
          if (!fresh2.trim()) return 0;
          TTS.push(fresh2);
          return fresh2.length;
        }
        const fresh = safe.slice(TTS._spokenUpto, upto);
        TTS._spokenUpto = upto;
        if (!fresh.trim()) return 0;
        TTS.push(fresh);
        return fresh.length;
      },
      /** Queue whatever tail is left once the stream has ended. */
      flush() {
        const rest = TTS.live.slice(TTS._spokenUpto);
        TTS._spokenUpto = TTS.live.length;
        if (rest.trim()) TTS.push(rest);
      },
      /** Queue text for speech, sentence by sentence. */
      push(text) {
        if (!TTS.enabled) return;
        const nativeOk = !!(global.ZY.Native && global.ZY.Native.app && global.ZY.Native.tts.available());
        if (!nativeOk && !("speechSynthesis" in global)) return;
        for (const p of splitSentences(text)) TTS._queue.push(p);
        TTS._drain();
      },
      /**
       * Reasoning stays on screen only. The user was explicit: the voice must
       * read the answer, never the internal thinking, the tool calls or the code
       * — hearing "todo_write, publish_website" out loud is noise, not help.
       * Kept as a no-op so every caller keeps working.
       */
      reason() {
      },
      resetLive() {
        TTS.live = "";
        TTS._spokenUpto = 0;
        TTS._reasonSaid = "";
        TTS._reasonBuf = "";
        TTS._reasonDone = 0;
        TTS._reasonChars = 0;
      },
      /** Speak text as it streams: split on sentence boundaries, queue each. */
      speak(text) {
        const nativeOk = !!(global.ZY.Native && global.ZY.Native.app && global.ZY.Native.tts.available());
        if (!TTS.enabled || !text) return;
        if (!nativeOk && !("speechSynthesis" in global)) return;
        TTS.push(text);
      },
      /** Replace everything currently queued/spoken (interrupt). */
      say(text, onEnd) {
        TTS.stop();
        TTS._buf = "";
        TTS.resetLive();
        TTS._endCb = onEnd;
        TTS.speak(text);
      },
      /**
       * One place decides whether the microphone may be open. Speaking closes it,
       * silence opens it — so her own voice can never be transcribed back into the
       * chat as if the user had spoken, and the user can talk again the instant she
       * finishes.
       */
      _gate(on) {
        const stt = global.ZY.VOICE && global.ZY.VOICE.STT;
        if (!stt) return;
        try {
          on ? stt.pause() : stt.resume();
        } catch {
        }
      },
      /**
       * A spoken sentence must always end, and the microphone must always come
       * back. Engines are not reliable about this: a hidden window, a machine with
       * no sound card and a browser that refuses autoplay all leave "speaking"
       * true forever, and because the microphone is closed while she talks the
       * assistant goes permanently deaf. This arms a timer proportional to the
       * sentence and forces the turn closed if nothing reported the end.
       */
      _guardArm(text) {
        TTS._guardDone();
        const ms = 4e3 + String(text || "").length * 150;
        TTS._guard = setTimeout(() => {
          TTS._guard = null;
          try {
            TTS._audio && TTS._audio.pause();
          } catch {
          }
          TTS._speaking = false;
          ORB.setLevel(0);
          TTS._drain();
        }, ms);
      },
      _guardDone() {
        if (TTS._guard) {
          clearTimeout(TTS._guard);
          TTS._guard = null;
        }
      },
      _drain() {
        if (TTS._speaking || !TTS._queue.length) {
          if (!TTS._speaking && !TTS._queue.length) {
            ORB.setState("idle");
            ORB.setLevel(0);
            TTS._gate(false);
            try {
              global.ZY.ui && global.ZY.ui.setSpeak && global.ZY.ui.setSpeak("", false);
            } catch {
            }
            const stt = global.ZY.VOICE && global.ZY.VOICE.STT;
            if (stt && stt.keepAlive) stt.keepAlive();
            const cb = TTS._endCb;
            TTS._endCb = null;
            cb && cb();
          }
          return;
        }
        const text = clean(TTS._queue.shift());
        TTS._spokeAt = Date.now();
        if (!text) {
          TTS._drain();
          return;
        }
        TTS._buf = ((TTS._buf || "") + " " + text).slice(-320);
        const nat = global.ZY.Native;
        try {
          global.ZY.ui && global.ZY.ui.setSpeak && global.ZY.ui.setSpeak(text, true);
        } catch {
        }
        if (nat && nat.app && nat.tts.available()) {
          TTS._speaking = true;
          ORB.setState("speaking");
          TTS._gate(true);
          const stop2 = ORB.talkEnvelope(text);
          let settled = false, timer = 0;
          const finish2 = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            stop2();
            TTS._speaking = false;
            ORB.setLevel(0);
            TTS._drain();
          };
          TTS._natDone = finish2;
          if (TTS._natRate !== TTS.rate) {
            nat.tts.setRate(TTS.rate);
            TTS._natRate = TTS.rate;
          }
          if (TTS._natPitch !== TTS.pitch) {
            nat.tts.setPitch(TTS.pitch);
            TTS._natPitch = TTS.pitch;
          }
          TTS._natId = nat.tts.speak(text, langOf(text));
          if (TTS._natId === "lang-missing") {
            TTS._natId = null;
            TTS._natDone = null;
            TTS._speaking = false;
            TTS._speakRemote(text, langOf(text));
            return;
          }
          timer = setTimeout(finish2, 6e3 + text.length * 150);
          return;
        }
        const voice = TTS.voice || TTS.pickVoice();
        const lang = langOf(text);
        const base = lang.split("-")[0].toLowerCase();
        const match = TTS.load().find((v) => {
          const vl = String(v.lang || "").toLowerCase();
          const vn = String(v.name || "").toLowerCase();
          return vl.startsWith(base + "-") || vl === base || base === "bn" && /bangla|bengali/.test(vn) || base === "en" && /^en/.test(vl);
        });
        if (!match) {
          const S = global.ZY.VOICE && global.ZY.VOICE.STT;
          if (S && S.bridgeOn && S.bridgeOn()) {
            TTS._speaking = true;
            ORB.setState("speaking");
            TTS._gate(true);
            const stop2 = ORB.talkEnvelope(text);
            TTS._brDone = () => {
              stop2();
              TTS._guardDone();
              TTS._speaking = false;
              ORB.setLevel(0);
              TTS._drain();
            };
            S._bridgeSay(text, lang);
            TTS._guardArm(text);
            return;
          }
          TTS._speakRemote(text, lang);
          return;
        }
        const u = new SpeechSynthesisUtterance(text);
        try {
          u.voice = match || voice;
        } catch {
        }
        u.rate = TTS.rate;
        u.pitch = TTS.pitch;
        u.lang = lang;
        TTS._speaking = true;
        ORB.setState("speaking");
        TTS._gate(true);
        const stop = ORB.talkEnvelope(text);
        TTS._guardArm(text);
        const finish = () => {
          stop();
          TTS._guardDone();
          TTS._speaking = false;
          ORB.setLevel(0);
          TTS._drain();
        };
        u.onend = finish;
        u.onerror = finish;
        try {
          speechSynthesis.speak(u);
        } catch {
          finish();
        }
        setTimeout(() => {
          if (!TTS._speaking) return;
          let live = false;
          try {
            live = speechSynthesis.speaking || speechSynthesis.pending;
          } catch {
          }
          if (!live) finish();
        }, 800);
      },
      /**
       * No Bangla voice in this engine — speak through the server's real Bangla
       * TTS instead. The endpoint caps a request at 200 characters, so a long
       * sentence is chained in pieces rather than truncated mid-word.
       */
      _speakRemote(text, lang) {
        if (!text.trim()) return TTS._drain();
        const tl = String(lang || TTS.lang || "en-US").split("-")[0].toLowerCase();
        const base = (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
        const pieces = [];
        let rest = text.trim();
        while (rest.length > 180) {
          const cut = rest.lastIndexOf(" ", 180);
          const at = cut > 60 ? cut : 180;
          pieces.push(rest.slice(0, at));
          rest = rest.slice(at).trim();
        }
        if (rest) pieces.push(rest);
        TTS._speaking = true;
        ORB.setState("speaking");
        TTS._gate(true);
        const play = (i) => {
          if (i >= pieces.length) {
            ORB.setLevel(0);
            TTS._speaking = false;
            TTS._drain();
            return;
          }
          const a = new Audio(`${base}/api/tts?tl=${tl}&q=${encodeURIComponent(pieces[i])}`);
          a.crossOrigin = "anonymous";
          const stopReal = ORB.trackAudio(a);
          const next = () => {
            stopReal();
            TTS._guardDone();
            play(i + 1);
          };
          a.onended = next;
          const fail = () => {
            stopReal();
            TTS._guardDone();
            ORB.setLevel(0);
            TTS._speaking = false;
            TTS._drain();
          };
          a.onerror = fail;
          TTS._audio = a;
          TTS._guardArm(pieces[i]);
          a.play().catch(fail);
        };
        play(0);
      },
      stop() {
        TTS._queue = [];
        try {
          speechSynthesis.cancel();
        } catch {
        }
        if (global.ZY.Native && global.ZY.Native.app) {
          try {
            global.ZY.Native.tts.stop();
          } catch {
          }
        }
        try {
          TTS._audio && TTS._audio.pause();
        } catch {
        }
        TTS._natId = null;
        TTS._natDone = null;
        TTS._guardDone();
        TTS._speaking = false;
        ORB.setLevel(0);
        TTS._gate(false);
        const cb = TTS._endCb;
        TTS._endCb = null;
        cb && setTimeout(cb, 0);
      },
      /**
       * Is this transcript just the assistant hearing itself? The phone's mic
       * picks up the loudspeaker, and without this the assistant's own words get
       * posted back to it as the user's prompt — the loop the user complained
       * about. Compare against what we actually spoke, word by word.
       */
      isEcho(text) {
        const norm = (s2) => String(s2).toLowerCase().replace(/[^\p{L}\p{N}\p{M}\s]/gu, " ").replace(/\s+/g, " ").trim();
        const heard = norm(text);
        if (!heard) return true;
        const since = Date.now() - (TTS._spokeAt || 0);
        const said = norm(TTS._buf || "");
        if (!said) return false;
        if (heard.length < 3) return true;
        const hw = heard.split(" ");
        const sw = said.split(" ");
        const overlap = hw.filter((x) => x.length > 1 && sw.includes(x)).length;
        const share = hw.length ? overlap / hw.length : 1;
        const bargeIn = hw.length >= 5 && share < 0.3;
        if (bargeIn) return false;
        const hot = TTS._speaking || since < 9e3;
        if (said.includes(heard)) return true;
        const w = heard.split(" ");
        const s = said.split(" ");
        const meaningful = w.filter((x) => x.length > 1);
        if (!meaningful.length) return false;
        const hit = meaningful.filter((x) => s.includes(x)).length;
        if (hit / meaningful.length > 0.6) return true;
        if (meaningful.length <= 4 && hit === meaningful.length) return true;
        if (hot && sim(heard, said) > 0.55) return true;
        return false;
      },
      test() {
        TTS.say("\u0986\u09B8\u09B8\u09BE\u09B2\u09BE\u09AE\u09C1 \u0986\u09B2\u09BE\u0987\u0995\u09C1\u09AE, \u0986\u09AE\u09BF \u099C\u09BE\u0987\u09B0\u09BE\u0995\u09CD\u09B8\u09A8\u0964 \u09AC\u09BE\u0982\u09B2\u09BE\u09AF\u09BC \u0995\u09A5\u09BE \u09AC\u09B2\u09A4\u09C7 \u09AA\u09BE\u09B0\u09BF\u0964");
      }
    };
    const SCRIPT_LANGS = [
      ["bn-BD", /[\u0980-\u09FF]/g],
      // Bengali
      ["hi-IN", /[\u0900-\u097F]/g],
      // Devanagari
      ["ar-SA", /[\u0600-\u06FF]/g],
      // Arabic
      ["ru-RU", /[\u0400-\u04FF]/g],
      // Cyrillic
      ["ja-JP", /[\u3040-\u30FF]/g],
      // kana
      ["zh-CN", /[\u4E00-\u9FFF]/g],
      // Han
      ["ko-KR", /[\uAC00-\uD7AF]/g],
      // Hangul
      ["th-TH", /[\u0E00-\u0E7F]/g],
      // Thai
      ["he-IL", /[\u0590-\u05FF]/g],
      // Hebrew
      ["el-GR", /[\u0370-\u03FF]/g]
      // Greek
    ];
    function detectLang(text) {
      const s = String(text || "");
      let best = "", bestN = 0;
      for (const [tag, re] of SCRIPT_LANGS) {
        const n = (s.match(re) || []).length;
        if (n > bestN) {
          bestN = n;
          best = tag;
        }
      }
      if (bestN > 0) return best;
      const ui = global.ZY.I18N && global.ZY.I18N.sttTag ? global.ZY.I18N.sttTag() : "en-US";
      return /^(en|es|fr|pt|id|tr|de|it|nl|pl|vi|ms|sv|da|no|fi|cs|ro|hu)/i.test(ui) ? ui : "en-US";
    }
    function langOf(text) {
      return detectLang(text);
    }
    function stripCode(t) {
      return String(t).replace(/```[\s\S]*?```/g, " ").replace(/```[\s\S]*$/, " ").replace(/`[^`]*`/g, " ").replace(/\bhttps?:\/\/\S+/gi, " ").replace(/\bwww\.\S+/gi, " ").replace(/\b[\w.-]+\.(?:com|org|net|io|app|dev|ai|bd|html?|css|js|json|md|txt|mp4|webm|png|jpe?g|svg|py|java|kt|sh|zip|apk)\b/gi, " ").replace(/(^|\s)[/~][\w./-]{2,}/g, " ").replace(/\b[A-Za-z0-9_-]+\/[A-Za-z0-9_.\/-]{2,}\b/g, " ").replace(/\b[a-z]+_[a-z_]+\b/g, " ");
    }
    function lastEnd(s) {
      const m = String(s).match(/[\s\S]*[।!?\.](?=\s|$)/);
      const byPunct = m ? m[0].length : 0;
      const byLine = String(s).lastIndexOf("\n") + 1;
      return Math.max(byPunct, byLine);
    }
    function splitSentences(t) {
      const out = String(t).split(new RegExp("(?<=[\u0964!?\\.])\\s+")).map((s) => s.trim()).filter(Boolean);
      const merged = [];
      for (const s of out) {
        if (merged.length && merged[merged.length - 1].length < 18) merged[merged.length - 1] += " " + s;
        else merged.push(s);
      }
      return merged.slice(0, 12);
    }
    function clean(s) {
      return stripCode(s).replace(/[\u{1F000}-\u{1FAFF}\u{2190}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0E}\u{FE0F}\u{200D}\u{2600}-\u{26FF}]/gu, " ").replace(/[*_#`>]/g, "").replace(/\[(.*?)\]\(.*?\)/g, "$1").replace(/\s+/g, " ").trim();
    }
    const STT = {
      // Electron exposes webkitSpeechRecognition but ships no speech backend:
      // the API is wired to Google's proprietary cloud keys, which only the real
      // Chrome and Edge builds carry. In Electron it is a stub that always fails
      // with "network"/"not-allowed". Reporting it as absent lets the desktop app
      // bridge real Chrome/Edge speech in (see bridge.html) instead of recording
      // audio that nothing can transcribe.
      SR: function() {
        return global.SpeechRecognition || global.webkitSpeechRecognition || null;
      }(),
      rec: null,
      onPartial: null,
      onFinal: null,
      onState: null,
      listening: false,
      lang: "",
      autoRestart: true,
      _manualStop: false,
      /** True while the assistant's own voice is playing and the mic is closed. */
      _paused: false,
      /** Empty language means "recognise whatever language is spoken". */
      auto: true,
      /** The mic is open from boot and never closes on its own. */
      always: true,
      _retry: 0,
      _timer: 0,
      /**
       * True when some recogniser can really hear the user.
       *
       * The local Whisper engine that used to fill this role is gone. It was the
       * wrong tool: Whisper writes Bangla in roman letters ("amar naam") or, if
       * forced into Bangla script with an initial prompt, produces nonsense. The
       * browser's own recogniser is Google-backed and writes Bangla exactly as
       * spoken, which is what the user asked for.
       */
      available() {
        const n = global.ZY.Native;
        return !!(n && n.app && n.stt.available()) || !!STT.SR || STT.bridgeOn() || STT._desktopShell();
      },
      /**
       * True on a desktop browser, where a helper window can be opened.
       *
       * The app no longer ships an Electron shell: on Windows, macOS and Linux it
       * runs as a localhost page inside the user's own Chrome. A phone never gets
       * here (Android uses its own recogniser), so "not a phone" is the test.
       */
      _desktopShell() {
        if (STT.nativeOn()) return false;
        const p = global.ZY.Platform && global.ZY.Platform.data;
        return !!(p && p.isDesktop) || !/Android|iPhone|iPad|iPod/i.test(navigator.userAgent || "");
      },
      /**
       * True once the browser's own recogniser has proved it has no speech
       * service. A Chromium build exposes webkitSpeechRecognition and then fails
       * every attempt with "network"; that is the signal to open the helper
       * window, which is a real Chrome/Edge and does have the service.
       */
      _srBroken: false,
      /** The phone's own recogniser (Android SpeechRecognizer, via the bridge). */
      nativeOn() {
        const n = global.ZY.Native;
        return !!(n && n.app && n.stt.available());
      },
      /* ── desktop speech bridge ──────────────────────────────────
         Electron has no speech backend at all, so the desktop shell opens a real
         Chrome/Edge window on bridge.html and the recognised words arrive here
         over SSE. The user sees them in the chat box exactly as they spoke them,
         in Bangla script, because it is the same Google recogniser Chrome uses.
      ─────────────────────────────────────────────────────────────── */
      _br: null,
      _brOn: false,
      _brTry: 0,
      _helper: false,
      _helperName: "",
      _helperPoll: null,
      /**
       * True only when a helper window has actually announced itself.
       *
       * This used to answer "is this Electron", which meant the app chose the
       * helper's voice on a machine where the helper was never opened — and since
       * nothing then reported the sentence as finished, the microphone gate stayed
       * shut and the assistant went deaf. Presence, not platform, decides.
       */
      bridgeOn() {
        return STT._helper === true;
      },
      /** Note that the helper window has announced itself (or has gone away). */
      _helperSeen(name) {
        STT._helper = !!name;
        STT._helperName = String(name || "");
        if (STT._helper) {
          STT._brOn = true;
          STT._bridgeInit();
        } else {
          STT._brOn = false;
          if (STT._paused) {
            STT._paused = false;
          }
        }
      },
      /**
       * Listen for recognised speech from the helper window.
       *
       * Exactly one stream is ever open. EventSource reconnects by itself, so
       * closing it and opening another on error would leave two live listeners —
       * and every sentence would then arrive twice and be sent to the assistant
       * twice.
       */
      _bridgeInit() {
        if (STT._br) return STT._br;
        const base = (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
        try {
          const es = new EventSource(base + "/api/bridge/events");
          es.onopen = () => {
            STT._brOn = true;
            STT._brTry = 0;
            STT.listening = true;
            ORB.setState("listening");
            STT.onState && STT.onState("listening");
          };
          es.onmessage = (m) => {
            let d;
            try {
              d = JSON.parse(m.data);
            } catch {
              return;
            }
            if (!STT._helper) {
              STT._helper = true;
              STT._paused = false;
            }
            if (d.type === "spoke") {
              const T = global.ZY.VOICE && global.ZY.VOICE.TTS;
              if (T && T._brDone) {
                const f = T._brDone;
                T._brDone = null;
                f();
              }
              return;
            }
            if (d.type !== "stt") return;
            if (STT._paused) return;
            if (d.kind === "final") STT.onFinal && STT.onFinal(String(d.text || "").trim());
            else STT.onPartial && STT.onPartial(String(d.text || ""));
          };
          es.onerror = () => {
            STT._brOn = false;
            STT.listening = false;
          };
          STT._br = es;
          STT._helperWatch();
        } catch {
          STT._br = null;
        }
        return STT._br;
      },
      /**
       * Open the speech helper window.
       *
       * A window opened by a click is allowed to keep running; one opened without
       * a gesture is popup-blocked, so it is also offered on the next tap. It is a
       * small, clearly-labelled window rather than a tab, and the app keeps
       * working (text chat, tools, publishing) whether or not it is there.
       */
      _openHelper() {
        if (STT._helperWin && !STT._helperWin.closed) {
          try {
            STT._helperWin.focus();
          } catch {
          }
          return;
        }
        const url = (global.ZY.store.get("base") || location.origin).replace(/\/$/, "") + "/bridge.html";
        let win = null;
        try {
          win = window.open(url, "zyraxon-voice", "width=460,height=620");
        } catch {
        }
        STT._helperWin = win || null;
        if (win) {
          global.ZY.ui && global.ZY.ui.toast && global.ZY.ui.toast(
            "\u09AD\u09AF\u09BC\u09C7\u09B8 \u0989\u0987\u09A8\u09CD\u09A1\u09CB \u0996\u09C1\u09B2\u099B\u09C7 \u2014 \u09AE\u09BE\u0987\u0995 \u0986\u09B0 \u0995\u09A3\u09CD\u09A0 \u09B8\u09C7\u0996\u09BE\u09A8\u09C7 \u0995\u09BE\u099C \u0995\u09B0\u09AC\u09C7\u0964"
          );
          STT._bridgeInit();
          return;
        }
        STT._needsGesture = true;
        STT._helperWanted = true;
        STT._armGesture();
        if (!STT._helperNotice) {
          STT._helperNotice = true;
          global.ZY.ui && global.ZY.ui.toast && global.ZY.ui.toast(
            "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09C7 \u098F\u0995\u09AC\u09BE\u09B0 \u099F\u09CD\u09AF\u09BE\u09AA \u0995\u09B0\u09CB \u2014 \u09AD\u09AF\u09BC\u09C7\u09B8 \u0989\u0987\u09A8\u09CD\u09A1\u09CB \u0996\u09C1\u09B2\u09AC\u09C7\u0964"
          );
        }
      },
      /** Ask the helper window to say something in its own (better) voice. */
      /**
       * Keep track of whether the helper window is really open.
       *
       * The helper closes when the user closes its window, and there is no
       * "goodbye" event for that. Without this the app would keep handing
       * sentences to a window that is gone, and nothing would ever report them
       * finished — which is how the microphone ended up shut for good.
       */
      _helperWatch() {
        if (STT._helperPoll) return;
        const base = (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
        STT._helperPoll = setInterval(async () => {
          if (STT._manualStop) return;
          try {
            const r = await fetch(base + "/api/bridge/status", { cache: "no-store" });
            const j = await r.json();
            if (j && j.helper) {
              if (!STT._helper) STT._helperSeen(j.helperName || "browser");
              STT._helperName = j.helperName || STT._helperName;
            } else if (STT._helper) {
              STT._helperSeen("");
            }
          } catch {
          }
        }, 2e3);
      },
      _bridgeSay(text, lang) {
        const base = (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
        return fetch(base + "/api/bridge/cmd", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ cmd: "speak", text: String(text || "").slice(0, 1200), lang: lang || "" })
        }).catch(() => {
        });
      },
      init() {
        if (STT._inited) return true;
        STT._inited = true;
        if (STT.nativeOn()) {
          global.ZY.Native.stt.onResult((p) => {
            if (p.state === "listening") {
              STT.listening = true;
              ORB.setState("listening");
              STT.onState && STT.onState("listening");
            } else if (p.state === "partial") {
              STT.onPartial && STT.onPartial(p.text);
            } else if (p.state === "final") {
              STT.listening = false;
              STT.onFinal && STT.onFinal((p.text || "").trim());
            } else if (p.state === "error") {
              STT.listening = false;
              const denied = p.text === "9" || p.text === "13";
              STT.onState && STT.onState(denied ? "denied" : "listening");
              if (denied) {
                STT.always = true;
                global.ZY.Native.stt.keepAlive(false);
                global.ZY.ui && global.ZY.ui.permissionSheet({ kind: "microphone", label: "Microphone", real: true }).then((ok) => {
                  if (ok) STT.start();
                });
              }
            } else if (p.state === "processing") {
              STT.onState && STT.onState("thinking");
            }
          });
          STT.rec = true;
          return true;
        }
        STT._helperWatch();
        if (!STT.SR || STT._srBroken) {
          if (!STT._desktopShell() && !STT.bridgeOn()) return false;
          STT._bridgeInit();
          return true;
        }
        const r = new STT.SR();
        r.continuous = true;
        r.interimResults = true;
        r.maxAlternatives = 1;
        const webLang = () => {
          if (!STT.auto && STT.lang) return STT.lang;
          const ui = global.ZY.I18N && global.ZY.I18N.sttTag ? global.ZY.I18N.sttTag() : "";
          return ui || "bn-BD";
        };
        r.lang = webLang();
        r.onstart = () => {
          STT._retry = 0;
          STT.listening = true;
          ORB.setState("listening");
          STT.onState && STT.onState("listening");
        };
        r.onresult = (e) => {
          let interim = "", final = "";
          for (let i = e.resultIndex; i < e.results.length; i++) {
            const t = e.results[i][0].transcript;
            if (e.results[i].isFinal) final += t;
            else interim += t;
          }
          if (interim && STT.onPartial) STT.onPartial(interim);
          if (final) STT.onFinal && STT.onFinal(final.trim());
        };
        r.onerror = (e) => {
          if (e.error === "network" && !STT._srBroken && STT._desktopShell()) {
            STT._srBroken = true;
            STT.listening = false;
            try {
              r.abort();
            } catch {
            }
            STT._openHelper();
            STT.onState && STT.onState("idle");
            return;
          }
          if (e.error === "not-allowed" || e.error === "service-not-allowed") {
            STT.listening = false;
            STT._needsGesture = true;
            STT._armGesture();
            STT.onState && STT.onState("idle");
          } else if (e.error === "aborted") {
            STT.listening = false;
          } else {
            STT.listening = false;
            STT.onState && STT.onState("idle");
          }
        };
        r.onend = () => {
          STT.listening = false;
          if (!STT._manualStop && STT.always) {
            const delay = Math.min(1200, 160 + STT._retry++ * 120);
            clearTimeout(STT._timer);
            STT._timer = setTimeout(() => {
              try {
                STT._start();
              } catch {
              }
            }, delay);
          } else {
            ORB.setState("idle");
            STT.onState && STT.onState("idle");
          }
        };
        STT.rec = r;
        return true;
      },
      _start() {
        try {
          if (STT._paused) return;
          const ui = global.ZY.I18N && global.ZY.I18N.sttTag ? global.ZY.I18N.sttTag() : "";
          STT.rec.lang = !STT.auto && STT.lang ? STT.lang : ui || "bn-BD";
          STT.rec.start();
        } catch {
        }
      },
      /**
       * Open the mic on the very first touch/keypress anywhere. Browsers block
       * microphone access before a gesture, and the user should never have to
       * find a button — the first tap anywhere is enough, and the orb also works.
       */
      _armGesture() {
        if (STT._gestureArmed) return;
        STT._gestureArmed = true;
        const go = () => {
          document.removeEventListener("pointerdown", go, true);
          document.removeEventListener("keydown", go, true);
          STT._gestureArmed = false;
          STT._needsGesture = false;
          if (STT._helperWanted) {
            STT._helperWanted = false;
            STT._openHelper();
            return;
          }
          if (STT.always) STT._start();
        };
        document.addEventListener("pointerdown", go, true);
        document.addEventListener("keydown", go, true);
      },
      start() {
        STT._manualStop = false;
        STT.always = true;
        STT._watchdog();
        if (STT._paused) return true;
        if (STT.nativeOn()) {
          global.ZY.Native.stt.keepAlive(true);
          global.ZY.Native.stt.start(STT.auto ? "" : STT.lang);
          return true;
        }
        if (STT.bridgeOn()) {
          STT._bridgeInit();
          return true;
        }
        if (!STT.rec && !STT.init()) return false;
        STT._start();
        return true;
      },
      /**
       * Close the microphone while the assistant's own voice is playing.
       *
       * This is the permanent fix for "her words come back into the chat box and
       * send themselves": the recogniser was hearing the loudspeaker, transcribing
       * her sentence and submitting it as if the user had spoken. Echo-matching in
       * JS was a guess; shutting the mic during playback removes the echo at its
       * source. The mic reopens by itself the moment the voice stops, so the user
       * can keep talking without touching anything.
       */
      pause() {
        STT._paused = true;
        clearTimeout(STT._timer);
        if (STT.nativeOn()) {
          try {
            global.ZY.Native.stt.stop();
          } catch {
          }
        } else if (!STT.bridgeOn()) {
          try {
            STT.rec && STT.rec.abort && STT.rec.abort();
          } catch {
          }
        }
        STT.listening = false;
        if (STT.onPartial) STT.onPartial("");
      },
      /** The voice has stopped — listen again immediately. */
      resume() {
        STT._paused = false;
        if (STT._manualStop) return;
        if (!STT.always && !global.ZY.store.get("alwaysMic", true)) return;
        if (STT.nativeOn()) {
          try {
            global.ZY.Native.stt.keepAlive(true);
          } catch {
          }
        } else if (STT.bridgeOn()) STT._bridgeInit();
        else if (STT.rec) {
          try {
            STT._start();
          } catch {
          }
        }
      },
      /** Re-arm the microphone after the assistant finished speaking. The mic is
       * never switched off — the recogniser simply needs to be told to listen
       * again after each utterance. This is what keeps the loop running.
       */
      keepAlive() {
        if (STT._paused) return;
        if (!STT.always && !global.ZY.store.get("alwaysMic", true)) return;
        if (STT.nativeOn()) {
          try {
            global.ZY.Native.stt.keepAlive(true);
          } catch {
          }
        } else if (STT.bridgeOn()) STT._bridgeInit();
        else if (STT.rec && !STT.listening) {
          try {
            STT._start();
          } catch {
          }
        }
      },
      /**
       * The phone's recogniser and its TTS engine share the audio hardware, so
       * Android closes the mic the moment speech starts. Nothing in the native
       * layer notices that the mic went away and never came back — that is the
       * "while she talks I cannot say a word" complaint.
       *
       * This is the single authority over the microphone: it keeps the gate shut
       * for exactly as long as her voice is playing and re-arms the microphone the
       * moment it stops, so the user is never left deaf and never hears herself
       * transcribed.
       */
      _watchdog() {
        clearInterval(STT._wd);
        STT._wd = setInterval(() => {
          if (STT._manualStop) return;
          if (!STT.always && !global.ZY.store.get("alwaysMic", true)) return;
          const TTS2 = global.ZY.VOICE && global.ZY.VOICE.TTS;
          const speaking = !!(TTS2 && (TTS2._speaking || TTS2._queue && TTS2._queue.length));
          if (speaking) {
            if (!STT._paused) STT.pause();
            return;
          }
          if (STT._paused) STT.resume();
          if (STT.listening) return;
          STT.keepAlive();
        }, 1200);
      },
      /** Stop only when the user explicitly asks — the default is "never". */
      stop() {
        STT._manualStop = true;
        STT.always = false;
        STT._paused = false;
        clearTimeout(STT._timer);
        clearInterval(STT._wd);
        if (STT.nativeOn()) {
          global.ZY.Native.stt.keepAlive(false);
          global.ZY.Native.stt.stop();
        } else if (STT.bridgeOn()) {
          try {
            STT._br && STT._br.close();
          } catch {
          }
          STT._br = null;
          STT._brOn = false;
        } else {
          try {
            STT.rec && STT.rec.stop();
          } catch {
          }
        }
        STT.listening = false;
        ORB.setState("idle");
        STT.onState && STT.onState("idle");
      },
      /**
       * An empty tag turns on automatic language detection, which is the default:
       * the user speaks Bangla, English, Arabic or anything else and the
       * recogniser works it out. Picking a language pins the recogniser to it.
       */
      setLang(tag) {
        STT.auto = !tag;
        STT.lang = tag || "";
        if (STT.rec && !STT.nativeOn()) {
          try {
            STT.rec.lang = STT.lang;
          } catch {
          }
        }
        if (STT.nativeOn() && STT.listening) {
          STT.stop();
          STT.start();
        }
      },
      toggle() {
        return STT.listening ? (STT.stop(), false) : STT.start();
      }
    };
    const RUDE_WORDS = ["\u09AE\u09C2\u09B0\u09CD\u0996", "\u09A8\u09BF\u09B0\u09CD\u09AC\u09CB\u09A7", "\u099C\u09BE\u09B9\u09BF\u09B2", "\u0996\u09BE\u09A8\u0995\u09BF", "\u09AE\u09BE\u0997\u09BF", "\u09B6\u09BE\u09B2\u09BE", "\u09B6\u09BE\u09B2\u09BF", "\u0995\u09C1\u09A4\u09CD\u09A4\u09BE", "\u09B9\u09BE\u09B0\u09BE\u09AE\u09BF", "\u099B\u09BE\u0997\u09B2", "\u09AC\u09CB\u0995\u09BE", "\u0997\u09BE\u09A7\u09BE"];
    const BN = "\u0980-\u09FF";
    const wordRe = (w, trailing) => new RegExp(`(^|[^${BN}])${w}${trailing || ""}`, "g");
    const TU_FORMS = [
      // Pronouns. Prefix edge only, so inflected forms (তুইও, তুইয়ের) are caught
      // too; "তোর" must not swallow "তোরণ", hence the one exclusion.
      [wordRe("\u09A4\u09C1\u0987"), "$1\u09A4\u09C1\u09AE\u09BF"],
      [wordRe("\u09A4\u09CB\u0995\u09C7"), "$1\u09A4\u09CB\u09AE\u09BE\u0995\u09C7"],
      [wordRe("\u09A4\u09CB\u09B0\u09BE"), "$1\u09A4\u09CB\u09AE\u09B0\u09BE"],
      [wordRe("\u09A4\u09CB\u09B0", "(?!\u09A3)"), "$1\u09A4\u09CB\u09AE\u09BE\u09B0"]
    ];
    const VERB_STEMS = ["\u0995\u09B0", "\u09AC\u09B2", "\u09A6\u09C7\u0996", "\u09B6\u09C1\u09A8", "\u09A7\u09B0", "\u09AC\u09B8", "\u0989\u09A0", "\u09AA\u09A1\u09BC", "\u09B2\u09BF\u0996", "\u09AD\u09BE\u09AC", "\u099C\u09BE\u09A8", "\u09AA\u09BE\u09B0", "\u099A\u09B2", "\u09A5\u09BE\u09AE", "\u0996\u09C7\u09B2", "\u0998\u09C1\u09AE\u09BE", "\u09B9\u09BE\u09B8", "\u0995\u09BE\u0981\u09A6", "\u0996\u09CB\u0981\u099C", "\u09A1\u09BE\u0995", "\u09AC\u09C1\u099D", "\u09B0\u09BE\u0996", "\u09A4\u09C1\u09B2", "\u099B\u09BE\u09A1\u09BC", "\u09AC\u09BE\u0981\u099A"];
    const TU_VERBS = { "\u09AF\u09BE\u09B8": "\u09AF\u09BE\u0993", "\u0996\u09BE\u09B8": "\u0996\u09BE\u0993", "\u09A8\u09BF\u09B8": "\u09A8\u09BE\u0993", "\u09A6\u09BF\u09B8": "\u09A6\u09BE\u0993", "\u09AF\u09BE\u09AC\u09BF": "\u09AF\u09BE\u09AC\u09C7", "\u0996\u09BE\u09AC\u09BF": "\u0996\u09BE\u09AC\u09C7", "\u09A6\u09BF\u09AC\u09BF": "\u09A6\u09C7\u09AC\u09C7", "\u09A8\u09BF\u09AC\u09BF": "\u09A8\u09C7\u09AC\u09C7", "\u0995\u0987\u09B2\u09BF": "\u09AC\u09B2\u09B2\u09C7", "\u0997\u09C7\u09B2\u09BF": "\u0997\u09C7\u09B2\u09C7" };
    function verbFix(text) {
      let out = text.replace(new RegExp(`([${BN}]+)\u09BF\u09B8(?=[\\s\u0964,!?]|$)`, "g"), (m, stem) => VERB_STEMS.includes(stem) ? stem + "\u09CB" : m);
      out = out.replace(new RegExp(`([${BN}]+)\u09AC\u09BF(?=[\\s\u0964,!?]|$)`, "g"), (m, stem) => VERB_STEMS.includes(stem) ? stem + "\u09AC\u09C7" : m);
      for (const [from, to] of Object.entries(TU_VERBS)) out = out.replace(wordRe(from), "$1" + to);
      return out;
    }
    function polite(text) {
      let out = String(text || "");
      for (const [re, to] of TU_FORMS) out = out.replace(re, to);
      out = verbFix(out);
      for (const w of RUDE_WORDS) out = out.replace(wordRe(w), "$1\u2014");
      return out;
    }
    function politeStream(full) {
      const upto = lastEnd(full);
      if (upto <= 0) return full;
      return polite(full.slice(0, upto)) + full.slice(upto);
    }
    global.ZY = global.ZY || {};
    global.ZY.ORB = ORB;
    TTS.langOf = langOf;
    TTS.detectLang = detectLang;
    TTS.polite = polite;
    TTS.politeStream = politeStream;
    global.ZY.VOICE = { TTS, STT };
  })(window);
})();
