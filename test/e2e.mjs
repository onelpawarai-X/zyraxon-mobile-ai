/**
 * ZYRAXON end-to-end verification.
 *
 * Drives the real app in a real Chromium and proves the things the user asked
 * for actually happen: the shell renders, the ten models load, a Bangla task
 * streams in over SSE, the agent really calls tools (not just talks about
 * them), the todo board fills in, a website is published and serves, and the
 * character persona switches and persists.
 *
 * Usage:  node test/e2e.mjs [port]
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const PORT = Number(process.argv[2] || 12000);
const APP = `http://127.0.0.1:${PORT}`;
const CDP_PORT = 9334;
const PROMPT = 'একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো যেখানে লেখা থাকবে হ্যালো জাইরাক্সন।';

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail !== undefined ? ' — ' + JSON.stringify(detail) : ''}`);
};

/** A tiny CDP client: enough to drive and inspect the page. */
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.waiting = new Map(); this.errors = []; }

  static async attach(port, url) {
    for (let i = 0; i < 80; i++) {
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        const page = list.find((t) => t.type === 'page');
        if (page && page.webSocketDebuggerUrl) {
          const ws = new WebSocket(page.webSocketDebuggerUrl);
          await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
          const c = new CDP(ws);
          ws.onmessage = (e) => {
            const m = JSON.parse(e.data);
            if (m.method === 'Runtime.exceptionThrown') {
              c.errors.push(String(m.params?.exceptionDetails?.exception?.description || 'exception'));
            }
            if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
              c.errors.push((m.params.args || []).map((a) => a.value || a.description || '').join(' '));
            }
            const w = c.waiting.get(m.id);
            if (w) { c.waiting.delete(m.id); w(m); }
          };
          await c.send('Page.enable');
          await c.send('Runtime.enable');
          // Headless Chromium has no speech engine; turning TTS off keeps the
          // run silent without changing any of the code paths under test.
          await c.send('Page.addScriptToEvaluateOnNewDocument', {
            source: `localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));`,
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

async function main() {
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health || !health.ok) {
    console.log(`FAIL server not running on ${APP} — start it first (node zyraxon.mjs)`);
    process.exit(1);
  }

  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-e2e-'));
  const chrome = spawn('chromium', [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    '--autoplay-policy=no-user-gesture-required',
    '--window-size=412,915', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});

  let cdp;
  try { cdp = await CDP.attach(CDP_PORT, APP); }
  catch (e) {
    console.log('FAIL could not start/attach Chromium —', e.message);
    chrome.kill();
    process.exit(1);
  }

  try {
    // ── 1. the shell really rendered ──
    const boot = await cdp.eval(`JSON.stringify({
      title: document.title,
      orb: !!document.getElementById('aura'),
      mic: !!document.getElementById('orb-btn'),
      input: !!document.getElementById('input'),
      ready: document.readyState,
    })`);
    record('the app shell renders in a real browser',
      boot.orb && boot.mic && boot.input, boot);

    // ── 2. the ten models load, fastest first ──
    await cdp.eval(`document.getElementById('model-btn').click(); 'ok'`);
    await sleep(700);
    const models = await cdp.eval(`JSON.stringify({
      count: document.querySelectorAll('.model-item[data-id]').length,
      order: [...document.querySelectorAll('.model-item[data-id]')].map(b => b.dataset.id),
      fastestBadge: (document.querySelector('.model-item[data-id] .badge-fast') || {}).textContent || '',
    })`);
    // The user asked for the models that answer fastest to sit at the top, so
    // the rendered order is compared against the provider's own live ranking.
    const health = await (await fetch(`${APP}/api/model-health`)).json().catch(() => null);
    const live = (health && health.order) || [];
    const expected = live.length ? live[0] : '';
    record('the model picker lists all ten ZYRAXON models',
      models.count === 10, { count: models.count });
    record('the model that answers fastest is listed first',
      !!expected && models.order[0] === expected,
      { first: models.order[0], healthFastest: expected, badge: models.fastestBadge });
    await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'})); 'ok'`);

    // ── 3. a real Bangla task: SSE stream + real tool calls ──
    await cdp.eval(`document.getElementById('btn-console').click(); 'ok'`);
    await sleep(600);
    await cdp.eval(`(() => {
      const i = document.getElementById('input');
      i.value = ${JSON.stringify(PROMPT)};
      i.dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('btn-send').click();
      return 'sent';
    })()`);

    let toolRows = 0;
    for (let i = 0; i < 240; i++) {
      await sleep(1000);
      toolRows = await cdp.eval(`document.querySelectorAll('#tl-list .tl-row').length`);
      const busy = await cdp.eval(`!document.getElementById('btn-stop').hidden`);
      if (toolRows > 0 && !busy) break;
    }
    record('the agent really executed tool calls', toolRows > 0, { tool_rows: toolRows });

    // ── 4. the todo board filled in from real tool calls ──
    const todos = await cdp.eval(`JSON.stringify({
      stored: (JSON.parse(localStorage.getItem('zyraxon:settings')||'{}').todos||[]).length,
      rows: document.querySelectorAll('#tb-list .tb-item').length,
      counter: (document.getElementById('tb-count')||{}).textContent || '',
    })`);
    record('the todo board shows the tasks the agent created',
      todos.stored > 0 && todos.rows === todos.stored, todos);

    // ── 5. publish_website was really called ──
    const names = await cdp.eval(`JSON.stringify(
      [...document.querySelectorAll('#tl-list .tl-row .tl-name')].map(n => n.textContent.trim()))`);
    record('publish_website was really called', names.includes('publish_website'), names);

    // ── 6. the published site really serves ──
    const url = await cdp.eval(`(() => {
      const txt = [...document.querySelectorAll('#tl-list .tl-row .tl-res')].map(n => n.textContent).join(' ');
      const m = txt.match(/https?:\\/\\/[^\\s"']+/);
      return m ? m[0] : '';
    })()`);
    let status = 0;
    if (url) {
      try { status = (await fetch(url)).status; } catch {}
    }
    record('the published website is live and serves',
      !!url && status === 200, { url, status });

    // ── 7. the answer streamed in as real Bangla text ──
    const bubbles = await cdp.eval(`JSON.stringify(
      [...document.querySelectorAll('.msg .body')].map(n => n.textContent).join('\\n').slice(0, 400))`);
    record('the reply arrived as real Bangla text',
      /[\u0980-\u09FF]/.test(bubbles), { sample: bubbles.slice(0, 120) });

    // ── 8. no uncaught page errors ──
    const real = cdp.errors.filter((e) => !/favicon/i.test(e));
    record('the page ran without uncaught errors', real.length === 0, real.slice(0, 4));

    // ── 9. opening a sheet must not cost frames ──
    // The orb is a stack of blurred, screen-blended layers. It used to keep
    // being composited over the playing clip while the chat sheet hid it, which
    // held the whole app at ~24fps. It must be taken out of the compositor for
    // as long as a sheet covers it, and come back untouched afterwards.
    const perf = await cdp.eval(`(async () => {
      const orb = document.querySelector('.orb-wrap');
      const before = getComputedStyle(orb).contentVisibility;
      document.getElementById('btn-console').click();
      await new Promise(r => setTimeout(r, 700));
      const open = getComputedStyle(orb).contentVisibility;
      const t = []; let last = performance.now();
      await new Promise((done) => {
        const end = performance.now() + 2000;
        const tick = () => { const n = performance.now(); t.push(n - last); last = n;
          if (n < end) requestAnimationFrame(tick); else done(); };
        requestAnimationFrame(tick);
      });
      t.shift(); t.sort((a, b) => a - b);
      const median = Math.round(t[Math.floor(t.length / 2)] * 10) / 10;
      document.getElementById('console-close').click();
      await new Promise(r => setTimeout(r, 700));
      return JSON.stringify({ before, open, median, fps: Math.round(1000 / median),
        after: getComputedStyle(orb).contentVisibility });
    })()`);
    record('opening the chat sheet keeps the app smooth and the orb intact',
      perf.open === 'hidden' && perf.after === 'visible' && perf.fps >= 30, perf);

    // ── 10. the persona switches and persists ──
    await cdp.eval(`document.getElementById('console-close').click(); 'ok'`);
    await sleep(400);
    await cdp.eval(`document.getElementById('btn-settings').click(); 'ok'`);
    await sleep(700);
    const switched = await cdp.eval(`(async () => {
      const nav = document.querySelector("[data-nav='characters']");
      if (!nav) return JSON.stringify({ ok: false, why: 'no characters tab' });
      nav.click();
      await new Promise(r => setTimeout(r, 500));
      const cards = document.querySelectorAll('.char-card');
      if (cards.length < 3) return JSON.stringify({ ok: false, why: 'cards=' + cards.length });
      cards[2].click();
      await new Promise(r => setTimeout(r, 800));
      const name = (document.getElementById('char-chip-name')||{}).textContent || '';
      const stored = JSON.parse(localStorage.getItem('zyraxon:settings')||'{}');
      return JSON.stringify({ ok: true, name, persona: stored.character || stored.persona || '' });
    })()`);
    record('the character persona switches and is stored',
      switched.ok === true && /বন্ধু/.test(String(switched.name || '')), switched);
  } finally {
    cdp.ws.close();
    try { chrome.kill('SIGKILL'); } catch {}
    await sleep(300);
    await rm(profile, { recursive: true, force: true }).catch(() => {});
  }

  const failed = results.filter((r) => !r.ok);
  console.log('\n' + (failed.length
    ? `${failed.length} FAILED: ${failed.map((f) => f.name).join(', ')}`
    : 'ALL CHECKS PASSED'));
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('crashed:', e); process.exit(1); });
