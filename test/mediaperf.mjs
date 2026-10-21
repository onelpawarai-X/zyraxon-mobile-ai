/**
 * ZYRAXON media performance check.
 *
 * Plays every bundled background clip in a real Chromium and measures what the
 * user actually feels: decoded-frame rate, dropped frames, and how much CPU the
 * page burns while the clip loops. The originals were software-decoded VP8/VP9
 * at up to 3840x2160, which is what made the shell lag; this test exists so a
 * future asset swap cannot quietly bring that back.
 *
 * Usage:  node test/mediaperf.mjs [port]
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const PORT = process.argv[2] || '12000';
const APP = `http://localhost:${PORT}`;
const CDP_PORT = 9336;

/** Chromium is shipped under different names across distros and CI images. */
function findChrome() {
  const names = [process.env.ZYRAXON_CHROME, 'chromium', 'chromium-browser',
    'google-chrome', 'google-chrome-stable',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    .filter(Boolean);
  for (const n of names) {
    try {
      if (spawnSync(n, ['--version'], { stdio: 'ignore' }).status === 0) return n;
    } catch {}
  }
  return null;
}

/** Minimal CDP client — same shape as the one in e2e.mjs. */
class CDP {
  static async attach(port, url) {
    for (let i = 0; i < 50; i++) {
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        const page = list.find((t) => t.type === 'page');
        if (page) {
          const ws = new WebSocket(page.webSocketDebuggerUrl);
          const c = new CDP(ws);
          await c.ready;
          await c.send('Page.enable');
          await c.send('Runtime.enable');
          await c.send('Page.navigate', { url });
          await sleep(1200);
          return c;
        }
      } catch {}
      await sleep(200);
    }
    throw new Error('no debuggable page');
  }

  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.ready = new Promise((res) => { ws.onopen = res; });
    ws.onmessage = (m) => {
      const msg = JSON.parse(m.data);
      if (msg.id && this.pending.has(msg.id)) {
        this.pending.get(msg.id)(msg);
        this.pending.delete(msg.id);
      }
    };
  }

  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((res) => this.pending.set(id, res));
  }

  async eval(expr) {
    const r = await this.send('Runtime.evaluate', {
      expression: expr, awaitPromise: true, returnByValue: true,
    });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.text);
    return r.result?.result?.value;
  }
}

/**
 * Play one clip and report what the compositor saw.
 *
 * requestVideoFrameCallback is the honest measurement: it fires once per frame
 * that is actually presented, so a clip whose decode cannot keep up shows up as
 * fewer callbacks than the source frame rate and as visible frame gaps.
 *
 * TaskDuration is the page's own CPU time, which is what separates "decoded on
 * the GPU, costs nothing" from "software-decoded, burns a core".
 */
const MEASURE = `(async (src, seconds) => {
  const v = document.getElementById('bg-video');
  v.pause(); v.removeAttribute('src'); v.load();
  await new Promise((r) => setTimeout(r, 300));
  v.src = src; v.muted = true; v.loop = true; v.playsInline = true;
  v.style.display = 'block'; v.style.opacity = '1';
  await v.play().catch(() => {});
  const decodedAtStart = v.getVideoPlaybackQuality
    ? v.getVideoPlaybackQuality().totalVideoFrames : 0;

  const gaps = []; let frames = 0; let last = 0;
  const t0 = performance.now();
  const stop = t0 + seconds * 1000;
  await new Promise((resolve) => {
    const step = (now, meta) => {
      if (now >= stop) return resolve();
      if (meta && meta.presentedFrames) {
        if (last) gaps.push(now - last);
        last = now; frames++;
      }
      v.requestVideoFrameCallback(step);
    };
    v.requestVideoFrameCallback(step);
  });

  const wall = (performance.now() - t0) / 1000;
  const vw = v.videoWidth, vh = v.videoHeight;
  const q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null;
  gaps.sort((a, b) => a - b);
  return JSON.stringify({
    src, vw, vh, fps: +(frames / wall).toFixed(1),
    // A clip's own frame rate is whatever it decodes at, so the honest question
    // is not "is this 24 fps" but "did every decoded frame reach the screen".
    // A 3 fps NASA timelapse is healthy at 3 fps; a 30 fps clip that presents 8
    // is not. The ratio catches both.
    decoded: q ? q.totalVideoFrames - decodedAtStart : null,
    presented: frames,
    p50: +(gaps[Math.floor(gaps.length / 2)] || 0).toFixed(1),
    p95: +(gaps[Math.floor(gaps.length * 0.95)] || 0).toFixed(1),
    worst: +(gaps[gaps.length - 1] || 0).toFixed(1),
    dropped: q ? q.droppedVideoFrames : 0,
  });
})`;

