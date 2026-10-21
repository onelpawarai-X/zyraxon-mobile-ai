/**
 * The speech helper contract.
 *
 * The app no longer ships an Electron shell, so on Windows/macOS/Linux the only
 * way to get Chrome's own recogniser and voices is the helper window: the app
 * opens bridge.html, that page announces itself, and from then on every word
 * the user says and every sentence the assistant speaks goes through it. If
 * this contract breaks, desktop voice goes silent with no error at all — which
 * is exactly the kind of failure that needs a test rather than a comment.
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const APP = process.env.ZYRAXON_APP || 'http://127.0.0.1:12000';
const CDP_PORT = 9371;

const profile = await mkdtemp(path.join(tmpdir(), 'helper-'));
const ch = spawn('chromium', ['--headless=new', '--no-sandbox', '--disable-gpu',
  '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required',
  `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
  '--window-size=412,915', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
ch.stderr.on('data', () => {});

let ws;
for (let i = 0; i < 80; i++) {
  try {
    const l = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json();
    const p = l.find((t) => t.type === 'page');
    if (p) { ws = new WebSocket(p.webSocketDebuggerUrl); await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; }); break; }
  } catch {}
  await sleep(300);
}
let id = 0; const wait = new Map();
ws.onmessage = (e) => { const m = JSON.parse(e.data); const f = wait.get(m.id); if (f) { wait.delete(m.id); f(m); } };
const send = (method, params = {}) => { const i = ++id; ws.send(JSON.stringify({ id: i, method, params })); return new Promise((r) => wait.set(i, r)); };
const ev = async (x) => {
  const r = await send('Runtime.evaluate', { expression: x, awaitPromise: true, returnByValue: true });
  const v = r.result?.result?.value;
  try { return typeof v === 'string' ? JSON.parse(v) : v; } catch { return v; }
};
await send('Page.enable'); await send('Runtime.enable');

const checks = [];
const record = (name, ok, info) => {
  checks.push(ok);
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${info !== undefined ? ' — ' + JSON.stringify(info) : ''}`);
};

// ── 1. the helper page exists and is served ──
const page = await fetch(`${APP}/bridge.html`);
const html = await page.text();
record('the voice helper page is served', page.status === 200 && /SpeechRecognition|speechSynthesis/.test(html),
  { status: page.status, bytes: html.length });

// ── 2. it announces itself, and the server remembers ──
await send('Page.navigate', { url: `${APP}/bridge.html` });
await sleep(3500);
let status = null;
for (let i = 0; i < 30; i++) {
  status = await (await fetch(`${APP}/api/bridge/status`)).json();
  if (status.helper) break;
  await sleep(300);
}
record('the helper announces itself to the server', !!status && status.helper === true,
  { helper: status?.helper, helperName: status?.helperName });

// ── 3. the app notices the already-open helper on its own ──
// This is the reload case: the window outlives the page, so the app has to
// discover it. It does that by asking the server, which is what lets a fresh
// page go straight to the bridge instead of retrying a dead recogniser.
await send('Page.navigate', { url: APP });
let app = null;
for (let i = 0; i < 30; i++) {
  await sleep(500);
  app = await ev(`JSON.stringify((() => {
    const S = window.ZY.VOICE.STT;
    return { bridgeOn: S.bridgeOn(), helper: S._helper, name: S._helperName,
             desktop: S._desktopShell(), sr: !!S.SR };
  })())`);
  if (app && app.bridgeOn) break;
}
record('the app finds an already-open helper on its own',
  app.desktop === true && app.bridgeOn === true && app.helper === true, app);

// ── 4. a recogniser that answers "network" hands over to the helper ──
// "network" is what a Chromium build without the speech service returns. The
// app must stop retrying it and open the helper window instead of going silent.
const handed = await ev(`JSON.stringify((() => {
  const S = window.ZY.VOICE.STT;
  S._srBroken = false;
  S._helperWin = null; S._helperNotice = false; S._helperWanted = false;
  if (!S.rec || typeof S.rec.onerror !== 'function') return { noRec: true };
  S.rec.onerror({ error: 'network' });
  return { srBroken: S._srBroken, wanted: S._helperWanted, opened: !!S._helperWin };
})())`);
record('a browser with no speech service hands over to the helper',
  handed.srBroken === true && (handed.wanted === true || handed.opened === true), handed);

// ── 5. a sentence the app hands to the helper survives the round trip ──
// The app never speaks to the helper directly; it posts a command and the
// helper collects it on its next poll. Both halves have to agree on the shape.
const sent = await ev(`(async () => {
  const S = window.ZY.VOICE.STT;
  const r = await S._bridgeSay('ভয়েস পরীক্ষা ১২৩', 'bn-BD');
  return JSON.stringify({ ok: r.ok });
})()`);
const polled = await (await fetch(`${APP}/api/bridge/poll?since=0`)).json();
const cmds = polled.cmds || [];
const mine = cmds.find((c) => c.cmd === 'speak' && /ভয়েস পরীক্ষা/.test(c.text));
record('a sentence the app hands to the helper is delivered', sent.ok === true && !!mine,
  { postOk: sent.ok, delivered: mine ? { cmd: mine.cmd, lang: mine.lang } : null });

console.log(checks.every(Boolean) ? '\nALL CHECKS PASSED' : '\nSOME CHECKS FAILED');
ws.close(); ch.kill();
await rm(profile, { recursive: true, force: true }).catch(() => {});
process.exit(checks.every(Boolean) ? 0 : 1);
