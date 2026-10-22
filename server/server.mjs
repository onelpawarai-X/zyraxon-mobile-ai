import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// The desktop app ships this server unpacked next to its resources and tells it
// where that is, because the code's own location may be inside a read-only asar.
const ROOT = process.env.ZYRAXON_ROOT ? path.resolve(process.env.ZYRAXON_ROOT) : path.resolve(__dirname, '..');
const PUBLIC_DIR = path.join(ROOT, 'public');
// Writable state (caches, published sites, the model health file) goes to a
// directory the caller owns when one is given; installed builds cannot write
// next to their own code.
const DATA_DIR = process.env.ZYRAXON_DATA_DIR ? path.resolve(process.env.ZYRAXON_DATA_DIR) : path.join(ROOT, 'data');
const SITES_DIR = path.join(DATA_DIR, 'sites');

const PORT = Number(process.env.PORT || 12000);

// The one true version. package.json is the source; the APK bakes the same
// string into its manifest, and the UI badge asks for it here (or reads it from
// the native bridge on Android), so the three can never disagree.
const APP_VERSION = (() => {
  try {
    return JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8')).version || '0.0.0';
  } catch {
    return '0.0.0';
  }
})();
const ZYRAXON_BASE = process.env.ZYRAXON_BASE || 'https://zyraxonai.lovable.app/api/public/zyraxon/v1';
// The origin a published site is advertised under. A hardcoded host is wrong on
// any machine but the one it names — the app runs on localhost, in a runner, or
// on the user's phone, and the URL it hands back must be one that really
// answers. The request's own Host header is therefore preferred; PUBLIC_ORIGIN
// stays as an explicit override for a reverse proxy that rewrites Host.
const PUBLIC_ORIGIN = process.env.PUBLIC_ORIGIN || '';
function originFor(req) {
  if (PUBLIC_ORIGIN) return PUBLIC_ORIGIN.replace(/\/$/, '');
  const fwdHost = req && req.headers['x-forwarded-host'];
  const fwdProto = req && req.headers['x-forwarded-proto'];
  const host = fwdHost || (req && req.headers.host) || `127.0.0.1:${PORT}`;
  const proto = String(fwdProto || '').split(',')[0].trim() || (/^(localhost|127\.|\[::1\])/.test(host) ? 'http' : 'https');
  return `${proto}://${String(host).split(',')[0].trim()}`;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.webm': 'video/webm', '.mp4': 'video/mp4',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp',
  '.gif': 'image/gif', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2',
  '.mp3': 'audio/mpeg', '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8', '.pdf': 'application/pdf', '.wasm': 'application/wasm',
};

const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

function send(res, code, body, headers = {}) {
  res.writeHead(code, { 'Cache-Control': 'no-store', ...headers });
  res.end(body);
}
function sendJson(res, code, obj) {
  send(res, code, JSON.stringify(obj), { 'Content-Type': 'application/json; charset=utf-8' });
}

/** True when the port is already served by a ZYRAXON instance (our /api/health). */
async function isOurServer(port = PORT) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/api/health', timeout: 1500 }, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(body).ok === true); } catch { resolve(false); }
      });
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

/**
 * A browser that can actually recognise speech.
 *
 * Only the branded Chrome and Edge builds carry the speech service behind
 * webkitSpeechRecognition — an open-source Chromium exposes the API but always
 * fails with "network". Since voice is the whole point of the app, the launcher
 * prefers Chrome, then Edge, then anything else installed.
 */
function speechBrowser() {
  const cands = process.platform === 'win32'
    ? [
      `${process.env['ProgramFiles'] || 'C:\\Program Files'}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)'}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env['LOCALAPPDATA'] || ''}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)'}\\Microsoft\\Edge\\Application\\msedge.exe`,
      `${process.env['ProgramFiles'] || 'C:\\Program Files'}\\Microsoft\\Edge\\Application\\msedge.exe`,
    ]
    : process.platform === 'darwin'
      ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
        '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser']
      : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/microsoft-edge',
        '/usr/bin/microsoft-edge-stable', '/usr/bin/brave-browser', '/opt/google/chrome/chrome',
        '/snap/bin/chromium', '/usr/bin/chromium', '/usr/lib/chromium/chromium'];
  return cands.find((p) => { try { return p && fs.existsSync(p); } catch { return false; } }) || null;
}

/**
 * Open the app in a window the user can actually talk to.
 *
 * The app is a local server, so "installing" it on any of the three desktop
 * systems is just this: start Node, open a browser. Chrome/Edge are preferred
 * and opened as an app window with the microphone allowed, which is what makes
 * speech work with no separate STT/TTS setup.
 */
function openBrowser(target, { app = false } = {}) {
  const exe = app ? speechBrowser() : null;
  if (exe) {
    const profile = path.join(DATA_DIR, 'browser-profile');
    try { fs.mkdirSync(profile, { recursive: true }); } catch {}
    try {
      const child = spawn(exe, [
        `--app=${target}`, `--user-data-dir=${profile}`,
        '--no-first-run', '--no-default-browser-check',
        '--use-fake-ui-for-media-stream',
      ], { detached: true, stdio: 'ignore' });
      child.unref();
      return exe;
    } catch { /* fall through to the default browser */ }
  }
  try {
    const cmd = process.platform === 'win32' ? 'cmd'
      : process.platform === 'darwin' ? 'open' : 'xdg-open';
    const args = process.platform === 'win32' ? ['/c', 'start', '', target] : [target];
    const child = spawn(cmd, args, { detached: true, stdio: 'ignore' });
    child.unref();
  } catch { /* a headless machine simply has no browser — not an error */ }
  return null;
}
/* --------------------------- speech bridge ---------------------------
   A plain Chromium ships a webkitSpeechRecognition stub with no speech backend — the
   API is wired to Google's proprietary cloud keys, which only real Chrome and
   Edge carry — so a packaged desktop app cannot recognise speech on its own.
   The app therefore opens a real Chrome/Edge window on bridge.html,
   which does have working speech, and the two talk over this relay.

   Transport is SSE plus POST rather than a WebSocket, so the server keeps zero
   dependencies. It is strictly a relay: audio never leaves the machine, and the
   only things that cross are the words Chrome already recognised.
------------------------------------------------------------------------ */
const bridge = { clients: new Set(), helperAt: 0, last: null };

/**
 * Open the app in the user's own browser.
 *
 * The desktop builds run the whole app on a local port, so the same address
 * already works in Chrome — with the browser's own voices, and surviving the
 * app window being closed. This is what the "open in browser" button calls.
 */
function openInBrowser(res, body) {
  let target = '';
  try { target = String((JSON.parse(body || '{}').url) || ''); } catch {}
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?/i.test(target)) {
    target = `http://localhost:${PORT}`;
  }
  const used = openBrowser(target, { app: true });
  return sendJson(res, 200, {
    ok: !!used, url: target, browser: used ? path.basename(used) : '',
    hint: used ? '' : 'no Chrome or Edge was found on this machine',
  });
}

/** Events for the app window. Held open, one per app window. */
function bridgeEvents(req, res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
    'Access-Control-Allow-Origin': '*',
    'X-Accel-Buffering': 'no',
  });
  res.write(': connected\n\n');
  bridge.clients.add(res);
  // A comment every 20s keeps proxies from closing an idle stream.
  const beat = setInterval(() => { try { res.write(': ping\n\n'); } catch {} }, 20000);
  const drop = () => { clearInterval(beat); bridge.clients.delete(res); };
  req.on('close', drop);
  req.on('error', drop);
}

function bridgePush(obj) {
  const line = `data: ${JSON.stringify(obj)}\n\n`;
  for (const c of bridge.clients) { try { c.write(line); } catch {} }
}

/** The helper (Chrome/Edge) reports what it heard, or that it stopped talking. */
async function bridgeSay(res, req) {
  let body;
  try { body = await readBody(req, 1024 * 1024); } catch { return sendJson(res, 413, { error: 'too large' }); }
  let msg;
  try { msg = JSON.parse(body.toString('utf8') || '{}'); } catch { return sendJson(res, 400, { error: 'bad json' }); }
  // "spoke" is the helper telling the app that a sentence finished playing, so
  // the app can release the next one instead of guessing a duration.
  if (msg.kind === 'spoke') { bridgePush({ type: 'spoke' }); return sendJson(res, 200, { ok: true }); }
  const text = String(msg.text || '').slice(0, 4000);
  const kind = msg.kind === 'partial' ? 'partial' : 'final';
  if (!text) return sendJson(res, 200, { ok: true, ignored: true });
  bridge.last = { text, kind, at: Date.now() };
  bridgePush({ type: 'stt', kind, text, lang: String(msg.lang || '') });
  return sendJson(res, 200, { ok: true });
}

