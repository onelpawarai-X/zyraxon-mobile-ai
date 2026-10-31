# ZYRAXON — project memory

## What this repo is
`onelpawarai-X/zyraxon-mobile-ai` — native Kotlin + Jetpack Compose Android app.
`namespace`/`applicationId = com.zyraxon.assistant`, minSdk 21, target/compileSdk 36.
`.aistudio/` + `metadata.json` are AI Studio export artifacts — keep them (the build
references their signing configs), but `metadata.json` holds a *stale* `com.aistudio.*`
id; the real app is `com.zyraxon.assistant`. README + release notes are English;
**ZYRAXON itself is Android-only** (its tools may build other OSes for other projects).
UI is **white glass** — use `ui/theme/Glass.kt` tokens, never a raw dark colour.

## Standing user rules
- **Language: mirror the user.** Bangla in → Bangla out, English in → English out; one
  stray word does not switch a set session language.
- **Decide small things yourself; stop only for big ones.** Pick defaults for names,
  app choice, ordering and move on. Ask one short question only when the answer is
  unknowable, irreversible, or touches identity/money/accounts. An open todo is a pause,
  never an ending (`MAX_TOOL_ROUNDS` = 120).
- **Do the one thing the user said, first.** A neighbouring subsystem must not absorb
  the session (v6.9.0 lesson).
- **This user reverses decisions.** Check `git log`/HEAD before trusting an older
  session's "the user asked for X" (v6.7.1 locked female-only, v6.8.0 reverted it).
- Play Protect: never write "the AI can never do this" — it walks the settings screen.
- No emulator in this container (no `/dev/kvm`), so runtime proof is the unit suite +
  `assembleDebug`; the phone is the only place to confirm runtime behaviour.

## Toolchain & build (reinstalled most sessions)
`/opt` is often empty at session start. Recipe (~6 min, background + poll):
openjdk-21-jdk-headless; **Gradle 9.3.1** at `/opt/gradle` (AGP 9.1.1 hard-fails below);
SDK at `/opt/android-sdk` via commandlinetools + `sdkmanager "platform-tools"
"platforms;android-36" "build-tools;36.0.0"` (also `platforms;android-36.1`). The licence
prompt hangs non-interactively — write `/opt/android-sdk/licenses/android-sdk-license`
by hand (needs sudo). `apt-get update` needs sudo.
- Build with system `gradle`, **not** `./gradlew` (no wrapper). `--offline` works for
  `compileDebugKotlin`/`assembleDebug`; unit tests need network the first time.
- Working dirs used by prior sessions: `GRADLE_USER_HOME=/tmp/gradle-home`,
  `/tmp/gradle-dist/gradle-9.3.1/bin/gradle`.
- Release signing: canonical keystore `my-upload-key.jks` (alias `upload`,
  store+key `zyraxon2026`, cert `6dcfa05e...`). `zyraxon-release.jks` (`112547e4...`) is
  a later mistake — builds signed with it cannot update over the canonical ones.
- Lint blocks `assembleRelease` on `InvalidFragmentVersionForActivityResult`; fix is the
  `androidx.fragment:fragment-ktx` dependency.
- **Verify the artifact, not the source:** `unzip -o apk 'classes*.dex' -d /tmp/x` then
  `strings /tmp/x/classes*.dex | grep -c <symbol>` — this caught v5.1.0 shipping no
  `ProviderCatalog` at all.

## Credentials
- Injected `$GITHUB_TOKEN` = account **zyraxon-X**: only `pull` on the release repo, so
  push/release/PR-edit via `gh` return **403 "Resource not accessible by integration"**.
  Writable repo: `zyraxon-X/lore-crafter-delight`.
