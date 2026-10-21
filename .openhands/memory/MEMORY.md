# ZYRAXON mobile assistant — project memory

## Deliverable & current state (latest session)
Ships as a real **Android APK**; `public/` is the exact web code the APK bundles
(`android/build.sh` copies it into assets). Server: `server/server.mjs`, port 12000.

Verified this session (all green):
- `test/e2e.py` ALL PASSED — 10 models (fastest first), 8 real tool calls,
  todos 4/4, published site HTTP 200, character switch persists.
- `test/homeconsole.py` — hit-test all 6 buttons ok, home = orb only
  (stageKids 1), console hidden on load, tabs ok, no page errors.
- `test/inject.py` — mid-task injection toast + todos + tool rows, no fails.
- `test/voiceflow.py` — tap orb -> listening -> speech -> auto-send -> reply
  spoken -> mic auto-restarts. No fails.
- Background: user's Drive clip as a seamless ping-pong loop **with audio**.

APK: `android/zyraxon.apk` (~40MB) via `cd android && bash build.sh`
(aapt2 + javac + d8 + apksigner; no Gradle). Debug keystore `~/.android/debug.keystore`.
The APK is kept in the workspace; the user asked for the APK link later.

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

## Artifact freshness (check before claiming a build is current)
Compare `ls -la --time-style=+%m-%d_%H:%M public/js/app.js` against every
`desktop/dist/*` and `android/zyraxon.apk`. Editing `public/` silently invalidates
all four packages; the `.exe`/`.zip` are the ones most often forgotten. There is no
emulator (no KVM) and no headless Electron (no `xauth`, SUID sandbox unset), so prove
each bundle by extracting and grepping it:
APK = `zipfile` -> `assets/js/...`; AppImage = `--appimage-extract` -> `resources/public/...`;
mac = `unzip` -> `*/Resources/public/...`; win = `desktop/dist/win-unpacked/resources/public/`.

## Verification
- `python3 test/e2e.py` (Playwright) is the acceptance test: real tool execution,
  visible todo board, `publish_website` really called, published URL returns 200.
  Currently ALL CHECKS PASSED.
- `python3 test/verify_all.py` is the one-shot matrix (streaming, TTS, tools, code
  panel, permissions, no page errors). Its stream probe must sum text across
  `.msg.ai.streaming/.think/.live .body` — measuring `.streaming .body` alone reports
  0 whenever a turn opens with reasoning.
- `python3 test/toolmatrix.py` = the real tool test (10/10 models issue a tool call).
  `test/toolcheck.py` is only a 3-model raw-JSON debug script.
- `python3 test/bangla_box.py` proves Bangla speech reaches the composer (`#input`)
  and the chat (`#chat .msg.user .body`) — the user's headline complaint.
- `python3 test/uicheck.py` covers PWA manifest/SW, model ordering, characters,
  6 background scenes, permission rows, token streaming and TTS invocation.
- `python3 test/memory.py` proves long-term memory: history, facts and todos all
  come back after a reload. `python3 test/inject.py` proves mid-task injection.
- `python3 test/nativecheck.py` proves the Android bridge (TTS/STT/accessibility).
- `python3 test/shots.py` captures all 15 app screens into `public/shots/` with a
  gallery page (`public/shots/index.html`) for visual review.
- APK: `android/build.sh` (aapt2+javac+d8+apksigner, no Gradle). Build it after any
  `public/` change; verify with
  `unzip -p android/zyraxon.apk assets/js/app.js | sha256sum` vs the source file.
  No KVM here, so an emulator is not possible — that hash check is the proof.

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