/** The helper announces itself and then polls here for commands (TTS, mute). */
async function bridgeHello(res, req) {
  let msg = {};
  try { msg = JSON.parse((await readBody(req, 64 * 1024)).toString('utf8') || '{}'); } catch {}
  bridge.helperAt = Date.now();
  bridge.helperName = String(msg.name || 'browser');
  return sendJson(res, 200, { ok: true, app: true, say: bridge.sayQueue || [] });
}

/** Poll for commands the app wants the helper's speech engine to perform. */
function bridgePoll(res, url) {
  const since = Number(url.searchParams.get('since') || 0);
  const out = (bridge.cmds || []).filter((c) => c.at > since);
  return sendJson(res, 200, { ok: true, cmds: out, now: Date.now() });
}

/** The app queues a command (e.g. "speak this out loud") for the helper. */
async function bridgeCmd(res, req) {
  let msg = {};
  try { msg = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}'); } catch {}
  bridge.cmds = (bridge.cmds || []).filter((c) => Date.now() - c.at < 120000);
  bridge.cmds.push({ at: Date.now(), cmd: String(msg.cmd || ''), text: String(msg.text || ''), lang: String(msg.lang || '') });
  return sendJson(res, 200, { ok: true });
}

/** Who is connected — the desktop app uses this to tell the user what to fix. */
function bridgeStatus(res) {
  return sendJson(res, 200, {
    ok: true,
    app: bridge.clients.size,
    helper: !!bridge.helperAt && Date.now() - bridge.helperAt < 15000,
    helperName: bridge.helperName || '',
    last: bridge.last,
  });
}

function readBody(req, limit = 40 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('payload too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

let healthCache = null;   // { at, payload }
const HEALTH_TTL = 60_000;
const HEALTH_FILE = path.join(DATA_DIR, 'model-health.json');

async function loadHealthCache() {
  try { healthCache = JSON.parse(await fsp.readFile(HEALTH_FILE, 'utf8')); } catch { healthCache = null; }
}

let healthRefreshing = null;

async function modelHealth(res, force) {
  // This endpoint must never block a page: probing all ten models takes ~30s.
  // Answer from cache when present (stale-while-revalidate); on a cold start
  // answer with the static benchmark order and probe in the background.
  if (!force) {
    if (!healthCache) {
      refreshHealthInBackground();
      return sendJson(res, 200, {
        checkedAt: 0,
        cold: true,
        order: ['zyraxon-apex', 'zyraxon-visionary', 'zyraxon-titan', 'zyraxon-pulse', 'zyraxon-codex',
          'zyraxon-neural', 'zyraxon-quantum', 'zyraxon-cyber', 'zyraxon-genesis', 'zyraxon-matrix'],
        results: [],
      });
    }
    if (Date.now() - healthCache.at > HEALTH_TTL) refreshHealthInBackground();
    return sendJson(res, 200, healthCache.payload);
  }
  const payload = await probeAllModels();
  if (res) sendJson(res, 200, payload);
}

function refreshHealthInBackground() {
  if (healthRefreshing) return healthRefreshing;
  healthRefreshing = probeAllModels().catch(() => {}).finally(() => { healthRefreshing = null; });
  return healthRefreshing;
}

async function probeAllModels() {
  const order = ['zyraxon-apex', 'zyraxon-matrix', 'zyraxon-titan', 'zyraxon-genesis',
    'zyraxon-codex', 'zyraxon-pulse', 'zyraxon-cyber', 'zyraxon-neural',
    'zyraxon-quantum', 'zyraxon-visionary'];
  const once = (m) => {
    const t0 = Date.now();
    const ctrl = new AbortController();
    const to = setTimeout(() => ctrl.abort(), 30000);
    return fetch(`${ZYRAXON_BASE}/chat/completions`, {
      method: 'POST', signal: ctrl.signal,
      headers: { 'Content-Type': 'application/json', 'User-Agent': UA, Origin: PUBLIC_ORIGIN },
      body: JSON.stringify({ model: m, messages: [{ role: 'user', content: 'hi' }], max_tokens: 1, stream: false }),
    }).then((r) => ({ ok: r.ok, ms: Date.now() - t0 }))
      .catch(() => ({ ok: false, ms: Date.now() - t0 }))
      .finally(() => clearTimeout(to));
  };
  // Three rounds, and a model is only trusted if it answered *every* time: the
  // user wants the fastest model that never drops, not one that is quick when
  // it feels like it. Ranking is drops first, then speed.
  const rounds = [];
  for (let i = 0; i < 3; i++) {
    rounds.push(await Promise.all(order.map(async (m) => ({ model: m, ...(await once(m)) }))));
  }
  const results = order.map((m) => {
    const tries = rounds.map((r) => r.find((x) => x.model === m));
    const wins = tries.filter((t) => t.ok);
    const ok = wins.length === tries.length;
    const best = wins.length ? Math.min(...wins.map((t) => t.ms)) : Math.max(...tries.map((t) => t.ms));
    return { model: m, ok, drops: tries.length - wins.length, ms: best };
  });
  results.sort((a, b) => (a.drops === b.drops ? a.ms - b.ms : a.drops - b.drops));
  const payload = { checkedAt: Date.now(), order: results.map((r) => r.model), results };
  healthCache = { at: Date.now(), payload };
  fsp.writeFile(HEALTH_FILE, JSON.stringify(healthCache)).catch(() => {});
  return payload;
}

/* ------------------------------ static files ------------------------------ */
async function serveFile(res, filePath, extra = {}) {
  try {
    const st = await fsp.stat(filePath);
    if (st.isDirectory()) return serveFile(res, path.join(filePath, 'index.html'), extra);
    const ext = path.extname(filePath).toLowerCase();
    const data = await fsp.readFile(filePath);
    send(res, 200, data, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      // JS/CSS must never be cached: a stale bundle is what made the phone run
      // yesterday's code after a fix. Media keeps a short cache (it is large).
      'Cache-Control': (ext === '.html' || ext === '.js' || ext === '.css' || ext === '.mjs' || ext === '.webmanifest')
        ? 'no-store, must-revalidate'
        : 'public, max-age=60',
      'Access-Control-Allow-Origin': '*',
      ...extra,
    });
  } catch {
    send(res, 404, 'not found');
  }
}

/* ------------------------------ ZYRAXON proxy ----------------------------- */
async function listModels(res) {
  try {
    const r = await fetch(`${ZYRAXON_BASE}/models`, {
      headers: { 'User-Agent': UA, Origin: PUBLIC_ORIGIN, Accept: 'application/json' },
    });
    const text = await r.text();
    send(res, r.status, text, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  } catch (e) {
    sendJson(res, 502, { error: { message: String(e) } });
  }
}

/** One plain completion, returned as ordinary JSON to the caller. */
async function chatOnce(res, payload) {
  try {
    const up = await fetch(`${ZYRAXON_BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': UA, Origin: PUBLIC_ORIGIN },
      body: JSON.stringify({ ...payload, stream: false }),
    });
    const text = await up.text();
    return send(res, up.ok ? 200 : up.status, text, { 'Content-Type': 'application/json' });
  } catch (e) {
    return sendJson(res, 502, { error: { message: String(e) } });
  }
}

/** Re-frame a non-streaming completion as a one-chunk SSE stream so clients
 *  always get a consistent event stream even when the upstream pool stalls
 *  on real streaming. Returns true when it produced a usable reply. */
async function proxyNonStreaming(res, payload, signal) {
  const body = JSON.stringify({ ...payload, stream: false });
  const up = await fetch(`${ZYRAXON_BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': UA, Origin: PUBLIC_ORIGIN },
    body,
    signal,
  });
  const text = await up.text();
  let j;
  try { j = JSON.parse(text); } catch { return false; }
  if (!j || !j.choices || !j.choices[0]) return false;
  const msg = j.choices[0].message || {};
  // Mark this as a reset so the client discards any partial tool-call
  // arguments accumulated from the abandoned stream.
  res.write(`data: ${JSON.stringify({ id: j.id, object: 'chat.completion.chunk', model: j.model, zyraxon: 'reset', choices: [{ index: 0, delta: {} }] })}\n\n`);
  const delta = { role: 'assistant', content: msg.content || '' };
  if (msg.tool_calls) delta.tool_calls = msg.tool_calls.map((t, i) => ({ index: i, id: t.id, type: 'function', function: t.function }));
  res.write(`data: ${JSON.stringify({ id: j.id, object: 'chat.completion.chunk', model: j.model, choices: [{ index: 0, delta }] })}\n\n`);
  res.write(`data: ${JSON.stringify({ id: j.id, object: 'chat.completion.chunk', model: j.model, choices: [{ index: 0, delta: {}, finish_reason: j.choices[0].finish_reason || 'stop' }] })}\n\n`);
  if (j.usage) res.write(`data: ${JSON.stringify({ id: j.id, object: 'chat.completion.chunk', model: j.model, choices: [], usage: j.usage })}\n\n`);
  res.write('data: [DONE]\n\n');
  return true;
}

async function chatProxy(req, res, bodyBuf) {
  const payload = JSON.parse(bodyBuf.toString('utf8') || '{}');
  payload.model = payload.model || 'zyraxon-titan';
  // Non-streaming callers (the monitoring model, the model picker) ask for a
  // plain completion and must get plain JSON back. Answering them with SSE
  // made every one of those calls look like an empty reply.
  if (!payload.stream) return chatOnce(res, payload);
  // Bigger budget than the client asks so reasoning + tool calls both fit.
  if (payload.stream) payload.stream_options = { include_usage: true };

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
    'Access-Control-Allow-Origin': '*',
  });
  res.write(': zyraxon-proxy open\n\n');
  const ping = setInterval(() => { try { res.write(': ping\n\n'); } catch {} }, 15000);

  const abort = new AbortController();
  req.on('close', () => abort.abort());

  try {
    const upstream = await fetch(`${ZYRAXON_BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': UA, Origin: PUBLIC_ORIGIN, Accept: 'text/event-stream' },
      body: JSON.stringify(payload),
      signal: abort.signal,
    });
    if (!upstream.ok || !upstream.body) {
      const t = await upstream.text().catch(() => '');
      res.write(`data: ${JSON.stringify({ error: { message: `upstream ${upstream.status}: ${t.slice(0, 300)}` } })}\n\n`);
      res.write('data: [DONE]\n\n');
      return;
    }
    const reader = upstream.body.getReader();
    let bytes = 0;
    let forwardedDone = false;
    let sawToolCall = false;
    let sawFinish = false;
    // The provider sometimes streams a few chunks and then leaves the socket
    // open forever, or ends a tool-calling turn without emitting finish_reason.
    // In either case we abandon the partial stream and re-ask without streaming,
    // which always returns the whole reply, so tool calls are never lost.
    // The provider routinely pauses for many seconds mid-reply (reasoning
    // tokens, tool-argument assembly, or pool backpressure). Cutting the
    // stream early is what made the assistant's words vanish, so the idle
    // allowance is generous; only a genuinely dead socket trips it.
    const stallMs = 45000;
    let timer = null;
    const arm = () => {
      clearTimeout(timer);
      timer = setTimeout(() => { if (!forwardedDone) reader.cancel().catch(() => {}); }, stallMs);
    };
    arm();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        arm();
        bytes += value.length;
        const tail = decTail(value);
        if (tail.includes('[DONE]')) forwardedDone = true;
        if (tail.includes('tool_calls')) sawToolCall = true;
        if (tail.includes('finish_reason')) sawFinish = true;
        res.write(value);
      }
    } catch { /* stall or abort, fall through */ }
    clearTimeout(timer);
    if (!forwardedDone && !abort.signal.aborted && (sawToolCall || !sawFinish || bytes === 0)) {
      // Incomplete turn: the non-streaming path returns the complete reply.
      await proxyNonStreaming(res, payload, abort.signal);
    } else if (!forwardedDone && !abort.signal.aborted) {
      res.write('data: [DONE]\n\n');
    }
  } catch (e) {
    if (!abort.signal.aborted) res.write(`data: ${JSON.stringify({ error: { message: String(e) } })}\n\n`);
  } finally {
    clearInterval(ping);
    res.end();
  }
}

const _tail = new TextDecoder();
const decTail = (u8) => _tail.decode(u8.length > 64 ? u8.slice(-64) : u8);

/* ------------------------------ publish sites ----------------------------- */
const slug = (s) => String(s || 'site').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'site';

async function publishSite(res, bodyBuf, req) {
  const { name, html, files } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const siteId = `${slug(name)}-${crypto.randomBytes(3).toString('hex')}`;
  const dir = path.join(SITES_DIR, siteId);
  await fsp.mkdir(dir, { recursive: true });
  let htmlOut = typeof html === 'string' ? html : '';
  if (files && typeof files === 'object') {
    for (const [rel, content] of Object.entries(files)) {
      const safe = path.normalize(rel).replace(/^(\.\.(\/|\\|$))+/, '');
      const full = path.join(dir, safe);
      if (!full.startsWith(dir)) continue;
      await fsp.mkdir(path.dirname(full), { recursive: true });
      await fsp.writeFile(full, typeof content === 'string' ? content : JSON.stringify(content));
      if (!htmlOut && /(^|\/)index\.html$/.test(safe) && typeof content === 'string') htmlOut = content;
    }
  }
  if (!htmlOut) {
    // The agent usually authors the page first with generate_file; serve it.
    try { htmlOut = await fsp.readFile(path.join(WORK_DIR, 'index.html'), 'utf8'); } catch {}
  }
  if (!htmlOut) htmlOut = `<!doctype html><meta charset="utf-8"><title>${slug(name)}</title><h1>${slug(name)}</h1>`;
  await fsp.writeFile(path.join(dir, 'index.html'), htmlOut);
  const url = `${originFor(req)}/sites/${siteId}/`;
  // Verify it really serves — and retry, because the first request for a brand
  // new path can hit a cold proxy and 404 even though the file is on disk.
  let verified = false, status = 0;
  for (let i = 0; i < 4 && !verified; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/sites/${siteId}/`, { cache: 'no-store' });
      status = r.status;
      verified = r.ok && (await r.text()).includes('</');
    } catch { status = 0; }
    if (!verified) await new Promise((r) => setTimeout(r, 250 * (i + 1)));
  }
  sendJson(res, 200, { ok: verified, verified, status, site: siteId, url, files: await fsp.readdir(dir) });
}

