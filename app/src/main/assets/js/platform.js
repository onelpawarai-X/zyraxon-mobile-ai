(() => {
  (function(global) {
    "use strict";
    const API = () => (global.ZY.store.get("base") || location.origin).replace(/\/$/, "");
    const PROVIDER_BASE = "https://zyraxonai.lovable.app/api/public/zyraxon/v1";
    const PROVIDER = {
      base: PROVIDER_BASE,
      chat: `${PROVIDER_BASE}/chat/completions`,
      models: `${PROVIDER_BASE}/models`
    };
    async function probeServer(origin) {
      const base = String(origin || "").replace(/\/$/, "");
      if (!base) return false;
      try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 2500);
        const r = await fetch(`${base}/api/health`, { signal: ctrl.signal, cache: "no-store" });
        clearTimeout(t);
        if (!r.ok) return false;
        const j = await r.json().catch(() => null);
        return !!(j && j.ok);
      } catch {
        return false;
      }
    }
    const Platform = {
      ready: false,
      data: null,
      /** Native (APK) is authoritative when present, otherwise the host probe. */
      detect() {
        const ua = navigator.userAgent || "";
        const nat = global.ZY.Native && global.ZY.Native.app ? global.ZY.Native : null;
        const hostOs = nat && nat.info ? String(nat.info.os || "") : "";
        const isDesktopApp = !!(nat && (nat.info && nat.info.desktop === true));
        const sdk = nat && nat.info ? nat.info.sdk : 0;
        const android = /Android/i.test(ua);
        const ios = /iPhone|iPad|iPod/i.test(ua) || /Macintosh/.test(ua) && "ontouchend" in document;
        const win = /Windows/i.test(ua) || hostOs === "Windows";
        const mac = (/Macintosh|Mac OS X/i.test(ua) || hostOs === "macOS") && !ios;
        const linux = (/Linux|X11/i.test(ua) || hostOs === "Linux") && !android;
        const kind = isDesktopApp ? win ? "windows" : mac ? "macos" : "linux" : nat ? "android-app" : android ? "android-browser" : ios ? "ios" : win ? "windows" : mac ? "macos" : linux ? "linux" : "unknown";
        const osName = isDesktopApp ? win ? "Windows" : mac ? "macOS" : "Linux" : nat ? "Android" : android ? "Android (browser)" : ios ? "iOS" : win ? "Windows" : mac ? "macOS" : linux ? "Linux" : "Unknown";
        const shell = win ? "powershell" : mac ? "zsh" : "bash";
        const hostIsLocal = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
        const deployment = hostIsLocal ? "localhost" : "server";
        const device = isDesktopApp ? "desktop" : nat || android || ios ? "mobile" : win || mac || linux ? "desktop" : "unknown";
        return {
          kind,
          osName,
          shell,
          ua,
          device,
          deployment,
          hostIsLocal,
          androidVersion: nat && nat.info && !isDesktopApp ? nat.info.release : "",
          sdk: isDesktopApp ? 0 : sdk,
          isMobile: !isDesktopApp && (android || ios || !!nat),
          isDesktop: isDesktopApp || win || mac || linux,
          nativeBridge: !!nat,
          viewport: { w: global.innerWidth, h: global.innerHeight },
          touch: "ontouchstart" in global,
          languages: navigator.languages || [navigator.language],
          online: navigator.onLine
        };
      },
      async init() {
        const local = Platform.detect();
        Platform.data = local;
        const typed = String(global.ZY.store.get("base", "") || "").trim();
        let baked = "";
        try {
          const ns = global.ZY.Native && global.ZY.Native.settings;
          if (ns && ns.serverBase) baked = String(ns.serverBase() || "").trim();
        } catch {
        }
        const candidate = (typed || baked || location.origin).replace(/\/$/, "");
        const hasServer = await probeServer(candidate);
        Platform.data.hasServer = hasServer;
        Platform.data.apiBase = hasServer ? candidate : "";
        Platform.data.bakedServerBase = baked;
        if (hasServer) {
          try {
            const r = await fetch(`${candidate}/api/platform`);
            const j = await r.json();
            Platform.data = { ...Platform.data, host: j };
            if (!local.isDesktop && j.os) {
              Platform.data.hostShell = j.defaultShell;
            }
          } catch {
            Platform.data.host = null;
          }
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
      hasServer() {
        return !!(Platform.data && Platform.data.hasServer);
      },
      /** The provider endpoints, shared so no module hardcodes them twice. */
      provider: PROVIDER,
      /** A compact Bangla+technical block for the system prompt. */
      describe() {
        const p = Platform.data || Platform.detect();
        const h = p.host || {};
        const lines = [
          `- \u098F\u0996\u09A8 \u0986\u09AE\u09BF \u099A\u09B2\u099B\u09BF: ${p.osName} (${p.kind})${p.androidVersion ? ", Android " + p.androidVersion : ""}${p.sdk ? ", SDK " + p.sdk : ""}`,
          `- \u09A1\u09BF\u09AD\u09BE\u0987\u09B8: ${p.device === "mobile" ? "\u09AE\u09CB\u09AC\u09BE\u0987\u09B2/\u099F\u09CD\u09AF\u09BE\u09AC" : p.device === "desktop" ? "\u09B2\u09CD\u09AF\u09BE\u09AA\u099F\u09AA/\u09A1\u09C7\u09B8\u09CD\u0995\u099F\u09AA" : "\u0985\u099C\u09BE\u09A8\u09BE"}, \u09B8\u09BE\u09B0\u09CD\u09AD\u09BE\u09B0: ${p.deployment === "localhost" ? "\u09B2\u09CB\u0995\u09BE\u09B2 \u09B9\u09CB\u09B8\u09CD\u099F (\u098F\u0987 \u09A1\u09BF\u09AD\u09BE\u0987\u09B8\u09C7\u0987)" : "\u09B0\u09BF\u09AE\u09CB\u099F \u09B8\u09BE\u09B0\u09CD\u09AD\u09BE\u09B0"}, \u099F\u09BE\u099A: ${p.touch ? "\u0986\u099B\u09C7" : "\u09A8\u09C7\u0987"}, \u0985\u09A8\u09B2\u09BE\u0987\u09A8: ${p.online ? "\u09B9\u09CD\u09AF\u09BE\u0981" : "\u09A8\u09BE"}`,
          `- \u09A8\u09C7\u099F\u09BF\u09AD \u09AC\u09CD\u09B0\u09BF\u099C (APK): ${p.nativeBridge ? "\u099A\u09BE\u09B2\u09C1 \u2014 \u0985\u09CD\u09AF\u09BE\u09AA/\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8/\u099F\u09CD\u09AF\u09BE\u09AA/\u0995\u09B2 \u09B8\u09AC \u09B8\u09AE\u09CD\u09AD\u09AC" : "\u09A8\u09C7\u0987 \u2014 \u09B6\u09C1\u09A7\u09C1 \u09AC\u09CD\u09B0\u09BE\u0989\u099C\u09BE\u09B0-\u09B8\u09A4\u09CD\u09AF \u099F\u09C1\u09B2"}`
        ];
        const nshell = global.ZY.Native && global.ZY.Native.shell && global.ZY.Native.shell.available();
        if (p.nativeBridge) {
          lines.push(`- \u0995\u09AE\u09BE\u09A8\u09CD\u09A1 \u0995\u09CB\u09A5\u09BE\u09AF\u09BC \u099A\u09B2\u09AC\u09C7: ${nshell ? "\u09AB\u09CB\u09A8\u09C7\u09B0 \u09A8\u09BF\u099C\u09C7\u09B0 Android sandbox-\u098F (`sh -c`) \u2014 \u09B8\u09BF\u09B8\u09CD\u099F\u09C7\u09AE \u09AA\u09BE\u09B0\u09CD\u099F\u09BF\u09B6\u09A8 read-only, /sdcard \u0993 \u0985\u09CD\u09AF\u09BE\u09AA-\u09A1\u09C7\u099F\u09BE \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AF\u09BC" : "\u09AB\u09CB\u09A8\u09C7 \u0995\u09AE\u09BE\u09A8\u09CD\u09A1 \u099A\u09BE\u09B2\u09BE\u09A8\u09CB\u09B0 \u09B8\u09BE\u09AA\u09CB\u09B0\u09CD\u099F \u09A8\u09C7\u0987"}`);
          lines.push("- \u0995\u09AE\u09BE\u09A8\u09CD\u09A1-\u09A8\u09BF\u09AF\u09BC\u09AE (\u09AE\u09CB\u09AC\u09BE\u0987\u09B2): PATH-\u098F \u09B6\u09C1\u09A7\u09C1 system/bin \u0986\u099B\u09C7; busybox/toybox-\u09B0 \u09B8\u09BE\u09A7\u09BE\u09B0\u09A3 \u0995\u09AE\u09BE\u09A8\u09CD\u09A1 (ls, cat, cp, mv, rm, grep, sed, wget, curl, mkdir, echo) \u099A\u09B2\u09C7\u0964 root-\u09AA\u09CD\u09B0\u09AF\u09BC\u09CB\u099C\u09A8\u09C0\u09AF\u09BC \u0995\u09BE\u099C \u099A\u09B2\u09AC\u09C7 \u09A8\u09BE \u2014 \u09AC\u09CD\u09AF\u09B0\u09CD\u09A5 \u09B9\u09B2\u09C7 \u09B8\u09C7\u099F\u09BE \u09B8\u09A4\u09CD\u09AF\u09BF \u09AC\u09B2\u09CB, \u09AC\u09BE\u09A8\u09BF\u09AF\u09BC\u09C7 \u09AC\u09B2\u09CB \u09A8\u09BE\u0964");
        } else {
          lines.push(`- \u0995\u09AE\u09BE\u09A8\u09CD\u09A1 \u0995\u09CB\u09A5\u09BE\u09AF\u09BC \u099A\u09B2\u09AC\u09C7: \u09B8\u09BE\u09B0\u09CD\u09AD\u09BE\u09B0 \u09B9\u09CB\u09B8\u09CD\u099F \u09AE\u09C7\u09B6\u09BF\u09A8\u09C7 (${h.osName || "host"})`);
          lines.push("- \u0995\u09AE\u09BE\u09A8\u09CD\u09A1-\u09A8\u09BF\u09AF\u09BC\u09AE (\u09A1\u09C7\u09B8\u09CD\u0995\u099F\u09AA): Windows-\u098F PowerShell \u0995\u09AE\u09BE\u09A8\u09CD\u09A1, macOS/Linux-\u098F bash/zsh \u0995\u09AE\u09BE\u09A8\u09CD\u09A1\u0964 \u09AA\u09BE\u09A5 \u09B8\u09C7\u09AA\u09BE\u09B0\u09C7\u099F\u09B0 \u09AE\u09C7\u09A8\u09C7 \u099A\u09B2\u09CB\u0964");
        }
        if (h.osName) {
          lines.push(`- \u09B9\u09CB\u09B8\u09CD\u099F \u09AE\u09C7\u09B6\u09BF\u09A8: ${h.osName} ${h.release || ""} ${h.arch || ""}, CPU ${h.cpus || "?"}, RAM ${h.totalMemMB || "?"}MB`);
          lines.push(`- \u09B6\u09C7\u09B2: \u09A1\u09BF\u09AB\u09B2\u09CD\u099F ${h.defaultShell || p.shell}, \u0986\u099B\u09C7: ${(h.shells || []).join(", ") || "\u0985\u099C\u09BE\u09A8\u09BE"}`);
          lines.push(`- \u09AB\u09BE\u0987\u09B2 \u09B0\u09C1\u099F: ${h.fsRoot || "?"} (\u098F\u0987 \u09AC\u09BE\u0987\u09B0\u09C7 \u09B2\u09C7\u0996\u09BE \u09AF\u09BE\u09AC\u09C7 \u09A8\u09BE)`);
        } else {
          lines.push(`- \u09B6\u09C7\u09B2: ${p.shell}`);
        }
        lines.push("- \u0995\u09AE\u09BE\u09A8\u09CD\u09A1\u09C7\u09B0 \u0997\u09C1\u09B0\u09C1\u09A4\u09CD\u09AC: \u0995\u09CB\u09A8\u09CB \u0995\u09BE\u099C\u09C7\u09B0 \u099C\u09A8\u09CD\u09AF \u09A8\u09BF\u09B0\u09CD\u09A6\u09BF\u09B7\u09CD\u099F \u099F\u09C1\u09B2 \u09A8\u09BE \u09A5\u09BE\u0995\u09B2\u09C7 \u0995\u09AE\u09BE\u09A8\u09CD\u09A1\u0987 \u09B6\u09C7\u09B7 \u09AD\u09B0\u09B8\u09BE \u2014 \u09A4\u0996\u09A8\u0993 \u0995\u09BE\u099C \u09B6\u09C7\u09B7 \u0995\u09B0\u09CB, \u09A5\u09C7\u09AE\u09C7 \u09AF\u09C7\u09AF\u09BC\u09CB \u09A8\u09BE\u0964");
        return lines.join("\n");
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.Platform = Platform;
  })(window);
})();
