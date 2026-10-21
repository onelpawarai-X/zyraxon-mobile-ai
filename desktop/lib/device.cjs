/* ══════════════════════════════════════════════════════════════
   ZYRAXON Desktop device bridge.

   The web app talks to the host through `window.ZYNative`, the same object the
   Android APK injects. Everything above this file — every tool, the screen feed,
   permissions, shell — is therefore unchanged: on Windows this answers with
   PowerShell and Win32, on Linux/macOS with the platform's own tools.

   A capability is only reported when the command that implements it is really
   present. Claiming a tap that never happened is the exact dishonesty the user
   complained about, so screen capture and app resolution fail honestly.
   ══════════════════════════════════════════════════════════════ */
'use strict';

const { execFile, exec, spawnSync } = require('node:child_process');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');

const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';

/** Installed-app list, filled once. */
let appsCache = null;

function runSync(cmd, args, timeout = 8000) {
  const r = spawnSync(cmd, args, { timeout, maxBuffer: 4 * 1024 * 1024, windowsHide: true, encoding: 'utf8' });
  return { ok: !r.error && r.status === 0, out: String(r.stdout || ''), err: String(r.error ? r.error.message : (r.stderr || '')) };
}

function run(cmd, args, timeout = 15000) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout, maxBuffer: 8 * 1024 * 1024, windowsHide: true }, (err, stdout) => {
      resolve({ ok: !err, out: String(stdout || ''), err: err ? String(err.message || err) : '' });
    });
  });
}

function runShell(cmd, timeout = 15000) {
  return new Promise((resolve) => {
    exec(cmd, { timeout, maxBuffer: 8 * 1024 * 1024, windowsHide: true }, (err, stdout) => {
      resolve({ ok: !err, out: String(stdout || ''), err: err ? String(err.message || err) : '' });
    });
  });
}

function which(bin) {
  const dirs = (process.env.PATH || '').split(IS_WIN ? ';' : ':');
  const exts = IS_WIN ? ['.exe', '.cmd', '.bat', ''] : [''];
  for (const d of dirs) {
    for (const e of exts) {
      try { if (fs.existsSync(path.join(d, bin + e))) return path.join(d, bin + e); } catch {}
    }
  }
  return '';
}

/** Run a PowerShell script without ever interpolating user text into it.
 *  Bangla (and any non-ASCII) survives an environment variable but not a
 *  quoted command line, so dynamic values always travel that way. */
function ps(script, timeout = 20000, env = {}) {
  const b64 = Buffer.from(script, 'utf16le').toString('base64');
  return new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand', b64],
      { timeout, maxBuffer: 16 * 1024 * 1024, windowsHide: true, env: { ...process.env, ...env } },
      (err, stdout) => resolve({ ok: !err, out: String(stdout || ''), err: err ? String(err.message || err) : '' }));
  });
}

