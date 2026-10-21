/* ══════════════════════════════════════════════════════════════
   ZYRAXON desktop self-test.

   Runs the assistant's real code inside the real Electron window and reports
   what actually happened. It exists because a desktop app that claims to open
   apps or read the screen while doing neither is exactly the failure the user
   called out, and the only way to know is to drive the real thing.

   Run it with:  ZYRAXON_SELFTEST=1 npm start
   ══════════════════════════════════════════════════════════════ */
'use strict';

const results = [];

/** A check that cannot be exercised here (no audio device in this container)
 *  is reported as skipped rather than silently passed or wrongly failed. */
function skip(name, why) {
  results.push({ name, ok: true, skipped: true, detail: why });
  console.log(`[selftest] SKIP ${name} — ${why}`);
}

function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : detail });
  console.log(`[selftest] ${ok ? 'PASS' : 'FAIL'} ${name}${detail === undefined || detail === '' ? '' : ' — ' + JSON.stringify(detail).slice(0, 300)}`);
}

function parseJs(v) {
  if (typeof v === 'string') { try { return JSON.parse(v); } catch { return {}; } }
  return v && typeof v === 'object' ? v : {};
}

async function js(win, expr, timeout = 180000) {
  const r = await win.webContents.executeJavaScript(expr, true).catch((e) => ({ __error: String(e).slice(0, 300) }));
  if (r && r.__error) throw new Error(r.__error);
  void timeout;
  return r;
}

/** Wait until the page's own globals exist — a desktop window can finish
 *  loading before the app's scripts have run. */