- The user pastes their own **classic PAT** (`ghp_…`, account **onelpawarai-X**, admin on
  the release repo) each session — do **not** store its value. `gh auth login --with-token`
  + `gh auth setup-git` makes the credential helper serve it. Push form if inline:
  `https://onelpawarai-X:$PAT@github.com/...` (`user:token@`; a bare token makes git hang).
  **PR edits need the classic PAT too:** `TOK=$(git remote get-url origin | sed -E
  's#https://[^:]+:([^@]+)@.*#\1#')` then `curl -X PATCH -H "Authorization: Bearer $TOK"
  .../pulls/<n> -d @pr.json`. `gh pr edit` is blocked for the app token.
- Older PATs live in bare backups at `/workspace/backup/*.git`.

## Models: nothing is hardcoded
The app carries **no model list** — every model comes from its provider's catalogue at
runtime; code holds only detection rules (`seedModels` are an offline fallback).
- `ProviderCatalog.parseModels` sets `AiModel.isRealtime`; picking a realtime model opens
  the live socket. Two protocols stay distinct: Gemini `BidiGenerateContent` vs
  OpenAI/xAI Realtime; voice names differ per protocol.
- **Verify a realtime endpoint with a raw TLS WebSocket handshake, never `curl -I`.**
  Gemini and `api.openai.com/v1/realtime` upgrade `101`; `api.x.ai/v1/realtime` `400`
  (real path, bad key); `api.groq.com/openai/v1/realtime` `404` (never labelled realtime).
- `parseModels` drops Gemini's TTS-only `-tts` ids; live ones are `native-audio`/`-live-`.
- Catalogue order = provider `speedRank`, fastest first. ZYRAXON is held at the end while
  its gate is shut.
- On-device bundles must be **token-free** (verified by ranged GET `206`): SmolLM-135M,
  TinyLlama-1.1B, Qwen2.5-1.5B, DeepSeek-R1-Distill-Qwen-1.5B, Qwen3-4B, Phi-4-mini.
  Gemma is licence-gated (`401`) and not offered.
- `fetchLiveModels()` loads all providers **in parallel** with a progress callback (never
  sequential). `adoptDefaultIfUnset(models, complete)` may only substitute a model from
  the **finished** catalogue — adopting from a partial one discarded the user's choice.
- `callModelWithFailover` retries the next **keyed** provider in `speedRank` order on
  quota/limit, each fallback using **its own** seed model id.

## The user's own provider (gated on purpose)
`zyraxonai.lovable.app/api/public/zyraxon/v1/models` returns 10 models, but
`/chat/completions` is **switched off** until `onelpawarai-X/ZYRAXON-AI` hits 100,000
stars — a failure there is expected, not a bug. Provider id `zyraxon`. Since v6.0.0 its
catalogue is `catalogIsPublic = true`, so the 10 models list before any key is set.
The container **can** read that host: a short `curl -m 25` returns HTTP 000 because the
body streams slowly; `curl -s --http1.1 --max-time 110` returns the full body.

## Voice (STT/TTS) — the area that keeps regressing
Phone's own engine only; a remote `translate.google.com/translate_tts` fallback was
removed on the user's instruction. Preferred engine `com.google.android.tts` (carries the
Bangla voice). `NativeVoiceManager` is the **single owner** of voice state — do not add a
second `_voiceGender` in the ViewModel (real compile error once).
- **Mic indicator**: the listening state belongs to the **user's switch** alone.
  `onReadyForSpeech`/`onError`/`onResults` must NOT flip `_isListening`; only
  `setMicEnabled` does. Every start attempt grows the backoff (a failed start used to
  leave the counter at 0 and hammer every 300 ms — the off/on churn). `RecognizerError`
  keeps the ear open for ordinary endings (NO_MATCH 7, SPEECH_TIMEOUT 6, RECOGNIZER_BUSY
  8, NETWORK 2, SERVER_DISCONNECTED 11).
- **Echo**: filter the assistant's own voice **by text** (`EchoMatcher.isOwnSpeech`,
  `strict` while the speaker rings), never by muting the mic for the whole reply — muting
  blocked real interruption. A partial only ever *interrupts*; it is never auto-sent.
