# ZYRAXON — agent notes

## Architecture (read this first)

The APK is a **native Jetpack Compose** app. The runtime path is:

`Compose UI → ZyraxonViewModel → ZyraxonAiEngine.streamAiResponse()`

- `app/src/main/java/ai/zyraxon/assistant/ai/ZyraxonAiEngine.kt` — the engine (prompt building, streaming, agentic loop, failover, judge).
- `ai/ToolRegistry.kt` — the native tool set for the APK (`defs` list + `execute()` dispatch).
- `ai/OpenAiSse.kt` — SSE streaming; parses `delta.content` **and** reasoning deltas (`reasoning_content` / `reasoning` / `thinking`) into `StreamedTurn.reasoning`.
- `ai/ProviderCatalog.kt` — provider endpoints. `zyraxon` = built-in proprietary endpoint (`zyraxonai.lovable.app`) — **keep it, do not delete**; it will be enabled later. `speedRank` orders providers fastest-first (Groq 1, Gemini 2, …).
- `update/Updater.kt` — in-app update (GitHub releases → PackageInstaller). `installPackage(apk, appPackageName)` installs **any** APK; the name is passed only for a self-update.
- `shell/SystemShell.kt` — Shizuku system shell (uid 2000 / root). `shell/ShizukuBundle.kt` — installs the bundled Shizuku app.

**The old `app/src/main/assets/js|css/*` web/PWA layer was deleted** (dead weight for the APK). Only `aura/aura.html` is loaded in a WebView. Do not recreate the JS layer; port anything needed to Kotlin.

## System prompts — three mode files
Runtime prompts live in `app/src/main/assets/knowledge/`: `prompt-chat.txt`, `prompt-build.txt`, `prompt-agent.txt` (one per mode) and `prompt-live.txt` (live voice). They supersede the single-md rule below. `zyraxon-system-prompt.md` is kept only because tests read it. Tool access is gated in code per mode (`ToolRegistry.allowed/schemas(mode)`), never by prompt text alone.

## System prompt (legacy md) — kept for tests

**All prompt text lives in exactly one file:** `app/src/main/assets/knowledge/zyraxon-system-prompt.md`.

- It holds BOTH the text contract and the live speech-to-speech contract, separated by the marker:
  `<!-- ==================== LIVE VOICE ==================== -->`
- `ZyraxonAiEngine.bundledContract` reads the whole file; `liveVoiceContract` slices from the marker to the end.
- The old `zyraxon-live-voice-prompt.md` was deleted on purpose — do not recreate it.

**Do not inject tool names into the prompt.** Tools are sent as real OpenAI tool schemas every turn (`ToolRegistry.schemas()` + MCP tools). The prompt says in prose that the tools exist, that a terminal exists, and that `setup_shizuku` is the way to system access.

## Shizuku (system access)

- The official Shizuku release is **bundled inside the APK** at `app/src/main/assets/shizuku/shizuku-13.6.0.apk` (Apache-2.0, so shipping it is allowed) plus `LICENSE-APACHE-2.0.txt`.
- `shell/ShizukuBundle.kt` copies it to the cache and installs it as a **separate app** via `Updater.installPackage(apk)` (no `appPackageName` for a fresh install).
- UI lives in the **Updates** section (`Sheets.kt → UpdatePane → ShizukuCard`), not in Permissions.
- Agent tool `setup_shizuku(action: status|install|open|request)`. Android reserves two taps for the user: the Install confirm and Shizuku's Allow. Never claim the app can do those.

## In-app update behaviour

`checkForUpdates()` **stages** the APK in the background the moment a newer release is found (`Updater.stage`, stored under `filesDir/updates/`, not cache). The user only taps Install. `file_paths.xml` exposes `files-path updates/` for the installer.

## Build

