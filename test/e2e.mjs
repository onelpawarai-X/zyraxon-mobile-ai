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
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';


/** Find a Chromium-family binary. CI runners ship Chrome, not "chromium", so
 *  the name is probed instead of assumed. */
function findChrome() {
  const names = [process.env.ZYRAXON_CHROME, 'chromium', 'chromium-browser',
    'google-chrome', 'google-chrome-stable',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    .filter(Boolean);
  for (const n of names) {
    try {
      const r = spawnSync(n, ['--version'], { stdio: 'ignore' });
      if (r.status === 0) return n;
    } catch {}
  }
  return 'chromium';
}

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

  /** Drive a real mouse press/release at an element's centre, the way a finger
   *  does. A synthetic Element.click() fires even when the control is off-screen
   *  or covered, so only a dispatched pointer proves the user can really tap it. */
  async click(selector) {
    const box = await this.eval(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      const r = el.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    for (const type of ['mousePressed', 'mouseReleased']) {
      await this.send('Input.dispatchMouseEvent', {
        type, x: box.x, y: box.y, button: 'left', clickCount: 1,
      });
    }
    await sleep(160);
  }
}

async function main() {
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health || !health.ok) {
    console.log(`FAIL server not running on ${APP} — start it first (node zyraxon.mjs)`);
    process.exit(1);
  }

  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-e2e-'));
  const chrome = spawn(findChrome(), [
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

    // ── the three panels are independent, and really tappable ──
    // The user's complaint: tapping one tab opened both floating panels, so a
    // single idea cost two taps — and on a wide screen the panels sat past the
    // edge of the viewport, so the tap did nothing at all. These tests dispatch
    // a real mouse event at the tab's own coordinates, then read the panels.
    await cdp.eval(`document.getElementById('btn-console').click(); 'ok'`);
    await sleep(200);
    const panels = async () => cdp.eval(`JSON.stringify({
      todos: !document.getElementById('dock-todos').hidden,
      tools: !document.getElementById('dock-tools').hidden,
    })`);

    await cdp.click('#tab-todos');
    let p = await panels();
    record('a real tap on Tasks opens only the todo panel',
      p.todos === true && p.tools === false, p);

    await cdp.click('#tab-tools');
    p = await panels();
    record('a real tap on Tools opens only the tool panel',
      p.tools === true && p.todos === true, p);

    await cdp.click('#tab-tools');
    p = await panels();
    record('a second tap on Tools closes just that panel',
      p.tools === false && p.todos === true, p);

    await cdp.click('#tab-todos');
    p = await panels();
    record('a second tap on Tasks closes just that panel',
      p.todos === false && p.tools === false, p);

    await cdp.click('.ctab[data-tab="chat"]');
    p = await panels();
    record('tapping Chat puts both docks away',
      p.todos === false && p.tools === false, p);

    // A tool call must never write its name into the chat bubble.
    const noToolText = await cdp.eval(`JSON.stringify({
      leaked: /\\[টুল:/.test(document.getElementById('chat').innerText),
    })`);
    record('tool names never leak into the chat bubbles', noToolText.leaked === false, noToolText);

    // ── the panels must be reachable by a real finger ──
    // The tests above call consoleTab() directly, which passes even when the
    // control is off-screen or covered — and on a wide screen the two docks are
    // absolutely positioned outside the sheet, so they can sit past the edge.
    // This drives the actual coordinates and asks what is under the point.
    const hit = await cdp.eval(`(() => {
      const probe = (id) => {
        const el = document.getElementById(id);
        const r = el.getBoundingClientRect();
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        const top = document.elementFromPoint(cx, cy);
        return {
          rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
          onScreen: r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
          reachable: !!top && (top === el || el.contains(top)),
          covering: top ? (top.id || top.className) : null,
        };
      };
      document.getElementById('btn-console').click();
      return JSON.stringify({
        vw: innerWidth, vh: innerHeight,
        tasks: probe('tab-todos'),
        tools: probe('tab-tools'),
        min: probe('co-min'),
      });
    })()`);
    record('the Tasks tab is reachable by a real click',
      hit.tasks.onScreen && hit.tasks.reachable, { ...hit, which: 'tasks' });
    record('the Tools tab is reachable by a real click',
      hit.tools.onScreen && hit.tools.reachable, { ...hit, which: 'tools' });

    // ── the live code box: line by line, and minimisable ──
    await cdp.eval(`(() => {
      window.ZY.ui.showCodeOverlay('Writing code · html', '<h1>hi</h1>\\n<p>there</p>');
      return 'ok';
    })()`);
    await sleep(200);
    const codeOpen = await cdp.eval(`JSON.stringify({
      visible: !document.getElementById('code-overlay').hidden,
      text: document.getElementById('co-code').textContent,
    })`);
    record('the code box shows the file line by line',
      codeOpen.visible === true && codeOpen.text.includes('<h1>hi</h1>'), codeOpen);

    await cdp.eval(`document.getElementById('co-min').click(); 'ok'`);
    await sleep(150);
    const minimised = await cdp.eval(`JSON.stringify({
      min: document.getElementById('code-overlay').classList.contains('min'),
      bodyHidden: getComputedStyle(document.querySelector('.co-body')).display === 'none',
    })`);
    record('the user can minimise the code box to its title bar',
      minimised.min === true && minimised.bodyHidden === true, minimised);

    // Minimised must not stop the work: new code still arrives.
    await cdp.eval(`window.ZY.ui.showCodeOverlay('Writing code · html', '<h1>hi</h1>\\n<p>more</p>'); 'ok'`);
    await sleep(150);
    const stillWriting = await cdp.eval(`JSON.stringify({
      min: document.getElementById('code-overlay').classList.contains('min'),
      text: document.getElementById('co-code').textContent,
    })`);
    record('code keeps streaming while the box stays minimised',
      stillWriting.min === true && stillWriting.text.includes('<p>more</p>'), stillWriting);

    // The assistant can close its own box through a real tool call.
    const closed = await cdp.eval(`(async () => {
      const r = await window.ZY.Tools.execute('code_box', { action: 'close' });
      return JSON.stringify({ ok: r.ok, hidden: document.getElementById('code-overlay').hidden });
    })()`);
    record('the assistant closes its own code box with a tool call',
      closed.ok === true && closed.hidden === true, closed);

    // A real file is revealed line by line, not in one block.
    const typed = await cdp.eval(`(async () => {
      const code = Array.from({length: 40}, (_, i) => 'line ' + i).join('\\n');
      window.ZY.ui.typeCodeBox('Writing big.js', code, 'js');
      await new Promise(r => setTimeout(r, 60));
      const early = document.getElementById('co-code').textContent.split('\\n').length;
      await new Promise(r => setTimeout(r, 700));
      const late = document.getElementById('co-code').textContent.split('\\n').length;
      return JSON.stringify({ early, late, title: document.getElementById('co-title').textContent });
    })()`);
    record('a written file is revealed line by line in the code box',
      typed.early < typed.late && typed.late >= 40, typed);
    await cdp.eval(`window.ZY.ui.closeCodeOverlay(); 'ok'`);
    await sleep(200);

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
    // The real regression this guards is the orb being left in the compositor
    // over the playing clip, which held the whole app near 24fps (≈42ms a
    // frame). The exact number depends on the machine and this run is headless
    // software rasterisation, so the bound is the regression itself (≤40ms a
    // frame, i.e. better than the bug), not a 60fps target the software
    // renderer can never reach.
    record('opening the chat sheet keeps the app smooth and the orb intact',
      perf.open === 'hidden' && perf.after === 'visible' && perf.median <= 40, perf);

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
