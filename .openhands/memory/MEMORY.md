# ZYRAXON mobile assistant — project memory

## Deliverable & current state (latest session)
Real **Android APK** (+ Windows/macOS/Linux desktop); `public/` is the exact code the
APK bundles. Server `server/server.mjs` on port 12000. Latest artifacts, all verified
fresh against source: `android/zyraxon.apk` 48.3MB, `desktop/dist/ZYRAXON-Setup-1.0.0.exe`
178.2MB, `ZYRAXON-1.0.0-mac.zip` 197.6MB, `ZYRAXON-1.0.0.AppImage` 206.3MB.
Published to GitHub (see below). Background: user's Drive clip as a seamless
ping-pong loop **with audio**.

## What this is
Bengali-language mobile AI assistant PWA at `/workspace/project`. Node server
(`server/server.mjs`, port 12000) proxies the real ZYRAXON OpenAI-compatible API
(`https://zyraxonai.lovable.app/api/public/zyraxon/v1`) with SSE streaming, plus
static hosting, `/sites/<id>/` publishing, workspace file writes, and a Bangla TTS
proxy. Client is dependency-free JS in `public/js/` (global `window.ZY`).

## Hard-won facts about the ZYRAXON provider (expensive to relearn)
- `stream:true` combined with `tools` frequently hangs: tokens (often only
  `reasoning_content`) arrive and then the socket never closes. Non-streaming
  (`stream:false`) with the same payload returns in ~1.7s. This is model/pool
  capacity throttling and varies by minute.
- Because of the above, the server proxy treats an incomplete stream (no
  `finish_reason`, or a stream that carried `tool_calls`) as a failure and re-asks
  non-streaming, re-framing the reply as SSE. It emits a `{zyraxon:"reset"}` chunk
  first so the client discards partial tool-call args.
- Cloudflare in front of the API requires a browser-like User-Agent.
- Truncated `function.arguments` echoed back upstream causes HTTP 500; always
  sanitize invalid-JSON tool args to `{}` before re-sending.
- Model latency order is unstable; `/api/model-health` probes it live (2 rounds,
  best-of-2) and caches to `data/model-health.json`. The client keeps the fastest
  healthy model first unless the user explicitly picks one (`userModel` in
  localStorage). Deployed default: `zyraxon-apex`. Measured order:
  apex > pulse > titan > codex > matrix > visionary > quantum > neural > cyber > genesis.

## Performance / caching traps (cost hours if forgotten)
- **Never await a slow network call in `App.boot()` before painting.** The model
  health probe takes ~12s; awaiting it delayed chat restore, which looked exactly
  like "history isn't persisted". Boot now restores history first, then loads
  models, and `refreshHealth()` runs in the background.
- **`public/sw.js` must stay network-first.** A cache-first SW pinned users to old
  JavaScript, so shipped fixes silently never arrived. It is also not registered
  inside the APK (`!global.ZY.Native.app`).
- When an edit "doesn't take effect", check what the server actually returns
  (`curl .../js/app.js | grep <symbol>`) and the SW cache before debugging logic.

## Verification (all green)
`test/e2e.py` is the acceptance test: real tool execution, visible todo board,
`publish_website` really called, published URL 200. One-shot matrix `verify_all.py`
(its stream probe must sum text across `.msg.ai.streaming/.think/.live .body`).
Others: `toolmatrix.py` 10/10 models issue a tool call; `bangla_box.py` Bangla
speech reaches `#input` + `#chat .msg.user .body`; `memory.py` history/facts/todos
survive reload; `inject.py` mid-task injection; `nativecheck.py` Android bridge;
`homefeed.py` speech-box growth (needs `tts:true` — with TTS off, `push()` returns
early and the box stays empty); `verify_echo.py`; `desktop_mic.py`; `_sweep.sh` runs
all of them serially. No KVM (no emulator) and no headless Electron, so prove a
bundle by extracting and grepping it: APK -> `assets/js/...`, AppImage ->
`--appimage-extract`, mac -> `unzip`, win -> `dist/win-unpacked/resources/public/`.

## Voice echo (the "আমার কথা আবার চ্যাটে উঠছে" bug)
`voice.js isEcho()` normalised with `[^\p{L}\p{N}\s]`, which deletes Bengali
vowel signs — they are Unicode **Marks** (`\p{M}`), not Letters — turning "আমার"
into "আম র" and matching unrelated words, so her own TTS re-entered as a user
prompt. Keep `\p{M}`. The echo window must also stay open ~9s after the last
spoken word (mic + reverb lag); a bigram `sim()` catches respelled fragments.
`test/verify_echo.py` covers both directions (echo dropped, real question kept).

## Mid-task talk (user's exact rule)
New words while she is working must NOT stop her speech immediately. The old
sentence keeps playing; the moment the *reply* to the new words emits its first
content token, the old speech stops (`Agent._stopSpeechOnNextToken`). Work is
never killed. `TTS.stop()` was removed from `app.js stt.onFinal`.

## Speed ranking
`/api/model-health?force=1` probes all ten models 3x and ranks by **drops first,
then ms** — the user wants the fastest model that never fails. Measured:
apex 0.35s, visionary 0.78, titan 1.0, pulse 1.8, codex 1.9, neural 3.5,
quantum 4.6, cyber 5.6, genesis 6.2, matrix 19.0 (all 0 drops). Hedge timeout is
**10 minutes** (a slow connect is not a failure), not 5s.

## Panel independence + the live code box (2026-10-21)
- The console sheet has three tabs. **Each panel must be toggled only by its own
  tab.** A shared `showPanel` that defaulted *both* todo and tool panels visible
  when no preference was saved made one tap open two panels. `ui.js` now has
  `togglePanel(which)` (own panel only) + `syncPanelTabs()` (tabs mirror real
  `.hidden` state).
