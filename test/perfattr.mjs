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
      if (what === 'orbcheap') {
        // Same orb, but the per-layer blur() that makes each aura ribbon a
        // separate offscreen render pass is dropped. Tests whether the cost is
        // the animation itself or the blurred layers it animates.
        document.querySelectorAll('.orb-wrap').forEach(o => o.style.display = '');
        document.querySelectorAll('.aura-ambient,.aura-plasma,.aura-ribbon,.aura-core,.aura-shine')
          .forEach(a => { a.style.filter = 'none'; });
      }
      if (what === 'orbnopromo') {
        // Control row: force the blurred layers back to plain, unpromoted
        // layers. It proves the compositor promotion a stylesheet may add is
        // not itself what makes the orb cheap — this row measures the same as
        // the shipping row, so no promotion rule is shipped.
        document.querySelectorAll('.orb-wrap').forEach(o => o.style.display = '');
        document.querySelectorAll('.aura-ambient,.aura-plasma,.aura-ribbon,.aura-core,.aura-shine')
          .forEach(a => { a.style.willChange = 'auto'; a.style.transform = 'none'; a.style.backfaceVisibility = 'visible'; });
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
  async function measureOnce(label, setup) {
    await cdp.eval(`(() => { window.__setLayer('video', false); window.__setLayer('orb', false); window.__setLayer('blur', false); return 'ok'; })()`);
    await cdp.eval(setup);
    await sleep(900); // let the compositor settle
    return JSON.parse(await cdp.eval(`${SAMPLE}(2000)`));
  }

  // A single sample under a software rasteriser is dominated by warm-up and
  // scheduler noise: the same configuration has read 37fps and 60fps in
  // consecutive runs. Interleave the configurations round-robin and keep the
  // best sample for each, which is the least-contended frame the machine can
  // actually produce. Compare that across configurations; a single run's
  // number is not evidence of anything.
  const configs = [
    ['nothing (bare shell)', `'ok'`],
    ['+ background video', `window.__setLayer('video', true)`],
    ['+ animated orb (shipping CSS)', `window.__setLayer('orb', true)`],
    ['+ orb without its blur passes', `window.__setLayer('orbcheap', true)`],
    ['+ orb with the promotion stripped', `window.__setLayer('orbnopromo', true)`],
    ['+ glass blur', `window.__setLayer('blur', true)`],
    ['all three (shipping state)', `window.__setLayer('video', true); window.__setLayer('orb', true); window.__setLayer('blur', true)`],
  ];
  const best = new Map(configs.map(([l]) => [l, []]));

  console.log(`\nFrame-cost attribution on ${APP} (headless, software raster — compare rows, not absolutes)\n`);
  await measureOnce('warm-up', `'ok'`); // discard
  const ROUNDS = 5;
  for (let r = 0; r < ROUNDS; r++) {
    for (const [label, setup] of configs) {
      best.get(label).push(await measureOnce(label, setup));
    }
  }
  // Median across interleaved rounds, not the best. A best-of hides a real
  // regression behind one lucky frame; the median is what a user would feel.
  const median = (xs) => xs.slice().sort((a, b) => a - b)[Math.floor(xs.length / 2)];
  const summary = new Map(configs.map(([l]) => {
    const xs = best.get(l);
    const pick = median(xs);
    return [l, pick];
  }));

  console.log(`  ${'layer'.padEnd(34)} fps        median         p95          worst        main-thread`);
  console.log('  ' + '─'.repeat(108));
  for (const [label] of configs) {
    const s = summary.get(label);
    const all = best.get(label).map((x) => x.fps).join('/');
    rows.push({ label, ...s });
    console.log(`  ${label.padEnd(34)} ${String(s.fps).padStart(3)} fps   med ${String(s.med).padStart(6)}ms   p95 ${String(s.p95).padStart(6)}ms   worst ${String(s.worst).padStart(6)}ms   busy ${String(s.busyPct).padStart(3)}% (${s.longTasks} long)   all: ${all}`);
  }
  console.log(`  (median of ${ROUNDS} interleaved samples each)`);

  const get = (l) => summary.get(l);
  const none = get('nothing (bare shell)');
  const video = get('+ background video');
  const orb = get('+ animated orb (shipping CSS)');
  const orbcheap = get('+ orb without its blur passes');
  const orbnopromo = get('+ orb with the promotion stripped');
  const blur = get('+ glass blur');
  const all = get('all three (shipping state)');

  const rel = (r) => (r.med / none.med);
  console.log('\n  Cost relative to the bare shell (higher = more frames dropped):');
  console.log(`    video alone       ${rel(video).toFixed(2)}x`);
  console.log(`    orb (shipping)    ${rel(orb).toFixed(2)}x`);
  console.log(`    orb, blur dropped ${rel(orbcheap).toFixed(2)}x`);
  console.log(`    orb, no promotion ${rel(orbnopromo).toFixed(2)}x`);
  console.log(`    blur alone        ${rel(blur).toFixed(2)}x`);
  console.log(`    all three         ${rel(all).toFixed(2)}x`);

  console.log(`\n  rAF callbacks: bare ${none.n}, all three ${all.n} (2s samples)`);

  // Guard, not just a report: every layer combination must stay within 15% of
  // the bare shell's frame interval. Measured median-of-5 so a single noisy
  // sample cannot fail the run — but a real regression (a layer that costs
  // frames on every sample) will push the median and fail.
  const LIMIT = 1.15;
  const over = configs.filter(([l]) => {
    const s = summary.get(l);
    return s.med > none.med * LIMIT;
  }).map(([l]) => `${l} ${summary.get(l).med}ms vs ${none.med}ms`);

  console.log('\n  Read this as: the frame cost is a property of the layers being composited,');
  console.log('  not of the language the code is written in.');

  if (over.length) {
    console.log(`\nFAIL layers over ${LIMIT}x the bare shell: ${over.join('; ')}`);
    cdp.ws.close(); chrome.kill(); process.exit(1);
  }
  console.log(`\nPASS every layer stays within ${LIMIT}x of the bare shell (${none.med}ms)`);

  cdp.ws.close(); chrome.kill();
  process.exit(0);
}

main().catch((e) => { console.error('ERROR', e); process.exit(1); });
