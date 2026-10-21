/* ══════════════════════════════════════════════════════════════
   ZYRAXON Desktop preload — injects the same `window.ZYNative` object the
   Android APK exposes, backed by the desktop bridge in main.cjs.

   Every method here mirrors MainActivity's @JavascriptInterface one-for-one,
   including its synchronous shape, so the web app cannot tell the two apart
   and no tool needs a desktop-specific branch.
   ══════════════════════════════════════════════════════════════ */
'use strict';

const { contextBridge, ipcRenderer, webUtils } = require('electron');

// A preload that throws leaves the page with no bridge at all and no clue why,
// so any failure here is reported loudly instead of silently disappearing.
process.on('uncaughtException', (e) => {
  try { console.error('[preload] uncaught:', e && e.stack ? e.stack : e); } catch {}
});

let seq = 0;
/** Synchronous call into the main process. `ipcRenderer.sendSync` returns the
 *  value directly, which is what a @JavascriptInterface method does too.
 *  Arguments always travel as an array so the receiver has one shape to read. */
function call(method, args = []) {
  try {
    const r = ipcRenderer.sendSync('zy:call', { id: ++seq, method, args });
    return r ? r.result : null;
  } catch (e) { return 'CALL-ERR:' + (e && e.message ? e.message : String(e)); }
}

// Events arrive on one channel; deliver them to the dispatcher the page binds
// at boot, exactly like Android's window.ZYNativeCB(kind, payload).
let cb = null;
ipcRenderer.on('zy:event', (_e, msg) => {
  try { if (cb && msg) cb(msg.kind, msg.payload); } catch {}
});

const bridge = {
  platform: () => call('platform'),

  // ── voice out: no native engine — the page's own chain speaks, and Bangla
  //    falls through to the server's TTS. Reporting false is the honest answer.
  ttsLanguages: () => call('ttsLanguages'),
  ttsSetLanguage: () => false,
  ttsSetVoice: () => false,
  ttsSetRate: () => false,
  ttsSetPitch: () => false,
  ttsSpeak: () => 'lang-missing',
  ttsStop: () => true,

  // ── voice in: the page records and runs Whisper itself (see voice.js).
  sttStart: () => false,
  sttStop: () => true,
  sttKeepAlive: (on) => call('sttKeepAlive', [!!on]),

  // ── permissions: granted at the top level, so the in-app sheet is the only
  //    click the user makes.
  permissionState: (n) => call('permissionState', [String(n)]),
  requestPermission: (n) => call('requestPermission', [String(n)]),
  revokePermission: () => false,
  openSystemSettings: (k) => call('openSystemSettings', [String(k)]),
  accessibilityOn: () => true,
  playProtect: () => false,
  overlayAllowed: () => false,
  overlayShow: () => false,
  overlayHide: () => true,
  serverBase: () => call('serverBase'),
  setServerBase: (u) => call('setServerBase', [String(u)]),

  // ── real desktop control ──
  openApp: (t) => call('openApp', [String(t)]),
  resolveApp: (t) => call('resolveApp', [String(t)]),
  launchUrl: (u) => call('launchUrl', [String(u)]),
  tap: (x, y) => call('tap', [x, y]),
  swipe: (x1, y1, x2, y2) => call('swipe', [x1, y1, x2, y2]),
  tapText: (t) => call('tapText', [String(t)]),
  typeText: (t) => call('typeText', [String(t)]),
  scroll: (f) => call('scroll', [!!f]),
  globalAction: (a) => call('globalAction', [String(a)]),
  readScreen: () => call('readScreen'),
  listApps: () => call('listApps'),
  foregroundApp: () => call('foregroundApp'),
  watchScreen: (on, ms) => call('watchScreen', [!!on, Number(ms) || 0]),
  watchingScreen: () => call('watchingScreen'),
  // Asynchronous, like Android: the frame comes back as a 'shot' event.
  screenShot: () => { ipcRenderer.send('zy:shot'); return true; },

  // ── misc ──
  notifyMsg: (t, b) => { ipcRenderer.send('zy:notify', { title: t, body: b }); return true; },
  vibrate: () => false,
  clipboardRead: () => call('clipboardRead'),
  clipboardWrite: (t) => call('clipboardWrite', [String(t)]),
  deviceInfo: () => call('deviceInfo'),

  // ── the real host shell (PowerShell on Windows, sh elsewhere) ──
  shellExec: (cmd, cwd, timeout) => call('shellExec', [String(cmd), cwd || '', Number(timeout) || 0]),

  // ── phone-only: honestly unavailable ──
  callState: () => call('callState'),
  answerCall: () => false,
  endCall: () => false,
  dialNumber: () => false,
  callPermissionsOk: () => false,
  setSpeaker: () => false,
  setCallAutoAnswer: () => false,
};

// The page sets window.ZYNativeCB after reading platform(), so the callback is
// read lazily on every event rather than captured once.
contextBridge.exposeInMainWorld('ZYNative', bridge);

// contextBridge can hand a function across the world boundary, so the page's
// event dispatcher is registered directly — no DOM relay needed. The page calls
// this once at boot with the same function it would give Android.
contextBridge.exposeInMainWorld('ZYDesktop', {
  isDesktop: true,
  versions: { electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node },
  filePath: (f) => { try { return webUtils.getPathForFile(f); } catch { return ''; } },
  bind(fn) { if (typeof fn === 'function') cb = fn; return true; },
});