- **Both voices**: `_voiceChosenByUser` (pref `voice_chosen_by_user`) is load-bearing —
  `setVoiceGender`/`applyVoice` set it, `setPersona` sets the voice only when it is false,
  and the constructor reads `voice_gender` back when true. Deleting it caused BOTH
  "only the female voice" and "my choice resets". `VoiceSelector.pick` order is
  gendered → gender-neutral → nothing; it never returns the opposite gender. When no
  voice states the wanted gender, gender is carried by pitch (female 1.28/1.00, male
  0.82/0.95).
- **Letter-by-letter bug** ("login" → "el-o-ji-ai-en"): an engine holds one language at a
  time, so the Bangla voice spells English words. `SpeechSegmenter` splits cleaned text
  into same-language runs; `speakRuns()` switches language per run; a single-language
  sentence stays ONE utterance so prosody is not chopped. Voice selection must fall back
  to *any* voice for the language (a leftover previous-language voice spelled English out).
- **Real-time + tone**: `SpeechChunker.take()` releases at the first sentence end and, for
  a long unpunctuated run, at a word boundary. Constants were lowered in v6.10.0
  (CLAUSE_MIN 42→30, COMMA_MIN 16→12, WORD_RELEASE_MIN 48→32, WORD_CUT_MIN 24→16).
  `SpeechTone` adds a small pitch/rate shift per sentence. Pipeline: `SpeechChunker` →
  `SpeechSegmenter` → `SpeechQueue` (one run at a time, `onDone` gates the next; a
  watchdog drops a stalled piece so speech continues).
- `SpeechTextCleaner` strips code fences, inline code, markdown, bullets, emoji and the
  `⚙️` tool-status lines before speaking.
- **Live speech-to-speech** follows the chosen model: `_liveVoicePreferred` must be
  initialised from pref `live_voice_preferred` and the session reopened on launch when
  the saved model `isRealtime` — writing the pref without reading it made the model's own
  voice work once and never return. Phone TTS is the fallback whenever no live session is
  active (`shouldUsePhoneTts()`).

## Realtime + agentic behaviour
- `OpenAiSse` streams `data:` deltas over OkHttp; `mergeToolCalls` concatenates split
  `tool_calls` args by index. `lastError` captures the provider's own error text so the
  phone repeats the real reason. Do **not** buffer the SSE body — it kills streaming.
- `ZyraxonAiEngine.streamAiResponse` is a `channelFlow` agentic loop feeding each tool
  result back into `messages`; `drainInjections` is drained per round.
- `sendMessage()` sets `_isGenerating = true` **before** `viewModelScope.launch`.
- **Latency**: gather the turn's inputs (DB reads, `screenContext()` = full
  accessibility-tree walk) off the main thread with `withContext(Dispatchers.IO)`; repaint
  the streaming reply at most every `UI_UPDATE_MS = 60` ms, not per token.
- Long-term memory: `ai/ContextWindow.kt` (`ENGINE_LIMIT=12`, `RECALL_LIMIT=4`,
  `RECENT_LIMIT=8`) — recalled turns must lead the history or the engine's `takeLast(12)`
  trims them away. Keep engine and ViewModel on the same constant.
- Settings persistence is guarded by `SettingsPersistenceTest`, which builds the real
  ViewModel twice against the same SharedPreferences (no mocks). **Run the suite before
  trusting a previous session's work** — a red test here was the alarm for v6.10.0.

## Phone integration notes
- **Channels**: no Android intent posts autonomously. `channel_post` opens the real app's
  composer pre-filled (Telegram/WhatsApp/Facebook/Messenger/Instagram/X via `ACTION_SEND`
  + `setPackage`; Gmail/SMS via `ACTION_SENDTO`) and must never claim it was sent. Android
  11+ needs the manifest `<queries>` block or those apps are invisible; images need
  `FileProvider`.