Toolchain: JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), Gradle 9.3.1 (`/opt/gradle-9.3.1`), Android SDK (`/opt/android-sdk`, platform 36 + build-tools 36.0.0), `local.properties` with `sdk.dir=/opt/android-sdk`.

The container has been reset before and can be again — if `gradle`/`java`/`/opt/android-sdk` are missing, reinstall:

```
sudo apt-get install -y openjdk-21-jdk-headless unzip
curl -sSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-9.3.1-bin.zip && sudo unzip -q -o /tmp/g.zip -d /opt
sudo mkdir -p /opt/android-sdk/cmdline-tools && curl -sSL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
sudo unzip -q -o /tmp/c.zip -d /opt/android-sdk/cmdline-tools && sudo mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | sudo -E /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
sudo -E /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sudo chown -R openhands:openhands /opt/android-sdk
```

Run: `gradle :app:testDebugUnitTest` (≈326 tests) and `gradle :app:assembleDebug`.

## Release

GitHub Actions `.github/workflows/release.yml` ("Build & Release APK") builds the signed release on a `v*` tag push (or manual dispatch) and attaches `ZYRAXON-vX.Y.Z.apk` + `.sha256`. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, push `main`, push the tag. The release is created with a default title/body — edit it afterwards with `gh release edit` for a proper title and notes.

## Decisions locked by the user

- Native Compose only. No WebView JS app.
- Language: the user speaks Bangla; replies in the user's language, never English unless the user writes English. Code/identifiers/comments stay English.
- `zyraxonai.lovable.app` stays (built-in proprietary, to be enabled later).
- MCP tools keep auto-injecting as schemas; the model inspects them itself with `mcp_list`.
- **No animation may ever be cut or removed.** Performance work must not delete motion; only the boot intro duration and unit-test-only guards may change.

## Build & verify (works without GitHub Actions)

