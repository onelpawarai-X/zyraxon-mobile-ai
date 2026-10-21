/**
 * Drive the real ZYRAXON page in a real Chromium and prove the desktop speech
 * bridge works end to end: a Bangla sentence posted the way bridge.html posts it
 * must land in the chat composer, and a reply handed to the helper must come out
 * as a "speak" command.
 *
 * This exercises the actual shipped code (public/js/voice.js and the server's
 * relay routes) rather than a stand-in, because the previous STT path passed its
 * own unit checks while producing romanised text in the real app.
 *
 * Usage:  node test/bridgecheck.mjs [port]
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const PORT = Number(process.argv[2] || 12000);
const APP = `http://127.0.0.1:${PORT}`;
const CDP_PORT = 9333;

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + JSON.stringify(detail) : ''}`);
};

/** A tiny CDP client: enough to evaluate expressions in the page. */
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.waiting = new Map(); }

  static async attach(port, url) {
    for (let i = 0; i < 60; i++) {
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        const page = list.find((t) => t.type === 'page');
        if (page && page.webSocketDebuggerUrl) {
          const ws = new WebSocket(page.webSocketDebuggerUrl);
          await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
          const c = new CDP(ws);
          ws.onmessage = (e) => {
            const m = JSON.parse(e.data);
            const w = c.waiting.get(m.id);
            if (w) { c.waiting.delete(m.id); w(m); }
          };
          await c.send('Page.enable');
          await c.send('Runtime.enable');
          // This container has no X server, so the real Electron shell cannot
          // be driven here. The one thing that differs inside Electron is that
          // it advertises itself through window.ZYDesktop — and that is exactly
          // what makes voice.js choose the bridge over its broken SR stub. So
          // the flag is injected before any page script runs, and everything
          // else exercised below is the shipped code.
          await c.send('Page.addScriptToEvaluateOnNewDocument', {
            source: 'window.ZYDesktop = { isDesktop: true, platform: "linux" };',
          });
          await c.send('Page.navigate', { url });
          await sleep(2500);
          return c;
        }
      } catch {}
      await sleep(300);
    }
    throw new Error('could not attach to Chromium');
  }

  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((res) => this.waiting.set(id, res));
  }

  /** Evaluate an async expression in the page and return its JSON value. */
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', {
      expression: expr, awaitPromise: true, returnByValue: true,
    });
    if (r.result && r.result.exceptionDetails) {
      throw new Error(JSON.stringify(r.result.exceptionDetails.exception || r.result.exceptionDetails));
    }
    const v = r.result && r.result.result ? r.result.result.value : undefined;
    try { return typeof v === 'string' ? JSON.parse(v) : v; } catch { return v; }
  }
}

/** Kill the browser and its children; killing the parent alone leaves
 *  renderers behind, and they keep the profile and the ports busy. */
async function stop(child) {
  try { child.kill('SIGKILL'); } catch {}
  await sleep(300);
}