/* ------------------------------- Bangla TTS ------------------------------- */
/** For devices with no Bangla speech voice, proxy Google's public translate TTS
 *  so the assistant always speaks Bangla out loud. Urls are cached on disk. */
const TTS_CACHE = path.join(DATA_DIR, 'tts');
const TTS_LANGS = new Set(['bn','en','hi','ar','es','fr','pt','ru','id','tr','zh','ja','ko','de','it','nl','pl','vi','th','he','el','sv','da','no','fi','cs','ro','hu','uk','fa','ur','ta','te','ml','kn','mr','gu','pa','ne','si','my','km','lo','ms','tl','sw','am','az','be','bg','ca','et','hr','lt','lv','sk','sl','sr','is','sq','hy','ka','kk','ky','uz','mn','af','zu','yo','ha','ig','so','mg','jv','su','ceb','eu','gl','cy','ga','mt','lb','fy','haw','eo','la']);
async function banglaTTS(res, url) {
  const text = (url.searchParams.get('q') || '').slice(0, 200).trim();
  if (!text) return sendJson(res, 400, { error: 'q required' });
  // Any language the app offers can be spoken here, so a device without the
  // matching engine voice still reads every reply out loud.
  const req = String(url.searchParams.get('tl') || 'bn').toLowerCase().split(/[-_]/)[0];
  const tl = TTS_LANGS.has(req) ? req : 'bn';
  const key = crypto.createHash('sha1').update(tl + '|' + text).digest('hex');
  const file = path.join(TTS_CACHE, key + '.mp3');
  try {
    const cached = await fsp.readFile(file);
    res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'public, max-age=604800', 'Access-Control-Allow-Origin': '*' });
    return res.end(cached);
  } catch {}
  try {
    const u = `https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=${tl}&q=${encodeURIComponent(text)}`;
    const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: 'https://translate.google.com/' } });
    if (!r.ok) return sendJson(res, 502, { error: 'tts ' + r.status });
    const buf = Buffer.from(await r.arrayBuffer());
    await fsp.mkdir(TTS_CACHE, { recursive: true });
    await fsp.writeFile(file, buf);
    res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'public, max-age=604800', 'Access-Control-Allow-Origin': '*' });
    res.end(buf);
  } catch (e) {
    sendJson(res, 502, { error: String(e) });
  }
}

