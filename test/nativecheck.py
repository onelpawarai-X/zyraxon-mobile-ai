"""ZYRAXON native-bridge verification.

Injects a faithful fake of the Android `window.ZYNative` interface *before*
the app scripts load, then drives the real JS code paths and asserts the app
actually routes voice/permissions/device tools through the native bridge
instead of silently no-op'ing.
"""
import asyncio, json, os, sys
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")

# A recording stand-in for the Java bridge (same method names/shapes).
FAKE = r"""
window.__CALLS = [];
window.__TTS_DONE = null;
window.__STT = null;
window.__PERM = null;
const rec = (m, a) => { window.__CALLS.push([m, a]); };
window.ZYNative = {
  platform() { rec('platform'); return JSON.stringify({app:true, sdk:34, release:'14', model:'Pixel 8', tts:true, stt:true, accessibility:true, apps:true}); },
  serverBase() { return 'http://localhost:12000'; },
  setServerBase() {},
  ttsLanguages() { return JSON.stringify([{lang:'bn-BD',name:'bn-in-x-bng#male_1-local'},{lang:'en-US',name:'en-us-x-tpf-local'}]); },
  ttsSetLanguage(t){ rec('ttsSetLanguage',t); return true; },
  ttsSetVoice(n){ rec('ttsSetVoice',n); },
  ttsSetRate(r){ rec('ttsSetRate',r); },
  ttsSetPitch(p){ rec('ttsSetPitch',p); },
  ttsSpeak(text, lang) {
    rec('ttsSpeak', {text: String(text).slice(0,80), lang});
    window.__TTS_DONE = 'u1';
    // simulate the native utterance finishing right after, like a real device
    setTimeout(() => { window.ZYNativeCB && window.ZYNativeCB('tts', JSON.stringify({state:'done',text:'u1'})); }, 120);
    return 'u1';
  },
  ttsStop(){ rec('ttsStop'); },
  sttAvailable(){ return true; },
  sttStart(lang){ rec('sttStart', lang); window.__STT='start'; return true; },
  sttStop(){ rec('sttStop'); },
  permissionState(name){
    rec('permissionState', name);
    const granted = ['microphone'].includes(name);
    return JSON.stringify({granted, name});
  },
  requestPermission(name, label){
    rec('requestPermission', name);
    window.__PERM = name;
    setTimeout(() => { window.ZYNativeCB && window.ZYNativeCB('perm', JSON.stringify({state:'granted',text:name})); }, 80);
  },
  openSystemSettings(k){ rec('openSystemSettings', k); return true; },
  accessibilityOn(){ return true; },
  openApp(p){ rec('openApp', p); },
  // open_app refuses to claim success unless the phone confirms the app exists,
  // so the stub must answer this the way the real bridge does.
  resolveApp(p){ rec('resolveApp', p); return JSON.stringify({ found: true, package: 'com.facebook.katana', label: 'Facebook' }); },
  launchUrl(u){ rec('launchUrl', u); return true; },
  tap(x,y){ rec('tap', [x,y]); return true; },
  swipe(a,b,c,d){ rec('swipe', [a,b,c,d]); return true; },
  tapText(t){ rec('tapText', t); return true; },
  typeText(t){ rec('typeText', t); return true; },
  scroll(f){ rec('scroll', f); return true; },
  globalAction(a){ rec('globalAction', a); return true; },
  readScreen(){ rec('readScreen'); return JSON.stringify([{text:'Facebook', x:100,y:200,click:true},{text:'Search', x:50,y:120,editable:true}]); },
  screenShot(){ rec('screenShot'); setTimeout(()=>{ window.ZYNativeCB && window.ZYNativeCB('shot', JSON.stringify({state:'ok',text:'AAAA'})); }, 60); },
  listApps(){ rec('listApps'); return JSON.stringify([{label:'Facebook',pkg:'com.facebook.katana'},{label:'WhatsApp',pkg:'com.whatsapp'}]); },
  notifyMsg(t,b){ rec('notifyMsg',[t,b]); },
  vibrate(ms){ rec('vibrate', ms); },
  clipboardRead(){ return 'clip-text'; },
  clipboardWrite(t){ rec('clipboardWrite', t); },
  deviceInfo(){ return JSON.stringify({manufacturer:'Google',model:'Pixel 8',android:'14',sdk:34,locale:'bn-BD'}); },
};
"""