Toolchain used in this container:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:/opt/gradle/gradle-9.3.1/bin:$PATH
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
base64 -d debug.keystore.base64 > debug.keystore   # debug build needs it
gradle :app:testDebugUnitTest :app:assembleDebug -x lintVitalRelease -x lintVitalAnalyzeRelease
```

`gradlew` is NOT in the repo; `gradle` 9.3.1 + AGP 9.1.1 + JDK 21 are required.
Run long builds with `nohup ... &` and poll the log: a foreground command is
capped below the 1200 s idle-cleanup window.

The debug APK is ~480 MB because every ABI's 30 MB Linux payload is bundled.
That is why the release workflow needs `timeout-minutes: 90`.

## Linux / proot (why it works on targetSdk 36)

Android 10+ blocks `execve` of files in the app's data dir. proot and its
loader therefore ship as `jniLibs` (`libproot.so`, `libproot-loader.so`), which
Android does allow executing. proot never execs the guest anyway: it `mmap`s the
binary and branches to its entry point (`src/loader/loader.c`), so the block
never applies. The whole system runs as the app's own uid, inside and out: no
`-0`, no `--change-id`, exactly like a `termux`-type container in proot-distro.
That is also what lets the guest's `pkg`/`apt` run at all, since they refuse
uid 0.

The proot argv follows proot-distro's defaults, which are the reference for
"does this work on a phone":

- `--kill-on-exit` reaps the traced tree, so a finished command cannot leave
  children behind holding the pipes open.
- `--link2symlink` emulates hard links. `dpkg` unpacks control files as hard
  links, so without it an install fails partway and corrupts the package db.
- `-L` corrects the size `lstat` reports for a symlink, which `dpkg` reads.
- `--sysvipc` is proot-distro's non-minimal default.

Commands run with **no timeout** (`timeoutMs = 0` in `ShellRuntime.awaitProcess`
means "wait forever"). Termux's interactive session has none either; a bound cut
`apt upgrade` off mid-transaction. Because there is no timeout, Ctrl-C must
work: while a command runs it stops it, otherwise it clears the line. The live
process handle (`ShellRuntime.LiveProcess`) carries both the input pipe and the
stop, so a prompt can be answered by typing into the running command.

- `LD_LIBRARY_PATH` must NOT contain the native library dir: Android's `libc.so`
  lives there and a guest binary that loads it dies immediately. proot finds its
  own libs through its `$ORIGIN` runpath.
- The guest prefix shown to the user is `/usr`; the payload's real build path
  (`/data/data/com.termux/files/usr`) is bound too, because the binaries embed it.
- Symlinks are created with bundled busybox, not `java.nio.file.Files` (API 26+;
  minSdk is 21). Absolute targets on the build path are rewritten to `/usr`.
- The system is unpacked in the background on first launch; there is no install
  step for the user. Nothing needs Shizuku to run the Terminal.

## Terminal fonts

`FontFamily.Monospace` has no Bengali glyphs, so Bengali rendered as garbage.
The Terminal uses JetBrains Mono followed by Noto Sans Bengali in one
`FontFamily`; Compose falls through per glyph, keeping code monospaced while
shaping Bengali correctly.

## Release process (GitHub Actions)

Two workflows, deliberately separate:

- `TEST-CI.yml` — manual (`workflow_dispatch`). Runs unit tests, builds a
  **debug** APK, uploads it as a run artifact. `permissions: contents: read` and
  no tag trigger, so it can never publish a release. This is the build to hand
  to someone for testing on a phone.
- `release.yml` — triggers on a `v*` tag (or by hand). Builds the signed release
  APK and publishes it to the release for that tag.

To ship a version, write the release notes into the tag itself:

```bash
git tag -a vX.Y.Z -F notes.md && git push origin vX.Y.Z
```

The workflow reads that annotation and uses it as the release note.

(`gh release create --notes-from-tag` cannot be combined with `--repo`, which
the workflow needs, so the annotation is read with `git tag -l --format='%(contents)'`
and passed as `--notes-file` instead.)

If a release already exists with the wrong body, patch it:

```bash
python3 - <<'PY'
import json,urllib.request,os
req=urllib.request.Request(
 "https://api.github.com/repos/onelpawarai-X/zyraxon-mobile-ai/releases/<id>",
 data=json.dumps({"body":open('notes.md').read(),"name":"ZYRAXON vX.Y.Z — ..."}).encode(),
 headers={"Authorization":f"Bearer {os.environ['GH_TOKEN']}",
          "Accept":"application/vnd.github+json","Content-Type":"application/json"},
 method="PATCH")
print(json.load(urllib.request.urlopen(req))['html_url'])
PY
```

Bump `versionCode` for every shipped build or the APK will not install over the
previous one.

The release APK is ~445 MB and the debug APK ~480 MB, because each ABI carries
its own ~30 MB Linux payload. Any step that builds an APK needs a generous
timeout.

Do not put a token in the git remote URL. Pass it per command:
`git -c credential.helper= push https://x-access-token:$GH_TOKEN@github.com/...`

## Cloud agent → phone tool bridge (verified against the live API)

The cloud conversation API accepts a `client_tools` array on
`POST /api/v1/app-conversations`, using `ClientToolSpec` shape:
`{name, description, parameters}` where `parameters` is a JSON Schema object.
Tools declared there get registered as real tools of the conversation
(`ClientAction_<name>` appears in the agent-server OpenAPI and in the event log).

Verified round trip (Python probe, live endpoint):
1. create conversation with `client_tools` + `sandbox_id` → 200
2. open WS `wss://<host>/sockets/events/<conv_id>` with header
   `X-Session-API-Key: <session_api_key>` (from `GET /app-conversations?ids=<id>`)
3. send a message asking for the client tool
4. receive `ActionEvent` whose `action.kind` is `ClientAction_<name>`
5. the server immediately emits its own stub `ObservationEvent`
   ("Tool call dispatched to client.") and finishes the run
6. to continue, send the real result back as a normal user message and run again

