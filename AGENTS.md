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
sudo chown -R "$USER" /opt/android-sdk
```

Run: `gradle :app:testDebugUnitTest` (412 tests) and `gradle :app:assembleDebug`.

## Release

GitHub Actions `.github/workflows/release.yml` ("Build & Release APK") builds the
signed release on a `v*` tag push (or manual dispatch) and attaches **two** APKs:

| Asset | For |
| --- | --- |
| `ZYRAXON-vX.Y.Z-arm64.apk` | 64-bit phones (`arm64-v8a`), ~229 MB |
| `ZYRAXON-vX.Y.Z-arm32.apk` | 32-bit phones (`armeabi-v7a`), ~210 MB |

Plus a `SHA256SUMS` asset covering both. Bump `versionCode`/`versionName` in
`app/build.gradle.kts`, commit, push `main`, push the tag. The release is created
with a default title/body — edit it afterwards with `gh release edit` for a
proper title and notes.

### Two APKs, one per CPU family

The Linux payload is a whole operating system: **165–183 MB per architecture**.
A single universal APK therefore made every phone carry two systems it could
never start, which is why the release ships one APK per family.

The choice is made at build time with `-PzyraxonAbi=arm64` or
`-PzyraxonAbi=arm32`. It drives two things, and they must stay in step:

1. `ndk { abiFilters }` — which native libraries are packed.
2. The `sourceSets` block — which payload folder is added as an asset directory.

Each payload lives in its own source set, and only the matching one is added:

```
app/src/payload64/assets/linux/aarch64   <- -PzyraxonAbi=arm64
app/src/payload32/assets/linux/arm       <- -PzyraxonAbi=arm32
app/src/payloadx64/assets/linux/x86_64   <- universal builds only
```

With no `-PzyraxonAbi`, every payload is added and the build is universal
(~599 MB) — that is what a plain `assembleDebug` and the unit tests get, so
nothing about local development changed. The paths are relative to the `:app`
module, so the source sets must sit under `app/src/`, not at the repo root.

A third payload for `i686` is **not** vendored, so a 32-bit x86 device finds no
payload and the terminal reports that rather than failing obscurely.

## UI strings and translation

- UI text is the **Bengali source string as the key**. `t("...")` looks the key up in
  `assets/i18n/<code>.json`; `bn.json` is the identity map.
- After adding or rewording any `t("...")` literal, run `python3 tools/translate_missing.py`.
  It unions the Kotlin literals with `tools/runtime_keys.json`, fills only the gaps, and
  is cheap to re-run. Two passes are often needed: the endpoint returns the source text
  under load, and a value equal to its Bengali key counts as missing, so the second run
  repairs it.
- **`assets/i18n/languages.json` is the language picker's own file, not a stray table.**
  Deleting it makes `LocalizationTest` fail on "the picker lists every language". It must
  list exactly the codes that have a table.
- `LocalizationTest` enforces the contract: every non-`as` language must differ from the
  Bengali source for every Bengali key. Check before pushing:
  `python3 -c "import json,glob,re;b=re.compile('[\u0980-\u09FF]');print([f for f in glob.glob('app/src/main/assets/i18n/*.json') if (lambda d:[k for k,v in d.items() if b.search(k) and v==k])(json.load(open(f,encoding='utf-8')))])"`

## Buttons carry icons

Every button that has a label draws an icon beside it, through `AppIcon(AppIcons.x, …)`
rather than raw `Icon(...)`, so an unknown key renders nothing instead of crashing.
`AppIcons.kt` holds the registry (`val x = Icons.Rounded.X` plus a `put(x, "key", "emoji")`).
Add the key in both places, or the emoji fallback path and the vector path disagree.

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

The debug APK is ~480 MB because every ABI's Linux payload is bundled. Build a
per-ABI APK with `-PzyraxonAbi=arm64` or `-PzyraxonAbi=arm32` to get the size a
real phone downloads. That is why the release workflow needs `timeout-minutes: 90`.

## Linux / proot (why it works on targetSdk 28)

`compileSdk` is 36 (the app compiles and links against the newest APIs) but
`targetSdk` is deliberately **28**. This is not an oversight and must not be
raised casually: Android 10+ blocks `execve` of files in the app's data dir, and
the guest Linux executes binaries from there. `targetSdk 28` keeps the legacy
behaviour that permits it, which is the same level Termux uses. Raising
`targetSdk` to 29+ would break `execve` inside app storage and take the whole
Linux path down with it. If the target ever has to move, the payload has to move
to `jniLibs` (as proot already does) or be reworked first.

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

### Version numbers

The version the user sees is `versionName`, and it must follow the **last
released** version by one — not the last version you happened to write into the
file. Before tagging, read what is actually released:

```bash
gh release list --limit 5          # the released versions, newest first
git show <last-tag>:app/build.gradle.kts | grep -E 'versionCode|versionName'
```

Then set `versionName` to the next number in that line and `versionCode` to one
more than the released `versionCode`. `versionCode` is the integer Android
compares, so a build with a code that is not greater will not install over the
one on the phone.

Do **not** invent versions from the working tree (that produced a jump from
6.23.2 to 6.23.7), and do **not** add a suffix such as `-test1` to a release
tag. Every release carries the plain `vX.Y.Z` form, and its notes are written in
English. A test build is produced by `TEST-CI.yml`, which publishes no release at
all — the release workflow is the only thing that tags.

The release APKs are ~229 MB (arm64) and ~210 MB (arm32), because each carries
its own Linux payload. Any step that builds an APK needs a generous timeout.

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

## Cloud transport — everything goes through the ZYRAXON gateway

The app never talks to an upstream host. `CloudClient.CLOUD` is
`https://zyraxonai.lovable.app/api/oh/v1`, and the website's gateway forwards
from there. Two upstreams sit behind it and they are not interchangeable:

- the **account API** (`/api/oh/v1/*`) creates, lists, reads, deletes and sends;
- a **per-conversation agent server** (`/api/oh/agent/*`) carries the live
  socket, the interrupt and the confirmation policy.

The agent host is a per-sandbox name that changes on every resume, so the
gateway cannot match it against a fixed upstream: it rewrites the origin of the
`conversation_url` in every account reply to `<gateway>/api/oh/agent` and keeps
the path. The app derives `agentBase` by cutting at `/api/conversations/`, so
rewriting that one field is what redirects every later agent call.

Consequence for callers: **agent calls must carry the account token as well as
the session key** (`Authorization: Bearer <token>` plus `X-Session-API-Key`).
The session key proves the caller may drive the conversation; only the account
token can say *where* that conversation lives. The gateway stores neither.

Do not add an upstream host to the app, its strings, its i18n files or its
prompts. The i18n bundles under `app/src/main/assets/i18n/` key their entries by
the Bengali source string, so changing a user-facing string changes the key in
all 120 files at once.

## Sandbox lifecycle — measured live (a chat must never look dead)

Probed with a throwaway conversation through the gateway, not the user's own:

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
  `{"items":[…]}`. Both are normalised in `CloudClient.call`.

The recovery lives in `CloudClient.sendMessage` (a 409 is woken through and
the message is resent) and `ensureRunning` (wake, then wait for RUNNING). The
overlay's 90s heartbeat and `refreshCloud()` keep a bound conversation warm.

## One message starts exactly one conversation — measured live

A message once left **three** conversations on the account. Two separate causes,
both now removed, and both worth not reintroducing:

1. **`startConversation` retried inside itself.** A `repeat(2)` loop restarted the
   whole start when the first attempt failed. The failure it was written for was a
   *reused* sandbox that had since been reclaimed (`"Sandbox not found"`), so the
   retry started a **second** task — a second conversation, a second sandbox — for
   one message. The loop is gone. A start that fails now fails once, with the
   reason, and the caller decides.

2. **A remembered sandbox id was offered for reuse.** `store.takeWarmSandbox()`
   handed over an id from an earlier chat, which may have been reclaimed in the
   meantime. That is precisely the failure above. Only a sandbox the account
   reports as live right now (`findWarmSandbox`) is reused; a cold start always
   works, so a bad reuse is strictly worse than not reusing at all.