/* ───────────────────────────── Linux (X11) ───────────────────────────── */
const linux = {
  available: () => !!process.env.DISPLAY && !!which('xdotool'),
  shot: async () => {
    const tool = which('scrot') || which('import');
    if (!tool) return { error: 'scrot or imagemagick missing (sudo apt install scrot)' };
    const file = path.join(os.tmpdir(), `zyraxon-shot-${Date.now()}.png`);
    const r = path.basename(tool) === 'scrot'
      ? await run(tool, ['-o', file])
      : await run(tool, ['-window', 'root', file]);
    if (!r.ok || !fs.existsSync(file)) return { error: r.err || 'capture failed' };
    const b64 = fs.readFileSync(file).toString('base64');
    fs.unlink(file, () => {});
    return { data: b64 };
  },
  tap: async (x, y) => (await run('xdotool', ['mousemove', '--sync', String(x), String(y)])).ok
    && (await run('xdotool', ['click', '1'])).ok,
  swipe: async (x1, y1, x2, y2) => {
    if (!(await run('xdotool', ['mousemove', '--sync', String(x1), String(y1)])).ok) return false;
    if (!(await run('xdotool', ['mousedown', '1'])).ok) return false;
    for (let i = 1; i <= 8; i++) {
      const t = i / 8;
      await run('xdotool', ['mousemove', '--sync', String(Math.round(x1 + (x2 - x1) * t)), String(Math.round(y1 + (y2 - y1) * t))]);
    }
    return (await run('xdotool', ['mouseup', '1'])).ok;
  },
  type: async (t) => (await run('xdotool', ['type', '--clearmodifiers', '--delay', '12', t])).ok,
  key: async (k) => (await run('xdotool', ['key', '--clearmodifiers', k])).ok,
  focus: async () => {
    const r = await runShell('xdotool getactivewindow getwindowname 2>/dev/null');
    const p = await runShell('xdotool getactivewindow getwindowpid 2>/dev/null');
    let label = (r.out || '').trim();
    if (!label && p.out) label = (await runShell(`cat /proc/${p.out.trim()}/comm 2>/dev/null`)).out.trim();
    return { pkg: (p.out || '').trim(), label: label || 'desktop' };
  },
  focusSync: () => {
    const r = runSync('xdotool', ['getactivewindow', 'getwindowpid']);
    const n = runSync('xdotool', ['getactivewindow', 'getwindowname']);
    let label = (n.out || '').trim();
    const pid = (r.out || '').trim();
    if (!label && pid) label = runSync('cat', [`/proc/${pid}/comm`]).out.trim();
    return { pkg: pid, label: label || 'desktop' };
  },
  // Listing is done once, eagerly, so the first call already has the real list.
  // A lazily-filled cache returned an empty array on the first ask, which is
  // indistinguishable from "no apps installed".
  apps: async () => {
    if (appsCache) return appsCache;
    const r = await runShell('ls /usr/share/applications/*.desktop /usr/local/share/applications/*.desktop 2>/dev/null | head -300');
    const list = [];
    for (const f of (r.out || '').split('\n').filter(Boolean)) {
      try {
        const body = fs.readFileSync(f, 'utf8');
        // .desktop files are INI-like: only the main section carries the real
        // Name/Exec, and localised copies (Name[bn]=…) must not be picked up as
        // the app's name or the launcher gets a display label instead of a binary.
        const main = body.split(/\n\[/)[0];
        const name = (main.match(/^Name=(.+)$/m) || [, ''])[1].trim();
        const bin = (main.match(/^Exec=(.+)$/m) || [, ''])[1].trim().replace(/^["']|["']$/g, '').split(' ')[0];
        if (name && bin && !/^(env|sh|bash)$/.test(bin)) list.push({ label: name, package: bin, activity: '' });
      } catch {}
    }
    appsCache = list;
    return list;
  },
  launch: async (target) => {
    const r = await new Promise((resolve) => {
      const child = execFile(target, [], { detached: true, stdio: 'ignore' }, () => {});
      child.on('error', () => resolve({ ok: false }));
      child.unref();
      setTimeout(() => resolve({ ok: true }), 400);
    });
    return r.ok;
  },
};

/* ───────────────────────────── Windows (Win32) ───────────────────────────── */
const PS_SHOT = `
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
$b = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
[Convert]::ToBase64String($ms.ToArray())
`;

const PS_MOUSE = `
Add-Type -AssemblyName System.Windows.Forms
Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,int e);' -Name U -Namespace W
`;

const windows = {
  available: () => IS_WIN,
  shot: async () => {
    const r = await ps(PS_SHOT, 30000);
    const b64 = (r.out || '').trim();
    return b64.length > 200 ? { data: b64 } : { error: r.err || 'capture failed' };
  },
  tap: async (x, y) => {
    const r = await ps(`${PS_MOUSE}
[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(${Math.round(x)},${Math.round(y)})
[W.U]::mouse_event(0x0002,0,0,0,0)
[W.U]::mouse_event(0x0004,0,0,0,0)`, 20000);
    return r.ok;
  },
  swipe: async (x1, y1, x2, y2) => {
    const steps = 10;
    const moves = [];
    for (let i = 0; i <= steps; i++) {
      const t = i / steps;
      moves.push(`[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(${Math.round(x1 + (x2 - x1) * t)},${Math.round(y1 + (y2 - y1) * t)}); Start-Sleep -Milliseconds 16`);
    }
    const r = await ps(`${PS_MOUSE}
[W.U]::mouse_event(0x0002,0,0,0,0)
${moves.join('\n')}
[W.U]::mouse_event(0x0004,0,0,0,0)`, 30000);
    return r.ok;
  },
  type: async (t) => {
    // SendKeys reads +^%~(){}[] as syntax, so every one is brace-escaped.
    const esc = String(t).replace(/([+^%~(){}[\]])/g, '{$1}');
    const r = await ps('Add-Type -AssemblyName System.Windows.Forms\n[System.Windows.Forms.SendKeys]::SendWait($env:ZYRAXON_TYPE)',
      30000, { ZYRAXON_TYPE: esc });
    return r.ok;
  },
  key: async (k) => {
    const map = { back: '{ESC}', home: '{HOME}', enter: '{ENTER}', backspace: '{BS}', recents: '%{TAB}' };
    const seq = map[k] || (/^[A-Za-z0-9]+$/.test(k) ? `{${k.toUpperCase()}}` : '');
    if (!seq) return false;
    const r = await ps('Add-Type -AssemblyName System.Windows.Forms\n[System.Windows.Forms.SendKeys]::SendWait($env:ZYRAXON_KEY)',
      20000, { ZYRAXON_KEY: seq });
    return r.ok;
  },
  focus: async () => {
    const r = await ps(`
Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h,System.Text.StringBuilder t,int c); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);' -Name U -Namespace W
$h=[W.U]::GetForegroundWindow()
$sb=New-Object System.Text.StringBuilder 512
[void][W.U]::GetWindowText($h,$sb,512)
$p=0
[void][W.U]::GetWindowThreadProcessId($h,[ref]$p)
$n=(Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName
Write-Output ($sb.ToString() + '|' + $n)`, 20000);
    const parts = (r.out || '').trim().split('|');
    return { pkg: (parts[1] || '').trim(), label: (parts[0] || '').trim() || 'desktop' };
  },
  focusSync: () => {
    const script = `Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h,System.Text.StringBuilder t,int c); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);' -Name U -Namespace W
$h=[W.U]::GetForegroundWindow()
$sb=New-Object System.Text.StringBuilder 512
[void][W.U]::GetWindowText($h,$sb,512)
$p=0
[void][W.U]::GetWindowThreadProcessId($h,[ref]$p)
$n=(Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName
Write-Output ($sb.ToString() + '|' + $n)`;
    const b64 = Buffer.from(script, 'utf16le').toString('base64');
    const r = runSync('powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand', b64], 8000);
    const parts = (r.out || '').trim().split('|');
    return { pkg: (parts[1] || '').trim(), label: (parts[0] || '').trim() || 'desktop' };
  },
  apps: async () => {
    if (appsCache) return appsCache;
    const r = await ps('Get-StartApps | ForEach-Object { $_.Name + "|" + $_.AppID }', 30000);
    appsCache = (r.out || '').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
      const i = l.lastIndexOf('|');
      return { label: l.slice(0, i).trim(), package: l.slice(i + 1).trim(), activity: '' };
    }).filter((a) => a.package);
    return appsCache;
  },
  launch: async (target) => {
    // A UWP AppID goes through the shell: protocol; a plain path is started
    // directly. Getting this wrong is why "open the app" would do nothing.
    const isId = /^[A-Za-z0-9_.-]+![A-Za-z0-9_.-]+$/.test(target);
    const r = isId
      ? await runShell(`explorer.exe shell:appsFolder\\${target}`, 20000)
      : await runShell(`Start-Process "${String(target).replace(/"/g, '')}"`, 20000);
    return r.ok;
  },
};