- **Floating bubble**: `floating_bubble` is a real tool; the service pushes text into the
  **same `ZyraxonViewModel`** (`ZyraxonViewModel.active`) — do not give it its own engine.
  `Settings.canDrawOverlays` is the gate (else `addView` throws; `stopSelf()` instead).
  Robolectric: cast `shadowOf(windowManager)` to `ShadowWindowManagerImpl` (the base has
  no `views`); overlay permission comes from `ShadowSettings.setCanDrawOverlays(bool)`.
- **Aura**: six styles via `data-aura` on `<html>` (`orb` default, `twin`, `vortex`,
  `bloom`, `helix`, `core`); `AuraHtmlTest` guards that every Settings chip has CSS +
  markup. Never centre with `height:100vh` + flex (WebView mis-measures it); use absolute
  `top/left:50%` + `translate(-50%,-50%)`.
- **MCP** (`ai.zyraxon.assistant.mcp`): OAuth servers reject a custom scheme and need a
  loopback redirect that **loops** (the browser fetches `/favicon.ico` right after the
  redirect). No client id ships — DCR per install. Tools are `<serverId>__<tool>`.
  `McpManager.close()` + `ViewModel.onCleared()` release SSE channels.
- Terminal: `SystemShell.runStreaming` streams stdout+stderr on separate threads line by
  line. The full userland is now **bundled in the APK** and installed offline through
  `LinuxRuntime` at system level via Shizuku (see v6.12.0 below).

## Releasing: check the published tag, not the branch
The recurring trap that made the user re-report already-fixed symptoms: the newest
release was **v6.9.0** while the fixes sat in **v6.10.0/v6.11.0**, never released. Always
`curl -s .../releases?per_page=1` before debugging a symptom, and bump
`versionCode`/`versionName` in `app/build.gradle.kts` when shipping.

## Release history
- **v6.12.0** (2026-10-30, PR #13, branch `feat/website-voice-realtime`): the terminal's
  full Linux userland is now **bundled in the APK** (`assets/linux/<abi>/`, ~355 MB,
  both ABIs) and installed offline — no network, no second download. `LinuxRuntime`
  replaces `TermuxRuntime`; `termux_run` is `linux_run` and **no upstream trace remains
  anywhere** (source, assets, docs). 282 tests / 0 failures; APK 467 MB.
  **Read the "v6.12.0" section of `2026-10-30.md` before touching the Linux runtime** —
  the four things that matter (no `ar` in busybox, bootstrap-vs-deb layout, why `proot`
  is required, and why the build prefix must be *derived* not written down).
- **v6.11.0**: website TTS endpoint is the default voice, streamed piece by piece.
- **v6.10.0** (2026-10-30, PR #11, branch `fix/terminal-termux-live`): voice stays on
  (mic state = user switch; backoff grows per attempt), no echo/auto-send, TTS back and
  the model's own S2S reopens on launch, both voices work (`_voiceChosenByUser` restored),
  no letter-by-letter English, faster turns (IO off the main thread), settings/model
  survive restart, terminal streaming/ANSI, new tools `youtube_play`,
  `network_status`, `call_dial` by contact name. 253 tests / 0 failures.
- **v6.9.0**: top of screen = one `সেটিংস` pill; the tab row moved into a `SessionNavPane`
  pane (the default Settings pane). 247 tests.
- **v6.8.0**: female-only lock reverted (both voices wanted); speech became a real queue.
- **v6.7.1**: mic-indicator fix via `RecognizerError`. **v6.7.0**: minSdk 24→21; real
  Shizuku system shell. **v6.6.0**: Terminal tab + JGit. **v6.5.0**: in-app auto-update.
  **v6.4.0**: no hardcoded models. **v6.3.0**: 60-language UI. **v6.2.0**: floating
  bubble, six aura styles, `read_media` by url. **v6.1.0**: provider failover.
  **v6.0.0**: parallel model loading, real key verification, public ZYRAXON catalogue.
  v3.0.0 was deleted and rebuilt once.
- Detailed history: `.openhands/memory/2026-10-20.md` and `.openhands/memory/2026-10-30.md`.