Three paths can each start a conversation — `prewarm`, `runTurn`, and the
launch-time sync — so a per-chat lock guards the start, and the first to get
there wins.

**Measured, so the timeouts are chosen rather than guessed.** One start, from
`POST /app-conversations` to a real `app_conversation_id`:

| Stage | Time |
| --- | --- |
| `POST /app-conversations` returns a task id | 0.2 s |
| `WORKING` | 0.3 s |
| `SETTING_UP_SKILLS` (sandbox id assigned ~3 s in) | 3–27 s |
| `READY` with a conversation id | **25–40 s**, varying run to run |

`START_TIMEOUT_MS` and `WAKE_TIMEOUT_MS` are both **180 s**: the common case is
under 30 s, but the service is shared and a busy moment stretches it, so the wait
is set well above the common case rather than at it. A start still working is not
an error.

Verified against the live API: one start → **one** new conversation, twice.

## A cloud chat must show its messages

The history import used to run once, immediately on session load, and that is the
one moment it could not work: the chat is bound to its cloud conversation in a
separate coroutine, so `conversationFor(id)` was still empty, the import returned
at once, and the session was marked loaded. The chat then stayed **empty forever**,
however many times it was opened.

Three things fix it and all three are needed:
- the import is re-tried on every `switchChat`, so the attempt that lands after
  the binding exists is the one that works;
- it is skipped only once it has actually imported something, not merely because
  the row count is above zero — a chat showing a greeting has a row too;
- it is idempotent (`saveMessages` writes with `REPLACE`), so re-running is free.

The sidebar was duplicating for a related reason: the launch-time sync adopted
**every** cloud conversation, including the ones this phone had just started, so
one chat appeared twice. Only conversations with no binding are adopted, and the
binding is written **before** the adoption check so the two never race.

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

