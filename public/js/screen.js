/**
 * ZYRAXON — background screen awareness.
 *
 * The user asked for this explicitly: while the assistant is running, every
 * time they move to a new screen the phone's own view is pushed into the
 * model's context *in the background*. Nothing is shown on screen, nothing is
 * written to the gallery, and no tool call is involved — the frames simply
 * arrive as context, exactly like the user's own words do.
 *
 * Two things come out of it:
 *   1. The assistant always knows which app the user is looking at, so it can
 *      offer help unprompted ("তুমি এখন Facebook-এ আছো, কী করতে চাও বলো").
 *   2. When the user opens an app and asks for something to be done in it, the
 *      screen is already in context — no `read_screen` round-trip needed.
 *
 * `read_screen` and `screen_shot` remain for the deliberate case: "look at the
 * screen *now* and act on it".
 */
(function (global) {
  const W = {
    on: false,
    everyMs: 1200,
    lastPkg: '',
    lastLabel: '',
    lastInjectAt: 0,
    pendingFrame: null,
    timer: null,
    seenAt: 0,
  };

  function nat() { return global.ZY && global.ZY.Native; }

  /** Is this really the APK, with the accessibility service granted? */
  function available() {
    const n = nat();
    return !!(n && n.app && n.settings && n.settings.accessibilityOn && n.settings.accessibilityOn());
  }

  /**
   * Start the background watch. It is idempotent, so calling it from several
   * places (boot, permission grant, app resume) is safe.
   */
  function start() {
    if (!available()) return false;
    const n = nat();
    try {
      if (n.device.watchScreen) n.device.watchScreen(true, W.everyMs);
      W.on = true;
    } catch (e) { W.on = false; }
    return W.on;
  }

  function stop() {
    const n = nat();
    try { if (n && n.device.watchScreen) n.device.watchScreen(false, 0); } catch (e) {}
    W.on = false;
    return false;
  }

  function toggle(on) { return on ? start() : stop(); }

  /**
   * A new screen arrived from the native side. The package change is the
   * interesting event: it is what "the user went somewhere else" means.
   */
  function onScreenChanged(pkg, label) {
    if (!pkg || pkg === W.lastPkg) return;
    W.lastPkg = pkg;
    W.lastLabel = label || pkg;
    W.seenAt = Date.now();
    // Our own app is not "somewhere else" — the user is looking at the chat.
    const mine = 'ai.zyraxon.assistant';
    if (pkg === mine) return;
    W.lastInjectAt = Date.now();
    const note = `[screen] ব্যবহারকারী এখন "${W.lastLabel}" (${pkg}) অ্যাপে আছেন। এটা ব্যাকগ্রাউন্ড থেকে জানানো হলো — ব্যবহারকারী দেখতে পাচ্ছেন না। দরকার হলে সংক্ষেপে সাহায্য অফার করো, নাহলে কাজ চালিয়ে যাও।`;
    inject(note);
    global.ZY.Monitor && global.ZY.Monitor.note && global.ZY.Monitor.note(`স্ক্রিন: ${W.lastLabel}`);
  }

  /**
   * A background frame. It is attached to the next turn rather than starting a
   * turn of its own — the assistant should not talk over the user, but it must
   * be able to *see* what is on screen when it does answer.
   */
  /** A background frame. It is attached to the next turn rather than starting a
   * turn of its own — the assistant should not talk over the user, but it must
   * be able to *see* what is on screen when it does answer. */
  function onFrame(b64) {
    W.pendingFrame = { kind: 'image', dataUrl: 'data:image/jpeg;base64,' + b64, name: 'screen.jpg', bg: true };
    W.seenAt = Date.now();
  }

  /** The window text of a screen change, for devices below API 30. */
  function onScreenText(text) {
    if (!text) return;
    W.pendingText = String(text).slice(0, 3000);
    W.seenAt = Date.now();
  }

  /**
   * The frame waiting to be shown to the model, if it is fresh enough to still
   * describe the screen the user is on.
   */
  function takeFrame(maxAgeMs) {
    const f = W.pendingFrame;
    if (!f) return null;
    const age = Date.now() - W.seenAt;
    if (age > (maxAgeMs || 20000)) { W.pendingFrame = null; return null; }
    W.pendingFrame = null;
    return f;
  }

  /** Where the user is, as a short human sentence in their language. */
  function where() {
    if (!W.lastPkg) return '';
    return W.lastLabel || W.lastPkg;
  }

  /* ── camera: permission is the only step, no tool call ever ────────────
     The user asked for exactly this. Once they allow the camera, the phone's
     eye is simply open: frames arrive in the background and ride along with
     the conversation, the same way the screen frames do. The model never has
     to ask for a photo, and the user never taps a tool. */

  const C = { on: false, stream: null, timer: null, video: null, canvas: null, facing: 'environment', lastAt: 0 };

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
        audio: false,
      });
    } catch (e) { C.on = false; return false; }
    C.video = document.createElement('video');
    C.video.srcObject = C.stream;
    C.video.muted = true;
    C.video.playsInline = true;
    try { await C.video.play(); } catch (e) {}
    C.canvas = document.createElement('canvas');
    C.on = true;
    C.timer = setInterval(camGrab, 2500);
    // The first frame goes immediately, so "she sees it at once" is true.
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
      C.canvas.getContext('2d').drawImage(C.video, 0, 0, C.canvas.width, C.canvas.height);
      const url = C.canvas.toDataURL('image/jpeg', 0.6);
      W.pendingFrame = { kind: 'image', dataUrl: url, name: 'camera.jpg', cam: true };
      W.seenAt = Date.now();
      C.lastAt = Date.now();
    } catch (e) {}
  }

  async function camStop() {
    C.on = false;
    if (C.timer) { clearInterval(C.timer); C.timer = null; }
    if (C.stream) { try { C.stream.getTracks().forEach((t) => t.stop()); } catch (e) {} C.stream = null; }
    if (C.video) { try { C.video.srcObject = null; } catch (e) {} C.video = null; }
    return false;
  }

  function camToggle(on, facing) { return on ? camStart(facing) : camStop(); }

  /**
   * Push a background observation into the running conversation. If the agent
   * is idle the note is kept as context for the next turn instead of making her
   * speak out of nowhere.
   */
  function inject(note) {
    if (!global.ZY.Agent) return;
    if (global.ZY.Agent.busy) {
      global.ZY.Agent.pending.push(note);
      return;
    }
    W.queuedNote = note;
  }

  /** The note waiting for the next real user turn, consumed once. */
  function takeNote() {
    const n = W.queuedNote || '';
    W.queuedNote = '';
    return n;
  }

  /**
   * Build the block of background context that rides along with every turn:
   * where the user is, and — when it is fresh — what their screen looks like.
   * This is the whole point of the feature: no tool call, always informed.
   */
  function contextParts() {
    const parts = [];
    const label = where();
    if (label) {
      parts.push({ type: 'text', text: `[screen] ব্যবহারকারী এখন "${label}" অ্যাপে আছেন (${W.lastPkg})।` });
    }
    const frame = takeFrame();
    if (frame) {
      const what = frame.cam ? 'ক্যামেরায় এখন যা দেখা যাচ্ছে' : 'ব্যবহারকারীর বর্তমান স্ক্রিন';
      parts.push({ type: 'text', text: `[screen] নিচের ছবিটি ${what} (ব্যাকগ্রাউন্ড থেকে, কোনো টুল কল ছাড়াই) — দেখে নাও।` });
      parts.push({ type: 'image_url', image_url: { url: frame.dataUrl } });
    } else if (W.pendingText) {
      parts.push({ type: 'text', text: '[screen] ব্যবহারকারীর স্ক্রিনে এখন যা দেখা যাচ্ছে:\n' + W.pendingText.slice(0, 2000) });
      W.pendingText = '';
    }
    return parts;
  }

  global.ZY = global.ZY || {};
  global.ZY.Screen = {
    start, stop, toggle, available,
    onScreenChanged, onFrame, onScreenText, takeFrame, takeNote, contextParts,
    camStart, camStop, camToggle, camAvailable,
    get cam() { return { on: C.on, facing: C.facing, lastAt: C.lastAt }; },
    get state() { return { on: W.on, pkg: W.lastPkg, label: W.lastLabel, seenAt: W.seenAt }; },
  };
})(window);
