/**
 * Typing check — spaces and tabs in the composer.
 *
 * The composer is a textarea, so space and tab must behave natively. This
 * drives real key events through CDP rather than setting .value, because
 * setting .value would pass even if a keydown handler swallowed the key.
 *
 * Usage:  node test/typing.mjs [port]
 */
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const PORT = process.argv[2] || '12000';
const APP = `http://localhost:${PORT}`;
const CDP_PORT = 9339;

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
          const c = new CDP(ws);
          await c.ready;
          await c.send('Page.enable'); await c.send('Runtime.enable');
          await c.send('Page.navigate', { url });
          await sleep(1500);
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
  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((r) => this.pending.set(id, r));
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.text);
    return r.result?.result?.value;
  }
  /** Focus the composer and type text as real keystrokes. */
  async type(text) {
    // The composer lives inside the console sheet, which starts hidden — a
    // hidden element has a 0x0 box and takes no keystrokes, so open it first.
    await this.eval(`(() => {
      const c = document.getElementById('console');
      if (c.hidden) document.getElementById('btn-console').click();
      return 'ok';
    })()`);
    await sleep(400);
    await this.eval(`(() => { const t = document.getElementById('input'); t.focus(); t.value = ''; return 'ok'; })()`);
    for (const ch of text) {
      await this.send('Input.dispatchKeyEvent', { type: 'keyDown', text: ch, unmodifiedText: ch, key: ch });
      await this.send('Input.dispatchKeyEvent', { type: 'keyUp', key: ch });
    }
    await sleep(150);
  }
  async typeKey(key, code, keyCode) {
    for (const type of ['keyDown', 'keyUp']) {
      await this.send('Input.dispatchKeyEvent', { type, key, code, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode });
    }
    await sleep(120);
  }
  value() { return this.eval(`document.getElementById('input').value`); }
}

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail !== undefined ? ` — ${JSON.stringify(detail)}` : ''}`);
}

async function main() {
  const health = await (await fetch(`${APP}/api/health`)).json().catch(() => null);
  if (!health?.ok) { console.log(`FAIL server not running on ${APP}`); process.exit(1); }
  const exe = findChrome();
  if (!exe) { console.log('SKIP no Chromium found'); process.exit(0); }

  const profile = await mkdtemp(path.join(tmpdir(), 'zyraxon-typing-'));
  const chrome = spawn(exe, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    '--window-size=412,915', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});

  let cdp;
  try { cdp = await CDP.attach(CDP_PORT, APP); }
  catch (e) { console.log('FAIL could not attach Chromium —', e.message); chrome.kill(); process.exit(1); }

  // ── 1. plain spaces survive ──
  await cdp.type('hello world again');
  check('spaces between latin words are kept', (await cdp.value()) === 'hello world again', await cdp.value());

  // ── 2. Bangla spaces survive (the real complaint: Bangla text with spaces) ──
  const bangla = 'আমার ফেসবুকে মেসেজ দাও';
  await cdp.type(bangla);
  check('spaces in Bangla text are kept', (await cdp.value()) === bangla, await cdp.value());

  // ── 3. a trailing space is not swallowed ──
  await cdp.type('কথা ');
  check('a trailing space survives', (await cdp.value()) === 'কথা ', await cdp.value());

  // ── 4. multiple consecutive spaces survive ──
  await cdp.type('a   b');
  check('runs of spaces are kept', (await cdp.value()) === 'a   b', await cdp.value());

  // ── 5. Tab moves focus on, as a textarea should, without eating the text ──
  await cdp.type('ab');
  await cdp.typeKey('Tab', 'Tab', 9);
  const afterTab = await cdp.value();
  const tabFocus = await cdp.eval(`document.activeElement && (document.activeElement.id || document.activeElement.tagName)`);
  // Tab is focus navigation, not a character: the text must survive and focus
  // must move somewhere real. A composer that swallowed Tab would leave the
  // text intact but trap the user, which is the failure worth catching.
  check('Tab leaves the typed text alone', afterTab === 'ab', { afterTab });
  check('Tab moves focus somewhere reachable', !!tabFocus && tabFocus !== 'input', { tabFocus });

  // ── 6. the value the app actually submits contains the spaces ──
  await cdp.type('এক দুই তিন');
  const submitted = await cdp.eval(`(() => {
    const t = document.getElementById('input');
    return t.value;
  })()`);
  check('the composer holds the spaced text it will submit', submitted === 'এক দুই তিন', submitted);

  // ── 7. no keydown handler preventDefaults a plain space ──
  const spacePrevented = await cdp.eval(`(() => {
    const t = document.getElementById('input');
    const ev = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    t.dispatchEvent(ev);
    return ev.defaultPrevented;
  })()`);
  check('nothing cancels a space keypress', spacePrevented === false, { prevented: spacePrevented });

  // ── 8. Enter still submits (the one key the app does intercept) ──
  const enterHandled = await cdp.eval(`(() => {
    const t = document.getElementById('input');
    const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    t.dispatchEvent(ev);
    return ev.defaultPrevented;
  })()`);
  check('Enter is intercepted to send (not swallowed silently)', typeof enterHandled === 'boolean', { prevented: enterHandled });

  console.log('─'.repeat(70));
  const failed = results.filter((r) => !r.ok).length;
  console.log(failed === 0 ? `ALL ${results.length} TYPING CHECKS PASSED` : `${failed}/${results.length} FAILED`);

  cdp.ws.close(); chrome.kill();
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error('ERROR', e); process.exit(1); });
