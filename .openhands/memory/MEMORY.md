# ZYRAXON mobile assistant — project memory

## Deliverable & current state
Real **Android APK** (+ Windows/macOS/Linux desktop); `public/` is the exact code the
APK bundles. Server `server/server.mjs` port 12000 proxies the real ZYRAXON
OpenAI-compatible API (`https://zyraxonai.lovable.app/api/public/zyraxon/v1`) with
SSE streaming, static hosting, `/sites/<id>/` publishing, workspace writes, Bangla
TTS proxy. Client source is TypeScript in `src/web/*.ts`; `public/js/` is COMPILED
(esbuild via `scripts/build-web.mjs`, global `window.ZY`) and committed on purpose —
`android/build.sh` and electron-builder both copy `public/` verbatim. Run
`npm run build` after editing `src/web`.
Artifacts, all verified fresh: `android/zyraxon.apk` ~22MB,
`ZYRAXON-Setup-1.0.0.exe` 178.2MB, `-mac.zip` 197.6MB, `-1.0.0.AppImage` 206.3MB.

## Provider facts (expensive to relearn)
- `stream:true` + `tools` frequently hangs: tokens (often only `reasoning_content`)
  arrive, socket never closes. `stream:false` with the same payload returns ~1.7s.
  Pool capacity throttling, varies by minute.
- So the proxy treats an incomplete stream (no `finish_reason`, or a stream that
  carried `tool_calls`) as failure, re-asks non-streaming, re-frames as SSE, and
  emits `{zyraxon:"reset"}` first so the client drops partial tool-call args.
- Cloudflare needs a browser-like User-Agent. Truncated `function.arguments` echoed
  upstream => HTTP 500; sanitize invalid-JSON tool args to `{}` before re-sending.
- Model latency order is UNSTABLE. `/api/model-health` probes live (2 rounds,
  best-of-2), caches to `data/model-health.json`, ranks drops-first then ms. Client
  keeps the fastest healthy model first unless the user picked one (`userModel`).
  Hedge timeout is **10 minutes** (a slow connect is not a failure).

## Performance / caching traps
- **Never await a slow network call in `App.boot()` before painting.** The health
  probe takes ~12s; awaiting it delayed chat restore, which looked exactly like
  "history isn't persisted". Boot restores history first, then models; `refreshHealth()`
  runs in background.
- **`public/sw.js` must stay network-first** (cache-first pinned users to old JS and
  shipped fixes never arrived). Not registered inside the APK (`!global.ZY.Native.app`).
- `/api/models` takes **2.7-4.2s** (provider round-trip). Never sleep a fixed time
  waiting on it — poll.
- When an edit "doesn't take effect", check what the server actually returns
  (`curl .../js/app.js | grep <symbol>`) and the SW cache before debugging logic.

## Frame cost (settled — answers "is it slow because of the language?")
No. The main thread sits at ~0% busy with ZERO long tasks with every visual layer
on, so none of our JS costs frames; the cost is compositor work (video decode +
blurred-layer rasterisation) a native app pays identically. `node test/perfattr.mjs`
measures it and fails if any layer exceeds 1.15x the bare shell.
- A SINGLE perf sample here is NOT evidence: one run showed the orb at 1.62x, and it
  did not reproduce (36fps on one sample, 60 on the next four). Warm up, interleave
  configs, take the MEDIAN of >=5 samples.
- Prove a guard by injecting a regression: full-screen `blur(60px)` => 1.56x FAIL,
  removed => PASS. A guard that has never failed is not a guard.
- Don't ship an unproven optimization: a will-change/translateZ promotion on the aura
  layers measured identically to none, so it was reverted (`public/css/aura.css` is
  unchanged). Unexplained GPU memory on a phone is worse than a no-op.
- Background clips: `gt/` were VP8/VP9 webm, which browsers almost never
  hardware-decode (nightlights 3840x2160, city 1920x1080) — every frame CPU-decoded.
  Now H.264 Main / yuv420p, max 1280x720. Only re-encode what is mis-encoded: the
  landscape `bg/*.mp4` were already H.264 720p. `nightlights` is a 3fps NASA
  timelapse — never upsample it, never judge its stalls on a 24fps yardstick.
  Guard: `node test/mediaperf.mjs`. Desktop `app.disableHardwareAcceleration()` is
  now opt-in (`ZYRAXON_FORCE_SOFTWARE=1`) — the unconditional call cost frames.