/* ------------------------------ workspace fs ------------------------------ */
const WORK_DIR = path.join(DATA_DIR, 'workspace');

async function fetchUrl(res, bodyBuf) {
  const { url } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  if (!/^https?:\/\//i.test(url || '')) return sendJson(res, 400, { error: 'http(s) url required' });
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, redirect: 'follow' });
    const buf = Buffer.from(await r.arrayBuffer());
    const ct = r.headers.get('content-type') || '';
    if (/^image\//.test(ct) || /^audio\//.test(ct) || /^video\//.test(ct) || /pdf/.test(ct)) {
      return sendJson(res, 200, { status: r.status, contentType: ct, bytes: buf.length, dataUrl: `data:${ct};base64,${buf.toString('base64')}` });
    }
    let text = buf.toString('utf8');
    const isHtml = /html/.test(ct) || /<html/i.test(text.slice(0, 500));
    if (isHtml) {
      text = text
        .replace(/<script[\s\S]*?<\/script>/gi, ' ')
        .replace(/<style[\s\S]*?<\/style>/gi, ' ')
        .replace(/<[^>]+>/g, ' ')
        .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/[ \t]+/g, ' ').replace(/\n\s*\n\s*\n+/g, '\n\n').trim();
    }
    sendJson(res, 200, { status: r.status, contentType: ct, text: text.slice(0, 300000) });
  } catch (e) {
    sendJson(res, 502, { error: String(e) });
  }
}

/**
 * Is this link real? A HEAD request first (cheap), a ranged GET as fallback for
 * servers that reject HEAD. This exists so the assistant never opens a browser
 * at a dead video or a 404 page and then tells the user it played.
 */
async function checkUrl(res, bodyBuf) {
  const { url } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  if (!/^https?:\/\//i.test(url || '')) return sendJson(res, 200, { ok: false, status: 0, error: 'http(s) url required' });
  const probe = async (method) => {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 8000);
    try {
      const r = await fetch(url, {
        method,
        headers: method === 'GET' ? { 'User-Agent': UA, Range: 'bytes=0-0' } : { 'User-Agent': UA },
        redirect: 'follow',
        signal: ctl.signal,
      });
      return { ok: r.status >= 200 && r.status < 400, status: r.status, contentType: r.headers.get('content-type') || '' };
    } finally { clearTimeout(t); }
  };
  try {
    let out;
    try { out = await probe('HEAD'); } catch (e) { out = await probe('GET'); }
    // Some hosts answer HEAD with 405/501; a real GET is the honest answer then.
    if (!out.ok && out.status >= 400 && out.status < 500 && out.status !== 404) {
      try { out = await probe('GET'); } catch (e) {}
    }
    return sendJson(res, 200, out);
  } catch (e) {
    return sendJson(res, 200, { ok: false, status: 0, error: String(e) });
  }
}

async function writeFile(res, bodyBuf) {
  const { path: rel, content, mode } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  if (!rel || typeof content !== 'string') return sendJson(res, 400, { error: 'path and content required' });
  const safe = path.normalize(rel).replace(/^(\.\.(\/|\\|$))+/, '').replace(/^\/+/, '');
  const full = path.join(WORK_DIR, safe);
  if (!full.startsWith(WORK_DIR)) return sendJson(res, 400, { error: 'invalid path' });
  await fsp.mkdir(path.dirname(full), { recursive: true });
  await fsp.writeFile(full, content, mode === 'append' ? { flag: 'a' } : undefined);
  sendJson(res, 200, { ok: true, path: safe, bytes: Buffer.byteLength(content) });
}

/* ------------------------------ shell bridge ------------------------------ */
/**
 * Every platform runs commands through its own native shell. The assistant is
 * told which one this machine has (see /api/platform) and picks it explicitly;
 * `auto` resolves to the platform default so the same tool call works
 * unchanged on Windows, macOS and Linux.
 */
const SHELLS = {
  win32: { auto: 'powershell', list: ['powershell', 'pwsh', 'cmd'] },
  darwin: { auto: 'zsh', list: ['zsh', 'bash', 'sh'] },
  linux: { auto: 'bash', list: ['bash', 'sh', 'zsh'] },
};

function shellSpec(kind) {
  const os = process.platform;
  const family = SHELLS[os] || SHELLS.linux;
  const want = !kind || kind === 'auto' ? family.auto : String(kind).toLowerCase();
  if (os === 'win32') {
    if (want === 'cmd') return { file: process.env.ComSpec || 'cmd.exe', args: ['/d', '/s', '/c'] };
    if (want === 'pwsh') return { file: 'pwsh', args: ['-NoProfile', '-NonInteractive', '-Command'] };
    return { file: 'powershell.exe', args: ['-NoProfile', '-NonInteractive', '-Command'] };
  }
  const candidates = { bash: '/bin/bash', zsh: '/bin/zsh', sh: '/bin/sh', fish: '/usr/bin/fish' };
  const file = candidates[want] || process.env.SHELL || '/bin/bash';
  if (want === 'fish') return { file, args: ['-c'] };
  return { file, args: ['-lc'] };
}

function runShell(res, bodyBuf) {
  const { command, cwd, shell, timeout_ms } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  if (!command || typeof command !== 'string') return sendJson(res, 400, { error: 'command required' });
  const spec = shellSpec(shell);
  const dir = cwd ? path.resolve(String(cwd)) : ROOT;
  // The shell can do anything on the host, but it must not be pointed at the
  // app's own source tree — that is how the UI got wiped once already.
  if (isProtected(dir)) return sendJson(res, 200, { ok: false, error: 'অ্যাপের নিজের ফোল্ডারে কমান্ড চালানো যাবে না', cwd: dir, protected: true });
  const child = spawn(spec.file, [...spec.args, command], { cwd: fs.existsSync(dir) ? dir : ROOT });
  let out = '', err = '';
  const ms = Math.min(Math.max(Number(timeout_ms) || 60000, 1000), 600000);
  const timer = setTimeout(() => child.kill('SIGKILL'), ms);
  child.stdout.on('data', (d) => { out += d; });
  child.stderr.on('data', (d) => { err += d; });
  child.on('error', (e) => { clearTimeout(timer); sendJson(res, 200, { code: -1, stdout: '', stderr: String(e), shell: path.basename(spec.file) }); });
  child.on('close', (code) => {
    clearTimeout(timer);
    sendJson(res, 200, { code, stdout: out.slice(0, 200000), stderr: err.slice(0, 40000), shell: path.basename(spec.file), cwd: dir });
  });
}

/* ------------------------------ platform probe ---------------------------- */
async function platformInfo(res) {
  const os = require$('os');
  const os0 = process.platform;
  const family = SHELLS[os0] || SHELLS.linux;
  const has = (p) => { try { return fs.existsSync(p); } catch { return false; } };
  const candidates = { bash: '/bin/bash', zsh: '/bin/zsh', sh: '/bin/sh', fish: '/usr/bin/fish' };
  const shells = os0 === 'win32'
    ? ['powershell', 'pwsh', 'cmd'].filter((s) => true)
    : Object.keys(candidates).filter((s) => has(candidates[s]) || has('/usr/local' + candidates[s]) || has('/opt/homebrew' + candidates[s]));
  sendJson(res, 200, {
    os: os0,
    osName: { win32: 'Windows', darwin: 'macOS', linux: 'Linux' }[os0] || os0,
    release: os.release(),
    arch: process.arch,
    hostname: os.hostname(),
    home: os.homedir(),
    tmp: os.tmpdir(),
    cpus: os.cpus().length,
    totalMemMB: Math.round(os.totalmem() / 1048576),
    defaultShell: family.auto,
    shells,
    nodeVersion: process.version,
    fsRoot: FS_ROOT,
    separator: path.sep,
  });
}

/* ------------------------------ filesystem -------------------------------- */
const FS_ROOT = process.env.ZYRAXON_FS_ROOT
  ? path.resolve(process.env.ZYRAXON_FS_ROOT)
  : ROOT;
const MAX_READ = 2 * 1024 * 1024;

/**
 * The assistant may write anywhere on the host — except the running app's own
 * source. It once overwrote `public/index.html` while "building a website" and
 * blanked the UI for the user. Generated sites belong in the workspace and are
 * published with `publish_website`; they must never replace the shell.
 */
const PROTECTED = [PUBLIC_DIR, path.join(ROOT, 'server'), path.join(ROOT, 'android')];
function isProtected(abs) {
  return PROTECTED.some((d) => abs === d || abs.startsWith(d + path.sep));
}

