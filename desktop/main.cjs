/* ══════════════════════════════════════════════════════════════
   ZYRAXON Desktop — the Windows / macOS / Linux app.

   The same web app the APK runs is loaded here, and the same
   `window.ZYNative` bridge is injected (see preload.cjs). That is the whole
   trick: the assistant, its tools, the screen feed and the voice loop are
   unchanged, but on Windows they now drive real Win32 calls instead of
   Android's accessibility service.

   The local server is started as a child process so there is nothing to
   install and no second terminal: launching the app is enough.
   ══════════════════════════════════════════════════════════════ */
'use strict';

const { app, BrowserWindow, ipcMain, session, shell, clipboard, Notification, systemPreferences, desktopCapturer } = require('electron');
const { spawn, spawnSync } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const { backend, which, runShell, IS_WIN, IS_MAC } = require('./lib/device.cjs');

// Where the server and its public/ folder live. Electron runs the desktop code
// from inside app.asar once installed, and asar is read-only — the server has to
// be unpacked, so electron-builder places it under resources/ outside the asar.
// In development the repo root is the same place and this resolves identically.
const APP_ROOT = app.isPackaged
  ? path.join(process.resourcesPath)
  : path.resolve(__dirname, '..');
const ROOT = APP_ROOT;
const SERVER = path.join(APP_ROOT, 'server', 'server.mjs');

// The server writes caches and published sites next to itself, which is read-only
// once installed (AppImage, /Applications). Point it at the user's data folder
// instead, so the app works the same installed as it does from a checkout.
function userDataDir() {
  try { return app.getPath('userData'); } catch { return os.tmpdir(); }
}

// Software rendering by default: the app must work on VMs and machines without
// a usable GPU, where Chromium's GPU process fails and the window stays black.
app.disableHardwareAcceleration();

let win = null;
let serverProc = null;
let serverPort = 0;
const store = { base: '', serverBase: '', sttKeepAlive: false, watch: false, watchTimer: null, watchMs: 1200, lastPkg: '' };

/* ───────────────────────────── server ───────────────────────────── */

function freePort() {
  return new Promise((resolve) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', () => resolve(12000));
  });
}