## Verification
`test/e2e.py` is the acceptance test: real tool execution, visible todo board,
`publish_website` really called, URL 200. Others: `toolmatrix.py` 10/10 models issue
a tool call; `bangla_box.py` Bangla speech reaches `#input` + chat; `memory.py`
history survives reload; `inject.py` mid-task injection; `nativecheck.py` bridge;
`homefeed.py` (needs `tts:true`); `_sweep.sh` runs all serially.
Node suites: `e2e.mjs`, `bridgecheck.mjs`, `mediaperf.mjs`, `typing.mjs`,
`perfattr.mjs`. Inside `CDP.eval` the page sees `window`, never Node's `global`
(ReferenceError). A test run rewrites `data/workspace/index.html` —
`git checkout -- data/workspace/` after.
No KVM (no emulator) and no headless Electron, so prove a bundle by extracting and
grepping it: APK -> `assets/js/...`, AppImage -> `--appimage-extract`, mac -> `unzip`,
win -> `dist/win-unpacked/resources/public/`.

## Voice
- Echo bug: `voice.js isEcho()` normalised with `[^\p{L}\p{N}\s]`, which deletes
  Bengali vowel signs — Unicode **Marks** (`\p{M}`), not Letters — so "আমার" became
  "আম র" and matched unrelated words; her own TTS re-entered as a user prompt. Keep
  `\p{M}`. Echo window stays open ~9s after the last spoken word; bigram `sim()`
  catches respelled fragments.
- Mid-task talk: new words must NOT stop her speech immediately. The old sentence
  keeps playing; the moment the reply to the new words emits its first content token
  the old speech stops (`Agent._stopSpeechOnNextToken`). Work is never killed.
- `TTS.push` is the single choke point for all speech; `TTS.reason()` speaks Bangla
  only (internal English reasoning stays silent on purpose). Falls back to `/api/tts`
  (Google TTS, disk-cached in `data/tts/`) when the device has no Bangla voice —
  desktop Chromium has none.

## UI structure traps
- The console sheet has three tabs. **Each panel is toggled only by its own tab**;
  a shared `showPanel` once defaulted both visible. `togglePanel(which)` +
  `syncPanelTabs()`. Console sheet z-index 76 > code overlay 75.
- **Never encode structured data in a persisted human-visible string.** Tool names
  were appended to message content and replayed into the chat bubble on reload; they
  ride in a `tools` field now.
- The assistant drives a real **code box** via the `code_box` tool
  (`open|close|minimize|restore`); written files are revealed line by line
  (`UI.typeCodeBox()`, 28ms/line). Minimise hides the body only; the stream keeps
  flowing. Android mirrors it into a real system overlay.
- **Layout trap:** composer + transcript live in `#console`, `hidden` on load, so on
  the home screen `.composer` measures 0x0 and `#input` is invisible — anything
  writing there looks like "nothing happens". Home has its own live feed
  (`#home-feed`/`#hf-list`, `UI.feed()/feedEnd()/feedNote()`). Tests must open the
  console (`#btn-console`) before touching `#input`, and never assert a transient
  element from a single sample.
- Composer `#input` is a `<textarea>`; Space/Tab/Enter are native, nothing cancels
  them. `node test/typing.mjs` proves Bangla spaces survive. The "typed text never
  arrives" complaint was the layout trap.

## Android / native bridge
- `MainActivity.java` `Bridge.shellExec` runs `/system/bin/sh -c` in the app sandbox
  and returns real `{code,stdout,stderr}`; registered `addJavascriptInterface(..., "ZYNative")`.
  `src/web/tools.ts` prefers that native path on a phone, falls back to host
  `/api/shell` on desktop (Windows powershell/pwsh/cmd; macOS zsh/bash/sh; Linux
  bash/sh/zsh).
- Screenshot API (`takeScreenshot`) is API 30+ only; below that it emits window text
  as an honest fallback. Guards are in place (`Build.VERSION.SDK_INT < 30`).
- `WRITE_SETTINGS` cannot be granted by a runtime dialog and `checkSelfPermission`
  always denies it — use `Settings.System.canWrite()`. `playProtect(off)` writes
  `package_verifier_enable` / `verifier_verify_adb_installs` / `package_verifier_user_consent`.
- This container cannot run `desktop/lib/selftest.cjs` to completion (crashes at its
  second check with or without local changes — no audio device / no GPU under Xvfb).
  Not a regression. Smoke the packaged app instead: boots, server line prints, page loads.

## GitHub publishing & release
- The user's token belongs to account **onelpawarai-X**, NOT `zyraxon-X`. Live repo =
  **neworigin** remote = `onelpawarai-X/zyraxon-mobile-ai`; `origin` (zyraxon) is
  stale — never push there. `onelpawarai-X/zyraxon` was an empty shell (zero commits).
