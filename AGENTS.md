# ZYRAXON — agent notes

## Architecture (read this first)

The APK is a **native Jetpack Compose** app. The runtime path is:

`Compose UI → ZyraxonViewModel → ZyraxonAiEngine.streamAiResponse()`

- `app/src/main/java/ai/zyraxon/assistant/ai/ZyraxonAiEngine.kt` — the engine (prompt building, streaming, agentic loop, failover, judge).
- `ai/ToolRegistry.kt` — 72 native tools (the real tool set for the APK).
- `ai/OpenAiSse.kt` — SSE streaming (only parses `delta.content` today).
- `ai/ProviderCatalog.kt` — provider endpoints. `zyraxon` = built-in proprietary endpoint (`zyraxonai.lovable.app`) — **keep it, do not delete**; it will be enabled later.

**The `app/src/main/assets/js/*.js` layer (≈8,600 lines) is NOT used by the APK.** Only `aura/aura.html` is loaded in a WebView (`ui/components/AuraWebView.kt`). The JS chat/agent/providers implementation is dead weight for the APK — it is the web/AI-Studio build. Do not add features there; port anything needed to Kotlin instead.

## System prompt — single source of truth

**All prompt text lives in exactly one file:** `app/src/main/assets/knowledge/zyraxon-system-prompt.md`.

- It holds BOTH the text contract and the live speech-to-speech contract, separated by the marker:
  `<!-- ==================== LIVE VOICE ==================== -->`
- `ZyraxonAiEngine.bundledContract` reads the whole file; `liveVoiceContract` slices from the marker to the end.
- The old `zyraxon-live-voice-prompt.md` was deleted on purpose — do not recreate it.

**Do not inject tool names into the prompt.** The tools are already sent as real OpenAI tool schemas every turn (`ToolRegistry.schemas()` + connected MCP tools via `mcpTools`). The prompt only says, in prose, that the tools exist, that a terminal exists, and (for MCP) that connected tools are already in the tool list so the model can call `mcp_list` to inspect them.

Removed from the prompt/engine on purpose: the `Your toolset` table, the `Tools available this turn: <72 names>` lines (was in `buildLiveVoicePrompt` and `buildComprehensiveSystemPrompt`), the four-shell name dump, and the per-turn MCP server/tool-name dumps. Do not reintroduce them.

## Decisions locked by the user

- Native Compose only. No WebView JS app.
- Language: the user speaks Bangla; replies must be in the user's language, never English unless the user writes English. Code/identifiers/comments stay English.
- `zyraxonai.lovable.app` stays (built-in proprietary, to be enabled later).
- MCP tools keep auto-injecting as schemas; the model inspects them itself with `mcp_list`.

## Build

No Android SDK is installed in this environment yet (`local.properties` absent). Kotlin edits cannot be compiled here until the SDK is set up — make edits carefully and re-read the file after each change.
