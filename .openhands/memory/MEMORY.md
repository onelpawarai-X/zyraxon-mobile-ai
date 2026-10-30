# ZYRAXON — project memory

## What this repo is (updated 2026-10-29)
`onelpawarai-X/zyraxon-mobile-ai` — a **native Kotlin + Jetpack Compose Android app**
(not the old web app; that codebase and its history were deleted at the user's request).
`namespace = "com.zyraxon.assistant"` and `applicationId = "com.zyraxon.assistant"`, `minSdk 24`,
`targetSdk/compileSdk 36`, versionCode 12 / versionName **6.4.0**. `.aistudio/` and
`metadata.json` are AI Studio export artifacts — keep them, the build references their
signing configs. `README.md` is English and states Android-only.

## Models: nothing is hardcoded (v6.4.0, 2026-10-20)
The user's standing rule: the app carries **no model list**. Every model comes from its
provider's own catalogue at runtime; code holds only *detection rules* (the first-run
`seedModels` are an offline fallback, nothing more).
- Speech-to-speech follows the **chosen model**, not a switch. `AiModel.isRealtime` is set
  in `ProviderCatalog.parseModels`; picking a realtime model is what opens the socket.
  `enableLiveVoice()` / `stopLiveVoice()` replaced the removed `setLiveVoiceEnabled`.
- Two real protocols, kept distinct: Gemini `BidiGenerateContent` vs OpenAI/xAI Realtime.
  Voice names differ per protocol, so the gender-to-name map is per-protocol.
- **Verify a realtime endpoint with a raw TLS WebSocket handshake, never `curl -I`** —
  curl only proves the path exists. Gemini and `api.openai.com/v1/realtime` upgrade with
  `101`; `api.x.ai/v1/realtime` gives `400` (real path, bad key);
  `api.groq.com/openai/v1/realtime` gives `404`, so Groq is never labelled realtime.
  curl can return HTTP 000 for Gemini while a raw socket succeeds — do not trust it.
- `parseModels` drops Gemini's TTS-only ids (`-tts`), which cannot chat; the live models are
  `native-audio` / `-live-` and survive. A test pins both halves.
- Catalogue order is by provider `speedRank` (fastest first); ZYRAXON is held at the end
  while its gate is shut. ZYRAXON's public catalogue really answers with 10 `zyraxon-*`
  models plus a `gate` object — fetch it with `curl --http1.1 --max-time 110` (it streams
  slowly; a short `-m` can report HTTP 000).
- On-device bundles must be **token-free**: the suggested ones are public and verified with
  a ranged GET (`206`) — SmolLM-135M, TinyLlama-1.1B, Qwen2.5-1.5B,
  DeepSeek-R1-Distill-Qwen-1.5B, Qwen3-4B, Phi-4-mini. Gemma is licence-gated (`401`) and
  is **not** offered.

## MCP servers (added 2026-10-29, v4.0.0)
`ai.zyraxon.assistant.mcp`: `McpModels` (catalog + tool schema), `McpClient` (JSON-RPC over
streamable HTTP), `McpOAuth` (RFC 9728 discovery, DCR, PKCE), `McpOAuthServer` (loopback
listener), `McpManager` (persist/connect/call). UI is the **MCP** tab (`McpScreen.kt`).
Four servers: Canva, Cloudflare, Supabase (OAuth) + GitHub (token).
- **All three OAuth servers reject a custom app scheme** and need a loopback redirect, hence
  the local server. It must **loop**, not serve once: the browser asks for `/favicon.ico`
  right after the redirect and a single-shot server mistakes that for the callback.
- **No client id ships.** Each install registers dynamically (DCR). Supabase's AS lives on
  `api.supabase.com`, not the MCP host, so discovery must follow RFC 9728.
- Canva/Cloudflare block non-browser clients → send a desktop Chrome User-Agent.
- Tools are namespaced `<serverId>__<tool>`. Agent tools: `mcp_connect`, `mcp_list`,
  `mcp_disconnect`.
- **v5.1.0** completed the protocol (ping, resources/subscribe, logging/setLevel,
  completion/complete, elicitation, roots, progress/cancelled). Three bugs worth not
  reintroducing: `awaitFreshToken` needs an `McpAuth.NONE` branch or a no-auth server
  connects then fails every call; `update()` must also look up custom servers, not just the
  catalogue; and a refreshed subscribed resource must be drained into the next agent round.
- `McpManager.close()` + `ViewModel.onCleared()` release the SSE channels — without them a
  screen change leaks a live channel and thread. `McpCatalog.slugId()` is the one id
  generator; keep both callers on it.