So one turn can end several times; the bridge must not treat a `finished` status
that follows a phone-tool call as the end of the turn.

Two things that wasted time, now known:
- WS handshake can return 403 for a few seconds right after the sandbox starts.
  Retry the connect; it succeeds on its own.
- `sandbox_id` must be passed when reusing a warm sandbox, otherwise a new
  sandbox is provisioned and can hit the daily sandbox limit.

## Sandbox lifecycle — measured live (a chat must never look dead)

Probed against `app.all-hands.dev/api/v1` with a throwaway conversation, not the
user's own:

- The cloud **pauses an idle sandbox on its own**. A send to a paused one answers
  `409 {"detail":"Sandbox is PAUSED. Use POST /api/v1/sandboxes/<id>/resume …"}`.
- Waking it takes **13–26s** (`POST /sandboxes/<id>/resume` → poll
  `GET /app-conversations?ids=<id>` until `sandbox_status == "RUNNING"`).
- The conversation **survives the pause**: same id, same title, whole event log.
  Only the sandbox sleeps, and the `session_api_key` rotates on resume, so the
  socket must re-read the conversation before reconnecting.
- After a wake, send is accepted immediately (200, ~0.4s warm).

Two traps that were live bugs in this app:
- `POST` with no body: OkHttp refuses `method("POST", null)`, and the refusal was
  swallowed by `runCatching`, so `resume()` silently never left the phone. A
  body-less POST now sends `{}`.
- `GET /app-conversations?ids=<id>` can answer with a bare JSON array as well as
  `{"items":[…]}`. Both are normalised in `OpenHandsClient.call`.

The recovery lives in `OpenHandsClient.sendMessage` (a 409 is woken through and
the message is resent) and `ensureRunning` (wake, then wait for RUNNING). The
overlay's 90s heartbeat and `refreshCloud()` keep a bound conversation warm.

## Phone tools must be published to the cloud with a prefix

The cloud agent already has its own tools called `read_file`, `write_file`,
`edit_file`, `list_dir`, `git`, `terminal_run`, `run_shell`, `grep_files` and
`find_files`. Handing it a client tool with one of those names makes its own server
reject the **whole conversation** with `422` before the first turn starts. Measured
live, twice, with controls:

```
client_tools: [read_file, write_file, git, terminal_run]  → 422 (conversation dies)
client_tools: [zzz_alpha, zzz_beta, zzz_gamma, zzz_delta] → accepted
client_tools: [phone_read_file, phone_write_file, …]      → accepted
client_tools: all 79 phone tools, prefixed                → accepted
```

So every phone tool is published as `phone_<name>` (`OpenHandsBridge.PHONE_PREFIX`,
`publishedName`/`localName`) and the prefix is stripped before the app runs it.
Nothing else uses that prefix, so a collision is impossible rather than unlikely.
MCP tools keep their own `server__tool` names — a server cannot collide with the
agent's built-ins — but are sent in the flat spec shape the cloud wants.

## How the cloud agent sees the phone (measured live)

The cloud conversation API takes **text only**. Probed against the live endpoint:

```
POST /app-conversations/<id>/send-message
  content: [{type:"text",…}, {type:"image_url",…}]
→ 422  "Input should be 'text'"   (loc: body.content.1.type)
```

So "send a picture when the model supports vision" is **not possible for the
cloud**, whatever model it runs — the endpoint rejects an image part outright.
The cloud model also has no eyes by default (measured: `openhands/deepseek-v4.1-
flash`), so a screenshot would be a blob it cannot read even if it were accepted.

What works instead, and what is implemented:

- `ai/CloudScreen.kt` turns the accessibility tree into a digest — every element's
  own label with the centre of its bounds, e.g. `- "Send" @(540,1820) [tap]`.
  Android supplies the labels, so Bangla arrives as Bangla with no OCR step, and
  the same coordinates that name a button also tap it.