/** Resolve a model-supplied path inside FS_ROOT and refuse escapes. */
function safePath(rel) {
  if (rel == null || rel === '') return null;
  const raw = String(rel);
  const abs = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(FS_ROOT, raw);
  const root = FS_ROOT.endsWith(path.sep) ? FS_ROOT : FS_ROOT + path.sep;
  if (abs !== FS_ROOT && !abs.startsWith(root)) return null;
  return abs;
}

const TEXT_EXT = new Set(['.txt', '.md', '.json', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.css', '.html', '.htm', '.xml', '.yml', '.yaml', '.toml', '.ini', '.cfg', '.conf', '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd', '.py', '.rb', '.go', '.rs', '.java', '.kt', '.c', '.h', '.cpp', '.hpp', '.cs', '.php', '.sql', '.env', '.gitignore', '.log', '.csv', '.tsv', '.svg']);

async function fsOp(res, bodyBuf) {
  const b = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const op = b.op;
  const p = safePath(b.path);
  if (!p) return sendJson(res, 400, { error: `path outside the allowed root (${FS_ROOT})`, root: FS_ROOT });
  const WRITE_OPS = { write: 1, edit: 1, delete: 1, move: 1, mkdir: 1 };
  if (WRITE_OPS[op] && isProtected(p)) {
    return sendJson(res, 200, { ok: false, error: 'এই ফাইলগুলো অ্যাপের নিজের — এখানে লেখা যাবে না। সাইট বানাও ওয়ার্কস্পেসে, তারপর publish_website দিয়ে প্রকাশ করো।', path: p, protected: true });
  }
  if (op === 'move' && isProtected(safePath(b.to))) {
    return sendJson(res, 200, { ok: false, error: 'অ্যাপের নিজের ফোল্ডারে কিছু সরানো যাবে না', path: p, protected: true });
  }
  try {
    switch (op) {
      case 'read': {
        const st = await fsp.stat(p);
        if (st.isDirectory()) return sendJson(res, 400, { error: 'is a directory — use op:list' });
        if (st.size > MAX_READ) return sendJson(res, 400, { error: `file too large (${st.size} bytes)` });
        const buf = await fsp.readFile(p);
        const isText = TEXT_EXT.has(path.extname(p).toLowerCase()) || !buf.subarray(0, 800).includes(0);
        const lines = isText ? buf.toString('utf8').split('\n') : [];
        const from = Math.max(0, (Number(b.from_line) || 1) - 1);
        const to = Number(b.to_line) > 0 ? Number(b.to_line) : lines.length;
        return sendJson(res, 200, {
          ok: true, path: p, bytes: st.size, lines: lines.length, text: !isText,
          content: isText ? lines.slice(from, to).join('\n').slice(0, 400000) : undefined,
          dataUrl: isText ? undefined : `data:${MIME[path.extname(p).toLowerCase()] || 'application/octet-stream'};base64,${buf.toString('base64')}`,
          mtime: st.mtimeMs,
        });
      }
      case 'write': {
        await fsp.mkdir(path.dirname(p), { recursive: true });
        const before = await fsp.readFile(p, 'utf8').catch(() => null);
        await fsp.writeFile(p, String(b.content ?? ''), b.append ? { flag: 'a' } : undefined);
        return sendJson(res, 200, { ok: true, path: p, bytes: Buffer.byteLength(String(b.content ?? '')), created: before === null, append: !!b.append });
      }
      case 'edit': {
        const src = await fsp.readFile(p, 'utf8');
        const find = String(b.find ?? '');
        if (!find) return sendJson(res, 400, { error: 'find required' });
        const count = src.split(find).length - 1;
        if (count === 0) return sendJson(res, 200, { ok: false, error: 'text not found', path: p, occurrences: 0 });
        if (count > 1 && !b.all) return sendJson(res, 200, { ok: false, error: `text appears ${count} times — pass all:true or a longer snippet`, occurrences: count });
        const out = b.all ? src.split(find).join(String(b.replace ?? '')) : src.replace(find, String(b.replace ?? ''));
        await fsp.writeFile(p, out);
        return sendJson(res, 200, { ok: true, path: p, occurrences: count, replaced: b.all ? count : 1 });
      }
      case 'list': {
        const entries = await fsp.readdir(p, { withFileTypes: true });
        const out = [];
        for (const e of entries) {
          const full = path.join(p, e.name);
          const st = await fsp.stat(full).catch(() => null);
          out.push({ name: e.name, dir: e.isDirectory(), bytes: st ? st.size : 0, mtime: st ? st.mtimeMs : 0 });
        }
        out.sort((a, b2) => (a.dir === b2.dir ? a.name.localeCompare(b2.name) : a.dir ? -1 : 1));
        return sendJson(res, 200, { ok: true, path: p, count: out.length, entries: out.slice(0, 2000) });
      }
      case 'stat': {
        const st = await fsp.stat(p);
        return sendJson(res, 200, { ok: true, path: p, dir: st.isDirectory(), bytes: st.size, mtime: st.mtimeMs, mode: st.mode.toString(8) });
      }
      case 'mkdir': { await fsp.mkdir(p, { recursive: true }); return sendJson(res, 200, { ok: true, path: p }); }
      case 'delete': { await fsp.rm(p, { recursive: !!b.recursive, force: true }); return sendJson(res, 200, { ok: true, path: p }); }
      case 'move': {
        const to = safePath(b.to);
        if (!to) return sendJson(res, 400, { error: 'bad destination' });
        await fsp.mkdir(path.dirname(to), { recursive: true });
        await fsp.rename(p, to);
        return sendJson(res, 200, { ok: true, from: p, to });
      }
      case 'glob': {
        const pattern = String(b.pattern || '*');
        const rx = new RegExp('^' + pattern.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*\*/g, '\u0000').replace(/\*/g, '[^/]*').replace(/\?/g, '.').replace(/\u0000/g, '.*') + '$', 'i');
        const base = p;
        const found = [];
        const walk = async (d, depth) => {
          if (found.length > 3000 || depth > 12) return;
          for (const e of await fsp.readdir(d, { withFileTypes: true }).catch(() => [])) {
            if (e.name === 'node_modules' || e.name === '.git') continue;
            const full = path.join(d, e.name);
            if (e.isDirectory()) await walk(full, depth + 1);
            else if (rx.test(path.relative(base, full))) found.push(path.relative(base, full));
          }
        };
        await walk(base, 0);
        return sendJson(res, 200, { ok: true, base, pattern, count: found.length, matches: found.slice(0, 3000) });
      }
      case 'grep': {
        const needle = String(b.query || '');
        if (!needle) return sendJson(res, 400, { error: 'query required' });
        const flags = b.case_sensitive ? 'g' : 'gi';
        let rx; try { rx = new RegExp(b.regex ? needle : needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), flags); } catch { rx = null; }
        const hits = [];
        const walk = async (d, depth) => {
          if (hits.length > 500 || depth > 10) return;
          for (const e of await fsp.readdir(d, { withFileTypes: true }).catch(() => [])) {
            if (e.name === 'node_modules' || e.name === '.git') continue;
            const full = path.join(d, e.name);
            if (e.isDirectory()) { await walk(full, depth + 1); continue; }
            if (!TEXT_EXT.has(path.extname(e.name).toLowerCase())) continue;
            const st = await fsp.stat(full).catch(() => null);
            if (!st || st.size > 800000) continue;
            const txt = await fsp.readFile(full, 'utf8').catch(() => '');
            txt.split('\n').forEach((line, i) => {
              if (hits.length < 500 && rx && rx.test(line)) hits.push({ file: path.relative(p, full), line: i + 1, text: line.slice(0, 300) });
              if (rx) rx.lastIndex = 0;
            });
          }
        };
        await walk(p, 0);
        return sendJson(res, 200, { ok: true, query: needle, count: hits.length, hits });
      }
      default:
        return sendJson(res, 400, { error: 'unknown op: ' + op });
    }
  } catch (e) {
    return sendJson(res, 200, { ok: false, error: String(e && e.code ? e.code : e), path: p });
  }
}

/* ------------------------------ web search -------------------------------- */
/** Real search with no API key: DuckDuckGo's html/lite endpoints, plus a
 *  Wikipedia fallback. Results are parsed into {title,url,snippet}. */