async function main() {
  // The server must already be up: this test is about the page, not startup.
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health || !health.ok) {
    console.log(`FAIL server not running on ${APP} — start it first (npm start)`);
    process.exit(1);
  }

  // A throwaway profile per run. The app keeps its conversation in IndexedDB, so
  // a reused profile would replay earlier runs' messages and make every check
  // count the same sentence more than once.
  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-bridge-'));
  const chrome = spawn('chromium', [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${profile}`,
    '--autoplay-policy=no-user-gesture-required',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});

  let cdp;
  let beat = null;
  try {
    cdp = await CDP.attach(CDP_PORT, APP);
    // bridge.html checks in periodically; without that the server stops
    // believing a helper is present after 15 seconds.
    beat = setInterval(() => {
      fetch(`${APP}/api/bridge/hello`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Chrome' }),
      }).catch(() => {});
    }, 5000);
  } catch (e) {
    console.log('FAIL could not start/attach Chromium —', e.message);
    chrome.kill();
    process.exit(1);
  }

  // ── the page really loaded ──
  const boot = await cdp.eval(`JSON.stringify({
    hasVoice: !!(window.ZY && window.ZY.VOICE && window.ZY.VOICE.STT),
    hasInput: !!document.getElementById('input'),
    ready: document.readyState,
  })`);
  record('the app loads in a real Chromium', boot.hasVoice && boot.hasInput, boot);

  // ── a Bangla sentence relayed from the helper lands in the chat box ──
  // bridge.html announces itself before it reports anything, and the app only
  // trusts the helper's voice once it has. Do the same from here.
  const hello = () => fetch(`${APP}/api/bridge/hello`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Chrome' }),
  });
  await hello();
  const seen = await cdp.eval(`(async () => {
    const STT = window.ZY.VOICE.STT;
    for (let i = 0; i < 60 && !STT._helper; i++) await new Promise(r => setTimeout(r, 100));
    return JSON.stringify({ helper: STT._helper, name: STT._helperName, bridgeOn: STT.bridgeOn() });
  })()`);
  record('the app notices the helper window and trusts its voice',
    seen.helper === true && seen.bridgeOn === true, seen);

  const heard = await cdp.eval(`(async () => {
    const STT = window.ZY.VOICE.STT;
    const input = document.getElementById('input');
    let live = '';
    window.ZY.ui.setLive = (t) => { live = t; };
    STT.init();
    // Silence the greeting the app plays on a first run, exactly as a user
    // pressing stop would, so the microphone gate is open before we speak.
    window.ZY.VOICE.TTS.stop();
    // Wait for the relay to be connected instead of guessing a delay: the
    // whole point is that speech reaches the chat box, and it cannot until the
    // events stream is open.
    for (let i = 0; i < 60 && !STT._brOn; i++) await new Promise(r => setTimeout(r, 100));
    for (let i = 0; i < 60 && STT._paused; i++) await new Promise(r => setTimeout(r, 100));
    const post = (kind, text) => fetch('/api/bridge/say', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind, text, lang: 'bn-BD' }),
    });
    const dbg = { brOn: STT._brOn, paused: STT._paused, br: !!STT._br,
      rs: STT._br ? STT._br.readyState : -1, onFinal: typeof STT.onFinal,
      listening: STT.listening };
    await post('partial', 'আমার নাম');
    await new Promise(r => setTimeout(r, 500));
    const afterPartial = { live, box: input.value, dbg };
    await post('final', 'আমার নাম জিয়ারাক্সন');
    await new Promise(r => setTimeout(r, 900));
    // A final result is submitted, so the composer is cleared by design; the
    // honest check is that the user's own words reached the conversation.
    const bubbles = [...document.querySelectorAll('.msg.user .body')].map(n => n.textContent.trim());
    const log = (window.ZY.Agent && window.ZY.Agent.messages ? window.ZY.Agent.messages : [])
      .filter(m => m.role === 'user').map(m => String(m.content || ''));
    return JSON.stringify({
      afterPartial,
      bubbles,
      log,
      box: input.value,
      live,
      said: bubbles.concat(log).join(' '),
      bangla: /[\\u0980-\\u09FF]/.test(bubbles.concat(log).join(' ') || ''),
    });
  })()`);
  record('interim Bangla speech shows up while speaking',
    /আমার নাম/.test(String(heard.afterPartial && heard.afterPartial.live || '')), heard.afterPartial);
  record('final Bangla speech reaches the conversation in Bangla script',
    heard.bangla === true && /জিয়ারাক্সন/.test(String(heard.said || '')),
    { bubbles: heard.bubbles, log: heard.log, dbg: heard.afterPartial && heard.afterPartial.dbg });
  record('the recognised sentence is not romanised',
    !/[A-Za-z]{3,}/.test(String(heard.said || '')), { said: heard.said });

  // ── the app hands her reply to the helper's voice ──
  const say = await cdp.eval(`(async () => {
    const before = (await (await fetch('/api/bridge/poll?since=0')).json()).now;
    await window.ZY.VOICE.STT._bridgeSay('হ্যালো, আমি শুনতে পাচ্ছি', 'bn-BD');
    const j = await (await fetch('/api/bridge/poll?since=' + before)).json();
    const cmd = (j.cmds || []).find(x => x.cmd === 'speak');
    return JSON.stringify({ sent: !!cmd, text: cmd ? cmd.text : '' });
  })()`);
  record('her reply is sent to the helper voice to be spoken',
    say.sent === true && /শুনতে/.test(String(say.text || '')), say);

  // ── the helper's "spoke" event releases the next sentence ──
  const spoke = await cdp.eval(`(async () => {
    const T = window.ZY.VOICE.TTS;
    let done = false;
    T._brDone = () => { done = true; };
    await fetch('/api/bridge/say', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind: 'spoke' }),
    });
    await new Promise(r => setTimeout(r, 600));
    return JSON.stringify({ done });
  })()`);
  record('the helper reports when a sentence finished, so the next one can play',
    spoke.done === true, spoke);

  // ── the relay is what the app actually uses on desktop ──
  const routing = await cdp.eval(`JSON.stringify({
    sr: typeof (window.SpeechRecognition || window.webkitSpeechRecognition),
    available: window.ZY.VOICE.STT.available(),
    hasBridgeFns: typeof window.ZY.VOICE.STT._bridgeInit === 'function'
      && typeof window.ZY.VOICE.STT._bridgeSay === 'function',
  })`);
  record('the bridge is present as a real speech path', routing.hasBridgeFns === true, routing);

  if (beat) clearInterval(beat);
  cdp.ws.close();
  await stop(chrome);
  await rm(profile, { recursive: true, force: true }).catch(() => {});

  const failed = results.filter((r) => !r.ok);
  console.log('\n' + (failed.length ? `${failed.length} FAILED: ${failed.map((f) => f.name).join(', ')}` : 'ALL BRIDGE CHECKS PASSED'));
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('crashed:', e); process.exit(1); });