- `ai/ScreenOcr.kt` (ML Kit, on-device) adds words drawn *inside* a picture, which
  the tree cannot see. It runs only when the frame actually changed.
- The digest is refreshed every 2s (`LIVE_FRAME_INTERVAL_MS`) while a cloud turn
  runs and is attached to the opening message **and every tool result**, so the
  agent acts on the screen as it is now. `OpenHandsBridge.runTurn(screenDigest)`.
- Local providers are routed by `ai/VisionSupport.kt`: a picture for a model that
  can see, text for one that cannot (an image part to a text-only model is a hard
  400 that fails the turn).

## Building: use GitHub Actions, not the local container

The local container lost its JDK/Android SDK/Gradle after a restart and building
the APK there costs ~480 MB of memory, which has caused restarts mid-build. The
supported path is the `TEST-CI` workflow (`workflow_dispatch`):

```bash
curl -s -X POST -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/onelpawarai-X/zyraxon-mobile-ai/actions/workflows/TEST-CI.yml/dispatches" \
  -d '{"ref":"main","inputs":{"note":"what to test"}}'
```

It runs `:app:testDebugUnitTest` then builds and uploads a debug APK artifact.
The Unit tests step gates everything: a failing test means no APK, so the suite
must be green before a test build is worth dispatching.

Two tests were already broken on `main` before the Apex work and were repaired:
- `ThoughtSignatureTest` read `arguments` without calling `finalizeArgs()`, which
  every production reader calls first — the assertion was wrong, not the code.
- `SettingsPersistenceTest` asserted a round trip for `AppTab.TOOLS`, but TOOLS
  is folded into the chat panel now and never comes back as a tab.
- `MicButtonUiTest` referenced `BottomComposer`, renamed away in v6.21.0. The
  microphone control had been dropped from the app entirely; it is now
  `ComposerMicButton` in `ui/shell/Composer.kt`.

## Localisation (assets/i18n)

Every UI string is a Bengali key looked up through `t(...)` /
`Loc.t(...)` (`i18n/Localization.kt`). The tables live in
`app/src/main/assets/i18n/<code>.json`, one per language in
`languages.json`, and Bengali is the identity table.

Pipeline: `tools/localize_ui.py` wraps raw Bengali literals in `t(...)`,
then `tools/translate_missing.py` fills the tables. The two tools must
agree on how a string is tokenised, so both use
`translate_ui.braced_end` / `translate_ui.tokenize`.

Three traps, each of which silently leaves Bengali on screen:

* A Kotlin interpolation can nest braces — `${a.count { it.x }.takeIf { it > 0 } ?: ""}`
  is real code here. Match braces with `braced_end`, never with `\{[^}]*\}`;
  a wrong match rewrites the call into one the compiler rejects.
* A failed translation is stored as the Bengali source. A check that only
  asks whether the key exists sees that as translated, so the failure
  becomes permanent. `translate_missing.py` repairs any entry whose value
  still equals its Bengali key, and scans the whole table rather than just
  the keys the current code looks up.
* The endpoint rewrites or drops positional tokens. `{0}` comes back
  renumbered in several languages and `__0__` loses an underscore in
  others, so the mask is `[[N]]` (`translate_ui.protect`/`restore` accept
  one or two brackets). The product name is masked the same way, because
  the endpoint transliterates it — into Cyrillic, katakana, or the
  trademark `Xerox`; `guard_brand`/`restore_brand` hold it.

Runtime display strings (enum titles, view-model status, `OverlayChatService`)
are looked up with `t(...)` too, but they are not literals in the `ui/`
package, so `localize_ui.py` cannot see them. Keep `tools/runtime_keys.json`
current and re-run `translate_missing.py` after adding one.

Audit after any change: all 60 tables must carry the same key set as
`bn.json`, with no blank value, no `{N}` mismatch, and no value still equal
to its Bengali key except where the word is genuinely the same (Assamese).
