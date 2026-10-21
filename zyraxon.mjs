#!/usr/bin/env node
/**
 * ZYRAXON — one file to run on Windows, macOS and Linux.
 *
 *     node zyraxon.mjs
 *
 * There is nothing to install and nothing to compile. The app is a local
 * server, so this starts it and opens it in a Chrome or Edge window — the only
 * browsers that can recognise speech — where the microphone prompt appears on
 * the first click and everything else (chat, tools, tasks, publishing) runs
 * against the local machine with full access.
 *
 * Flags:
 *     --port 13000   start on a specific port
 *     --no-open      start headless (services, containers, tests)
 *     --browser      also print which browser will be used, then exit
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, 'server', 'server.mjs');

/** Ports to try, in order, when the preferred one is taken. */
const PORTS = [12000, 12001, 12002, 13000, 13001, 13002, 14000, 8080];

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const value = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

/* ── a browser that can hear the user ───────────────────────────────────
   Only the branded Chrome and Edge builds ship the speech service behind
   webkitSpeechRecognition. An open-source Chromium exposes the API and then
   fails with "network", so it is listed last, after the real ones. */
function findBrowsers() {
  const list = [];
  const add = (p) => { if (p && !list.includes(p)) list.push(p); };
  const exists = (p) => { try { return !!p && fs.existsSync(p); } catch { return false; } };

  if (process.platform === 'win32') {
    const pf = process.env['ProgramFiles'] || 'C:\\Program Files';
    const pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
    const local = process.env['LOCALAPPDATA'] || path.join(os.homedir(), 'AppData', 'Local');
    add(path.join(pf, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    add(path.join(pf86, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    add(path.join(local, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    add(path.join(pf86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
    add(path.join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
    add(path.join(pf, 'Chromium', 'Application', 'chrome.exe'));
  } else if (process.platform === 'darwin') {
    const apps = '/Applications';
    add(path.join(apps, 'Google Chrome.app', 'Contents', 'MacOS', 'Google Chrome'));
    add(path.join(apps, 'Microsoft Edge.app', 'Contents', 'MacOS', 'Microsoft Edge'));
    add(path.join(apps, 'Brave Browser.app', 'Contents', 'MacOS', 'Brave Browser'));
    add(path.join(apps, 'Chromium.app', 'Contents', 'MacOS', 'Chromium'));
    add(path.join(os.homedir(), 'Applications', 'Google Chrome.app', 'Contents', 'MacOS', 'Google Chrome'));
  } else {
    for (const p of ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
      '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable', '/usr/bin/brave-browser',
      '/opt/google/chrome/chrome', '/snap/bin/chromium',
      '/usr/bin/chromium', '/usr/lib/chromium/chromium']) add(p);
  }
  return list.filter(exists);
}

const BROWSERS = findBrowsers();
/**
 * True when the browser carries Google's speech service.
 *
 * The branded builds do; a plain Chromium exposes the API and then fails with
 * "network", so it must not be preferred for the app window.
 */
const speaks = (p) => {
  const n = path.basename(p).toLowerCase();
  if (n.startsWith('chromium')) return false;
  return /(chrome|msedge|edge|brave)/.test(n);
};

/* ── port ───────────────────────────────────────────────────────────── */
function free(port) {
  return new Promise((resolve) => {
    const s = createServer();
    s.once('error', () => resolve(false));
    s.once('listening', () => s.close(() => resolve(true)));
    s.listen(port, '0.0.0.0');
  });
}

function ourServer(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/api/health', timeout: 1500 }, (res) => {
      let b = '';
      res.on('data', (c) => { b += c; });
      res.on('end', () => { try { resolve(JSON.parse(b).ok === true); } catch { resolve(false); } });
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

/** Open the app window. Chrome/Edge get an app window with the mic allowed. */
function open(target) {
  const exe = BROWSERS.find(speaks) || BROWSERS[0];
  try {
    if (exe) {
      const profile = path.join(HERE, 'data', 'browser-profile');
      try { fs.mkdirSync(profile, { recursive: true }); } catch {}
      const child = spawn(exe, [
        `--app=${target}`, `--user-data-dir=${profile}`,
        '--no-first-run', '--no-default-browser-check',
        '--use-fake-ui-for-media-stream',
      ], { detached: true, stdio: 'ignore' });
      child.unref();
      return exe;
    }
    const cmd = process.platform === 'win32' ? 'cmd'
      : process.platform === 'darwin' ? 'open' : 'xdg-open';
    const args = process.platform === 'win32' ? ['/c', 'start', '', target] : [target];
    const child = spawn(cmd, args, { detached: true, stdio: 'ignore' });
    child.unref();
  } catch { /* no browser on a headless machine — not an error */ }
  return null;
}

async function main() {
  if (flag('--browser')) {
    console.log(BROWSERS.length ? BROWSERS.join('\n') : 'none found');
    return 0;
  }

  if (!fs.existsSync(SERVER)) {
    console.error(`Cannot find ${SERVER} — run this file from the project folder.`);
    return 1;
  }

  // The ports the app prefers, in order. 12000 is the one it has always used;
  // the rest are tried in turn so a second copy — or anything else already on
  // 12000 — never stops the app from starting. --port/`PORT` puts a port first
  // instead of replacing the list.
  const want = Number(value('--port', process.env.PORT || PORTS[0]));
  const chain = [want, ...PORTS.filter((p) => p !== want)];
  let port = 0;
  for (const p of chain) {
    if (await free(p)) { port = p; break; }
    if (await ourServer(p)) {
      console.log(`ZYRAXON is already running on http://localhost:${p} — opening it.`);
      if (!flag('--no-open')) open(`http://localhost:${p}`);
      return 0;
    }
    console.log(`Port ${p} is busy — trying the next one.`);
  }
  if (!port) {
    console.error(`None of these ports is free: ${chain.join(', ')}.`
      + '\nStart it on any other port:  node zyraxon.mjs --port 13500');
    return 1;
  }

  const node = process.execPath;
  const child = spawn(node, [SERVER], {
    cwd: HERE,
    env: { ...process.env, PORT: String(port), ZYRAXON_NO_OPEN: flag('--no-open') ? '1' : '' },
    stdio: 'inherit',
  });

  // Wait for the server to answer, then open the window ourselves so the user
  // gets one clear line about what happened.
  const deadline = Date.now() + 30000;
  let up = false;
  while (Date.now() < deadline && child.exitCode === null) {
    if (await ourServer(port)) { up = true; break; }
    await new Promise((r) => setTimeout(r, 300));
  }

  if (!up) {
    console.error('The server did not start. See the messages above.');
    try { child.kill(); } catch {}
    return 1;
  }

  if (!flag('--no-open')) {
    const used = open(`http://localhost:${port}`);
    if (used) console.log(`\nZYRAXON is open in ${path.basename(used)} → http://localhost:${port}`);
    else {
      console.log(`\nZYRAXON is running at http://localhost:${port}`);
      console.log('No Chrome or Edge was found. Speech needs one of them; everything else works in any browser.');
    }
    console.log(`Local address:  http://localhost:${port}`);
    console.log(`On this machine only — nothing is uploaded anywhere.`);
  } else {
    console.log(`\nZYRAXON is running at http://localhost:${port} (headless)`);
  }

  const bye = () => { try { child.kill(); } catch {} };
  process.on('SIGINT', () => { bye(); process.exit(0); });
  process.on('SIGTERM', () => { bye(); process.exit(0); });
  child.on('exit', (code) => process.exit(code === null ? 0 : code));
  return new Promise(() => {});
}

main().then((code) => { if (typeof code === 'number') process.exit(code); })
  .catch((e) => { console.error('ZYRAXON failed to start:', e && e.message ? e.message : e); process.exit(1); });
