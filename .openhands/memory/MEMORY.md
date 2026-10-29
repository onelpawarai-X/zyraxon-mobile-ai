# ZYRAXON — project memory

## What this repo is (updated 2026-10-29)
`onelpawarai-X/zyraxon-mobile-ai` — a **native Kotlin + Jetpack Compose Android app**
(not the old web app; that codebase and its history were deleted at the user's request).
`namespace = "com.example"`, `applicationId = com.aistudio.zyraxon.kxqmpz`, `minSdk 24`,
`targetSdk/compileSdk 36`, versionCode 10 / versionName **6.2.0**. `.aistudio/` and
`metadata.json` are AI Studio export artifacts — keep them, the build references their
signing configs. `README.md` is English and states Android-only.

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
- **v5.1.0** added the rest of the protocol: `ping`, `resources/subscribe`,
  `notifications/resources/updated`, `logging/setLevel`, `completion/complete`,
  `elicitation/create`, `roots/list`, progress/cancelled. Tools: `mcp_ping`,
  `mcp_subscribe`, `mcp_complete`, `mcp_log_level`. Three real bugs fixed:
  (a) `awaitFreshToken` had no `McpAuth.NONE` branch, so a no-auth custom server
  connected and then failed every call with "permission expired" — the four built-ins are
  all OAUTH/TOKEN, which is why it hid; (b) `update()` only looked ids up in the catalogue,
  so a **custom server never appeared** even after connecting; (c) a subscribed resource's
  refreshed text was cached but never read, so `refreshOneResource` now also queues a note
  that `ZyraxonAiEngine` injects each round (`drainResourceUpdates`).
- `McpManager.close()` + `ViewModel.onCleared()` release the SSE channels; without them a
  screen change leaked a live channel and thread. `McpCatalog.slugId()` is the one id
  generator (collapse `_` runs + hash) — keep both callers on it.

## Remote main gets force-pushed from AI Studio — always fetch first
Twice now a re-export force-pushed `main` and deleted my commits. Before pushing run
`git fetch` and compare. If histories are unrelated (no merge-base), rebase/cherry-pick will
not work — regenerate the work as a patch (`git diff <old-base> <my-HEAD>`) and `git apply -3`
it onto the new main; that also preserves the remote's own edits.

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
Note: the container cannot read that host (`curl` code 000, TLS completes but no body) —
test it from the phone, not from here.

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
- **v6.2.0** (current, release id 427752198, tag `v6.2.0`): floating bubble
  (`floating_bubble` tool + `OverlayChatService`), six aura styles, `read_media` by
  `url` + multi-frame `frames` (2–8). 105 unit tests / 0 failures. Its old schema
  required `path`, which is why link reads never worked.
- **v6.1.0**: failover between providers. **v6.0.0**: parallel model loading,
  real key verification, public ZYRAXON catalogue, ZYRAXON/OpenCode key rows, new launcher
  icon. Both have 5 assets each; 87 unit tests / 0 failures.
- v3.0.0 was deleted and rebuilt once (tag deleted then recreated) — details in the daily log.
- Older detail lives in `.openhands/memory/2026-10-20.md`.