function startServer(port) {
  serverProc = spawn(process.execPath, [SERVER], {
    cwd: ROOT,
    env: {
      ...process.env,
      PORT: String(port),
      ELECTRON_RUN_AS_NODE: '1',
      ZYRAXON_ROOT: APP_ROOT,
      ZYRAXON_DATA_DIR: userDataDir(),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  serverProc.stdout.on('data', (d) => process.stdout.write('[server] ' + d));
  serverProc.stderr.on('data', (d) => process.stderr.write('[server] ' + d));
  serverProc.on('exit', (code) => { if (code && code !== 0) console.error('server exited', code); });
}

async function waitForServer(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`);
      if (r.ok) return true;
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  return false;
}

/* ───────────────────────── device capabilities ───────────────────────── */

function platformJson() {
  return JSON.stringify({
    app: true,
    sdk: 30,                       // the screen feed needs API 30 semantics
    release: os.release(),
    model: `${os.type()} ${os.arch()}`,
    // Voice out goes through the renderer's own chain (Web Speech, then the
    // server's Bangla TTS); voice in through the in-page Whisper model. Both
    // already work, so no native engine is claimed here.
    tts: false,
    stt: false,
    accessibility: true,           // the desktop bridge is always live
    desktop: true,
    os: IS_WIN ? 'Windows' : IS_MAC ? 'macOS' : 'Linux',
  });
}

function emit(kind, obj) {
  if (win && !win.isDestroyed()) win.webContents.send('zy:event', { kind, payload: JSON.stringify(obj) });
}

async function captureScreen() {
  const r = await backend.shot();
  return r && r.data ? r.data : null;
}

/** The background watch: frames plus "where did the user go". Nothing is shown
 *  and nothing is saved — exactly like the phone's silent screen feed. */
function startWatch(everyMs) {
  stopWatch();
  store.watchMs = Math.max(600, Number(everyMs) || 1200);
  store.watch = true;
  const tick = async () => {
    if (!store.watch) return;
    const fg = await backend.focus().catch(() => null);
    if (fg && fg.pkg && fg.pkg !== store.lastPkg) {
      store.lastPkg = fg.pkg;
      emit('screen', { state: 'changed', text: `${fg.pkg}\u0001${fg.label}` });
    }
    const b64 = await captureScreen().catch(() => null);
    if (b64) emit('shot', { state: 'bg', text: b64 });
    store.watchTimer = setTimeout(tick, store.watchMs);
  };
  tick();
  return true;
}

function stopWatch() {
  store.watch = false;
  if (store.watchTimer) { clearTimeout(store.watchTimer); store.watchTimer = null; }
  return false;
}

/* ───────────────────────── the sync bridge ───────────────────────── */

function syncExec(command, cwd, timeoutMs) {
  const isWin = IS_WIN;
  const opts = {
    cwd: cwd && fs.existsSync(cwd) ? cwd : ROOT,
    timeout: Number(timeoutMs) || 20000,
    maxBuffer: 8 * 1024 * 1024,
    windowsHide: true,
    encoding: 'utf8',
  };
  const res = isWin
    ? spawnSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', command], opts)
    : spawnSync('/bin/sh', ['-c', command], opts);
  return JSON.stringify({
    code: res.status === null ? -1 : res.status,
    stdout: String(res.stdout || ''),
    stderr: String(res.stderr || '') || (res.error ? String(res.error.message) : ''),
  });
}

/** One synchronous entry point for every method the web app calls without
 *  awaiting a promise (the Android interface is synchronous, and the bridge is
 *  shaped to match it). Long operations — screenshots, speech — are events
 *  instead, exactly as on Android. */
function handleCall(method, args) {
  switch (method) {
    case 'platform': return platformJson();

    case 'resolveApp': {
      const q = String(args || '').trim();
      if (!q) return JSON.stringify({ found: false, package: '', label: '' });
      const apps = backendAppsCache();
      const lower = q.toLowerCase();
      const hit = apps.find((a) => a.package.toLowerCase() === lower)
        || apps.find((a) => a.label.toLowerCase() === lower)
        || apps.find((a) => a.label.toLowerCase().includes(lower))
        || apps.find((a) => a.package.toLowerCase().includes(lower));
      if (hit) return JSON.stringify({ found: true, package: hit.package, label: hit.label });
      // A real file or binary is openable even if it is not in the app list.
      if (which(q) || fs.existsSync(q)) return JSON.stringify({ found: true, package: q, label: path.basename(q) });
      return JSON.stringify({ found: false, package: q, label: '' });
    }
    case 'openApp': {
      backend.launch(String(args || '')).catch(() => {});
      return true;
    }
    case 'launchUrl': {
      const url = String(args || '');
      if (!/^https?:\/\//i.test(url)) return false;
      shell.openExternal(url).catch(() => {});
      return true;
    }
    case 'tap': return backend.tap(args[0], args[1]);
    case 'swipe': return backend.swipe(args[0], args[1], args[2], args[3]);
    case 'tapText': return false;      // no accessibility tree on the desktop
    case 'typeText': return backend.type(String(args || ''));
    case 'scroll': return backend.key(args ? 'pagedown' : 'pageup');
    case 'globalAction': {
      const map = { back: 'escape', home: 'home', recents: 'alt+tab', enter: 'Return' };
      return backend.key(map[String(args || '').toLowerCase()] || String(args || ''));
    }
    case 'readScreen': return JSON.stringify([]);   // no text tree: use a screenshot
    case 'listApps': return JSON.stringify(backendAppsCache());

    case 'foregroundApp': {
      const fg = backend.focusSync ? backend.focusSync() : { pkg: store.lastPkg, label: '' };
      return JSON.stringify({ pkg: fg.pkg || '', label: fg.label || '', since: Date.now() });
    }
    case 'watchScreen': return args ? startWatch(args) : stopWatch();
    case 'watchingScreen': return store.watch;

    case 'permissionState': return JSON.stringify({ granted: permState(String(args || '')) });
    case 'requestPermission': {
      const name = String(args || '');
      emit('perm', { state: 'granted', text: name });
      return true;
    }
    case 'revokePermission': return false;
    case 'openSystemSettings': {
      const kind = String(args || '');
      const uri = IS_WIN ? ({
        microphone: 'ms-settings:privacy-microphone',
        camera: 'ms-settings:privacy-webcam',
        notifications: 'ms-settings:notifications',
        accessibility: 'ms-settings:easeofaccess',
        'write-settings': 'ms-settings:appsfeatures',
        playprotect: 'ms-settings:windowsdefender',
        battery: 'ms-settings:batterymanagement',
        apps: 'ms-settings:appsfeatures',
      }[kind] || 'ms-settings:')
        : IS_MAC ? 'x-apple.systempreferences:com.apple.preference.security' : '';
      if (uri) shell.openExternal(uri).catch(() => {});
      else if (!IS_WIN && !IS_MAC) runShell('gnome-control-center sound || xdg-open /').catch(() => {});
      return true;
    }
    case 'accessibilityOn': return true;
    case 'playProtect': return false;         // Windows has no Play Protect
    case 'overlayAllowed': return false;
    case 'overlayShow': return false;
    case 'overlayHide': return true;

    case 'serverBase': return store.serverBase || store.base;
    case 'setServerBase': store.serverBase = String(args || ''); return true;

    case 'ttsLanguages': return JSON.stringify([]);
    case 'ttsSetLanguage': return false;
    case 'ttsSpeak': return 'lang-missing';   // hand Bangla to the server's TTS
    case 'ttsStop': return true;

    case 'sttStart': return false;
    case 'sttStop': return true;
    case 'sttKeepAlive': store.sttKeepAlive = !!args; return true;

    case 'shellExec': return syncExec(args[0], args[1], args[2]);
    case 'clipboardRead': return clipboard.readText();
    case 'clipboardWrite': clipboard.writeText(String(args || '')); return true;
    case 'deviceInfo': return JSON.stringify({
      os: IS_WIN ? 'Windows' : IS_MAC ? 'macOS' : 'Linux',
      release: os.release(), arch: os.arch(), host: os.hostname(),
      cpus: os.cpus().length, totalMemMB: Math.round(os.totalmem() / 1048576),
      electron: process.versions.electron, chrome: process.versions.chrome,
    });
    case 'notifyMsg': {
      try { new Notification({ title: String(args[0] || 'ZYRAXON'), body: String(args[1] || '') }).show(); } catch {}
      return true;
    }
    case 'vibrate': return false;
    case 'callState': return JSON.stringify({ inCall: false, ringing: false });
    case 'answerCall': return false;
    case 'endCall': return false;
    case 'dialNumber': return false;
    case 'callPermissionsOk': return false;
    case 'setSpeaker': return false;
    case 'setCallAutoAnswer': return false;
    default: return null;
  }
}

function permState(name) {
  // Windows and Linux have no OS-level gate the app can query; the request
  // handler below already grants media, so the honest answer is "granted".
  if (IS_MAC) {
    try {
      if (name === 'microphone') return systemPreferences.getMediaAccessStatus('microphone') === 'granted';
      if (name === 'camera') return systemPreferences.getMediaAccessStatus('camera') === 'granted';
    } catch {}
  }
  return true;
}

let appCache = null;
/** The installed-app list, synchronously, for the sendSync IPC path.
 *  The real list is warmed at startup, so this normally returns it immediately
 *  instead of the empty array a lazily-filled cache used to hand back. */
function backendAppsCache() {
  return Array.isArray(appCache) ? appCache : [];
}

/** Keep `appCache` current in the background. */
function warmApps() {
  backend.apps().then((list) => { appCache = Array.isArray(list) ? list : []; }).catch(() => {});
}
setTimeout(warmApps, 0);

/* ───────────────────────────── the window ───────────────────────────── */

async function createWindow() {
  serverPort = await freePort();
  store.base = `http://127.0.0.1:${serverPort}`;
  startServer(serverPort);
  const up = await waitForServer(serverPort);

  win = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 420,
    minHeight: 560,
    backgroundColor: '#05060f',
    title: 'ZYRAXON',
    icon: path.join(ROOT, 'public', 'assets', 'icon.svg'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      // The screen watch and the voice loop keep running while the window is
      // not focused — that is what "always on" means here.
      backgroundThrottling: false,
    },
  });

  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url).catch(() => {}); return { action: 'deny' }; });

  // A page that silently never loads is the hardest desktop failure to see, so
  // every outcome is logged rather than left to guesswork.
  win.webContents.on('did-finish-load', () => console.log('[desktop] page loaded'));
  win.webContents.on('did-fail-load', (_e, code, desc, url) => console.error(`[desktop] page failed to load (${code} ${desc}) ${url}`));
  win.webContents.on('render-process-gone', (_e, d) => console.error('[desktop] renderer gone:', JSON.stringify(d)));

  if (!up) {
    win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
      '<body style="background:#05060f;color:#ff6b8a;font-family:sans-serif;padding:40px">'
      + '<h2>সার্ভার চালু হয়নি</h2><p>server/server.mjs চালু করা যায়নি। Node.js আছে কিনা দেখো।</p></body>'));
    return;
  }

  // Permissions are granted at the top level, so the in-app sheet is the only
  // thing the user ever has to click — never an OS dialog inside a hidden page.
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(true));
  session.defaultSession.setPermissionCheckHandler(() => true);

  // getDisplayMedia (the "look at my screen" path) picks the primary screen
  // itself; without this Electron opens a picker every time.
  try {
    session.defaultSession.setDisplayMediaRequestHandler((request, cb) => {
      desktopCapturer.getSources({ types: ['screen'] }).then((sources) => {
        cb({ video: sources[0], audio: 'loopback' });
      }).catch(() => cb({}));
    }, { useSystemPicker: false });
  } catch {}

  await win.loadURL(store.base);

  // A headless self-test that drives the real window and reports what actually
  // happened — the honest way to know the desktop app works.
  if (process.env.ZYRAXON_SELFTEST) {
    console.log('[selftest] window loaded, starting checks');
    try {
      const { run } = require('./lib/selftest.cjs');
      const ok = await run(win, app);
      process.exitCode = ok ? 0 : 1;
      setTimeout(() => app.quit(), 500);
    } catch (e) {
      console.error('[selftest] crashed:', e && e.stack ? e.stack : e);
      process.exitCode = 1;
      setTimeout(() => app.quit(), 500);
    }
  }
}

