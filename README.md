# ZYRAXON

A Bengali-first AI assistant that lives on the phone. Not a chat window in a
browser — a real Android app that opens other apps, reads the screen, taps and
types, writes files, and keeps working through a multi-step task without
stopping in the middle.

**Android only.** ZYRAXON ships as an APK. There is no desktop, Linux or macOS
build of it, and none is planned.

- **Package:** `com.zyraxon.assistant`
- **Version:** 6.9.0 (versionCode 18)
- **Minimum Android:** 5.0 (API 21) · **Target:** API 36
- **Language:** Kotlin + Jetpack Compose, 45 source files
- **Tools:** 68 real tools · **Characters:** 6

---

## Read this first: the responsibility is yours

ZYRAXON has real hands. It opens other apps, reads the screen, taps and types,
writes files, and runs commands. That is the whole point of it — and it is also
why what you point it at is **your decision and your responsibility**. The app
does not judge the task and does not decide what is allowed. You do.

**Google Play Protect.** Play Protect scans apps and can block or disable one
that drives the device the way ZYRAXON does. If you want ZYRAXON to do
everything it is built to do, you may have to turn Play Protect off yourself:

- Open **Play Store → profile → Play Protect → ⚙ settings** and turn scanning off,
  or open the Play Protect warning for this app and choose **Install anyway**.
- The assistant can walk you to that screen for you (`open_settings("play_protect")`),
  but it cannot flip the switch. Only you can, and it will ask you rather than
  claim it did.

Once you have taken that decision, ZYRAXON will use everything its tools allow.
That is the trade: you take the responsibility, and the assistant stops holding
back.

**What this does not change.** ZYRAXON still runs as an ordinary Android app in
its own sandbox, under its own user ID, with no root. Turning Play Protect off
removes Google's scanning — it does not remove Android's permission model. The
assistant works inside the permissions the app has been granted, and asks you for
the ones it has not.

---

## What it actually does

### It talks, in Bengali, in real time

Every token streams over SSE and is spoken as it arrives — sentence by sentence,
not after the reply is finished. The microphone stays open, so you can talk over
it and it will stop and listen.

Text-to-speech and speech-to-text both use the phone's own engine. The bundled
`SpeechTextCleaner` strips markdown and emoji before speaking (otherwise the
engine reads "asterisk asterisk" out loud), and `SpeechSegmenter` splits mixed
Bangla/English text into language runs so an English word inside a Bengali
sentence is pronounced properly instead of being spelled out letter by letter.

Speech follows the words as they stream, not the whole reply at the end.
`SpeechChunker` releases a finished sentence, a clause after a comma, or — when
the model writes one long run — a piece at the last whole word, so the voice
starts a few words in rather than waiting for a full stop that may be a sentence
away. Each released piece is then split into language runs, and `SpeechQueue`
hands exactly one run to the engine at a time, waiting for its `onDone` before
the next. The engine holds one language and one voice at a time, so speaking the
runs one by one is what stops the voice being cut off after a word or two — the
defect the user heard as the microphone flicking off and on. `SpeechTone` adds a
small pitch and rate shift from the punctuation and a handful of cue words, so a
warm line, an excited one and a serious one do not all sound the same.

The microphone is a single switch, saved across restarts. A tap on the transcript
over the aura, or on the labelled mic button beside the transcript header, holds
the mic closed until it is tapped again; there is no automatic churn while the
assistant speaks.

### It has hands

66 tools, wired to the real Android APIs:

| Area | Tools |
|---|---|
| Task board | `todo_write` |
| Apps and input | `open_app`, `list_apps`, `tap`, `tap_text`, `swipe`, `type_text`, `press_key` |
| Seeing | `read_screen`, `screen_shot`, `look_camera`, `read_media` |
| Media | `read_media` (local path or remote URL, multi-frame video, audio metadata) |
| Files | `write_file`, `edit_file`, `read_file`, `list_dir`, `find_files`, `grep_files`, `delete_file` |
| Web | `launch_url`, `verify_url`, `read_url`, `web_search`, `http_request`, `download_file` |
| Publishing | `publish_github`, `publish_website`, `publish_cloudflare`, `serve_local` |
| Phone | `notify`, `call_dial`, `call_answer`, `call_hangup`, `clipboard_read`, `clipboard_write`, `share`, `sys_info`, `process_list` |
| Messaging | `channel_post` (Telegram, WhatsApp, Facebook, Messenger, Instagram, X, Gmail, SMS) |
| System | `run_shell`, `terminal_run`, `request_permission`, `open_settings`, `voice_change`, `memory_write`, `memory_read` |
| Voice and device | `live_voice` (follows the chosen model: realtime model = speech-to-speech, text model = read-aloud), `device_lock` (lock, unlock, enrol, status) |
| Offline | `on_device_model` (list, download, load, run a local model) |
| UI panels | `code_box`, `files_panel`, `floating_bubble`, `terminal_open` |

The assistant reads the screen as an actual image, taps by coordinate or by
visible text, and types into whatever is focused. After each call the real result
is injected back into its context and it decides the next step.

### The shell it uses

`run_shell` is real. It runs through the app's own bundled shell — a static
busybox — not `cmd.exe` and not PowerShell. Each call returns the real exit code,
stdout and stderr, with a 60 second default timeout and output caps of 30,000 and
8,000 characters.

It runs **as the app**, in the app's own sandbox, under the app's own user ID.
There is no root and no `su`. So the honest description is: the assistant can run
any shell command, within the permissions the app has been granted. It cannot
change `/system`, read another app's private data, or touch anything privileged
unless the device is rooted.

| | |
|---|---|
| Shell | bundled busybox `sh` (~400 applets), falling back to `/system/bin/sh` |
| User | the app's own UID — no root |
| Working directory | `/storage/emulated/0/Android/data/com.zyraxon.assistant/files/linux` |
| Timeout | 60 s default, process killed after |
| Returns | exit code, stdout, stderr |

### The Terminal tab

There is a real terminal inside the app, opened from the **ZX** button on the tab
bar. It is a full-screen shell: type a line, it runs, the output stays in the
scrollback. The assistant and the user drive the **same** shell at the same
working directory, so either can take over from the other mid-task.

busybox is bundled as a static binary for all four Android ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) in `jniLibs`, so it needs no root
and no separate install. On first use it installs its own applets into an
internal-storage `bin/` directory — internal, because Android's external storage
is FUSE and refuses the symlinks the applet farm is made of.

`git` is the one thing busybox does not ship, so `git clone`, `git pull` and
`git status` are served by JGit — a complete, pure-Java git. Clone a repository
straight into `~`:

```sh
git clone https://github.com/onelpawarai-X/zyraxon-mobile-ai
```

JGit is built on `java.nio.file`, which is API 26+, so core-library desugaring is
enabled and those classes are rewritten at build time. The app still runs on
API 21. Everything else — `awk`, `sed`, `grep`, `tar`, `wget`, `curl`, `find`,
`sort`, `base64`, `md5sum` — is a busybox applet, already on `PATH`.

### It does not stop halfway

A multi-step request becomes a task board first. While any item is open, the
assistant keeps calling tools — the loop allows 120 rounds. A tool failure is
treated as a step to recover from, not a reason to give up.

Small decisions are the assistant's own: file names, which app to open, what
order to do things in. It only stops to ask when the answer is genuinely yours —
a password it does not have, which account to post from, or something
irreversible.

You can also interrupt mid-task. New words are drained into the running loop, so
the assistant folds your instruction into the plan instead of abandoning it.

### It answers with its own voice

Speech-to-speech follows the model you pick, not a separate switch. Every model
comes from its provider's own live catalogue — nothing is hardcoded. A model the
provider marks as a live, realtime model (Gemini's `*-native-audio-*` and
`*-live-*`, OpenAI's `*realtime*`, xAI's `grok-voice-*`) is badged 🎙️ realtime
in the selector and, when chosen, opens a real WebSocket session: the microphone
streams up, the model's own voice streams back, and the phone's text-to-speech
goes silent so the two voices never overlap. A plain text model is badged as
text and has its replies read aloud by the phone's own TTS instead.