- **Never encode structured data in a persisted human-visible string.** Tool names
  were appended to the assistant message content, so they lived inside `content`
  and replayed into the chat bubble on every reload. They ride in a `tools` field.
- The assistant drives a real **code box** through the `code_box` tool
  (`open|close|minimize|restore`). Written files are revealed line by line via
  `UI.typeCodeBox()` (28ms/line). Minimise only hides the body; the stream keeps
  flowing. Android mirrors the box into a real system overlay.
- **This container cannot run `desktop/lib/selftest.cjs` to completion** — it
  crashes at its second check with or without local changes (no audio device / no
  GPU under Xvfb). Smoked the packaged app as: boots, server line prints, page
  loaded. Do not treat that crash as a regression.

## Layout trap (cost a full debugging session)
The composer and the transcript live inside `#console`, a modal sheet that is
`hidden` on load. On the home screen `.composer` measures **0x0** and `#input` is
invisible, so anything writing there looks like "nothing happens". The home screen
now has its own live feed (`#home-feed`/`#hf-list`) driven by `UI.feed()` /
`UI.feedEnd()` / `UI.feedNote()`; `UI.streamToken` mirrors the stream into it.
Two consequences for tests: open the console (`#btn-console`) before touching
`#input`, and never assert a transient UI element with a single sample.

## Permissions
`WRITE_SETTINGS` cannot be granted by a runtime dialog and `checkSelfPermission`
always denies it — use `Settings.System.canWrite()`. `playProtect(off)` writes
`package_verifier_enable` / `verifier_verify_adb_installs` / `package_verifier_user_consent`.
Speech: `TTS.push` is the single choke point for all speech; `TTS.reason()` only
speaks Bangla (internal English reasoning stays silent on purpose).

## Conventions
- All user-facing copy is Bengali. Default character is `girlfriend` (প্রেমিকা).
- TTS falls back to `/api/tts` (Google translate TTS, disk-cached in `data/tts/`)
  when the device has no Bangla speech voice — desktop Chromium has none.

## GitHub publishing (verified 2026-10-21)
- The user's token belongs to account **onelpawarai-X**, NOT `zyraxon-X`.
- Live repos: `onelpawarai-X/zyraxon-mobile-ai` (current, README + source + release)
  and `onelpawarai-X/zyraxon` (older, same content). Both have release `v1.0.0`
  with 4 assets: `zyraxon.apk` 48.3MB, `ZYRAXON-Setup-1.0.0.exe` 178.2MB,
  `ZYRAXON-1.0.0-mac.zip` 197.6MB, `ZYRAXON-1.0.0.AppImage` 206.3MB.
- The old `zyraxon` repo was created 2026-09-18 but had **zero commits** before
  this session (verified: commits API returned only my commit, events API showed
  only my ReleaseEvent). Nothing was deleted — it was always an empty shell.
- `.gitignore` excludes `desktop/dist/`, `desktop/node_modules/`, `android/build/`,
  `public/vendor|shots|assets/`, `data/sites/` — source is ~756 files.
- Release assets go to `uploads.github.com` (returns 201); browser download URLs
  are `github.com/<owner>/<repo>/releases/download/v1.0.0/<file>`.

## Cross-platform rebuild recipe (each verified)
- Android: `bash android/build.sh` — no Gradle; needs `ANDROID_HOME` + debug keystore.
- Linux: `npx electron-builder --linux AppImage`
- Windows: `npx electron-builder --win nsis` — needs wine32:i386 **and** an initialised
  32-bit prefix (`WINEPREFIX=~/.wine-zyraxon WINEARCH=win32 wineboot -i`); without the
  prefix rcedit-ia32 dies with "could not load kernel32.dll".
- macOS: `npx electron-builder --mac zip`
- Freshness check: compare the packed `js/voice.js` byte size with the source
  (56120 = fresh). Don't trust build timestamps alone.
- Container restarts wipe `$HOME`, the Android SDK, wine and the Python test deps;
  re-verify tool presence at the start of a session.

## Real shell on the phone (user asked whether it exists — it does)
`MainActivity.java:566` `Bridge.shellExec` runs `/system/bin/sh -c` inside the app
sandbox and returns real `{code,stdout,stderr}`; registered at `:185` via
`addJavascriptInterface(..., "ZYNative")`. `public/js/tools.js:435` prefers that
native path on a phone and falls back to the host `/api/shell` on desktop.
Host shells: Windows powershell/pwsh/cmd, macOS zsh/bash/sh, Linux bash/sh/zsh.

## Push & release (learned the hard way)
- Live repo is **neworigin remote** = github.com/onelpawarai-X/zyraxon-mobile-ai.
  `origin` (zyraxon) is stale history — never push there.
- A token embedded in a git remote URL is IGNORED (git prompts for a password).
  Working recipe: `gh auth setup-git`, set remote to plain https URL, then push.
- Tag push runs `.github/workflows/build.yml` -> APK + 3 desktop installers -> release.
- CI android: `yes | sdkmanager --licenses; yes | sdkmanager "build-tools;36.0.0" ...`
  (a bare sdkmanager call blocks on stdin for licences and installs nothing -> aapt2 missing).
- Bump version in ALL of: package.json, desktop/package.json, AndroidManifest
  versionCode/versionName, and .github/RELEASE_NOTES.md installer filenames.
- Console sheet must stay above the live code overlay: console z-index 76 > overlay 75.
- Server published-site origin comes from the request Host (PUBLIC_ORIGIN overrides);
  a hardcoded origin makes every published URL 404 off the dev box.