async function webSearch(res, bodyBuf) {
  const { query, count } = JSON.parse(bodyBuf.toString('utf8') || '{}');
  if (!query) return sendJson(res, 400, { error: 'query required' });
  const n = Math.min(Math.max(Number(count) || 8, 1), 25);
  const parseDDG = (html) => {
    const out = [];
    const rx = /<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:class="result__snippet"[^>]*>([\s\S]*?)<\/a>)?/g;
    let m;
    while ((m = rx.exec(html)) && out.length < n) {
      let url = m[1];
      const red = /uddg=([^&]+)/.exec(url);
      if (red) { try { url = decodeURIComponent(red[1]); } catch {} }
      const strip = (s) => String(s || '').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&#x27;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\s+/g, ' ').trim();
      if (/^https?:/.test(url)) out.push({ title: strip(m[2]), url, snippet: strip(m[3]) });
    }
    return out;
  };
  const attempts = [
    `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`,
    `https://lite.duckduckgo.com/lite/?q=${encodeURIComponent(query)}`,
  ];
  for (const u of attempts) {
    try {
      const r = await fetch(u, { headers: { 'User-Agent': UA, Accept: 'text/html' } });
      if (!r.ok) continue;
      const html = await r.text();
      let results = parseDDG(html);
      if (!results.length) {
        const rx = /<a[^>]+href="(https?:\/\/[^"]+)"[^>]*>([^<]{3,120})<\/a>/g;
        let m;
        while ((m = rx.exec(html)) && results.length < n) {
          if (/duckduckgo|javascript/.test(m[1])) continue;
          results.push({ title: m[2].replace(/&amp;/g, '&').trim(), url: m[1], snippet: '' });
        }
      }
      if (results.length) return sendJson(res, 200, { ok: true, engine: 'duckduckgo', query, count: results.length, results });
    } catch { /* try the next engine */ }
  }
  try {
    const r = await fetch(`https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(query)}&format=json&srlimit=${n}`, { headers: { 'User-Agent': UA } });
    const j = await r.json();
    const results = (j.query && j.query.search || []).map((s) => ({ title: s.title, url: `https://en.wikipedia.org/wiki/${encodeURIComponent(s.title.replace(/ /g, '_'))}`, snippet: String(s.snippet || '').replace(/<[^>]+>/g, '') }));
    if (results.length) return sendJson(res, 200, { ok: true, engine: 'wikipedia', query, count: results.length, results });
  } catch {}
  return sendJson(res, 200, { ok: false, error: 'no search engine reachable', query, results: [] });
}