/* ───────────────────────────── IPC ───────────────────────────── */

ipcMain.on('zy:call', (e, msg) => {
  const { id, method, args } = msg || {};
  let result = null;
  try { result = handleCall(method, args); } catch (err) { result = null; }
  e.returnValue = { id, result };
});

ipcMain.on('zy:shot', async () => {
  const b64 = await captureScreen().catch(() => null);
  emit('shot', b64 ? { state: 'ok', text: b64 } : { state: 'error', text: 'capture-failed' });
});

ipcMain.on('zy:tts', (e, text) => { void text; });
ipcMain.on('zy:stt', (e, on) => { void on; });
ipcMain.on('zy:notify', (e, msg) => {
  try { new Notification({ title: msg.title || 'ZYRAXON', body: msg.body || '' }).show(); } catch {}
});

/* ───────────────────────────── lifecycle ───────────────────────────── */

const single = app.requestSingleInstanceLock();
if (!single) {
  app.quit();
} else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

  app.whenReady().then(createWindow);

  app.on('window-all-closed', () => {
    stopWatch();
    if (serverProc) { try { serverProc.kill(); } catch {} }
    if (!IS_MAC) app.quit();
  });

  app.on('before-quit', () => {
    stopWatch();
    if (serverProc) { try { serverProc.kill(); } catch {} }
  });
}
