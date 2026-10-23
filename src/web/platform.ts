/* ══════════════════════════════════════════════════════════════
   ZYRAXON Platform — where am I actually running?

   The assistant must never guess this. One probe at boot fills
   ZY.Platform with the real OS, the real shell to use, and what the
   host can actually do (files, shell, search, device control). The
   result is injected into the system prompt on every turn so the
   model picks the right commands and the right tools.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const API = () => (global.ZY.store.get('base') || location.origin).replace(/\/$/, '');

  // The model provider. Every build can reach it (CORS is open), so it is the
  // one endpoint that works even when this build has no server of its own.
  const PROVIDER_BASE = 'https://zyraxonai.lovable.app/api/public/zyraxon/v1';
  const PROVIDER = {
    base: PROVIDER_BASE,
    chat: `${PROVIDER_BASE}/chat/completions`,
    models: `${PROVIDER_BASE}/models`,
  };

  /**
   * Does a ZYRAXON server answer on `origin`? The APK loads its pages from
   * `https://appassets.androidplatform.net`, which is a static asset host with
   * no API behind it — so "can I reach a server?" is a real question, not an
   * assumption, and the answer decides whether chat goes through /api/chat or
   * straight to the model provider.
   */
  async function probeServer(origin) {
    const base = String(origin || '').replace(/\/$/, '');
    if (!base) return false;
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 2500);
      const r = await fetch(`${base}/api/health`, { signal: ctrl.signal, cache: 'no-store' });
      clearTimeout(t);
      if (!r.ok) return false;
      const j = await r.json().catch(() => null);
      return !!(j && j.ok);
    } catch { return false; }
  }

  const Platform: any = {
    ready: false,
    data: null,

    /** Native (APK) is authoritative when present, otherwise the host probe. */
    detect() {
      const ua = navigator.userAgent || '';
      const nat = global.ZY.Native && global.ZY.Native.app ? global.ZY.Native : null;
      // The desktop app injects the same bridge as the APK, so the bridge alone
      // cannot say which one this is. The host it reports does: a desktop
      // platform means this is the Windows/macOS/Linux app, not the phone.
      const hostOs = nat && nat.info ? String(nat.info.os || '') : '';
      const isDesktopApp = !!(nat && (nat.info && nat.info.desktop === true));
      const sdk = nat && nat.info ? nat.info.sdk : 0;
      const android = /Android/i.test(ua);
      const ios = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && 'ontouchend' in document);
      const win = /Windows/i.test(ua) || hostOs === 'Windows';
      const mac = (/Macintosh|Mac OS X/i.test(ua) || hostOs === 'macOS') && !ios;
      const linux = (/Linux|X11/i.test(ua) || hostOs === 'Linux') && !android;
      const kind = isDesktopApp ? (win ? 'windows' : mac ? 'macos' : 'linux')
        : nat ? 'android-app' : android ? 'android-browser' : ios ? 'ios' : win ? 'windows' : mac ? 'macos' : linux ? 'linux' : 'unknown';
      const osName = isDesktopApp ? (win ? 'Windows' : mac ? 'macOS' : 'Linux')
        : nat ? 'Android' : android ? 'Android (browser)' : ios ? 'iOS' : win ? 'Windows' : mac ? 'macOS' : linux ? 'Linux' : 'Unknown';
      const shell = win ? 'powershell' : mac ? 'zsh' : 'bash';
      // Where the *server* lives is a different fact from what the user holds:
      // a phone talking to a laptop's server is a phone. Report both.
      const hostIsLocal = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
      const deployment = hostIsLocal ? 'localhost' : 'server';
      const device = isDesktopApp ? 'desktop' : (nat || android || ios) ? 'mobile' : (win || mac || linux) ? 'desktop' : 'unknown';
      return {
        kind, osName, shell, ua, device, deployment, hostIsLocal,
        androidVersion: nat && nat.info && !isDesktopApp ? nat.info.release : '',
        sdk: isDesktopApp ? 0 : sdk,
        isMobile: !isDesktopApp && (android || ios || !!nat),
        isDesktop: isDesktopApp || win || mac || linux,
        nativeBridge: !!nat,
        viewport: { w: global.innerWidth, h: global.innerHeight },
        touch: 'ontouchstart' in global,
        languages: navigator.languages || [navigator.language],
        online: navigator.onLine,
      };
    },

    async init() {
      const local = Platform.detect();
      Platform.data = local;
      // Which API base actually answers? Priority: what the user typed, then
      // the server address baked into the APK (set with `setServerBase`), then
      // this page's own origin. The APK's origin has no API behind it, so the
      // probe below fails there and the app switches to talking to the model
      // provider directly instead of posting into the void.
      const typed = String(global.ZY.store.get('base', '') || '').trim();
      let baked = '';
      try {
        const ns = global.ZY.Native && global.ZY.Native.settings;
        if (ns && ns.serverBase) baked = String(ns.serverBase() || '').trim();
      } catch {}
      const candidate = (typed || baked || location.origin).replace(/\/$/, '');
      const hasServer = await probeServer(candidate);
      Platform.data.hasServer = hasServer;
      Platform.data.apiBase = hasServer ? candidate : '';
      Platform.data.bakedServerBase = baked;
      // Ask the host what it really is (it knows the shell, arch, fs root).
      if (hasServer) {
        try {
          const r = await fetch(`${candidate}/api/platform`);
          const j = await r.json();
          Platform.data = { ...Platform.data, host: j };
          if (!local.isDesktop && j.os) {
            Platform.data.hostShell = j.defaultShell;
          }
        } catch { Platform.data.host = null; }
      } else {
        Platform.data.host = null;
      }
      Platform.ready = true;
      document.documentElement.dataset.platform = Platform.data.kind;
      return Platform.data;
    },

    /**
     * Can this build reach a ZYRAXON server? When it cannot (the APK runs from
     * a static asset host), every server-only feature must fall back rather
     * than fail: chat goes straight to the provider, tools use the native
     * bridge, and speech uses the phone's own engine.
     */
    hasServer() { return !!(Platform.data && Platform.data.hasServer); },

    /** The provider endpoints, shared so no module hardcodes them twice. */
    provider: PROVIDER,

    /** A compact Bangla+technical block for the system prompt. */
    describe() {
      const p = Platform.data || Platform.detect();
      const h = p.host || {};
      const lines = [
        `- এখন আমি চলছি: ${p.osName} (${p.kind})${p.androidVersion ? ', Android ' + p.androidVersion : ''}${p.sdk ? ', SDK ' + p.sdk : ''}`,
        `- ডিভাইস: ${p.device === 'mobile' ? 'মোবাইল/ট্যাব' : p.device === 'desktop' ? 'ল্যাপটপ/ডেস্কটপ' : 'অজানা'}, সার্ভার: ${p.deployment === 'localhost' ? 'লোকাল হোস্ট (এই ডিভাইসেই)' : 'রিমোট সার্ভার'}, টাচ: ${p.touch ? 'আছে' : 'নেই'}, অনলাইন: ${p.online ? 'হ্যাঁ' : 'না'}`,
        `- নেটিভ ব্রিজ (APK): ${p.nativeBridge ? 'চালু — অ্যাপ/স্ক্রিন/ট্যাপ/কল সব সম্ভব' : 'নেই — শুধু ব্রাউজার-সত্য টুল'}`,
      ];
      const nshell = global.ZY.Native && global.ZY.Native.shell && global.ZY.Native.shell.available();
      if (p.nativeBridge) {
        lines.push(`- কমান্ড কোথায় চলবে: ${nshell ? 'ফোনের নিজের Android sandbox-এ (`sh -c`) — সিস্টেম পার্টিশন read-only, /sdcard ও অ্যাপ-ডেটা লেখা যায়' : 'ফোনে কমান্ড চালানোর সাপোর্ট নেই'}`);
        lines.push('- কমান্ড-নিয়ম (মোবাইল): PATH-এ শুধু system/bin আছে; busybox/toybox-র সাধারণ কমান্ড (ls, cat, cp, mv, rm, grep, sed, wget, curl, mkdir, echo) চলে। root-প্রয়োজনীয় কাজ চলবে না — ব্যর্থ হলে সেটা সত্যি বলো, বানিয়ে বলো না।');
      } else {
        lines.push(`- কমান্ড কোথায় চলবে: সার্ভার হোস্ট মেশিনে (${h.osName || 'host'})`);
        lines.push('- কমান্ড-নিয়ম (ডেস্কটপ): Windows-এ PowerShell কমান্ড, macOS/Linux-এ bash/zsh কমান্ড। পাথ সেপারেটর মেনে চলো।');
      }
      if (h.osName) {
        lines.push(`- হোস্ট মেশিন: ${h.osName} ${h.release || ''} ${h.arch || ''}, CPU ${h.cpus || '?'}, RAM ${h.totalMemMB || '?'}MB`);
        lines.push(`- শেল: ডিফল্ট ${h.defaultShell || p.shell}, আছে: ${(h.shells || []).join(', ') || 'অজানা'}`);
        lines.push(`- ফাইল রুট: ${h.fsRoot || '?'} (এই বাইরে লেখা যাবে না)`);
      } else {
        lines.push(`- শেল: ${p.shell}`);
      }
      lines.push('- কমান্ডের গুরুত্ব: কোনো কাজের জন্য নির্দিষ্ট টুল না থাকলে কমান্ডই শেষ ভরসা — তখনও কাজ শেষ করো, থেমে যেয়ো না।');
      return lines.join('\n');
    },
  };

  global.ZY = global.ZY || {};
  global.ZY.Platform = Platform;
})(window);