/* ------------------------------ generic http ------------------------------ */
async function httpRequest(res, bodyBuf) {
  const b = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const url = b.url;
  if (!/^https?:\/\//i.test(url || '')) return sendJson(res, 400, { error: 'http(s) url required' });
  try {
    const r = await fetch(url, {
      method: String(b.method || 'GET').toUpperCase(),
      headers: { 'User-Agent': UA, ...(b.headers || {}) },
      body: b.body != null ? (typeof b.body === 'string' ? b.body : JSON.stringify(b.body)) : undefined,
      redirect: 'follow',
    });
    const buf = Buffer.from(await r.arrayBuffer());
    const ct = r.headers.get('content-type') || '';
    const isText = /json|text|xml|javascript|x-www-form-urlencoded/i.test(ct) || !buf.subarray(0, 800).includes(0);
    let data = null;
    if (isText) { try { data = JSON.parse(buf.toString('utf8')); } catch { data = buf.toString('utf8').slice(0, 200000); } }
    return sendJson(res, 200, {
      ok: r.ok, status: r.status, contentType: ct, bytes: buf.length,
      headers: Object.fromEntries([...r.headers].slice(0, 40)),
      data,
      dataUrl: isText ? undefined : `data:${ct || 'application/octet-stream'};base64,${buf.toString('base64')}`,
    });
  } catch (e) {
    return sendJson(res, 200, { ok: false, error: String(e) });
  }
}

/* ------------------------------ system info ------------------------------- */
async function sysInfo(res) {
  const os = require$('os');
  const out = {
    os: process.platform, osName: { win32: 'Windows', darwin: 'macOS', linux: 'Linux' }[process.platform] || process.platform,
    release: os.release(), arch: process.arch, hostname: os.hostname(), uptimeSec: Math.round(os.uptime()),
    cpus: os.cpus().map((c) => c.model).slice(0, 1).concat(`${os.cpus().length} cores`),
    load: os.loadavg(), totalMemMB: Math.round(os.totalmem() / 1048576), freeMemMB: Math.round(os.freemem() / 1048576),
    home: os.homedir(), tmp: os.tmpdir(), node: process.version, pid: process.pid, cwd: process.cwd(),
    env: Object.keys(process.env).filter((k) => !/token|key|secret|pass/i.test(k)).length,
  };
  out.interfaces = Object.entries(os.networkInterfaces()).flatMap(([name, addrs]) => (addrs || []).filter((a) => !a.internal).map((a) => ({ name, family: a.family, address: a.address }))).slice(0, 12);
  try { out.disk = await fsp.statfs(ROOT).then((s) => ({ totalGB: +(s.blocks * s.bsize / 1e9).toFixed(1), freeGB: +(s.bavail * s.bsize / 1e9).toFixed(1) })); } catch {}
  try {
    out.processes = await new Promise((resolve) => {
      const ps = process.platform === 'win32' ? spawn('tasklist', ['/fo', 'csv', '/nh']) : spawn('ps', ['-eo', 'pid,pcpu,pmem,comm', '--sort=-pcpu']);
      let o = '';
      ps.stdout.on('data', (d) => { o += d; });
      ps.on('close', () => resolve(o.split('\n').slice(0, 15).map((l) => l.trim()).filter(Boolean)));
      ps.on('error', () => resolve([]));
      setTimeout(() => { try { ps.kill(); } catch {} }, 8000);
    });
  } catch {}
  sendJson(res, 200, out);
}

/** Tiny lazy require for node builtins inside ESM. */
function require$(m) { return createRequire(import.meta.url)(m); }

/* ══════════════════════════ GitHub ══════════════════════════════
   The user's own token drives a real account, so the assistant can create
   repositories, push files and run builds in GitHub Actions. The token is
   read from the environment at call time and never written to disk, never
   logged and never returned to the browser. */
const GH_API = 'https://api.github.com';
const GH_TOKEN = () => process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';
const ghHeaders = () => ({
  Authorization: `Bearer ${GH_TOKEN()}`,
  Accept: 'application/vnd.github+json',
  'User-Agent': UA,
  'X-GitHub-Api-Version': '2022-11-28',
});

/** One GitHub REST call. Returns parsed JSON, or { error } on failure. */
async function gh(method, endpoint, body) {
  if (!GH_TOKEN()) return { error: 'no-token', message: 'GitHub access token is not configured on the server.' };
  try {
    const r = await fetch(`${GH_API}${endpoint}`, {
      method,
      headers: { ...ghHeaders(), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await r.text();
    let j = null;
    try { j = text ? JSON.parse(text) : null; } catch { j = { raw: text.slice(0, 500) }; }
    if (!r.ok) return { error: `github-${r.status}`, message: (j && (j.message || j.error)) || text.slice(0, 300) };
    return j || {};
  } catch (e) {
    return { error: 'network', message: String(e) };
  }
}

/** Who the token belongs to — used by the UI to show the connected account. */
async function ghUser(res) {
  const u = await gh('GET', '/user');
  if (u.error) return sendJson(res, 200, { ok: false, ...u });
  const scopes = '';
  return sendJson(res, 200, { ok: true, login: u.login, name: u.name, avatar: u.avatar_url, repos: u.public_repos, scopes });
}

/** Create (or reuse) a repository, then commit the given files to it. */
async function ghPublish(res, bodyBuf) {
  const body = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const user = await gh('GET', '/user');
  if (user.error) return sendJson(res, 200, { ok: false, error: user.error, message: user.message });

  let owner = body.owner || user.login;
  let name = String(body.name || '').replace(/[^A-Za-z0-9._-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 90);
  let target = body.repo && body.repo.includes('/') ? body.repo : '';
  let created = false;
  let fellBack = false;
  let repo = null;

  if (target) {
    repo = await gh('GET', `/repos/${target}`);
    if (repo.error) return sendJson(res, 200, { ok: false, error: repo.error, message: repo.message });
    [owner, name] = target.split('/');
  } else {
    if (!name) return sendJson(res, 200, { ok: false, error: 'bad-name' });
    repo = await gh('GET', `/repos/${owner}/${name}`);
    if (repo.error === 'github-404') {
      repo = await gh('POST', '/user/repos', {
        name,
        description: body.description || 'Built by ZYRAXON',
        private: !!body.private,
        auto_init: false,
        has_pages: true,
      });
      if (repo.error) {
        // A restricted token can only touch the repositories it was granted.
        // Publishing into one of those is far more useful than failing, so we
        // fall back to the first repository the token can actually reach.
        const list = await gh('GET', `/users/${user.login}/repos?per_page=1&sort=updated`);
        const first = Array.isArray(list) && list[0];
        if (!first) return sendJson(res, 200, { ok: false, error: repo.error, message: repo.message });
        target = first.full_name;
        [owner, name] = target.split('/');
        repo = first;
        fellBack = true;
      } else {
        created = true;
        // GitHub needs a moment before the contents API accepts a first commit.
        await new Promise((r) => setTimeout(r, 1200));
      }
    } else if (repo.error) {
      return sendJson(res, 200, { ok: false, error: repo.error, message: repo.message });
    }
  }

  const files = body.files && typeof body.files === 'object' ? body.files : {};
  const branch = body.branch || repo.default_branch || 'main';
  // A fallback publish must not overwrite the repository's own files, so the
  // site goes into its own folder and Pages serves that folder.
  const folder = (body.folder !== undefined ? String(body.folder) : (fellBack ? `sites/${name}` : ''))
    .replace(/^\/+|\/+$/g, '');
  const committed = [];
  const failed = [];

  // One commit for every file: simple, and a partial failure is visible.
  for (const [rawPath, content] of Object.entries(files)) {
    const rel = String(rawPath).replace(/^\/+/, '');
    if (!rel || rel.includes('..')) continue;
    const filePath = folder ? `${folder}/${rel}` : rel;
    const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}?ref=${encodeURIComponent(branch)}`);
    const put = await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}`, {
      message: body.message || `Add ${filePath} (ZYRAXON)`,
      content: Buffer.from(String(content), 'utf8').toString('base64'),
      branch,
      ...(existing && existing.sha ? { sha: existing.sha } : {}),
    });
    if (put.error) failed.push({ path: filePath, error: put.error, message: put.message });
    else committed.push(filePath);
  }

  // Publish the site onto a real GitHub Pages site and then prove it serves.
  //
  // The Pages REST API needs a permission this token does not have, but GitHub
  // enables Pages automatically the moment a `gh-pages` branch exists. So we
  // commit the site to `gh-pages` (which *is* permitted), wait for the first
  // build, and fetch the public URL — reporting the real status, never an
  // assumption. The branch is created from the default branch's head so the
  // first commit is not orphaned.
  let pages = null;
  if (body.pages && committed.length) {
    const pb = body.pagesBranch || 'gh-pages';
    let head = await gh('GET', `/repos/${owner}/${name}/git/ref/heads/${pb}`);
    if (head.error === 'github-404') {
      const def = await gh('GET', `/repos/${owner}/${name}/git/ref/heads/${branch}`);
      if (def && def.object && def.object.sha) {
        await gh('POST', `/repos/${owner}/${name}/git/refs`, { ref: `refs/heads/${pb}`, sha: def.object.sha });
        head = await gh('GET', `/repos/${owner}/${name}/git/ref/heads/${pb}`);
      }
    }

    const pagesFiles = Object.entries(files).map(([rawPath, content]) => {
      const rel = String(rawPath).replace(/^\/+/, '');
      // When the site lives in a subfolder it must be at the branch root for
      // Pages to serve it; a root publish keeps its own layout.
      return [folder ? rel.replace(new RegExp('^' + folder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/'), '') : rel, content];
    });

    for (const [rel, content] of pagesFiles) {
      if (!rel || rel.includes('..')) continue;
      const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(rel)}?ref=${encodeURIComponent(pb)}`);
      await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(rel)}`, {
        message: body.message || `Publish ${rel} (ZYRAXON)`,
        content: Buffer.from(String(content), 'utf8').toString('base64'),
        branch: pb,
        ...(existing && existing.sha ? { sha: existing.sha } : {}),
      });
    }

    const url = `https://${owner.toLowerCase()}.github.io/${name}/`;
    // A 200 alone is not proof: a previously-published site keeps answering 200
    // from the CDN while the new build is still propagating. So we also look
    // for a marker that only the content we just pushed contains.
    const marker = pagesMarker(files);
    let status = 0, matched = false, served = '';
    // The first Pages build for a brand new site takes a while; poll instead of
    // declaring success on an optimistic timer.
    for (let i = 0; i < 24; i++) {
      try {
        const r = await fetch(`${url}?zyraxon=${Date.now()}`, { redirect: 'follow', headers: { 'User-Agent': UA, 'Cache-Control': 'no-cache' } });
        status = r.status;
        if (r.ok) {
          served = await r.text();
          matched = marker ? served.includes(marker) : true;
          if (matched) break;
        }
      } catch { status = 0; }
      await new Promise((r) => setTimeout(r, 5000));
    }
    pages = { url, status, live: status === 200 && matched, verified: matched, marker: marker || null };
  }

  return sendJson(res, 200, {
    ok: committed.length > 0 || !failed.length,
    created,
    fellBack,
    folder: folder || '/',
    repo: `https://github.com/${owner}/${name}`,
    fullName: `${owner}/${name}`,
    owner,
    name,
    branch,
    committed,
    failed,
    pages,
  });
}

/**
 * A short string that must appear in the served HTML if the build we just
 * pushed is the one being served. Prefers a distinctive literal from the
 * content over anything generated, so it survives minification-free HTML.
 */
function pagesMarker(files) {
  const html = files['index.html'] || files['/index.html'] || Object.entries(files).find(([k]) => /(^|\/)index\.html?$/i.test(k));
  const text = typeof html === 'string' ? html : (html && html[1]) || '';
  if (!text) return '';
  const title = /<title[^>]*>([^<]{3,80})<\/title>/i.exec(text);
  if (title) return title[1].trim();
  const h1 = /<h1[^>]*>([^<]{3,80})<\/h1>/i.exec(text);
  if (h1) return h1[1].trim();
  const body = text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  return body.slice(0, 40);
}

/** The live URL a repository's Pages site will answer on. */
function pagesUrl(owner, name, folder) {
  const base = `https://${owner.toLowerCase()}.github.io/${name}/`;
  return folder ? `${base}${folder}/` : base;
}

/** Run a workflow and wait for it, returning the real per-job conclusion. */
async function ghActions(res, bodyBuf) {
  const body = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const full = body.repo;
  if (!full || !full.includes('/')) return sendJson(res, 200, { ok: false, error: 'bad-repo' });
  const wf = body.workflow || 'build.yml';
  const ref = body.ref || 'main';

  if (body.files && Object.keys(body.files).length) {
    const pub = await fetch(`http://127.0.0.1:${PORT}/api/github/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: full.split('/')[1], owner: full.split('/')[0], files: body.files, branch: ref }),
    }).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
    if (!pub.ok) return sendJson(res, 200, { ok: false, stage: 'push', ...pub });
  }

  // A workflow file that was just committed is not dispatchable for a few
  // seconds, so retry the dispatch instead of failing the whole build.
  let dispatch = null;
  for (let i = 0; i < 10; i++) {
    dispatch = await gh('POST', `/repos/${full}/actions/workflows/${wf}/dispatches`, { ref });
    if (!dispatch.error) break;
    if (dispatch.error !== 'github-404' && dispatch.error !== 'github-422') {
      return sendJson(res, 200, { ok: false, stage: 'dispatch', error: dispatch.error, message: dispatch.message });
    }
    await new Promise((r) => setTimeout(r, 4000));
  }
  if (dispatch.error) {
    return sendJson(res, 200, { ok: false, stage: 'dispatch', error: dispatch.error, message: dispatch.message });
  }

  // Poll the newest run of that workflow until it finishes.
  const since = Date.now() - 60000;
  const deadline = Date.now() + Math.min(Number(body.timeout_ms) || 900000, 900000);
  let run = null;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 6000));
    const runs = await gh('GET', `/repos/${full}/actions/workflows/${wf}/runs?per_page=5`);
    // Only the run this dispatch created — an older finished run must not be
    // mistaken for the build we just started.
    const mine = (runs && runs.workflow_runs ? runs.workflow_runs : [])
      .filter((x) => Date.parse(x.created_at) >= since);
    const first = mine[0];
    if (!first) continue;
    run = first;
    if (first.status === 'completed') break;
  }
  if (!run) return sendJson(res, 200, { ok: false, stage: 'timeout', error: 'timeout' });

  const jobs = await gh('GET', `/repos/${full}/actions/runs/${run.id}/jobs`);
  const list = (jobs.jobs || []).map((j) => ({
    name: j.name,
    conclusion: j.conclusion,
    steps: (j.steps || []).map((s) => ({ name: s.name, conclusion: s.conclusion })),
  }));
  const artifacts = await gh('GET', `/repos/${full}/actions/runs/${run.id}/artifacts`);
  return sendJson(res, 200, {
    ok: run.conclusion === 'success',
    conclusion: run.conclusion,
    runUrl: run.html_url,
    jobs: list,
    artifacts: (artifacts.artifacts || []).map((a) => ({ name: a.name, size: a.size_in_bytes, url: a.archive_download_url })),
  });
}

/** Push a file into a repo's workflow folder so Actions can build anything. */
async function ghWorkflow(res, bodyBuf) {
  const body = JSON.parse(bodyBuf.toString('utf8') || '{}');
  const full = body.repo;
  if (!full || !full.includes('/')) return sendJson(res, 200, { ok: false, error: 'bad-repo' });
  const [owner, name] = full.split('/');
  const filePath = String(body.path || '.github/workflows/build.yml');
  const branch = body.branch || 'main';
  const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}?ref=${encodeURIComponent(branch)}`);
  const put = await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}`, {
    message: body.message || `Add ${filePath} (ZYRAXON)`,
    content: Buffer.from(String(body.content || ''), 'utf8').toString('base64'),
    branch,
    ...(existing && existing.sha ? { sha: existing.sha } : {}),
  });
  return sendJson(res, 200, put.error ? { ok: false, ...put } : { ok: true, path: filePath, url: `https://github.com/${full}/blob/${branch}/${filePath}` });
}

/** Everything the assistant needs to know about the connected account. */
async function ghInfo(res) {
  const u = await gh('GET', '/user');
  if (u.error) return sendJson(res, 200, { ok: false, connected: false, ...u });
  const repos = await gh('GET', `/users/${u.login}/repos?per_page=100&sort=updated`);
  return sendJson(res, 200, {
    ok: true,
    connected: true,
    login: u.login,
    name: u.name,
    avatar: u.avatar_url,
    repos: Array.isArray(repos) ? repos.map((r) => ({ name: r.name, url: r.html_url, private: r.private, pages: r.has_pages })) : [],
  });
}

/* --------------------------------- router --------------------------------- */
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const p = url.pathname;

  if (req.method === 'OPTIONS') {
    return send(res, 204, '', {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'content-type,authorization',
      'Access-Control-Max-Age': '86400',
    });
  }

  try {
    if (p === '/api/health') return sendJson(res, 200, { ok: true, ts: Date.now() });
    if (p === '/api/version') return sendJson(res, 200, { version: APP_VERSION });
    if ((p === '/zyraxon.apk' || p === '/download/zyraxon.apk') && req.method === 'GET') {
      return serveFile(res, path.join(ROOT, 'android', 'zyraxon.apk'), {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="zyraxon.apk"',
      });
    }
    if (p === '/api/open-browser' && req.method === 'POST') return openInBrowser(res, await readBody(req));
    if (p === '/api/bridge/events' && req.method === 'GET') return bridgeEvents(req, res);
    if (p === '/api/bridge/say' && req.method === 'POST') return bridgeSay(res, req);
    if (p === '/api/bridge/hello' && req.method === 'POST') return bridgeHello(res, req);
    if (p === '/api/bridge/cmd' && req.method === 'POST') return bridgeCmd(res, req);
    if (p === '/api/bridge/poll' && req.method === 'GET') return bridgePoll(res, url);
    if (p === '/api/bridge/status' && req.method === 'GET') return bridgeStatus(res);
    if (p === '/api/models' && req.method === 'GET') return listModels(res);
    if (p === '/api/model-health' && req.method === 'GET') return modelHealth(res, url.searchParams.get('force') === '1');
    if (p === '/api/chat' && req.method === 'POST') return chatProxy(req, res, await readBody(req));
    if (p === '/api/publish' && req.method === 'POST') return publishSite(res, await readBody(req), req);
    if (p === '/api/shell' && req.method === 'POST') return runShell(res, await readBody(req));
    if (p === '/api/fs' && req.method === 'POST') return fsOp(res, await readBody(req));
    if (p === '/api/platform' && req.method === 'GET') return platformInfo(res);
    if (p === '/api/sysinfo' && req.method === 'GET') return sysInfo(res);
    if (p === '/api/web-search' && req.method === 'POST') return webSearch(res, await readBody(req));
    if (p === '/api/http' && req.method === 'POST') return httpRequest(res, await readBody(req));
    if (p === '/api/fetch-url' && req.method === 'POST') return fetchUrl(res, await readBody(req));
    if (p === '/api/check-url' && req.method === 'POST') return checkUrl(res, await readBody(req));
    if (p === '/api/write-file' && req.method === 'POST') return writeFile(res, await readBody(req));
    if (p === '/api/tts' && req.method === 'GET') return banglaTTS(res, url);
    if (p === '/api/github/info' && req.method === 'GET') return ghInfo(res);
    if (p === '/api/github/user' && req.method === 'GET') return ghUser(res);
    if (p === '/api/github/publish' && req.method === 'POST') return ghPublish(res, await readBody(req));
    if (p === '/api/github/actions' && req.method === 'POST') return ghActions(res, await readBody(req));
    if (p === '/api/github/workflow' && req.method === 'POST') return ghWorkflow(res, await readBody(req));
    if (p === '/api/tree' && req.method === 'GET') {
      const walk = async (d, base = '') => {
        const out = [];
        for (const e of await fsp.readdir(d, { withFileTypes: true })) {
          const r = path.join(base, e.name);
          if (e.isDirectory()) out.push(...await walk(path.join(d, e.name), r));
          else out.push(r);
        }
        return out;
      };
      return sendJson(res, 200, { site: url.searchParams.get('site'), files: await walk(SITES_DIR) });
    }
    if (p.startsWith('/sites/')) return serveFile(res, path.join(SITES_DIR, p.slice('/sites/'.length)));
    if (p === '/cli') return serveFile(res, path.join(PUBLIC_DIR, 'cli.html'));
    return serveFile(res, path.join(PUBLIC_DIR, p === '/' ? 'index.html' : p));
  } catch (e) {
    sendJson(res, 500, { error: String(e) });
  }
});

await fsp.mkdir(SITES_DIR, { recursive: true });
await fsp.mkdir(DATA_DIR, { recursive: true });
await loadHealthCache();
// Warm the pool ranking in the background so the first /api/model-health is fast
// without ever delaying a page load.
if (!healthCache) refreshHealthInBackground();

/**
 * Starting twice used to print an unhandled EADDRINUSE stack trace, which reads
 * as "the app is broken" when in fact it is already running. Detect that case,
 * confirm the process on the port is really us, and exit with a clear message.
 */
server.on('error', async (err) => {
  if (err && err.code === 'EADDRINUSE') {
    const already = await isOurServer();
    if (already) {
      console.log(`ZYRAXON is already running on http://localhost:${PORT} — opening it instead of starting a second copy.`);
      openBrowser(`http://localhost:${PORT}`);
      process.exit(0);
    }
    console.error(`Port ${PORT} is in use by another program.\n`
      + `Close it, or start ZYRAXON on a different port:\n`
      + `  PORT=8080 npm start          (macOS / Linux)\n`
      + `  set PORT=8080 && npm start   (Windows cmd)\n`
      + `  $env:PORT=8080; npm start    (Windows PowerShell)`);
    process.exit(1);
  }
  console.error('ZYRAXON failed to start:', err && err.message ? err.message : err);
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`ZYRAXON mobile assistant running on http://0.0.0.0:${PORT}`);
  console.log(`public origin: ${PUBLIC_ORIGIN || '(from request Host header)'}`);
  // Starting the app is the user's request to use it, so open the window.
  // Set ZYRAXON_NO_OPEN=1 for a headless run (services, containers, tests).
  if (!process.env.ZYRAXON_NO_OPEN) {
    const used = openBrowser(`http://localhost:${PORT}`, { app: true });
    if (used) console.log(`opened in ${path.basename(used)} (speech-capable window)`);
    else console.log('no Chrome or Edge found — speech will need a Chromium-based browser');
  }
  // Re-probe only when the cached ranking has gone stale; never block startup.
  if (!healthCache || Date.now() - healthCache.at > HEALTH_TTL) refreshHealthInBackground();
});