- The INJECTED `$GITHUB_TOKEN` is account **zyraxon-X** and has only `pull` on the
  release repo: push and release-create both 403. Use the user-supplied PAT
  (account **onelpawarai-X**, admin on both repos) for anything that writes.
- Working push recipe (no prompt): `git push https://onelpawarai-X:$TOKEN@github.com/OWNER/REPO.git main`.
  A token in the URL with NO username is what makes git prompt for one — always
  include `user:token@`. (Older note said tokens in URLs are ignored; that was the
  missing-username case.)
- Tag push runs `.github/workflows/build.yml` -> APK + 3 desktop installers -> release.
  Release assets upload to `uploads.github.com` (201). To publish by hand instead:
  POST /repos/O/R/git/refs (tag), POST /repos/O/R/releases, POST the asset to the
  release's upload_url.
- CI android needs `yes | sdkmanager --licenses` first (a bare `sdkmanager` blocks on
  stdin and installs nothing -> aapt2 missing).
- Version lives in FIVE places: package.json, desktop/package.json, AndroidManifest
  versionCode+versionName, BuildConfig.java (+ RELEASE_NOTES installer filenames).
  `test/versioncheck.mjs` (`npm run test:version`) fails on any drift and reads the
  built APK's manifest back. versionCode = dotted parts padded to 2 digits each
  (19.0.6 -> 190006); Android refuses an update if it does not grow.
- Server published-site origin comes from the request Host (PUBLIC_ORIGIN overrides);
  a hardcoded origin makes every published URL 404 off the dev box.
- `GET /api/version` serves the version (read from package.json at boot). The UI
  bottom-left badge uses it on web, the `appVersion()` bridge (package manager) on
  Android.

## Build recipes
- Android: `bash android/build.sh` (no Gradle; needs `ANDROID_HOME` + debug keystore).
  It also needs the **`zip`** binary at the packaging step — without it the build dies
  at "6/6 packaging" after doing all the work. `sudo apt-get install -y zip`.
  The built APK is ~22MB: it stages public/ minus `shots/` (19MB) and
  `vendor/transformers/` — both gitignored; that is correct, not a regression.
- Linux: `npx electron-builder --linux AppImage`; macOS: `--mac zip`
- Windows: `--win nsis` — needs wine32:i386 AND an initialised 32-bit prefix
  (`WINEPREFIX=~/.wine-zyraxon WINEARCH=win32 wineboot -i`), else rcedit-ia32 dies
  with "could not load kernel32.dll".
- Freshness: compare packed `js/voice.js` byte size with source (56120 = fresh);
  don't trust timestamps.
- Container restarts wipe `$HOME`, the Android SDK, wine and the Python test deps;
  re-verify tool presence at session start.
- Re-provisioning the Android toolchain after a wipe (verified 2026-10-23):
  `sudo apt-get install -y openjdk-21-jdk-headless unzip zip`; download
  `commandlinetools-linux-11076708_latest.zip` to `$HOME/android-sdk/cmdline-tools/latest`;
  `sdkmanager --sdk_root=$HOME/android-sdk --licenses` then install
  `platform-tools platforms;android-34 build-tools;36.0.0`; recreate the debug
  keystore with `keytool -genkeypair -keystore $HOME/.android/debug.keystore
  -storepass android -keypass android -alias androiddebugkey -keyalg RSA -dname
  "CN=Android Debug,O=Android,C=US"`. A stopped background `apt-get` (state `T`)
  holds the dpkg lock forever — kill its exact PIDs.

## The todo contract is enforced client-side, not just prompted
`Agent.send`'s loop refuses to finish while a todo is open. Two traps: (1) a model
that marks every row `done` in one turn used to satisfy it with zero real tools run
— now `todo_write` is excluded from the "something happened" test and a bounded
nudge demands a real call; (2) the nudge is gated on the board being written *this*
turn, else a previous turn's leftover todos nag every later chat. See 2026-10-23 log.

## Network helper contract (was a silent, wide bug — see 2026-10-23 log)
`Tools._netFetch` has TWO backends (Android native bridge, else browser `fetch`) and
must return ONE shape: `{ ok, status, headers, text, contentType, bytes, viaBridge,
json() }`. `json()` must return a **Promise** on both paths. The browser path once
returned a raw `Response`, whose `.text` is a *method* — so `r.text != null` was true
and `read_url`, `web_search`, `http_request`, `download_file` and the GitHub client
all parsed `"function text() { [native code] }"`. It failed *silently* (ok:true,
empty data) and looked like provider flakiness. When changing a helper's contract,
grep every caller for `await`/`.catch`.

## Conventions
All user-facing copy is Bengali. Default character is `girlfriend` (প্রেমিকা).
