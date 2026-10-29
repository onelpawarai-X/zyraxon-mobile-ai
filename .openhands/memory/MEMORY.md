# ZYRAXON — project memory

## What this repo is (updated 2026-10-28)
`onelpawarai-X/zyraxon-mobile-ai` — a **native Kotlin + Jetpack Compose Android app**
(not the old web app; that codebase and its history were deleted at the user's request).
`namespace = "com.example"`, `applicationId = com.aistudio.zyraxon.kxqmpz`, `minSdk 24`,
`targetSdk/compileSdk 36`, versionCode 5 / versionName **4.0.0**. `.aistudio/` and
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
  `app/build.gradle.kts`. Keystore `zyraxon-release.jks` (alias `upload`, store+key
  password `zyraxon2026`); `*.jks` is gitignored. The original keystore password was lost.
- Lint blocks `assembleRelease` with `InvalidFragmentVersionForActivityResult` on
  `registerForActivityResult`; fix is the `androidx.fragment:fragment-ktx` dependency even
  though the app uses no fragments.
- Verify a build with `apksigner verify --print-certs` then
  `strings classes*.dex | grep -c <toolname>` to prove a tool really shipped.

## Aura & UI (fixed 2026-10-28)
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

## Channels: no Android intent can post autonomously
`channel_post` opens the real app's composer pre-filled (Telegram/WhatsApp/Facebook/
Messenger/Instagram/X via `ACTION_SEND` + `setPackage`; Gmail/SMS via `ACTION_SENDTO`). It
must never claim the post was sent. YouTube has no publish intent — it opens Studio and
returns `manual: true`. Android 11+ needs the manifest `<queries>` block or those apps are
invisible; images need the `androidx.core.content.FileProvider`.

## Release history
- v3.0.0 was **deleted and rebuilt** on 2026-10-28 (release id 426952504 → **427181965**,
  tag `v3.0.0` deleted then recreated at commit `f9ea6e7`). versionCode 3 → 4.
- Current `ZYRAXON-v3.0.0.apk` SHA-256
  `590f49fa90ee47a71d0b77e437c3686c06bd8c1ae94a7bcd4bfbd730479f1dae` (38,515,993 bytes),
  24 tests / 0 failures. Assets: APK, `zyraxon-v3.0.0-source.zip`, system prompt.
- Older detail lives in `.openhands/memory/2026-10-20.md`.