/* ───────────────────────────── macOS ───────────────────────────── */
const mac = {
  available: () => IS_MAC,
  shot: async () => {
    const file = path.join(os.tmpdir(), `zyraxon-shot-${Date.now()}.png`);
    const r = await run('screencapture', ['-x', file]);
    if (!r.ok || !fs.existsSync(file)) return { error: r.err || 'capture failed' };
    const b64 = fs.readFileSync(file).toString('base64');
    fs.unlink(file, () => {});
    return { data: b64 };
  },
  tap: async (x, y) => (await runShell(`osascript -e 'tell application "System Events" to click at {${Math.round(x)}, ${Math.round(y)}}'`)).ok,
  swipe: async () => false,
  type: async (t) => {
    const r = await runShell(`osascript -e 'tell application "System Events" to keystroke (system attribute "ZYRAXON_TYPE")'`, 20000);
    return r.ok;
  },
  key: async (k) => (await runShell(`osascript -e 'tell application "System Events" to key code ${k === 'back' ? 53 : 36}'`)).ok,
  focus: async () => {
    const r = await runShell('osascript -e \'tell application "System Events" to get name of first application process whose frontmost is true\'');
    return { pkg: '', label: (r.out || '').trim() || 'desktop' };
  },
  focusSync: () => {
    const r = runSync('osascript', ['-e', 'tell application "System Events" to get name of first application process whose frontmost is true']);
    return { pkg: '', label: (r.out || '').trim() || 'desktop' };
  },
  apps: async () => {
    if (appsCache) return appsCache;
    const r = await runShell('ls /Applications | sed "s/.app$//"');
    appsCache = (r.out || '').split('\n').map((s) => s.trim()).filter(Boolean).map((label) => ({ label, package: label, activity: '' }));
    return appsCache;
  },
  launch: async (t) => (await runShell(`open -a "${String(t).replace(/"/g, '')}"`)).ok,
};

const backend = IS_WIN ? windows : IS_MAC ? mac : linux;

// Warm the app list immediately: the model asks for it at unpredictable times
// and an empty first answer reads as "nothing is installed".
setTimeout(() => { backend.apps().catch(() => {}); }, 0);

module.exports = { backend, which, runShell, IS_WIN, IS_MAC };