/**
 * Budgets. Calibrated for headless software decode, the worst case a user can
 * hit — a machine with no video hardware at all. A clip that fits these plays
 * smoothly everywhere.
 *
 * These three are what actually caught the old assets: `nightlights.webm` was
 * 3840x2160 (resolution), and `city.webm` 1920x1080 (resolution). CPU is shown
 * but not enforced — it is the whole page's task time, so it moves with
 * whatever else the app is animating and is too noisy to fail a build on.
 */
const MAX_EDGE = 1280;     // no background may be larger than 720p-class
const MIN_KEEP = 0.6;      // presented / decoded — are frames reaching the screen
const MIN_FPS = 2;         // absolute floor; catches a clip that barely moves
const MAX_STALL = 6;       // worst frame gap, as a multiple of the clip's own interval

async function main() {
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health?.ok) {
    console.log(`FAIL server not running on ${APP} — start it first`);
    process.exit(1);
  }
  const exe = findChrome();
  if (!exe) {
    console.log('SKIP no Chromium found');
    process.exit(0);
  }

  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-perf-'));
  const chrome = spawn(exe, [
    '--headless=new', '--no-sandbox', '--disable-dev-shm-usage',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    '--autoplay-policy=no-user-gesture-required',
    '--window-size=412,915', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});

  let cdp;
  try { cdp = await CDP.attach(CDP_PORT, APP); }
  catch (e) {
    console.log('FAIL could not attach Chromium —', e.message);
    chrome.kill();
    process.exit(1);
  }

  // Extra args are src URLs to test instead of the bundled scenes, so a new
  // clip can be checked before it is committed.
  const extra = process.argv.slice(3);
  const scenes = extra.length ? extra : JSON.parse(await cdp.eval(
    `JSON.stringify(ZY.BG.scenes.filter(s => s.src).map(s => s.src))`));

  await cdp.send('Performance.enable');
  const cpuSeconds = async () => {
    const r = await cdp.send('Performance.getMetrics');
    return r.result.metrics.find((m) => m.name === 'TaskDuration')?.value || 0;
  };

  console.log(`measuring ${scenes.length} clips (3s each, median of 3)\n`);
  console.log('clip                     size        fps   keep   p95   worst   cpu%');
  console.log('─'.repeat(78));

  let bad = 0;
  for (const src of scenes) {
    const reps = [];
    for (let i = 0; i < 3; i++) {
      const c0 = await cpuSeconds();
      const r = JSON.parse(await cdp.eval(`${MEASURE}(${JSON.stringify(src)}, 3)`));
      r.cpu = Math.round(((await cpuSeconds()) - c0) / 3 * 100);
      reps.push(r);
    }
    const pick = (k) => reps.map((r) => r[k]).sort((a, b) => a - b)[1];
    const r = { ...reps[0], fps: pick('fps'), p95: pick('p95'), worst: pick('worst'), cpu: pick('cpu'), dropped: pick('dropped') };
    const keep = r.decoded ? Math.min(1, r.presented / r.decoded) : 1;

    const name = src.split('/').pop();
    const reasons = [];
    if (Math.max(r.vw, r.vh) > MAX_EDGE) reasons.push(`>${MAX_EDGE}px`);
    if (keep < MIN_KEEP) reasons.push('dropping frames');
    if (r.fps < MIN_FPS) reasons.push('low fps');
    // The clip's own frame interval, so a 3 fps timelapse is not judged by a
    // 24 fps yardstick.
    const interval = 1000 / Math.max(r.fps, 0.1);
    if (r.worst > interval * MAX_STALL) reasons.push('stalls');
    if (reasons.length) bad++;

    console.log(
      `${(reasons.length ? '! ' : '  ') + name.padEnd(22)} ${(r.vw + 'x' + r.vh).padEnd(11)}` +
      `${String(r.fps).padStart(5)}${(Math.round(keep * 100) + '%').padStart(7)}` +
      `${String(r.p95).padStart(6)}ms${String(r.worst).padStart(6)}ms${String(r.cpu).padStart(6)}%` +
      (reasons.length ? `   ${reasons.join(', ')}` : ''));
  }

  console.log('─'.repeat(78));
  console.log(bad === 0
    ? `PASS all ${scenes.length} clips play smoothly`
    : `FAIL ${bad}/${scenes.length} clips stutter`);

  cdp.ws.close();
  chrome.kill();
  process.exit(bad === 0 ? 0 : 1);
}

main().catch((e) => { console.error('ERROR', e); process.exit(1); });
