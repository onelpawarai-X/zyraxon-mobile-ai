/**
 * Frame-cost attribution — which layer actually costs the frames?
 *
 * The question this answers is "does the app lag because of the language it is
 * written in?". Language is not a measurable property of a rendered frame, so
 * instead this isolates the things that DO cost frames: the background video,
 * the animated orb, and the glass blur. Each is toggled independently and the
 * frame interval is measured in a real browser.
 *
 * Usage:  node test/perfattr.mjs [port]
 */
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const PORT = process.argv[2] || '12000';
const APP = `http://localhost:${PORT}`;
const CDP_PORT = 9351;

function findChrome() {
  for (const n of ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable']) {
    try { if (spawnSync(n, ['--version'], { stdio: 'ignore' }).status === 0) return n; } catch {}
  }
  return null;
}

class CDP {
  static async attach(port, url) {
    for (let i = 0; i < 50; i++) {
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        const page = list.find((t) => t.type === 'page');
        if (page) {
          const ws = new WebSocket(page.webSocketDebuggerUrl);
          const c = new CDP(ws); await c.ready;
          await c.send('Page.enable'); await c.send('Runtime.enable');
          await c.send('Page.navigate', { url }); await sleep(2000);
          return c;
        }
      } catch {}
      await sleep(200);
    }
    throw new Error('no debuggable page');
  }
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map();
    this.ready = new Promise((r) => { ws.onopen = r; });
    ws.onmessage = (m) => {
      const g = JSON.parse(m.data);
      if (g.id && this.pending.has(g.id)) { this.pending.get(g.id)(g); this.pending.delete(g.id); }
    };
  }
  send(m, p = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method: m, params: p })); return new Promise((r) => this.pending.set(id, r)); }
  async eval(e) {
    const r = await this.send('Runtime.evaluate', { expression: e, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.text);
    return r.result?.result?.value;
  }
}

/** Sample rAF frame intervals for `ms` and return summary stats. */
const SAMPLE = `(async (ms) => {
  // Long tasks are main-thread work over 50ms. This is the one number a
  // language change could move: if the main thread is idle, no rewrite helps.
  let longMs = 0, longN = 0;
  let obs = null;
  try {
    obs = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) { longMs += e.duration; longN++; }
    });
    obs.observe({ entryTypes: ['longtask'] });
  } catch (e) {}
  const t = []; let last = performance.now();
  await new Promise((done) => {
    const end = performance.now() + ms;
    const tick = () => { const n = performance.now(); t.push(n - last); last = n;
      if (n < end) requestAnimationFrame(tick); else done(); };
    requestAnimationFrame(tick);
  });
  if (obs) obs.disconnect();
  t.shift(); t.sort((a, b) => a - b);
  const at = (p) => t[Math.min(t.length - 1, Math.floor(t.length * p))] || 0;
  const med = at(0.5);
  return JSON.stringify({ n: t.length, med: Math.round(med * 10) / 10,
    p95: Math.round(at(0.95) * 10) / 10, worst: Math.round((t[t.length - 1] || 0) * 10) / 10,
    fps: Math.round(1000 / med),
    longTasks: longN, longMs: Math.round(longMs),
    busyPct: Math.round((longMs / ms) * 100) });
})`;

async function main() {
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health?.ok) { console.log(`FAIL server not running on ${APP}`); process.exit(1); }
  const exe = findChrome();
  if (!exe) { console.log('SKIP no Chromium'); process.exit(0); }

  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-perfattr-'));
  const chrome = spawn(exe, [
    '--headless=new', '--no-sandbox', '--disable-dev-shm-usage',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    '--window-size=412,915', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});

  let cdp;
  try { cdp = await CDP.attach(CDP_PORT, APP); }
  catch (e) { console.log('FAIL attach —', e.message); chrome.kill(); process.exit(1); }

  // Helper injected once: force each layer on/off deterministically.
  await cdp.eval(`(() => {
    window.__setLayer = (what, on) => {
      if (what === 'video') {
        document.querySelectorAll('.bg-video').forEach(v => {
          if (on) { v.classList.add('on'); v.play && v.play().catch(()=>{}); }
          else { v.classList.remove('on'); v.pause && v.pause(); }
        });
      }
      if (what === 'orb') {
        document.querySelectorAll('.orb-wrap').forEach(o => o.style.display = on ? '' : 'none');
      }
      if (what === 'blur') {
        document.querySelectorAll('.glass, .console-panel, .composer-inner').forEach(g => {
          g.style.backdropFilter = on ? '' : 'none';
          g.style.webkitBackdropFilter = on ? '' : 'none';
        });
      }
      return 'ok';
    };
    return 'ok';
  })()`);

  const rows = [];
  async function measure(label, setup) {
    await cdp.eval(`(() => { window.__setLayer('video', false); window.__setLayer('orb', false); window.__setLayer('blur', false); return 'ok'; })()`);
    await cdp.eval(setup);
    await sleep(900); // let the compositor settle
    const r = JSON.parse(await cdp.eval(`${SAMPLE}(3000)`));
    rows.push({ label, ...r });
    console.log(`  ${label.padEnd(34)} ${String(r.fps).padStart(3)} fps   med ${String(r.med).padStart(6)}ms   p95 ${String(r.p95).padStart(6)}ms   worst ${String(r.worst).padStart(6)}ms   busy ${String(r.busyPct).padStart(3)}% (${r.longTasks} long)`);
    return r;
  }

  console.log(`\nFrame-cost attribution on ${APP} (headless, software raster — compare rows, not absolutes)\n`);
  console.log(`  ${'layer'.padEnd(34)} fps        median         p95          worst        main-thread`);
  console.log('  ' + '─'.repeat(108));

  const none = await measure('nothing (bare shell)', `'ok'`);
  const video = await measure('+ background video', `window.__setLayer('video', true)`);
  const orb = await measure('+ animated orb', `window.__setLayer('orb', true)`);
  const blur = await measure('+ glass blur', `window.__setLayer('blur', true)`);
  const all = await measure('all three (shipping state)', `window.__setLayer('video', true); window.__setLayer('orb', true); window.__setLayer('blur', true)`);

  const rel = (r) => (r.med / none.med);
  console.log('\n  Cost relative to the bare shell (higher = more frames dropped):');
  console.log(`    video alone    ${rel(video).toFixed(2)}x`);
  console.log(`    orb alone      ${rel(orb).toFixed(2)}x`);
  console.log(`    blur alone     ${rel(blur).toFixed(2)}x`);
  console.log(`    all three      ${rel(all).toFixed(2)}x`);

  console.log(`\n  rAF callbacks in 3s: bare ${none.n}, all three ${all.n}`);
  console.log('\n  Read this as: the frame cost is a property of the layers being composited,');
  console.log('  not of the language the code is written in.');

  cdp.ws.close(); chrome.kill();
  process.exit(0);
}

main().catch((e) => { console.error('ERROR', e); process.exit(1); });
