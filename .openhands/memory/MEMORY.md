# Project memory — ZYRAXON mobile AI

Index of durable facts. Detail lives in the daily logs (`YYYY-MM-DD.md`).

## What this is
Android-only assistant app, package `com.zyraxon.assistant`, minSdk 24 /
compileSdk 36, versionCode 10 / versionName 6.2.0. Bengali-first, "girlfriend"
persona, real tool calling. Repo: `onelpawarai-X/zyraxon-mobile-ai`.

## Build & test
- Toolchain: Gradle 9.3.1 at `/opt/gradle`, JDK 21, Android SDK at `/opt/android-sdk`.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk /opt/gradle/bin/gradle <task>`
- Run long Gradle tasks detached (`setsid nohup … &`) and poll the log; the shell
  soft-timeouts on quiet commands.
- 118 unit tests pass (`testDebugUnitTest`). Robolectric `@Config(sdk = [36])`.

## Gotchas that cost time
- **`t()` is a plain function, not `@Composable`**, so it works inside
  `remember { }` and `onClick = { }`. Language changes rebuild the tree via
  `key(languageCode)` at the app root in `MainActivity.setContent`.
- **Never put a `LazyColumn` inside a `verticalScroll` Column** (the Settings
  sheet) — infinite-height measure crash. Use a scrolling `Column`.
- Provider API is the user's own Lovable host:
  `https://zyraxonai.lovable.app/api/public/zyraxon/v1/{models,chat/completions}`.
  The `lovable.app` string in the dex is intentional, not leftover branding.
- `tools/localize_ui.py` rewrites Bengali literals to `t(...)`; the interpolation
  tokenizer lives in `tools/translate_ui.py`.

See `2026-10-20.md` for the localization work in detail.