So every phone tool is published as `phone_<name>` (`CloudBridge.PHONE_PREFIX`,
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
The cloud model also has no eyes by default (measured: `zyraxon/deepseek-v4.1-
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
  agent acts on the screen as it is now. `CloudBridge.runTurn(screenDigest)`.
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

### Never cache setup on a process-wide flag that Robolectric can invalidate

A hang, not a failure, is the trap here: Gradle waits forever and the job is
cancelled only by the 90-minute timeout, with no useful log. The cause in
`ShellRuntime` was a process-wide `ready` flag: Robolectric hands each test class
a fresh data directory, so the applet farm was never rebuilt for the new
`filesDir`, commands fell through to `/system/bin/sh` (absent on the CI host),
every poll returned exit `-1`, and the `wait` tool's `until` loop spun with no end.
Key any such cache on the directory it was built for (`readyDir`), not a bare
boolean. When the Unit tests step hangs, take a thread dump of the Gradle test
worker (`jstack <GradleWorkerMain pid>`) and look at the `SDK <n> Main Thread`
stack — it names the exact test and line still running.

Stale voice/link tests were also cleared in the same pass: the segmenter now
keeps an English loanword inside its Bangla sentence on purpose, a URL is spoken
as a natural phrase, and a chat link tap always fires `ACTION_VIEW`.

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

`localize_ui.py` only wraps *literals*; a `t("… $var …")` written by hand
still compiles but never matches a table key, because Kotlin evaluates the
`$` before `t()` runs. Grep for it before a build:

```bash
grep -rn 't(".*\$' app/src/main/java --include=*.kt
```

Every hit must become `t("… {0} …", var)`.

## Reading a screenshot

`tools/read_screenshot.py` OCRs an image in English and Bengali and prints what
it found. Use it whenever a screenshot is the evidence — a crash dialog, a
settings screen, a chat transcript — instead of guessing from the description.

```bash
python3 tools/read_screenshot.py shot.png              # whole page + bands
python3 tools/read_screenshot.py shot.png --crop 40 300 1040 430
```

It needs `tesseract-ocr` with the `eng` and `ben` language packs and Pillow.

Two things it does that a plain `tesseract shot.png stdout` does not:

* It upscales first. Tesseract wants ~30 px of glyph height; a 1080 px phone
  screenshot carries 12-14 px UI text, so native-size OCR drops exactly the
  small labels that matter.
* It reads the page in overlapping horizontal bands as well as whole. On a chat
  screen the text sits on coloured bubbles whose local contrast is far below the
  page's, so a whole-page pass (psm 3) segments the layout and skips a bubble
  entirely. The band pass (psm 6, per-band autocontrast) recovers those lines.

Beware of one trap when *making* a test image: drawing English text with a
Bengali-only font renders every Latin glyph as a box, and tesseract then reports
`HOU QUOUUU YO UO YOUU` — which looks like an OCR failure and is not one.

## Testing traps

* `JSONArray(listOf("""{"id":"e1"}"""))` stores each element as a **string**,
  not an object, and `optJSONObject(i)` then returns null for every one. A
  fixture written that way answers every page empty, which looks exactly like
  a client that drops events. Parse each event into a `JSONObject` first:
  `JSONArray(events.map { JSONObject(it) })`.
* `MockWebServer` runs its dispatcher on its **own thread**, while the test
  writes the fixture on the test thread. Fields the dispatcher reads must be
  `@Volatile`, or it can answer from the stale default and the failure looks
  like a client bug.
* Answer a request from the **query it carries**, not from a queue popped in
  arrival order. A client that pages makes more than one request, and a queue
  silently pairs an answer with the wrong question — a wrong read then looks
  correct.
* The build logs a failing assertion as `AssertionError at File.kt:line` with
  no values by default. `app/build.gradle.kts` now sets
  `exceptionFormat = FULL`, so a failure prints what it actually saw. Do not
  remove it: without it every diagnosis is a guess.

## Icon traps

* A tool row's icon is an `AppIcons` **registry key**, resolved by the UI through
  `AppIcons.of`. It used to hold an emoji, and when the emoji were stripped from
  the UI the map was emptied along with them: every tool card then drew a blank
  where its icon belongs, and no test caught it because an empty string is a
  valid string. When removing a glyph, put the vector key in its place in the
  same change.
* `toolIcon`'s `else` branch must return a key that exists. `AppIcons.of`
  returns null for an unknown key, so the call site falls back to `build`; a
  key with no icon is a silent blank, not a compile error.

## Chat links and spoken text

* A link in a reply — `[label](url)` or a bare `https://…` / `www.…` — is a real
  link: `inline()` in `ChatBlocks.kt` marks the range with `LinkAnnotation.Url`
  plus a listener that fires `ACTION_VIEW`. That opens the phone's real browser.
  The in-app preview browser is for the pages the agent is *building*; a link the
  agent hands the user is not one of those, so it never goes there.
* `SpeechTextCleaner` drops every backticked span whole rather than unwrapping it.
  Tool names (`run_shell`), model ids (`gpt-4o`) and file names live in backticks,
  and unwrapping them made the speaker read "run shell ok" mid-sentence. Only
  prose is spoken.
* It also drops bare URLs. An engine spells a link out letter by letter, so the
  address is removed from the spoken copy and left in the on-screen text.

## Bangladesh funding route — researched notes (2026-11)

Kept for a later application push. Two distinct doors, not one:

**1. iDEA (Innovation Design and Entrepreneurship Academy) — grants, no equity.**
ICT Division body, E-14/X ICT Tower (14th floor), Agargaon, Dhaka-1207.
Phone 02-55007016 / 02-55007017. contact@idea.gov.bd, admin@idea.gov.bd.
Pre-Seed Startup Grant: up to BDT 10 lakh, non-equity and non-repayable.
Open to Bangladeshi entrepreneurs, teams, innovators or early-stage startups with
ICT-based ideas — a registered company is *not* a stated precondition, which makes
this the more accessible door for us. Process: online application, preliminary
screening, pitching, final selection, agreement, disbursement — 2-3 months.
Needs: NID/passport copy, written idea description, short video pitch, pitch deck,
prototype if available, contact details. Video pitch at most 5 minutes, avi or mp4,
no reading off slides; deck follows their PowerPoint template.
Grant is spent on prototype development, product design, marketing, legal setup,
operations and team building, with periodic progress reports and committee-set KPIs.
Already having taken an iDEA grant makes a second one ineligible.
Apply at apply.idea.gov.bd; SOP at apply.idea.gov.bd/SOP.pdf.

**2. Startup Bangladesh Limited — equity investment, the successor to the iDEA
investment arm.** ICT Tower (Level-14), Plot E-14/X, Agargaon, Dhaka-1207.
Phone +880255006433. info@startupbangladesh.gov.bd; pitches to
investment@startupbangladeshvc.gov.bd. Equity or quasi-equity in seed, early and
growth stage companies *registered in Bangladesh*. Ticket BDT 25 lakh to BDT 5 crore
per round, at most 49 percent stake, exit expected in 5-8 years. Sectors include
core technology/AI/deep tech and enterprise software. Intake is continuous; no reply
within two months means the application missed the current criteria, and a reapply is
invited after four further months. Term-sheet negotiation runs 1-4 months.
Documentation expected: pitch deck with financial projections, plus Trade License,
e-TIN, VAT registration, Certificate of Incorporation, and MoA/AoA.

**3. National Startup and Entrepreneur Platform (startup.ictd.gov.bd)** — ICT Division
launchpad with four stages and a self-check for which stage fits:
01 idea and prototyping (BDT 50,000-1 lakh grant, campus space),
02 MVP, testing and validation (up to BDT 10 lakh, fab lab, coworking),
03 bridging and acceleration (BDT 10-50 lakh, bridge fund, pitch training),
04 fundraising and expansion (BDT 50 lakh-5 crore, investor introductions).
Stage 03 and 04 preconditions name a registered limited company and consistent
month-over-month growth.

Also noted: the Bangladesh Fund of Funds, BDT 400 crore, run by Startup Bangladesh
Limited, routes public capital through professional VC fund managers rather than
directly into individual startups.

Practical read: the iDEA grant is the realistic first step because it does not demand
an incorporated company, and its stated evaluation criteria — innovation, scalability,
team capability, business model, market potential, impact, feasibility — match what
this project already demonstrates. Equity money from Startup Bangladesh can follow
once the company is registered.

## Team and preparation plan — before the iDEA application

The founder's plan: finish exams, enter college, build a five-person team there,
then apply to iDEA together. Recorded here so the groundwork starts now rather
than on application day.

**Five roles, chosen so no two overlap.** iDEA scores team capability as one of
seven criteria; five friends who each "know tech" reads weak, five who each own a
different piece reads strong.

1. Tech lead — Android/Compose, the bridge, the tool system. Can read the code and
   repair it when it breaks.
2. Backend and infrastructure — server side, API keys, MCP servers, model
   orchestration, cost control.
3. Product and design — the agent's experience, the voice, the languages. The
   multilingual reach is a differentiator that lives here.
4. Growth and content — distribution, demos, social. The presenting skill is
   separate from the building skill, and the pitch needs both.
5. Business, finance and legal — company registration, grant paperwork, financial
   projections, pitch deck.

**The founder is 16 now, so the runway is about two years.** Not a waiting
period — the window to build real usage, gather installs and record the agent
working, and to assemble the team at college. NID is issued at 18.

**What to accumulate before turning 18.** The strongest evidence for a grant is not
a polished demo but real usage: real installs, real sessions, real things the agent
did. Screen recordings of the agent genuinely working are worth more than a scripted
video, and iDEA's video-pitch rule ("do not read from the slides") fits real footage
naturally. The pitch deck can be pre-filled now because iDEA publishes the exact
template: Introduction/Company, Problem & Solution, Market Size, Competitive
Advantage, What is your need, Technology Strategy, Business Strategy, Financial
Strategy, Management Team.

**Why the timing lines up.** NID is issued at 18 and iDEA asks for an NID or
passport copy. Below 18 an agreement is void, so forming the team now and signing
later is the correct order, not a delay. A Bangladeshi private limited company needs
at least two shareholders and two directors, which a five-person team satisfies from
the start.

**One page to write on day one.** Who does what, and how much each person owns. It
costs nothing and it is the single thing whose absence breaks young teams from the
inside.
