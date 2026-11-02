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

## System prompt — single source of truth

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
never applies. The whole system runs as the app's own uid, with `proot -0`
presenting uid 0 inside the guest.

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