async def main():
    failures, notes = [], []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2)
        page = await ctx.new_page()
        errs = []
        page.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errs.append(str(e)))
        print("step: fake ready", flush=True)
        await page.add_init_script(FAKE)
        await page.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:true,automic:true}));")
        print("step: goto", flush=True)
        await page.goto(BASE, wait_until="domcontentloaded")
        print("step: loaded", flush=True)
        await page.wait_for_timeout(1200)

        print("step: info", flush=True)
        info = await page.evaluate("window.ZY.Native.info")
        notes.append(f"native.info={info}")
        if not info.get("app"): failures.append("native bridge not detected")
        if not info.get("tts"): failures.append("native TTS not reported")
        if not info.get("stt"): failures.append("native STT not reported")

        kind = await page.evaluate("window.ZY.Tools.native.kind()")
        notes.append(f"tools.native.kind={kind}")
        if kind != "zyraxon-native": failures.append(f"tools not routed to native (kind={kind})")

        # ── voice out: native TTS path ──
        print("step: tts", flush=True)
        await page.evaluate("window.ZY.VOICE.TTS.say('আসসালামু আলাইকুম, আমি জাইরাক্সন')")
        await page.wait_for_timeout(500)
        calls = await page.evaluate("window.__CALLS.map(c => c[0])")
        notes.append(f"tts_calls={[c for c in calls if c.startswith('tts')]}")
        if "ttsSpeak" not in calls: failures.append("native ttsSpeak never called")

        # ── voice in: native STT path ──
        print("step: stt", flush=True)
        await page.evaluate("window.ZY.VOICE.STT.init(); window.ZY.VOICE.STT.start('bn-BD')")
        await page.wait_for_timeout(200)
        calls = await page.evaluate("window.__CALLS.map(c => c[0])")
        notes.append(f"stt_started={'sttStart' in calls}")
        if "sttStart" not in calls: failures.append("native sttStart never called")

        # simulate a final transcript arriving from the recogniser
        await page.evaluate("window.ZYNativeCB('stt', JSON.stringify({state:'final', text:'টুডু বানাও'}))")
        await page.wait_for_timeout(300)

        # ── device tools really route through the bridge ──
        print("step: tools", flush=True)
        r = await page.evaluate("window.ZY.Tools.execute('list_apps', {})")
        notes.append(f"list_apps ok={r.get('ok')} n={len((r.get('data') or {}).get('apps') or [])}")
        if not r.get("ok") or len(r["data"]["apps"]) != 2: failures.append("list_apps did not return native apps")

        r = await page.evaluate("window.ZY.Tools.execute('tap', {x: 120, y: 340})")
        if not r.get("ok"): failures.append("tap tool failed")
        r = await page.evaluate("window.ZY.Tools.execute('swipe', {x1:10,y1:20,x2:30,y2:400})")
        if not r.get("ok"): failures.append("swipe tool failed")
        r = await page.evaluate("window.ZY.Tools.execute('read_screen', {})")
        notes.append(f"read_screen ok={r.get('ok')} lines={len((r.get('data') or {}).get('lines') or [])}")
        if not r.get("ok"): failures.append("read_screen tool failed")
        r = await page.evaluate("window.ZY.Tools.execute('screen_shot', {})")
        notes.append(f"screen_shot ok={r.get('ok')} has_img={bool((r.get('data') or {}).get('dataUrl'))}")
        if not r.get("ok"): failures.append("screen_shot tool failed")
        r = await page.evaluate("window.ZY.Tools.execute('open_app', {name:'Facebook'})")
        if not r.get("ok"): failures.append("open_app tool failed")

        calls = await page.evaluate("window.__CALLS.map(c => c[0])")
        for need in ["tap", "swipe", "readScreen", "screenShot", "listApps", "openApp"]:
            if need not in calls: failures.append(f"native {need} never invoked")

        # ── permission flow: sheet -> native requestPermission -> granted ──
        print("step: perm", flush=True)
        await page.evaluate("void window.ZY.ui.permissionSheet({kind:'camera', label:'ক্যামেরা', real:true}); 0")
        await page.wait_for_timeout(200)
        await page.click("#sheet-allow")
        await page.wait_for_timeout(600)
        calls = await page.evaluate("window.__CALLS.map(c => c[0])")
        notes.append(f"perm_calls={[c for c in calls if 'ermission' in c or 'SystemSettings' in c]}")
        if "requestPermission" not in calls: failures.append("permission sheet did not hit the native prompt")

        real_errors = [e for e in errs if "favicon" not in e.lower()]
        notes.append(f"console_errors={real_errors[:6]}")
        if real_errors: failures.append(f"console errors: {real_errors[:3]}")

        await page.screenshot(path="/tmp/zyraxon-native.png")
        await b.close()

    print("── NOTES ──")
    for n in notes: print(" •", n)
    print("── RESULT ──")
    if failures:
        for f in failures: print(" •", f)
        sys.exit(1)
    print("ALL NATIVE CHECKS PASSED")

asyncio.run(main())