Two real wire protocols are implemented. Gemini speaks `BidiGenerateContent`;
OpenAI and xAI speak the OpenAI Realtime event stream. Each was probed against
the live service — Gemini and OpenAI complete a WebSocket handshake with `101
Switching Protocols`, xAI answers on `/v1/realtime` — and Groq, whose guessed
path returns `404`, is deliberately never labelled realtime. The socket URL,
protocol and key all come from the selected model's own provider, so a Grok
voice model is served by xAI and a Gemini live model by Google. The live model
also receives screen frames, so it can comment on what is on screen while you
talk. Both transcripts are requested, which is why the chat timeline fills in
with the same words you hear.

### It works with no network

`on_device_model` runs a MediaPipe `.task` bundle from local storage, so answers
keep coming with the radio off. Bundles are downloaded into the app's own
directory, loaded once, and streamed token by token. The suggested bundles are
all public — SmolLM 135M, TinyLlama 1.1B, Qwen2.5 1.5B, DeepSeek-R1 Distill
Qwen 1.5B, Qwen3 4B and Phi-4 mini — and each was verified to download without
any Hugging Face token (a ranged GET returns `206`). The licence-gated Gemma
repositories, which answer `401`, are not in the list. The token field stays for
a gated file a user adds by hand, but no suggested download needs it.

### It can lock the phone

`device_lock` locks the screen for real through the device-admin API — the
keyguard comes up, not a dark overlay. Enrolment happens on the system's own
"Allow" screen, so the app never has to be opened to grant it. Unlocking is
reported honestly: Android refuses it while a PIN or pattern is set, and the
tool says so rather than claiming success.

### It changes its own voice

Both voices are installed and both work. The `voice_change` tool switches between
the male and female voice and adjusts rate and pitch, so the assistant can match
the character it is playing, and Settings has the same pair of cards — tapping one
previews it at once. The girlfriend and friend characters use the female voice;
the boyfriend character uses the male one. A voice the user picks in Settings wins
over the character, and the choice is saved and restored on the next launch.

### Six characters

Girlfriend, boyfriend, friend, super assistant, cyber specialist, and
philosopher. Each has its own personality and voice. Switching character also
switches the voice. Everything is stored in a local Room database, so memories,
tasks and conversation history survive the app being closed — and are not lost if
a cloud provider goes down.

### The aura, and the bubble

The aura is the animated orb behind the chat. It is drawn in a WebView so the
motion costs almost no CPU, and every knob is real: hue, size, glow, speed, and
the style. Six styles ship — orb (the original), twin, vortex, bloom, helix and
core — and the settings pane switches between them live.

`floating_bubble` puts a small orb over every other app, so the user can talk to
the assistant without coming back to ZYRAXON. It needs the overlay permission,
which only the user can grant; the tool opens the exact settings row and the
permission panel's Allow button is one tap.

### Reading media, local or remote

`read_media` takes either a `path` (a file on the phone) or a `url` (anything on
the web) and hands the result to the model's vision. A picture comes back as an
image. A video comes back as several stills sampled across the whole clip —
`frames` controls how many, up to 8 — so a long clip is understood rather than
judged from one frame; `at_second` still pins one exact moment. Audio returns its
real duration and size. A link with no file extension works too: the type is
taken from the server's own `Content-Type`.

---

## Sixty languages

Every string is written in Bengali in the source and the tables in
`app/src/main/assets/i18n` map that Bengali text to each language, so a missing
translation shows readable Bengali instead of a key like `settings_title`. The
Settings sheet has a searchable language list; picking one swaps the table and
rebuilds the screens in place, with no restart. The choice is remembered in
`SharedPreferences` and restored before the first frame on the next launch, and
the first launch follows the phone's own locale when a table exists for it.

Adding a language means dropping one JSON file into that directory and one entry
into `languages.json`; no Kotlin changes.

---

## Getting it

