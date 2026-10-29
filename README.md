# ZYRAXON

A Bengali-first AI assistant that lives on the phone. Not a chat window in a
browser — a real Android app that opens other apps, reads the screen, taps and
types, writes files, and keeps working through a multi-step task without
stopping in the middle.

**Android only.** ZYRAXON ships as an APK. There is no desktop, Linux or macOS
build of it, and none is planned.

- **Package:** `com.aistudio.zyraxon.kxqmpz`
- **Version:** 3.0.0 (versionCode 3)
- **Minimum Android:** 7.0 (API 24) · **Target:** API 36
- **Language:** Kotlin + Jetpack Compose, 34 source files
- **Tools:** 49 real tools · **Characters:** 6

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

### It has hands

49 tools, wired to the real Android APIs:

| Area | Tools |
|---|---|
| Task board | `todo_write` |
| Apps and input | `open_app`, `list_apps`, `tap`, `tap_text`, `swipe`, `type_text`, `press_key` |
| Seeing | `read_screen`, `screen_shot`, `look_camera`, `read_media` |
| Files | `write_file`, `edit_file`, `read_file`, `list_dir`, `find_files`, `grep_files`, `delete_file` |
| Web | `launch_url`, `verify_url`, `read_url`, `web_search`, `http_request`, `download_file` |
| Publishing | `publish_github`, `publish_website`, `publish_cloudflare`, `serve_local` |
| Phone | `notify`, `call_dial`, `call_answer`, `call_hangup`, `clipboard_read`, `clipboard_write`, `share`, `sys_info`, `process_list` |
| Messaging | `channel_post` (Telegram, WhatsApp, Facebook, Messenger, Instagram, X, Gmail, SMS) |
| System | `run_shell`, `request_permission`, `open_settings`, `voice_change`, `memory_write`, `memory_read` |
| UI panels | `code_box`, `files_panel` |

The assistant reads the screen as an actual image, taps by coordinate or by
visible text, and types into whatever is focused. After each call the real result
is injected back into its context and it decides the next step.

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

### It changes its own voice

The `voice_change` tool switches between the male and female voice and adjusts
rate and pitch, so the assistant can match the character it is playing. The
girlfriend and friend characters use the female voice; the boyfriend character
uses the male one. The choice is saved and follows the character.

### Six characters

Girlfriend, boyfriend, friend, super assistant, cyber specialist, and
philosopher. Each has its own personality and voice. Switching character also
switches the voice. Everything is stored in a local Room database, so memories,
tasks and conversation history survive the app being closed — and are not lost if
a cloud provider goes down.

---

## Getting it

Download `ZYRAXON-v3.0.0.apk` from the
[releases page](https://github.com/onelpawarai-X/zyraxon-mobile-ai/releases) and
install it. Android will warn about installing from outside the Play Store;
allow it.

**SHA-256 of the v3.0.0 APK:**
`05df5a4e836c65c7e89a1c6103f72e868e8108f87e9b89b8d506cca75955a91c`

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

19 tests, all passing:

| Suite | Tests | Covers |
|---|---|---|
| `OpenAiSseTest` | 4 | The real SSE parser and the advertised tool set |
| `VisionPayloadTest` | 3 | Image payloads sent to the model |
| `SpeechCleaningTest` | 4 | Markdown and emoji stripping before speaking |
| `SpeechSegmenterTest` | 6 | Splitting mixed Bangla/English text into language runs |
| `ExampleRobolectricTest` | 1 | Robolectric wiring |
| `ExampleUnitTest` | 1 | Arithmetic sanity |

To confirm a tool actually shipped in a built APK:

```bash
unzip -o -q app-release.apk -d /tmp/apk "classes*.dex"
strings /tmp/apk/classes*.dex | grep -c voice_change
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
│   ├── ToolRegistry.kt          49 tool schemas and their implementations
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