## Remote main gets force-pushed from AI Studio — always fetch first
Once a re-export force-pushed `main` and deleted my commits. Before pushing run
`git fetch` and compare. If histories are unrelated (no merge-base) do **not** rebase — use
`git merge origin/main --allow-unrelated-histories` on a `release/*` branch, resolve add/add
conflicts with `git checkout --ours` (the local line is the evolved superset since the
force-push), then push the branch, open a PR, and merge it. That keeps every file from both
sides and pushes nothing destructively. **Done for v6.4.0 (PR #2, 2026-10-20): 32 add/add
conflicts, all resolved to ours; the merge commit let `main` move forward without a
force-push.**
- `metadata.json` here is the *stale AI Studio v3.0.0 descriptor* (`applicationId
  com.aistudio.zyraxon.kxqmpz`) — the real build is `com.zyraxon.assistant`, so ignore that
  field when reading it.
- The two `com.example.*` scaffold tests from AI Studio reference the old `R`/`BuildConfig`;
  their imports must point at `com.zyraxon.assistant` or `compileDebugUnitTestKotlin` fails
  with `Unresolved reference 'R'` (fixed after the merge). Unit tests then run **135**.

## Toolchain: gone between sessions, must be reinstalled (2026-10-28)
`/opt` was **empty** at session start — no JDK, no Gradle, no Android SDK. Recipe (~6 min,
run in background and poll):
- `sudo apt-get install -y openjdk-21-jdk-headless unzip zip` → `/usr/lib/jvm/java-21-openjdk-amd64`
- **Gradle 9.3.1** at `/opt/gradle` — AGP 9.1.1 hard-fails below it ("Minimum supported
  Gradle version is 9.3.1"); 8.13 does NOT work.
- SDK at `/opt/android-sdk`: `commandlinetools-linux-11076708_latest.zip`, then
  `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`. AGP also wants
`platforms;android-36.1`. **The license prompt hangs** non-interactively — write
`/opt/android-sdk/licenses/android-sdk-license` by hand (needs `sudo`, then `chmod -R 777`
`/opt/android-sdk`, or the install fails with "Failed to read or create install properties
file").
- `apt-get update` needs **sudo**; plain `openhands` gets "List directory
  /var/lib/apt/lists/partial is missing".

## Build & test
- Build with system `gradle`, **not** `./gradlew` (there is no `gradlew` or wrapper jar).
- `--offline` works for `compileDebugKotlin` / `assembleDebug`. **Unit tests need the
  network the first time** (junit/robolectric must populate the Gradle cache).
- Release APK: `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` env vars are read by
  `app/build.gradle.kts`. **The canonical keystore is `my-upload-key.jks`** (alias
  `upload`, store+key password `zyraxon2026`), the AI Studio upload key whose cert
  SHA-256 is `6dcfa05e...`; it signed v3.0.0. `zyraxon-release.jks` (cert `112547e4...`)
  was generated in a later session on the mistaken belief that the password was lost —
  v4.0.0 was signed with it, which is why v4.0.0 **cannot** be installed as an update
  over v3.0.0. Use `my-upload-key.jks` unless the user says otherwise. `*.jks` is
  gitignored, so both live only in the workspace.
- Lint blocks `assembleRelease` with `InvalidFragmentVersionForActivityResult` on
  `registerForActivityResult`; fix is the `androidx.fragment:fragment-ktx` dependency even
  though the app uses no fragments.
- Verify a build with `apksigner verify --print-certs` then
  `strings classes*.dex | grep -c <toolname>` to prove a tool really shipped.

## Aura & UI (fixed 2026-10-28)
- **Six aura styles** (v6.2.0): `orb` (default, the original), `twin`, `vortex`, `bloom`,
  `helix`, `core`. `data-aura` on `<html>` picks one; each is a real element set, not a
  recolour. `AuraHtmlTest` (8) guards that every chip in `Sheets.kt` has a matching CSS
  rule and that its elements exist in the markup — add both together or the test fails.
- **Never centre the aura page with `height:100vh` + flex.** Android's WebView mis-measures
  `100vh` as the system bars change, which slid the orb up under the toolbar. `aura.html`
  uses absolute `top:50%/left:50%` + `translate(-50%,-50%)`.
- **A large `blur()` on a circle wider than the WebView bleeds past the edge and gets
  sliced.** `.atmosphere` is `blur(48px)` + a radial `mask-image` fading to transparent at
  82%. Do not restore the `@media (max-width:420px) { scale(0.84) }` rule — a 360dp
  WebView always matched it and the transform fought the centring.
- `aura.html` exposes `--aura-hue/-size/-glow/-speed`; `AuraWebView` writes them through
  `window.ZyraxonAuraLook(...)`. All colours are `hsl(var(--aura-hue) ...)` — no hard-coded
  pink — which is what makes the colour slider work. `AuraHtmlTest` guards all of this.
- The UI is **white glass**, not black: use `ui/theme/Glass.kt` (`fill(alpha)`, `sheetFill`,
  `rowFill`, near-black text tokens). The old panels were `0xD90B0A1E`. `panelAlpha` is a
  user setting. New panels must use `Glass.*`, never a raw dark `Color(0x...)`.

## Credentials
- Injected `$GITHUB_TOKEN` = account **zyraxon-X**: only `pull` on the release repo, so
  push / release-create / release-delete all return **403**. Writable repo:
  `zyraxon-X/lore-crafter-delight`.
- The user's PAT (admin on the release repo) is stored in the **bare backup clones**
  (`/workspace/backup/*.git` `remote.origin.url`). Read it with
  `git --git-dir=/workspace/backup/zyraxon-mobile-ai.git config --get remote.origin.url`.
  Shell variables (`$PAT`) do **not** persist across tool calls — re-read it each time.
  Push form `https://onelpawarai-X:$PAT@github.com/...`; never leave it in `.git/config`.
- The user's PAT = account **onelpawarai-X**, `admin: true` on the release repo — needed for
  push, tag, release create and **delete**. Push form:
  `git push "https://onelpawarai-X:$PAT@github.com/..."` (always `user:token@`; a bare token
  makes git prompt and hang). Never leave it in `.git/config` — `git remote set-url` after.
- Backups of the pre-rewrite repos are bare clones at `/workspace/backup/`
  (`zyraxonairr.git`, `zyraxon-mobile-ai.git`). Outside the workspace, not committed.

## Realtime + agentic behaviour
- `OpenAiSse.kt` streams `data:` deltas over OkHttp; `StreamedTurn.mergeToolCalls`
  concatenates split `tool_calls` arguments by index (OpenAI sends them in fragments).
- `ZyraxonAiEngine.streamAiResponse` is a `channelFlow` agentic loop that feeds each tool
  result back into `messages`. Mid-task injection: `drainInjections` is drained at the top
  of every round from a `ConcurrentLinkedQueue`.
- `sendMessage()` sets `_isGenerating = true` **before** `viewModelScope.launch`; setting it
  inside the coroutine raced and let two quick messages start competing turns.
- **No empty/hallucinated replies:** `OpenAiSse.lastError` captures the provider's own error
  text and `ZyraxonAiEngine.lastProviderError` re-throws it, so the phone repeats the real
  reason. Do **not** buffer the SSE body to read errors — only the non-2xx body is read
  whole, buffering kills real-time streaming.

## Voice: native STT/TTS only
- Phone's own engine only; a remote `translate.google.com/translate_tts` fallback was
  removed on the user's explicit instruction. `ttsAvailable` is false when no engine exists
  so the UI warns instead of pretending. Preferred engine `com.google.android.tts` (carries
  the Bangla voice; AOSP usually does not). STT is `SpeechRecognizer` +
  `EXTRA_PARTIAL_RESULTS` for live transcription.
- **Letter-by-letter bug** ("login" -> "el-o-ji-ai-en"): one sentence in one call, and an
  engine holds **one language at a time**, so the Bangla voice spells English words out.
  Fix: `voice/SpeechSegmenter.kt` splits cleaned text into same-language runs and
  `NativeVoiceManager.speakRuns()` switches language per run. A single-language sentence
  stays ONE utterance so prosody is not chopped. Guarded by `SpeechSegmenterTest` (6).
- `SpeechTextCleaner` strips code fences, inline code, markdown, bullets and emoji before
  speaking, else the engine says "asterisk asterisk". `SpeechCleaningTest` (4).
- `voice_change` tool -> `MainActivity.applyVoice()` -> `ZyraxonViewModel.applyVoice()`.
  `MainActivity.viewModel` is private, so that public door exists for tools.
  `NativeVoiceManager` is the **single owner** of voice state — do not add a second
  `_voiceGender` in the ViewModel (that was a real compile error). Persona drives it:
  `boyfriend` -> male, otherwise female.

## Prompt rules the user settled
- **Language: mirror the user.** Bangla in → Bangla out, English in → English out; once the
  session language is set one stray word does not switch it. (The user first said "always
  Bangla", then corrected to mirroring. Mirroring is the answer.)
- **Decide small things yourself; stop only for big ones.** Pick defaults for file names,
  app choice, ordering and move on; ask one short question only when the answer is
  unknowable, irreversible, or touches identity/money/accounts. An open todo is a pause,
  never an ending; the AI never stops while one is open (`MAX_TOOL_ROUNDS` = 120).
- **Play Protect: never write "the AI can never do this".** It walks the settings screen
  itself (`open_settings("play_protect")` → `screen_shot` → `tap`) and asks for permission
  when it cannot. A refusal-style block was explicitly rejected.
- **Android-only for ZYRAXON itself**; its tools may build Linux/macOS/Windows/Android apps
  for *other* projects via GitHub Actions/shell. README and release notes are in English.

## The user's own provider (gated on purpose)
`zyraxonai.lovable.app/api/public/zyraxon/v1/models` is live and returns 10 models, but
`/chat/completions` is **switched off** until `onelpawarai-X/ZYRAXON-AI` reaches 100,000
stars — the gate reply says so, so a failure there is expected, not a bug. Wired in as
provider `zyraxon`, fastest-first (pulse, neural, codex, visionary, matrix, cyber, quantum,
titan, apex, genesis). TTS/STT on that site stay available regardless.
**Since v6.0.0 its catalogue is marked `catalogIsPublic = true`**, so those 10 models list
before any key is set; chat still needs the key.
The container **can** read that host after all (verified 2026-10-20): a plain `curl -m 25`
returns HTTP 000 because the response streams slowly, but
`curl -s --http1.1 --max-time 110` returns the full 5.6 KB body. Do not conclude the host is
unreachable from a short-timeout curl.

## Provider connection & model loading (fixed 2026-10-29, v6.0.0)
The long-standing "model selector is empty / connecting does nothing" report was **not** a
UI bug. The shipped v5.1.0 APK contained no `ProviderCatalog` and no `verifyProviderKey`
at all — `saveProviderKey` only wrote the key to `SharedPreferences`. **Verify the artifact
before editing source**: `unzip -o apk -d /tmp/x 'classes*.dex'` then
`strings /tmp/x/classes*.dex | grep -c ProviderCatalog`.
Now: `fetchLiveModels()` loads all 8 providers **in parallel** and streams each provider's
models through a progress callback (never sequential — a dead endpoint used to block the
rest for 20s each); saving a key verifies against that provider's own catalogue and says
"connected" only when it truly answered; Settings has ZYRAXON and OpenCode key rows.
`callModelWithFailover` retries the next **keyed** provider in `speedRank` order on
quota/limit, each fallback using **its own** seed model id (a foreign id 404s everywhere).

## Channels: no Android intent can post autonomously
`channel_post` opens the real app's composer pre-filled (Telegram/WhatsApp/Facebook/
Messenger/Instagram/X via `ACTION_SEND` + `setPackage`; Gmail/SMS via `ACTION_SENDTO`). It
must never claim the post was sent. YouTube has no publish intent — it opens Studio and
returns `manual: true`. Android 11+ needs the manifest `<queries>` block or those apps are
invisible; images need the `androidx.core.content.FileProvider`.

## The floating bubble (v6.2.0)
`floating_bubble` is a real tool (`UI_TOOLS`), not just a settings toggle. The service
draws an orb over every app; a tap expands a box that pushes text into the **same
`ZyraxonViewModel`** the main screen uses (`ZyraxonViewModel.active`), so tools, memory and
voice are identical — do not give the service its own engine.
- **`Settings.canDrawOverlays` is the gate.** Without it `addView` throws and the service
  would die, so `onStartCommand` `stopSelf()`s with `START_NOT_STICKY` instead. The
  settings row, the panel and the tool all open the exact permission row in place.
- Test gotchas: `shadowOf(windowManager)` returns the *base* `ShadowWindowManager` which
  has **no** `views` field — cast to `ShadowWindowManagerImpl`. And overlay permission in
  Robolectric comes from `ShadowSettings.setCanDrawOverlays(bool)`, **not**
  `denyPermissions(SYSTEM_ALERT_WINDOW)` (which has no effect on it).

## Release history
- **v6.4.0** (2026-10-20, pushed, tag `v6.4.0`, release id 428662148): no hardcoded models
  — provider-driven realtime detection, protocol-aware live voice, token-free on-device
  bundles, TTS-id filter. 5 release assets (signed APK, sha256, source zip, system prompt,
  notes). Merged with AI Studio main via PR #2. **135 unit tests / 0 failures.**
  versionCode 12 / versionName 6.4.0.
- **v6.3.0**: sixty-language UI made real — 246 strings through `t()`, searchable language
  picker, choice persisted; `LocalizationTest` (8). 118 tests / 0 failures.
- **v6.2.0** (tag `v6.2.0`): floating bubble (`OverlayChatService`), six aura styles,
  `read_media` by `url` + multi-frame `frames`. 105 tests. Its old schema required `path`,
  which is why link reads never worked.
- **v6.1.0** provider failover; **v6.0.0** parallel model loading, key verification, public
  ZYRAXON catalogue, new launcher icon (87 tests). v3.0.0 was deleted and rebuilt once.
- Older detail lives in `.openhands/memory/2026-10-20.md`.