Download `ZYRAXON-v6.2.0.apk` from the
[releases page](https://github.com/onelpawarai-X/zyraxon-mobile-ai/releases) and
install it. Android will warn about installing from outside the Play Store;
allow it.

**SHA-256 of the v6.2.0 APK:**
`895b2f84ff5d914de7fa142a6c8b9408875dbf36ee6d225a94c092c8f36cb337`

Signed with certificate `CN=ZYRAXON, O=ZYRAXON, C=BD`.

### First run

1. Open the app and enter an API key in Settings.
2. Open the Permissions panel and grant what you need — each button raises the
   system dialog, so you only have to tap Allow.
3. Accessibility permission is what lets the assistant tap and type in other
   apps. Without it, the tools that touch the screen will not work.

### Bengali voice

Bengali speech needs **Speech Services by Google** with the Bengali voice
installed. If the assistant spells Bengali out letter by letter, install the
voice from the Play Store. The assistant can do this itself if you ask.

---

## Providers

The assistant works with OpenAI-compatible endpoints. It ships with a catalogue
for the ZYRAXON provider, listed fastest first:

`zyraxon-pulse`, `zyraxon-neural`, `zyraxon-codex`, `zyraxon-visionary`,
`zyraxon-matrix`, `zyraxon-cyber`, `zyraxon-quantum`, `zyraxon-titan`,
`zyraxon-apex`, `zyraxon-genesis`

The ZYRAXON provider's chat endpoint is gated until the
[ZYRAXON-AI](https://github.com/onelpawarai-X/ZYRAXON-AI) repository reaches
100,000 stars. Until then, use another provider.

---

## Building from source

Requires JDK 21 and the Android SDK. There is no Gradle wrapper in this
repository, so use a system Gradle.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

gradle assembleDebug          # debug APK
gradle testDebugUnitTest      # unit tests
```

For a signed release build, provide the signing material through the environment
(the build reads these names):

```bash
export KEYSTORE_PATH=/path/to/keystore.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...

gradle assembleRelease
```

Output lands in `app/build/outputs/apk/release/`.

---

## Tests

```bash
gradle testDebugUnitTest --rerun-tasks
```

206 tests, all passing:

| Suite | Tests | Covers |
|---|---|---|
| `OpenAiSseTest` | 4 | The real SSE parser and the advertised tool set |
| `MediaReadToolTest` | 7 | Real `read_media`: a file on disk, an HTTP-served JPEG by URL, a link with no extension, and the failures |
| `ToolCatalogueTest` | 5 | Every advertised tool has the OpenAI function shape, unique names, and the bubble tool accepts on/off |
| `ProviderCatalogTest` | 21 | Provider catalogue parsing, the TTS-model filter, and per-provider realtime detection |
| `ModelLoadingNetworkTest` | 6 | Parallel model loading and per-provider failure reporting |
| `ContextWindowTest` | 4 | History trimming and long-term recall |
| `McpClientTest` | 29 | MCP protocol: handshake, tools, resources, prompts, ping, logging, completion |
| `UpdaterTest` | 8 | Reading the real GitHub release payload and comparing versions |
| `OverlayChatServiceTest` | 3 | The floating bubble is really added to the WindowManager, removed on destroy, and the service stops itself when the overlay permission is missing |
| `ShellRuntimeTest` | 9 | The bundled busybox really executes: stdout, stderr, exit codes, timeouts, and the applet farm on `PATH` |
| `GitRuntimeTest` | 7 | Real `git clone`, `pull` and `status` against a repository built in the test, plus the line dispatcher |
| `TerminalViewModelTest` | 5 | A typed line runs and lands in the scrollback, `cd` survives past the line, and a bad command is flagged |
| `SettingsPersistenceTest` | 5 | A setting changed, then the ViewModel rebuilt: the value is still there |
| `MicGateTest` | 7 | The mic opens once and stays open; the user can hold it closed |
| `SpeechChunkerTest` | 5 | Sentence chunking for read-aloud |
| `SpeechToneTest` | 5 | Tone and pitch applied per persona |
| `AuraHtmlTest` | 13 | The aura CSS: centring, glow masking, the hue variable, and every aura style being real |
| `SpeechCleaningTest` | 4 | Markdown and emoji stripping before speaking |
| `SpeechSegmenterTest` | 6 | Splitting mixed Bangla/English text into language runs |
| `VoiceSelectorTest` | 8 | Native voice selection and fallback |
| `VisionPayloadTest` | 3 | Image payloads sent to the model |
| `LiveVoiceUiTest` | 3 | The rendered top bar: the live-voice button is on screen, reports taps, and does not displace the persona or model pills |
| `AdvancedToolsTest` | 12 | Live-voice and on-device-model tools: argument validation, the token-free bundle list, per-provider realtime detection (Groq excluded), and the two prompt contracts staying distinct |
| `LocalizationTest` | 8 | The real asset tables: every language answers every key, placeholders survive, and a missing entry falls back to Bengali |
| `PackageRobolectricTest`, `ExampleRobolectricTest`, `ExampleUnitTest`, `SanityUnitTest` | 4 | Robolectric wiring and arithmetic sanity |

191 tests, 0 failures.

The shell tests are not mocked. Robolectric's JVM is x86_64 Linux, one of the four
ABIs busybox is packaged for, so the binary the phone would run is the one the
tests run — real commands, real exit codes.

To confirm a tool actually shipped in a built APK:

```bash
unzip -o -q app-release.apk -d /tmp/apk "classes*.dex"
strings /tmp/apk/classes*.dex | grep -c voice_change
```

To confirm the terminal's binaries really shipped:

```bash
unzip -l app-release.apk | grep busybox
```

---

## How it is put together

```
app/src/main/java/ai/zyraxon/assistant/
├── MainActivity.kt              Permissions, activity results, entry point
├── AccessibilityBridge.java     Real taps, typing and screenshot capture
├── DeviceBridge.java            Thin static wrapper over the bridge
├── ai/
│   ├── ZyraxonAiEngine.kt       Streaming agentic loop, personas, system prompt
│   ├── OpenAiSse.kt             SSE parsing, tool-call fragment merging
│   ├── StreamedTurn.kt          One streamed reply, merged
│   ├── ToolRegistry.kt          61 tool schemas and their implementations
│   ├── CameraActivity.kt        In-app camera capture
│   └── CameraCapture.kt         Capture plumbing
├── voice/
│   ├── NativeVoiceManager.kt    Engine TTS/STT, echo guard, language switching
│   ├── SpeechSegmenter.kt       Language-run splitting for mixed text
│   └── SpeechTextCleaner.kt     Markdown/emoji removal
├── viewmodel/ZyraxonViewModel.kt  State, tool routing, injection queue
├── ui/                          Compose layout and panels
├── db/                          Room entities, DAO, repository
└── model/ZyraxonModels.kt       Personas, models, messages
app/src/main/assets/knowledge/zyraxon-system-prompt.md   The operating contract
```

The system prompt in `assets/knowledge/` is injected into context on every turn.
It is plain Markdown and is meant to be read and edited — it is where the
assistant's rules live.

---

## Known limits

- **PowerShell is not bundled, and cannot be.** The shell is Android's own `sh`
  (mksh). PowerShell is not a part of Android, and Microsoft ships no officially
  supported Android target for it — the supported platforms are Windows, Linux
  and macOS. Dropping a `pwsh` binary into the app would not work either: since
  Android 10 (API 29) an app may not execute a binary from its own writable
  storage (the W^X rule), and bundling the .NET runtime would add well over
  100 MB to an APK that is already ~146 MB. `sh` is the honest, supported shell
  for an Android app, and it is the one the assistant uses.
- The voice has not been heard on a physical device by the author of these notes.
  Verification so far is at the code level: unit tests, compilation, APK
  contents, signature. Install it and try it.
- `publish_github` is implemented and compiles, but has not been exercised
  against a live account.
- The Play Protect toggle is hidden on some manufacturer builds. When the
  assistant cannot find it, it will ask you rather than claim success.
- `channel_post` opens the target app's composer with your text filled in. It
  does not press Send for you unless you chain a tap afterwards.

---

## License

No license file is included in this repository.
