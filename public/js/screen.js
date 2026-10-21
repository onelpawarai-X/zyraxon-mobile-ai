(() => {
  (function(global) {
    const W = {
      on: false,
      everyMs: 1200,
      lastPkg: "",
      lastLabel: "",
      lastInjectAt: 0,
      pendingFrame: null,
      timer: null,
      seenAt: 0
    };
    function nat() {
      return global.ZY && global.ZY.Native;
    }
    function available() {
      const n = nat();
      return !!(n && n.app && n.settings && n.settings.accessibilityOn && n.settings.accessibilityOn());
    }
    function start() {
      if (!available()) return false;
      const n = nat();
      try {
        if (n.device.watchScreen) n.device.watchScreen(true, W.everyMs);
        W.on = true;
      } catch (e) {
        W.on = false;
      }
      return W.on;
    }
    function stop() {
      const n = nat();
      try {
        if (n && n.device.watchScreen) n.device.watchScreen(false, 0);
      } catch (e) {
      }
      W.on = false;
      return false;
    }
    function toggle(on) {
      return on ? start() : stop();
    }
    function onScreenChanged(pkg, label) {
      if (!pkg || pkg === W.lastPkg) return;
      W.lastPkg = pkg;
      W.lastLabel = label || pkg;
      W.seenAt = Date.now();
      const mine = "ai.zyraxon.assistant";
      if (pkg === mine) return;
      W.lastInjectAt = Date.now();
      const note = `[screen] \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0\u0995\u09BE\u09B0\u09C0 \u098F\u0996\u09A8 "${W.lastLabel}" (${pkg}) \u0985\u09CD\u09AF\u09BE\u09AA\u09C7 \u0986\u099B\u09C7\u09A8\u0964 \u098F\u099F\u09BE \u09AC\u09CD\u09AF\u09BE\u0995\u0997\u09CD\u09B0\u09BE\u0989\u09A8\u09CD\u09A1 \u09A5\u09C7\u0995\u09C7 \u099C\u09BE\u09A8\u09BE\u09A8\u09CB \u09B9\u09B2\u09CB \u2014 \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0\u0995\u09BE\u09B0\u09C0 \u09A6\u09C7\u0996\u09A4\u09C7 \u09AA\u09BE\u099A\u09CD\u099B\u09C7\u09A8 \u09A8\u09BE\u0964 \u09A6\u09B0\u0995\u09BE\u09B0 \u09B9\u09B2\u09C7 \u09B8\u0982\u0995\u09CD\u09B7\u09C7\u09AA\u09C7 \u09B8\u09BE\u09B9\u09BE\u09AF\u09CD\u09AF \u0985\u09AB\u09BE\u09B0 \u0995\u09B0\u09CB, \u09A8\u09BE\u09B9\u09B2\u09C7 \u0995\u09BE\u099C \u099A\u09BE\u09B2\u09BF\u09AF\u09BC\u09C7 \u09AF\u09BE\u0993\u0964`;
      inject(note);
      global.ZY.Monitor && global.ZY.Monitor.note && global.ZY.Monitor.note(`\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8: ${W.lastLabel}`);
    }
    function onFrame(b64) {
      W.pendingFrame = { kind: "image", dataUrl: "data:image/jpeg;base64," + b64, name: "screen.jpg", bg: true };
      W.seenAt = Date.now();
    }
    function onScreenText(text) {
      if (!text) return;
      W.pendingText = String(text).slice(0, 3e3);
      W.seenAt = Date.now();
    }
    function takeFrame(maxAgeMs) {
      const f = W.pendingFrame;
      if (!f) return null;
      const age = Date.now() - W.seenAt;
      if (age > (maxAgeMs || 2e4)) {
        W.pendingFrame = null;
        return null;
      }
      W.pendingFrame = null;
      return f;
    }
    function where() {
      if (!W.lastPkg) return "";
      return W.lastLabel || W.lastPkg;
    }
    const C = { on: false, stream: null, timer: null, video: null, canvas: null, facing: "environment", lastAt: 0 };
    function camAvailable() {
      return !!(global.navigator && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
    }
    async function camStart(facing) {
      if (!camAvailable()) return false;
      if (C.on && (!facing || facing === C.facing)) return true;
      await camStop();
      if (facing) C.facing = facing;
      try {
        C.stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: C.facing, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false
        });
      } catch (e) {
        C.on = false;
        return false;
      }
      C.video = document.createElement("video");
      C.video.srcObject = C.stream;
      C.video.muted = true;
      C.video.playsInline = true;
      try {
        await C.video.play();
      } catch (e) {
      }
      C.canvas = document.createElement("canvas");
      C.on = true;
      C.timer = setInterval(camGrab, 2500);
      setTimeout(camGrab, 600);
      return true;
    }
    function camGrab() {
      if (!C.on || !C.video || !C.canvas) return;
      const vw = C.video.videoWidth, vh = C.video.videoHeight;
      if (!vw || !vh) return;
      const scale = Math.min(1, 960 / Math.max(vw, vh));
      C.canvas.width = Math.round(vw * scale);
      C.canvas.height = Math.round(vh * scale);
      try {
        C.canvas.getContext("2d").drawImage(C.video, 0, 0, C.canvas.width, C.canvas.height);
        const url = C.canvas.toDataURL("image/jpeg", 0.6);
        W.pendingFrame = { kind: "image", dataUrl: url, name: "camera.jpg", cam: true };
        W.seenAt = Date.now();
        C.lastAt = Date.now();
      } catch (e) {
      }
    }
    async function camStop() {
      C.on = false;
      if (C.timer) {
        clearInterval(C.timer);
        C.timer = null;
      }
      if (C.stream) {
        try {
          C.stream.getTracks().forEach((t) => t.stop());
        } catch (e) {
        }
        C.stream = null;
      }
      if (C.video) {
        try {
          C.video.srcObject = null;
        } catch (e) {
        }
        C.video = null;
      }
      return false;
    }
    function camToggle(on, facing) {
      return on ? camStart(facing) : camStop();
    }
    function inject(note) {
      if (!global.ZY.Agent) return;
      if (global.ZY.Agent.busy) {
        global.ZY.Agent.pending.push(note);
        return;
      }
      W.queuedNote = note;
    }
    function takeNote() {
      const n = W.queuedNote || "";
      W.queuedNote = "";
      return n;
    }
    function contextParts() {
      const parts = [];
      const label = where();
      if (label) {
        parts.push({ type: "text", text: `[screen] \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0\u0995\u09BE\u09B0\u09C0 \u098F\u0996\u09A8 "${label}" \u0985\u09CD\u09AF\u09BE\u09AA\u09C7 \u0986\u099B\u09C7\u09A8 (${W.lastPkg})\u0964` });
      }
      const frame = takeFrame();
      if (frame) {
        const what = frame.cam ? "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE\u09AF\u09BC \u098F\u0996\u09A8 \u09AF\u09BE \u09A6\u09C7\u0996\u09BE \u09AF\u09BE\u099A\u09CD\u099B\u09C7" : "\u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0\u0995\u09BE\u09B0\u09C0\u09B0 \u09AC\u09B0\u09CD\u09A4\u09AE\u09BE\u09A8 \u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8";
        parts.push({ type: "text", text: `[screen] \u09A8\u09BF\u099A\u09C7\u09B0 \u099B\u09AC\u09BF\u099F\u09BF ${what} (\u09AC\u09CD\u09AF\u09BE\u0995\u0997\u09CD\u09B0\u09BE\u0989\u09A8\u09CD\u09A1 \u09A5\u09C7\u0995\u09C7, \u0995\u09CB\u09A8\u09CB \u099F\u09C1\u09B2 \u0995\u09B2 \u099B\u09BE\u09A1\u09BC\u09BE\u0987) \u2014 \u09A6\u09C7\u0996\u09C7 \u09A8\u09BE\u0993\u0964` });
        parts.push({ type: "image_url", image_url: { url: frame.dataUrl } });
      } else if (W.pendingText) {
        parts.push({ type: "text", text: "[screen] \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0\u0995\u09BE\u09B0\u09C0\u09B0 \u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09C7 \u098F\u0996\u09A8 \u09AF\u09BE \u09A6\u09C7\u0996\u09BE \u09AF\u09BE\u099A\u09CD\u099B\u09C7:\n" + W.pendingText.slice(0, 2e3) });
        W.pendingText = "";
      }
      return parts;
    }
    global.ZY = global.ZY || {};
    global.ZY.Screen = {
      start,
      stop,
      toggle,
      available,
      onScreenChanged,
      onFrame,
      onScreenText,
      takeFrame,
      takeNote,
      contextParts,
      camStart,
      camStop,
      camToggle,
      camAvailable,
      get cam() {
        return { on: C.on, facing: C.facing, lastAt: C.lastAt };
      },
      get state() {
        return { on: W.on, pkg: W.lastPkg, label: W.lastLabel, seenAt: W.seenAt };
      }
    };
  })(window);
})();