async function waitReady(win, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ok = await win.webContents.executeJavaScript(
      '!!(window.ZY && window.ZY.Agent && window.ZY.Tools && window.ZY.Platform)', true,
    ).catch(() => false);
    if (ok) return true;
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

async function run(win, app) {
  void app;
  // Give the bundled server time to warm and the page time to finish booting.
  const ready = await waitReady(win);
  record('the app finished booting', ready);
  await new Promise((r) => setTimeout(r, 2000));

  const raw = await js(win, `JSON.stringify({
    zyKeys: Object.keys(window).filter(k => k.startsWith('ZY')),
    hasNative: typeof window.ZYNative, hasDesktop: typeof window.ZYDesktop,
    ready: document.readyState, href: location.href,
    preloadHint: !!document.getElementById('zy-preload-hint'),
    MediaRecorder: typeof MediaRecorder, AudioContext: typeof (window.AudioContext||window.webkitAudioContext),
    mediaDevices: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
    serverSttSetting: window.ZY.store.get('serverStt', true),
    sr: typeof (window.SpeechRecognition||window.webkitSpeechRecognition),
    nativeOn: window.ZY.VOICE.STT.nativeOn(), serverOn: window.ZY.VOICE.STT.serverOn(),
  })`);
  console.log('[selftest] raw window:', raw);

  // ── the bridge is present and reports the truth ──
  const info = await js(win, `JSON.stringify((() => { try { return {
    hasNative: !!window.ZYNative,
    hasDesktop: !!window.ZYDesktop,
    platform: window.ZYNative ? window.ZYNative.platform() : null,
    app: window.ZY.Native ? window.ZY.Native.app : false,
    nativeInfo: window.ZY.Native ? window.ZY.Native.info : null,
    kind: window.ZY.Platform && window.ZY.Platform.data ? window.ZY.Platform.data.kind : '',
    base: window.ZY.store.get('base'),
    origin: location.origin,
  }; } catch (e) { return { __threw: String(e) }; } })())`);
  const o = typeof info === 'string' ? JSON.parse(info) : info;
  record('Electron injects the native bridge', o.hasNative && o.hasDesktop, o.hasNative);
  const plat = JSON.parse(o.platform || '{}') || {};
  record('bridge reports a real desktop host', plat.desktop === true && plat.accessibility === true, plat);
  record('assistant sees itself as a native app', o.app === true, o.nativeInfo);
  record('platform detected as a desktop OS', ['windows', 'linux', 'macos'].includes(o.kind), o.kind);
  // `base` is usually unset and the app falls back to its own origin, so the
  // real question is whether the bundled server answers on that origin.
  const api = parseJs(await js(win, `(async () => {
    try {
      const r = await fetch('/api/health');
      const j = await r.json();
      return JSON.stringify({ status: r.status, ok: j.ok === true });
    } catch (e) { return JSON.stringify({ status: 0, ok: false, err: String(e) }); }
  })()`));
  record('page talks to the bundled server', api.status === 200 && api.ok === true, api);

  const diag = await js(win, `(async () => {
    const out = { base: window.ZY.store.get('base'), origin: location.origin, href: location.href };
    try {
      const s = await navigator.mediaDevices.getUserMedia({ audio: true });
      out.gum = 'ok:' + s.getAudioTracks().length;
      s.getTracks().forEach(t => t.stop());
    } catch (e) { out.gum = 'ERR:' + (e && e.name ? e.name : String(e)); }
    return JSON.stringify(out);
  })()`);
  const dg = typeof diag === 'string' ? JSON.parse(diag) : {};
  const hasAudio = String(dg.gum || '').startsWith('ok:');
  console.log('[selftest] diag:', diag, hasAudio ? '' : '(no audio device)');

  // ── the host shell is the real host shell ──
  const shell = parseJs(await js(win, `(async () => {
    const r = await window.ZY.Tools.execute('run_shell', { command: 'echo zyraxon-shell-ok' });
    return JSON.stringify({ ok: r.ok, out: (r.data && r.data.stdout) || '' });
  })()`));
  record('real host shell runs commands', shell.ok && String(shell.out).includes('zyraxon-shell-ok'), shell.out && shell.out.trim());

  // ── screen capture returns a real frame ──
  const shot = parseJs(await js(win, `(async () => {
    const n = window.ZY.Native;
    const e = await new Promise((res) => {
      n.device.onShot((x) => { if (x.state === 'ok' || x.state === 'error') res(x); });
      n.device.screenShot();
      setTimeout(() => res({ state: 'timeout', text: '' }), 20000);
    });
    return JSON.stringify({ state: e.state, bytes: (e.text || '').length });
  })()`));
  record('screen capture returns a real frame', shot.state === 'ok' && shot.bytes > 500, shot);

  // ── the background screen watch: real frames, on its own ──
  const watch = parseJs(await js(win, `(async () => {
    const n = window.ZY.Native;
    const frames = [];
    n.device.onShot((e) => { if (e.state === 'bg') frames.push((e.text || '').length); });
    n.device.watchScreen(true, 900);
    await new Promise(r => setTimeout(r, 5000));
    const watching = n.device.watchingScreen();
    n.device.watchScreen(false, 0);
    return JSON.stringify({ watching, frames: frames.length, firstFrame: frames[0] || 0 });
  })()`));
  record('background screen watch turns on', watch.watching === true, watch);
  record('background watch delivers real frames', watch.frames >= 1 && watch.firstFrame > 500, watch);

  // ── the screen feed reaches the model's context without a tool call ──
  const ctx = parseJs(await js(win, `(async () => {
    const n = window.ZY.Native;
    n.device.watchScreen(true, 800);
    await new Promise(r => setTimeout(r, 3000));
    n.device.watchScreen(false, 0);
    const parts = window.ZY.Screen.contextParts();
    return JSON.stringify({ n: parts.length, kinds: parts.map(p => p.type) });
  })()`));
  record('screen feed reaches the model context', ctx.n >= 1, ctx);

  const fg = parseJs(await js(win, `JSON.stringify(window.ZY.Native.device.foregroundApp())`));
  record('foreground app is reported', !!fg.label, fg);

  // ── real tools: the app list, and an honest miss ──
  const apps = parseJs(await js(win, `(async () => {
    const r = await window.ZY.Tools.execute('list_apps', {});
    return JSON.stringify({ ok: r.ok, n: (r.data && r.data.apps || []).length });
  })()`));
  record('real installed apps are listed', apps.ok && apps.n > 0, apps);

  const miss = parseJs(await js(win, `(async () => {
    const r = await window.ZY.Tools.execute('open_app', { name: 'zzz-no-such-app-zzz' });
    return JSON.stringify({ ok: r.ok, error: r.error });
  })()`));
  record('a missing app is reported missing, not faked', miss.ok === false && miss.error === 'app-not-installed', miss);

  // ── live voice: the microphone path really transcribes Bangla ──
  const stt = parseJs(await js(win, `(async () => {
    const STT = window.ZY.VOICE.STT;
    const avail = STT.available();
    const buf = await (await fetch('/api/tts?tl=bn&q=' + encodeURIComponent('আমার গোপন কোড হলো বেগুনি সাতাশ'))).arrayBuffer();
    const text = await STT._transcribe(new Blob([buf], { type: 'audio/mpeg' }));
    return JSON.stringify({ avail, text: String(text || ''), srv: STT.serverOn() });
  })()`));
  const t = String(stt.text || '').toLowerCase();
  const hits = ['amar', 'gop', 'kod', 'beg', 'sat'].filter((f) => t.includes(f)).length;
  record('microphone path is available on desktop', stt.avail === true && stt.srv === true, { avail: stt.avail, srv: stt.srv });
  record('live voice really transcribes Bangla', hits >= 3, { hits, text: t });

  // ── the voice loop opens and releases the microphone ──
  const loop = parseJs(await js(win, `(async () => {
    const STT = window.ZY.VOICE.STT;
    STT.init();
    const started = STT.start();
    await new Promise(r => setTimeout(r, 1200));
    const listening = STT.listening;
    const streamOpen = !!STT._srvStream;
    STT.stop();
    await new Promise(r => setTimeout(r, 400));
    return JSON.stringify({ started, listening, streamOpen, stopped: !STT._srvStream && !STT.listening });
  })()`));
  if (!hasAudio) {
    skip('voice loop opens the microphone', 'no audio input device in this container');
    skip('voice loop releases the microphone on request', 'no audio input device in this container');
  } else {
    record('voice loop opens the microphone', loop.started === true && loop.listening === true && loop.streamOpen === true, loop);
    record('voice loop releases the microphone on request', loop.stopped === true, loop);
  }

  const perm = parseJs(await js(win, `JSON.stringify(window.ZY.Native.permission.state('microphone'))`));
  record('microphone permission already granted', perm.granted === true, perm);

  const failed = results.filter((r) => !r.ok);
  console.log('\n[selftest] ' + (failed.length ? `${failed.length} FAILED: ${failed.map((f) => f.name).join(', ')}` : 'ALL DESKTOP CHECKS PASSED'));
  return failed.length === 0;
}

module.exports = { run };
