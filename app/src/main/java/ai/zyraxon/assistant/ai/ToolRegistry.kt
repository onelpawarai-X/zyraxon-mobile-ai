// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.core.safe

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.MainActivity
import ai.zyraxon.assistant.ai.OnDeviceModel
import ai.zyraxon.assistant.lock.DeviceLocker
import ai.zyraxon.assistant.shell.BackgroundCommandService
import ai.zyraxon.assistant.shell.GitRuntime
import ai.zyraxon.assistant.shell.ShellRuntime
import ai.zyraxon.assistant.shell.ShizukuBundle
import ai.zyraxon.assistant.shell.ShizukuState
import ai.zyraxon.assistant.shell.SystemShell
import ai.zyraxon.assistant.shell.LinuxRuntime
import ai.zyraxon.assistant.shell.ScheduledCommandReceiver
import ai.zyraxon.assistant.tools.PreviewBridge
import ai.zyraxon.assistant.update.Updater
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import androidx.core.content.FileProvider

/**
 * The real mobile toolset. Every entry here does something on the device and
 * returns what actually happened — no simulated results.
 *
 * `schemas()` is what the model is offered; `execute()` is what runs. The two
 * are kept in the same file so a tool can never be advertised without an
 * implementation behind it.
 */
object ToolRegistry {

    private fun S(props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(required)).put("additionalProperties", false)

    /** The real Play Protect screen, hosted by Play services. */
    private const val PLAY_PROTECT_ACTIVITY = "com.google.android.gms.security.settings.SecuritySettingsActivity"

    private fun str() = JSONObject().put("type", "string")
    private fun num() = JSONObject().put("type", "number")
    private fun bool() = JSONObject().put("type", "boolean")

    /** Tools the UI layer owns; the engine routes these to the ViewModel. */
    val UI_TOOLS = setOf("todo_write", "code_box", "files_panel", "floating_bubble", "terminal_open", "terminal_run",
        "preview", "sub_agent", "memory_delete", "generate_image", "generate_audio", "generate_video", "ffmpeg", "git", "github_pr")

    private val defs: List<Pair<String, JSONObject>> by lazy { listOf(
        "mcp_connect" to JSONObject()
            .put("description", "Connect an MCP server so its tools become yours. Call it when the user asks to connect Canva, Cloudflare, Supabase or GitHub, or a server of their own. For canva/cloudflare/supabase this opens the browser where the user taps Allow and the connection finishes by itself. For github pass the personal access token in `token`. After connecting, that server's tools appear in your tool list on the next turn.")
            .put("parameters", S(JSONObject()
                .put("server", str().put("description", "canva | cloudflare | supabase | github | custom"))
                .put("url", str().put("description", "The MCP endpoint URL, only when server is custom"))
                .put("name", str().put("description", "A label for a custom server"))
                .put("token", str().put("description", "Access token, required for github")), listOf("server"))),
        "mcp_list" to JSONObject()
            .put("description", "List every MCP server, whether it is connected, and exactly which tools each connected one offers. Call it before using an MCP tool so you use a name that really exists.")
            .put("parameters", S(JSONObject(), emptyList())),
        "mcp_disconnect" to JSONObject()
            .put("description", "Disconnect an MCP server and forget its token. Call it when the user asks to remove or disconnect a server.")
            .put("parameters", S(JSONObject().put("server", str()), listOf("server"))),
        "mcp_resources" to JSONObject()
            .put("description", "List the files, documents and records the connected MCP servers expose. Call it when the user asks what a server can show you, or before reading a resource whose address you do not know. Entries marked as a template contain placeholders you must fill in.")
            .put("parameters", S(JSONObject().put("server", str().put("description", "Optional: only this server")), emptyList())),
        "mcp_read_resource" to JSONObject()
            .put("description", "Read one MCP resource by its URI and get its real contents. Call mcp_resources first to learn the URI. For a template, fill the placeholders in before reading.")
            .put("parameters", S(JSONObject().put("uri", str().put("description", "The resource URI, or <server>__<uri>")), listOf("uri"))),
        "mcp_prompts" to JSONObject()
            .put("description", "List the prompt templates the connected MCP servers ship, with the arguments each one takes. Call it before mcp_prompt.")
            .put("parameters", S(JSONObject().put("server", str().put("description", "Optional: only this server")), emptyList())),
        "mcp_prompt" to JSONObject()
            .put("description", "Render one MCP prompt template into real text and use it. Call mcp_prompts first for the exact name and its arguments.")
            .put("parameters", S(JSONObject()
                .put("name", str().put("description", "The prompt name, or <server>__<name>"))
                .put("arguments", JSONObject().put("type", "object")
                    .put("description", "The template's arguments as key/value pairs")), listOf("name"))),
        "mcp_subscribe" to JSONObject()
            .put("description", "Subscribe to an MCP resource so the server tells you the moment its contents change, and the fresh text is fetched for you. Call it when the user wants to keep watching a file, log or record. Not every server supports it; if it does not, you are told so.")
            .put("parameters", S(JSONObject()
                .put("uri", str().put("description", "The resource URI, or <server>__<uri>"))
                .put("action", str().put("enum", JSONArray(listOf("subscribe", "unsubscribe")))), listOf("uri"))),
        "mcp_complete" to JSONObject()
            .put("description", "Ask an MCP server to autocomplete a prompt argument or a resource URI. Call it when the user is unsure what values are valid, or you want to offer them choices.")
            .put("parameters", S(JSONObject()
                .put("server", str())
                .put("argument", str().put("description", "The argument name to complete"))
                .put("value", str().put("description", "What the user has typed so far"))
                .put("kind", str().put("description", "prompt (default) or resource"))
                .put("ref", str().put("description", "The prompt name or resource URI being completed")),
                listOf("server", "argument"))),
        "mcp_ping" to JSONObject()
            .put("description", "Check that an MCP server's session is still alive. Call it when a call fails oddly, or before a long run of MCP calls.")
            .put("parameters", S(JSONObject().put("server", str()), listOf("server"))),
        "mcp_log_level" to JSONObject()
            .put("description", "Set how much an MCP server logs back to you. Call it when you want more detail about what a server is doing, or less noise.")
            .put("parameters", S(JSONObject()
                .put("server", str())
                .put("level", str().put("enum", JSONArray(listOf("debug", "info", "notice", "warning", "error")))),
                listOf("server", "level"))),
        "code_box" to JSONObject()
            .put("description", "Show or close the live code panel on screen. Open it before writing a file so the user watches it being written, then close it when the file is done.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("open", "close", "minimize", "restore"))))
                .put("title", str()).put("code", str()), listOf("action"))),
        "files_panel" to JSONObject()
            .put("description", "Open the live file browser so the user can see and open every file you created. Call it after writing files, or when the user asks to see the project.")
            .put("parameters", S(JSONObject().put("path", str()).put("action", str().put("enum", JSONArray(listOf("open", "close")))), emptyList())),
        "floating_bubble" to JSONObject()
            .put("description", "Turn the floating chat bubble on or off. When it is on a small orb sits over every other app and the user can talk to you without leaving what they are doing. Call it when the user asks for the bubble, or says they want to reach you from inside another app.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("on", "off")))), listOf("action"))),
        "todo_write" to JSONObject()
            .put("description", "Create or update the live task board. Call it after every tool result. Never give a final answer while a task is unfinished.")
            .put("parameters", S(JSONObject().put("todos", JSONObject().put("type", "array")
                .put("items", S(JSONObject().put("id", str()).put("title", str())
                    .put("status", str().put("enum", JSONArray(listOf("pending", "in_progress", "done")))), listOf("id", "title", "status")))),
                listOf("todos"))),
        "open_app" to JSONObject()
            .put("description", "Open a real app installed on this phone. Pass `name` with the app the user named (ইউটিউব, ফেসবুক, ক্যামেরা, WhatsApp, সেটিংস…) or `package`. Never ask the user which app when they already said its name — pass that name straight here.")
            .put("parameters", S(JSONObject().put("package", str()).put("name", str()), emptyList())),
        "youtube_play" to JSONObject()
            .put("description", "Play a song or video on YouTube. Call this for any request like 'ইউটিউবে <গান/ভিডিও> চালাও/প্লে করো' — it opens YouTube at the search results for that exact query, so the music is one tap away. Never reply with a question when the user already named what to play.")
            .put("parameters", S(JSONObject().put("query", str()), listOf("query"))),
        "network_status" to JSONObject()
            .put("description", "The real connection state right now: whether the phone is online, whether it is on Wi-Fi or mobile data, and the Wi-Fi network name. Call this before answering anything about Wi-Fi, internet, or connection — never guess.")
            .put("parameters", S(JSONObject(), emptyList())),
        "list_apps" to JSONObject()
            .put("description", "List every app installed on this phone (label and package). Use it when you do not know a package name.")
            .put("parameters", S(JSONObject(), emptyList())),
        "launch_url" to JSONObject()
            .put("description", "Open a URL in the browser on this phone.")
            .put("parameters", S(JSONObject().put("url", str()), listOf("url"))),
        "verify_url" to JSONObject()
            .put("description", "Check that a link really exists before opening it or telling the user it works. Returns the real HTTP status.")
            .put("parameters", S(JSONObject().put("url", str()), listOf("url"))),
        "read_screen" to JSONObject()
            .put("description", "Read the text on the screen right now, with the tap coordinates for every element. Use it when you are about to tap or type.")
            .put("parameters", S(JSONObject(), emptyList())),
        "screen_shot" to JSONObject()
            .put("description", "Take a real screenshot of the current screen.")
            .put("parameters", S(JSONObject(), emptyList())),
        "tap" to JSONObject()
            .put("description", "Tap a point on the screen, in pixels.")
            .put("parameters", S(JSONObject().put("x", num()).put("y", num()), listOf("x", "y"))),
        "tap_text" to JSONObject()
            .put("description", "Tap the element that carries this text. Safer than tapping coordinates when the label is unique.")
            .put("parameters", S(JSONObject().put("text", str()), listOf("text"))),
        "swipe" to JSONObject()
            .put("description", "Swipe on the screen (scroll or the back gesture).")
            .put("parameters", S(JSONObject().put("x1", num()).put("y1", num()).put("x2", num()).put("y2", num()), listOf("x1", "y1", "x2", "y2"))),
        "type_text" to JSONObject()
            .put("description", "Type text into the field that currently has focus.")
            .put("parameters", S(JSONObject().put("text", str()), listOf("text"))),
        "press_key" to JSONObject()
            .put("description", "Press a system key: back, home, recents, notifications, quick_settings, lock.")
            .put("parameters", S(JSONObject().put("key", str()), listOf("key"))),
        "notify" to JSONObject()
            .put("description", "Post a real Android notification.")
            .put("parameters", S(JSONObject().put("title", str()).put("body", str()), listOf("body"))),
        "clipboard_read" to JSONObject()
            .put("description", "Read the phone clipboard.")
            .put("parameters", S(JSONObject(), emptyList())),
        "read_sms" to JSONObject()
            .put("description", "Read the newest SMS messages on this phone — for example an OTP or verification code sent to the user. Optional `contains` filters by text or sender; `count` is how many (default 5, max 20). If it fails for a missing permission, call open_settings with page 'apps', tap through to the SMS permission yourself, then call this again. Never ask the user to read the code out.")
            .put("parameters", S(JSONObject().put("contains", str()).put("count", num())
                .put("box", str().put("description", "inbox (default) | sent | all"))
                .put("since_minutes", num().put("description", "only messages from the last N minutes"))
                .put("wait_seconds", num().put("description", "wait up to N seconds (max 120) for a matching message to arrive — use right after requesting a code; the result carries newest_code")),
                emptyList())),
        "clipboard_write" to JSONObject()
            .put("description", "Write text to the phone clipboard.")
            .put("parameters", S(JSONObject().put("text", str()), listOf("text"))),
        "share" to JSONObject()
            .put("description", "Open the Android share sheet with text, a link or a file.")
            .put("parameters", S(JSONObject().put("text", str()).put("url", str()).put("title", str()), emptyList())),
        "look_camera" to JSONObject()
            .put("description", "Open the camera and take one photo, returning it as an image the model can look at. Use it when the user asks what you see or shows something to the phone.")
            .put("parameters", S(JSONObject().put("facing", str().put("enum", JSONArray(listOf("back", "front")))), emptyList())),
        "read_media" to JSONObject()
            .put("description", "Read a picture, audio or video and hand its bytes to the model. Give either a local `path` or a remote `url` — a video URL is downloaded and decoded here, so the model reads it itself instead of you describing it. For a video ask for `at_second` to pick one moment, or `frames` to sample several across the whole clip. For audio you get duration, bitrate and channel count.")
            .put("parameters", S(JSONObject()
                .put("path", str().put("description", "Local file path"))
                .put("url", str().put("description", "Remote http(s) URL to download and read"))
                .put("kind", str().put("enum", JSONArray(listOf("image", "audio", "video", "auto"))))
                .put("at_second", num().put("description", "For video: which second to grab"))
                .put("frames", num().put("description", "For video: sample this many frames evenly across the clip (max 8)"))
                .put("max_side", num().put("description", "Longest image side in pixels, default 1024")),
                emptyList())),
        "read_url" to JSONObject()
            .put("description", "Fetch a web page as text. Use it for research and to verify a page really serves the content.")
            .put("parameters", S(JSONObject().put("url", str()).put("max_chars", num()), listOf("url"))),
        "web_search" to JSONObject()
            .put("description", "Search the real web. Use it before saying you do not know something.")
            .put("parameters", S(JSONObject().put("query", str()).put("count", num()), listOf("query"))),
        "http_request" to JSONObject()
            .put("description", "Make any HTTP request (GET, POST, PUT, PATCH, DELETE) with headers and a body.")
            .put("parameters", S(JSONObject().put("url", str())
                .put("method", str().put("enum", JSONArray(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))))
                .put("headers", JSONObject().put("type", "object"))
                .put("body", str()), listOf("url"))),
        "download_file" to JSONObject()
            .put("description", "Download a URL to a file on this phone.")
            .put("parameters", S(JSONObject().put("url", str()).put("path", str()), listOf("url"))),
        "brand_kit" to JSONObject()
            .put("description", "A brand kit: colours taken from the user's logo (`image` = a file path) or given as `colors` (hex list, first = main). It is saved, and from then on every document, slide deck and design you make uses the main colour automatically — use the returned palette (and `css`) for websites and designs too. `apply_to_app` true (default) also recolours ZYRAXON itself. Use action `get` to read the saved kit, `reset` to remove it.")
            .put("parameters", S(JSONObject().put("action", str().put("enum", JSONArray(listOf("set", "get", "reset"))))
                .put("image", str()).put("colors", JSONObject().put("type", "array").put("items", str()))
                .put("name", str()).put("apply_to_app", bool()), emptyList())),
        "make_design" to JSONObject()
            .put("description", "Design a poster, banner, logo, social post/card, story or cover and export it as PNG and PDF (shown in the chat with Share). Write the design as `html` (one self-contained page; inline CSS; use font-family 'Noto Sans Bengali' for Bengali; no scripts needed) or as `svg`. `kind` sets the default pixel size: poster 1080x1528, banner 1500x500, logo 1024x1024, card 1080x1080, story 1080x1920, cover 1640x924 — or pass `width`/`height`. The page is exactly that many CSS pixels, so lay it out to fill 100% width and height. `transparent` true gives a PNG with no background (logos). `format` is png, pdf or both (default both). Look at the picture afterwards and fix anything off.")
            .put("parameters", S(JSONObject().put("kind", str()).put("html", str()).put("svg", str())
                .put("width", num()).put("height", num()).put("filename", str()).put("transparent", bool())
                .put("format", str().put("enum", JSONArray(listOf("png", "pdf", "both")))), emptyList())),
        "open_file" to JSONObject()
            .put("description", "Open a file you made (or any file on the phone) so the user SEES it. Default `in`=app: ZYRAXON steps aside and the phone's own app for that type opens the file (Word/Docs, Sheets, Slides, PDF viewer, Gallery, browser…). After it opens, call screen_shot or read_screen to confirm it is really on screen. `in`=preview shows it inside ZYRAXON's Preview panel instead (Word, Excel, PowerPoint, PDF, pictures, Markdown, CSV, code and web pages all work there). Call this right after every make_document / make_design / write_file you want the user to look at.")
            .put("parameters", S(JSONObject().put("path", str()).put("in", str().put("enum", JSONArray(listOf("app", "preview")))), listOf("path"))),
        "present_slides" to JSONObject()
            .put("description", "Present slides out loud: the 3D character on the home screen speaks while slide cards show underneath, and the next slide opens by itself when the voice finishes. Pass `slides` = [{title, bullets:[…], speech}] where `speech` is exactly what to say for that slide, in the user's language, 2 to 4 natural sentences. Use it when the user asks you to present, explain with slides, or teach a topic aloud. Do not read the slides back in your reply.")
            .put("parameters", S(JSONObject().put("slides", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")))
                .put("auto", bool()), listOf("slides"))),
        "office_template" to JSONObject()
            .put("description", "Ready Bengali office papers with [blanks]: ছুটির দরখাস্ত, চাকরির আবেদন, চালান/ইনভয়েস, সিভি, ব্যবসার প্রস্তাব, কোটেশন, নোটিশ, মিটিং কার্যবিবরণী, চুক্তিপত্র, মাসিক হিসাব (শিট), পিচ স্লাইড. Call with no `id` to list them; with an `id` to get the Markdown. Fill every [blank] from what the user said (ask for what is missing), then pass it to make_document.")
            .put("parameters", S(JSONObject().put("id", str()), emptyList())),
        "make_document" to JSONObject()
            .put("description", "Make a real Word (.docx), Excel (.xlsx) or PowerPoint (.pptx) file on this phone — no install needed, Bengali works. `type` is docx, xlsx or pptx. docx: pass `markdown` (# ## ### headings, paragraphs, - bullets, 1. numbered, | tables |, --- page break). xlsx: pass `csv` (or `markdown` table, or `sheets` = [{name, rows:[[cell,…],…]}]); a cell starting with = is a formula; the first row is the header. pptx: pass `markdown` where each `# Title` starts a slide followed by - bullets (or `slides` = [{title, bullets:[…]}]). The file appears in the chat with Open and Share buttons. Use it whenever the user asks for a document, sheet, slides, application letter, invoice, résumé or proposal.")
            .put("parameters", S(JSONObject().put("type", str().put("enum", JSONArray(listOf("docx", "xlsx", "pptx"))))
                .put("filename", str()).put("title", str()).put("markdown", str()).put("csv", str()).put("color", str())
                .put("sheets", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")))
                .put("slides", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))),
                listOf("type"))),
        "write_file" to JSONObject()
            .put("description", "Create a file or replace it whole. Missing folders are created. Use this to write code — never paste code into the chat.")
            .put("parameters", S(JSONObject().put("path", str()).put("content", str())
                .put("mode", str().put("enum", JSONArray(listOf("overwrite", "append")))), listOf("path", "content"))),
        "edit_file" to JSONObject()
            .put("description", "Write code. One tool does it all. (1) New file or full rewrite: pass `path` and `content`. (2) Change part of a file: pass `path`, `find` (exact text) and `replace`; set `all` to replace every match. (3) Add to the end: `content` with `append` true. Missing folders are created. The code you pass is shown live in the chat as it is written — never paste code into your reply.")
            .put("parameters", S(JSONObject().put("path", str()).put("content", str()).put("find", str()).put("replace", str())
                .put("all", bool()).put("append", bool()), listOf("path"))),
        "read_file" to JSONObject()
            .put("description", "Read a file. An optional line range is allowed.")
            .put("parameters", S(JSONObject().put("path", str()).put("from_line", num()).put("to_line", num()), listOf("path"))),
        "list_dir" to JSONObject()
            .put("description", "List the files and folders inside a directory.")
            .put("parameters", S(JSONObject().put("path", str()), emptyList())),
        "find_files" to JSONObject()
            .put("description", "Find files by name pattern, for example **/*.js or *.md.")
            .put("parameters", S(JSONObject().put("path", str()).put("pattern", str()), listOf("pattern"))),
        "grep_files" to JSONObject()
            .put("description", "Search text or a regular expression inside files. Returns which file and which line.")
            .put("parameters", S(JSONObject().put("path", str()).put("query", str())
                .put("regex", bool()).put("case_sensitive", bool()), listOf("query"))),
        "delete_file" to JSONObject()
            .put("description", "Delete a file or folder.")
            .put("parameters", S(JSONObject().put("path", str()).put("recursive", bool()), listOf("path"))),
        "run_shell" to JSONObject()
            .put("description", "Run a real command in YOUR OWN tool shell (bundled busybox, ~300 Unix commands) and return stdout, stderr and the exit code. It runs in the tool workspace, invisible to the user. Use this for the work behind the scenes. For a command the user should watch, use terminal_run instead. There is NO time limit by default — a long command runs to completion. Pass `timeout_ms` only for a command you expect to hang.")
            .put("parameters", S(JSONObject().put("command", str()).put("cwd", str()).put("timeout_ms", num()), listOf("command"))),
        "terminal_run" to JSONObject()
            .put("description", "Run one line in a Terminal the USER is watching (the ⌘ ZX tab). The line and its output appear in front of them, so they can watch or take over. Pass `shell:\"linux\"` to run it in ZYRAXON's full Linux system (bash, apt/pkg, git, python, node, clang) or `shell:\"android\"` for the phone's own shell; without it, the shell already on screen is used. Pair with terminal_open first. There is NO time limit by default — a long command runs to completion; pass `timeout_ms` only for one you expect to hang.")
            .put("parameters", S(JSONObject().put("command", str()).put("shell", str()).put("timeout_ms", num()), listOf("command"))),
        "terminal_open" to JSONObject()
            .put("description", "Bring the Terminal tab to the front. ONLY call this when the user explicitly asks to open or see the Terminal tab. Never call this automatically.")
            .put("parameters", S(JSONObject(), emptyList())),
        "system_shell" to JSONObject()
            .put("description", "Run a command at SYSTEM level, above the app sandbox — the way a system shell and adb do. With `level:\"root\"` it runs as uid 0 (full root: read every app's data, write /system, mount, iptables). With `level:\"shell\"` it runs as uid 2000 (the adb shell user: read most of /data, use pm/am/settings/dumpsys/input). Pass `level:\"auto\"` to use root when the phone is rooted and shell otherwise. It needs the user to have Shizuku running and to have granted ZYRAXON access — if that is missing the tool says exactly what to do, and run_shell still works. Returns the real exit code and real output. There is NO time limit by default; pass `timeout_ms` only for a command you expect to hang.")
            .put("parameters", S(JSONObject().put("command", str()).put("level", str()).put("timeout_ms", num()), listOf("command"))),
        "linux_run" to JSONObject()
            .put("description", "Run a command inside ZYRAXON's own full Linux system — bash, apt, git, python, node and clang, not the bundled busybox. It is the same system the user sees in the Terminal tab, and it is installed straight from the APK, so it works with no network at all. Use `action:\"status\"` to see whether it is installed, `action:\"install\"` to unpack it from the APK, or `action:\"run\"` with a command. If it is not installed yet, `run` installs it automatically first.")
            .put("parameters", S(JSONObject().put("action", str()).put("command", str()).put("timeout_ms", num()), listOf("action"))),
        "setup_shizuku" to JSONObject()
            .put("description", "Set up Shizuku, the system-access app ZYRAXON carries inside itself. Use `action:\"status\"` to see whether it is installed, running and granted; `action:\"install\"` to install the bundled copy (Android shows its own confirm dialog — the user taps Install once); `action:\"open\"` to open Shizuku so the user can start it; `action:\"request\"` to ask Shizuku for permission (the user taps Allow in Shizuku's dialog). Do this before the system-level tools (system_shell, linux_run) can work. The user must make the Install and Allow taps themselves — Android does not let an app make those two taps.")
            .put("parameters", S(JSONObject().put("action", str()), listOf("action"))),
        "background_command" to JSONObject()
            .put("description", "Run one command in a background service so it keeps running after the user closes the app — for long jobs like a big download, a clone or a build. It shows a notification with a Stop button. Use `action:\"start\"` with a command, or `action:\"stop\"` to end the running one, `action:\"status\"` to see whether one is running and how it ended, `action:\"logs\"` for the full output so far, or `action:\"kill\"` to force-kill the process tree.")
            .put("parameters", S(JSONObject().put("action", str()).put("command", str()), listOf("action"))),
        "wait" to JSONObject()
            .put("description", "Pause on purpose, then carry on — the one tool for timing. Use it instead of guessing: after you start a slow job (a build, an install, a server, a deploy) wait here for it instead of replying too early, and use it to poll for a condition that comes true on its own (a port that opens, a file that appears, a URL that starts answering). Give `seconds` to simply pause, and/or `until` with `command` to poll a command until its output contains `contains` (or until it exits 0 when `contains` is empty). Polls every `every_ms` (default 1000) and stops the moment the condition is met. There is no overall deadline: it keeps checking until the condition is true. Returns how long it waited and what it last saw.")
            .put("parameters", S(JSONObject()
                .put("seconds", num())
                .put("until", str().put("description", "condition | command-exit — omit to only sleep"))
                .put("command", str())
                .put("contains", str())
                .put("every_ms", num()), emptyList())),
        "publish_website" to JSONObject()
            .put("description", "Write a site to local storage and serve it on a public port. Returns a local URL. You write the HTML yourself with the `html` argument (or a map of files) — never ask the user for files, just build it.")
            .put("parameters", S(JSONObject().put("name", str()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("name"))),
        "publish_github" to JSONObject()
            .put("description", "Publish a site or project to a real GitHub repository the user owns. Creates the repo if missing, commits every file and pushes. Returns the real repository URL. Needs a GitHub token saved in settings.")
            .put("parameters", S(JSONObject()
                .put("repo", str()).put("name", str()).put("description", str())
                .put("private", bool()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("repo"))),
        "publish_cloudflare" to JSONObject()
            .put("description", "Publish a site to the real internet with no account: writes the files, starts a Cloudflare quick tunnel and returns a public https URL, then opens it in the browser. You write the HTML yourself with the `html` argument — never ask the user to supply files; if they just said \"make a website\", build one and pass it here.")
            .put("parameters", S(JSONObject().put("name", str()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("name"))),
        "github_actions" to JSONObject()
            .put("description", "Drive a repository's GitHub Actions from the phone — the CI side of a build, without a desktop. Use `action:\"list\"` to see the workflows, `action:\"dispatch\"` with `workflow` (file name or id) and `ref` to start one, `action:\"runs\"` to see recent runs (with their status and conclusion), `action:\"status\"` with `run_id` to watch one run, and `action:\"logs\"` with `run_id` to read its output. Needs a GitHub token saved in settings. Pair with `wait` to poll `status` until a run finishes instead of guessing.")
            .put("parameters", S(JSONObject()
                .put("action", str())
                .put("repo", str())
                .put("workflow", str())
                .put("ref", str())
                .put("run_id", str())
                .put("inputs", JSONObject().put("type", "object")), listOf("action", "repo"))),
        "schedule_command" to JSONObject()
            .put("description", "Run a shell command later, at a real wall-clock time, surviving the app being closed — the OS holds the timer, not this app. Use `action:\"schedule\"` with `command` and either `in_seconds` or `at` (epoch seconds) to book it, `action:\"list\"` is not supported (only one alarm is held at a time per command), and `action:\"cancel\"` with the same `command` to drop it. When the alarm fires the command runs in the background and the assistant is woken with the result, exactly like `background_command`. Use this for \"১০ মিনিট পরে ফোন চেক করো\" style jobs; use `wait` only inside a turn that is already running.")
            .put("parameters", S(JSONObject()
                .put("action", str())
                .put("command", str())
                .put("in_seconds", num())
                .put("at", num()), listOf("action", "command"))),
        "build_app" to JSONObject()
            .put("description", "Build this ZYRAXON project on the phone and install the result. Runs the project's own Gradle build in a real shell (`assembleDebug`, or `assembleRelease` with variant:\"release\") and then hands the produced APK to the Android installer, which the user confirms. Use `install:false` to build only. The build output is streamed to the live view, so the user sees progress. Long builds are expected — start it, then `wait` if you need to.")
            .put("parameters", S(JSONObject()
                .put("variant", str())
                .put("install", bool())
                .put("timeout_ms", num()), emptyList())),
        "sys_info" to JSONObject()
            .put("description", "Real facts about this phone: Android version, model, CPU, RAM, storage, and the current app.")
            .put("parameters", S(JSONObject(), emptyList())),
        "platform_info" to JSONObject()
            .put("description", "Where you are really running, and the shell your commands will use.")
            .put("parameters", S(JSONObject(), emptyList())),
        "process_list" to JSONObject()
            .put("description", "List the processes running on this phone.")
            .put("parameters", S(JSONObject(), emptyList())),
        "memory_write" to JSONObject()
            .put("description", "Store a durable fact about the user in the local database so it is remembered forever.")
            .put("parameters", S(JSONObject().put("key", str()).put("value", str()).put("tag", str()), listOf("key", "value"))),
        "memory_read" to JSONObject()
            .put("description", "Look up what is remembered about the user.")
            .put("parameters", S(JSONObject().put("query", str()), emptyList())),
        "call_state" to JSONObject()
            .put("description", "Check whether a call is ringing or in progress.")
            .put("parameters", S(JSONObject(), emptyList())),
        "call_answer" to JSONObject()
            .put("description", "Answer the incoming call, so you are the one speaking on it.")
            .put("parameters", S(JSONObject(), emptyList())),
        "call_hangup" to JSONObject()
            .put("description", "Hang up the current call.")
            .put("parameters", S(JSONObject(), emptyList())),
        "call_dial" to JSONObject()
            .put("description", "Place a real call. Pass `number` for a phone number, or `name` for a saved contact (মা, বাবা, রহিম…). Call this whenever the user says to call someone — never just say you are calling.")
            .put("parameters", S(JSONObject().put("number", str()).put("name", str()), emptyList())),
        "request_permission" to JSONObject()
            .put("description", "Open the system dialog that lets the user grant a permission with one tap. Use it when a tool fails for a missing permission. name is one of: camera, microphone, location, accessibility, overlay, notifications, files.")
            .put("parameters", S(JSONObject().put("name", str()), listOf("name"))),
        "voice_change" to JSONObject()
            .put("description", "Adjust your own speaking voice. Both a male and a female voice are installed and both work, so 'male' and 'female' each select the real one. rate is speed (0.5-2.0, default 1.0) and pitch is tone (0.5-2.0). Use it when the user asks for a different gender, speed or tone. The change takes effect on your next sentence.")
            .put("parameters", S(JSONObject()
                .put("gender", str().put("enum", JSONArray(listOf("female", "male"))))
                .put("rate", num()).put("pitch", num()), listOf("gender"))),
        "open_settings" to JSONObject()
            .put("description", "Open a system settings page: accessibility, overlay, notifications, apps, wifi, battery, play_protect. It opens the page; combine it with screen_shot and tap/tap_text/swipe to flip the switch yourself instead of stopping here.")
            .put("parameters", S(JSONObject().put("page", str()), listOf("page"))),
        "channel_post" to JSONObject()
            .put("description", "Publish to a real messaging channel or app: Telegram, WhatsApp, Facebook, Messenger, YouTube, X, Instagram, Gmail, or SMS. Opens the app's own composer pre-filled with the text, link and optional image, so the post goes out from the user's real account. For a fully unattended post, chain tap/tap_text/press_key afterwards to hit the send button.")
            .put("parameters", S(JSONObject()
                .put("channel", str())
                .put("text", str())
                .put("url", str())
                .put("image_path", str())
                .put("to", str()), listOf("channel"))),
        "serve_local" to JSONObject()
            .put("description", "Start a real HTTP server on the phone for a folder or a site, and return the LAN URL other devices on the same Wi-Fi can open. Use it after publishing files so the user can preview on any device.")
            .put("parameters", S(JSONObject()
                .put("name", str())
                .put("html", str())
                .put("files", JSONObject().put("type", "object"))
                .put("port", num()), listOf("name"))),
        "device_lock" to JSONObject()
            .put("description", "Lock or unlock the phone's screen for real, or set up the permission that makes locking possible. Actions: lock (lock the screen now), unlock (lift the keyguard — only works when the phone has no PIN/pattern/password, otherwise the phone's own lock stays in charge), enroll (open the system screen where the user taps Allow once), status (report whether the admin is active and whether a secure lock exists), secure_lock_settings (open the system screen to set a PIN). Use this when the user asks to lock the phone, or when they say someone is threatening them or trying to take their data — lock at once, then speak.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("lock", "unlock", "enroll", "status", "secure_lock_settings")))),
                listOf("action"))),
        "live_voice" to JSONObject()
            .put("description", "Switch between the two ways you speak. `on` opens a live speech-to-speech session with a realtime model: the model hears the microphone and answers with its own voice, and the phone's text-to-speech is silenced so only one voice is heard. `off` returns to normal text turns read by the phone's voice. Call `on` when the user asks for a real conversation, the fastest back-and-forth, or a live call; call `off` when they want the normal assistant back.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("on", "off")))), listOf("action"))),
        "memory_delete" to JSONObject()
            .put("description", "Forget one remembered fact by its key (use when the user says it is wrong, or asks you to forget it).")
            .put("parameters", S(JSONObject().put("key", str()), listOf("key"))),
        "generate_image" to JSONObject()
            .put("description", "Create a picture from a text description and save it to the project. The picture appears in the chat with share buttons. Write the prompt in English, rich in detail (subject, style, light, mood). Call once per picture.")
            .put("parameters", S(JSONObject()
                .put("prompt", str()).put("width", num()).put("height", num()).put("seed", num())
                .put("model", str().put("description", "flux (default, best quality) | turbo (faster)"))
                .put("name", str().put("description", "File name without extension")),
                listOf("prompt"))),
        "generate_audio" to JSONObject()
            .put("description", "Make spoken audio (an .mp3) from any text with the app's own voices. For several voices (a dialogue, a story with characters) pass `segments`, each with its own `text` and `voice`. `voice` = {gender: female|male, age: child|teen|adult|senior, language: bn|hi|en|ur|ar|... , tone: calm|cheerful|serious|..., name}. Use action `voices` to list the voices the server really has. The audio appears in the chat with a player and share buttons.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("speak", "voices"))))
                .put("text", str()).put("language", str()).put("gender", str()).put("age", str()).put("tone", str()).put("name", str())
                .put("segments", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("text", str()).put("voice", JSONObject().put("type", "object")))
                    .put("required", JSONArray(listOf("text"))))),
                emptyList())),
        "generate_video" to JSONObject()
            .put("description", "Edit a real video (not AI-generated footage) from scenes, using ffmpeg inside ZYRAXON Linux. Each scene: {type: image|video|text|color, source (a file path/URL, or for image an image prompt to generate), text (on-screen), caption, narration (spoken by the app's voices), duration (seconds), transition}. Optional `music` (file or URL), `voice` (narration voice), `width`/`height`/`fps`, `name`. Runs in the background with a live percentage in the chat and can take 5–10 minutes for long videos. Returns the .mp4.")
            .put("parameters", S(JSONObject()
                .put("title", str()).put("name", str()).put("music", str()).put("width", num()).put("height", num()).put("fps", num())
                .put("voice", JSONObject().put("type", "object"))
                .put("scenes", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))),
                listOf("scenes"))),
        "ffmpeg" to JSONObject()
            .put("description", "Run ffmpeg directly inside ZYRAXON Linux for any media edit: cut, join, overlay, subtitles, speed, audio mix, convert. `args` is the argument string after `ffmpeg` (paths relative to `cwd`, default is the project folder). It installs itself the first time. Progress shows in the chat.")
            .put("parameters", S(JSONObject().put("args", str()).put("cwd", str()).put("output", str().put("description", "Path of the file this command produces, so the chat can show it")), listOf("args"))),
        "git" to JSONObject()
            .put("description", "Real git on the phone. Actions: clone (url[, dir, branch]), init, status, add (files, default all), commit (message), push (remote, branch; uses the saved GitHub token), pull, branch (name; creates), checkout (branch), log (count), diff, remote (url to set origin), scan (a summary of all repos in the workspace). `dir` is the repo folder (default: the current project).")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("clone", "init", "status", "add", "commit", "push", "pull", "branch", "checkout", "log", "diff", "remote", "scan"))))
                .put("dir", str()).put("url", str()).put("branch", str()).put("message", str())
                .put("files", str()).put("remote", str()).put("name", str()).put("count", num()),
                listOf("action"))),
        "github_pr" to JSONObject()
            .put("description", "GitHub pull requests through the API with the saved token. Actions: create (title, body, head, base), list, get (number), merge (number; method merge|squash|rebase), comment (number, body), close (number). `repo` is owner/name; omit it to use the current repo's origin.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("create", "list", "get", "merge", "comment", "close"))))
                .put("repo", str()).put("title", str()).put("body", str()).put("head", str()).put("base", str())
                .put("number", num()).put("method", str()),
                listOf("action"))),
        "preview" to JSONObject()
            .put("description", "Drive the app's real browser (the Preview panel), and see what it shows. Actions: open (url — a web address, `localhost:PORT` for a server you started, or a file path), search (query; Google), reload, back, forward, screenshot (you receive the picture), scroll (dy pixels, positive is down), click (selector, or the visible text/label, or x/y — pressed with a real touch; if nothing matches you get the list of what is clickable), type (text; selector optional — it types into the focused or first input; submit=true presses Enter), read (the page's visible text), console (JS errors and logs), eval (script), problems (collected load/JS errors), close. Always open what you built and look at it before you say it works.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("open", "search", "reload", "back", "forward", "screenshot", "scroll", "click", "type", "read", "console", "eval", "problems", "close"))))
                .put("url", str()).put("query", str()).put("selector", str()).put("text", str())
                .put("submit", JSONObject().put("type", "boolean"))
                .put("x", num()).put("y", num()).put("dy", num()).put("script", str()),
                listOf("action"))),
        "sub_agent" to JSONObject()
            .put("description", "Run other agents in the background on parts of a big job. Actions: spawn (task — everything the helper needs; title; model optional: any model id you can see; mode optional chat|build|agent, default build), status (id — progress %, current step), list, result (id — final output), message (id, text — extra instruction), stop (id), wait (id, seconds). The user sees each helper with its model and percentage. Several can run at once. When one finishes you are told automatically.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("spawn", "status", "list", "result", "message", "stop", "wait"))))
                .put("task", str()).put("title", str()).put("model", str()).put("mode", str())
                .put("id", str()).put("text", str()).put("seconds", num()),
                listOf("action"))),
        "on_device_model" to JSONObject()
            .put("description", "Run a language model on the phone itself, with no network at all. Actions: list (show which bundles are already downloaded), load (load one by file name so it can answer offline), unload, generate (produce text from the loaded model — use it only when the user asked for offline or private answers), download (fetch a suggested bundle; every suggested bundle is public and needs no token), import (register a .task file already on the phone). Use this when the user asks for offline answers or says their internet is down.")
            .put("parameters", S(JSONObject()
                .put("action", str().put("enum", JSONArray(listOf("list", "load", "unload", "generate", "download", "import"))))
                .put("file", str().put("description", "File name for load, or the path of a .task file for import"))
                .put("prompt", str().put("description", "The prompt for generate"))
                .put("token", str().put("description", "Optional Hugging Face read token, only needed for a gated bundle")),
                listOf("action")))
    ) }


    // ---- Modes ------------------------------------------------------------
    // The mode decides which tools the model is offered AND which it may run.
    // Both are enforced here, in code: the prompt can ask a model to behave, but
    // it is this list that makes a Chat-mode model unable to touch a file.

    private val MEMORY_TOOLS = setOf("memory_write", "memory_read", "memory_delete")

    /** Office files and designs work in every mode: asking for a letter or a poster should never need Agent mode. */
    private val OFFICE_TOOLS = setOf("make_document", "office_template", "make_design", "brand_kit", "present_slides", "open_file")

    private val BUILD_TOOLS = MEMORY_TOOLS + OFFICE_TOOLS + setOf(
        "edit_file", "read_file", "list_dir", "find_files", "grep_files", "delete_file", "download_file",
        "run_shell", "terminal_run", "terminal_open", "linux_run", "background_command", "wait",
        "serve_local", "publish_website", "publish_cloudflare", "publish_github", "github_actions", "build_app",
        "git", "github_pr", "preview", "generate_image", "generate_audio", "generate_video", "ffmpeg",
        "todo_write", "read_url", "web_search", "http_request", "verify_url",
        "sys_info", "platform_info", "process_list", "share", "notify",
        "mcp_connect", "mcp_list", "mcp_disconnect", "mcp_resources", "mcp_read_resource",
        "mcp_prompts", "mcp_prompt", "mcp_subscribe", "mcp_complete", "mcp_ping", "mcp_log_level"
    )

    /** What Agent mode never gets: the old Write tool, and panels now owned by the chat itself. */
    private val AGENT_EXCLUDED = setOf("write_file", "code_box", "files_panel")

    /** Tools that only exist in Agent mode on top of Build's. */
    private val SUB_AGENT_TOOLS = setOf("sub_agent")

    fun toolNamesFor(mode: ai.zyraxon.assistant.model.AgentMode): Set<String> = when (mode) {
        ai.zyraxon.assistant.model.AgentMode.CHAT -> MEMORY_TOOLS + OFFICE_TOOLS
        ai.zyraxon.assistant.model.AgentMode.BUILD -> BUILD_TOOLS
        ai.zyraxon.assistant.model.AgentMode.AGENT -> names().toSet() - AGENT_EXCLUDED
    }

    /** True when [name] may run in [mode]. A server's own MCP tools follow the mode too. */
    fun allowed(mode: ai.zyraxon.assistant.model.AgentMode, name: String): Boolean =
        if (name.contains("__")) mode != ai.zyraxon.assistant.model.AgentMode.CHAT
        else name in toolNamesFor(mode)

    /** The tool schemas the model is offered in [mode]. */
    fun schemas(mode: ai.zyraxon.assistant.model.AgentMode): JSONArray {
        val allow = toolNamesFor(mode)
        val arr = JSONArray()
        for ((name, d) in defs) {
            if (name !in allow) continue
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", d.getString("description"))
                    .put("parameters", d.getJSONObject("parameters"))))
        }
        return arr
    }

    fun schemas(): JSONArray {
        val arr = JSONArray()
        for ((name, d) in defs) {
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", d.getString("description"))
                    .put("parameters", d.getJSONObject("parameters"))))
        }
        return arr
    }

    fun names(): List<String> = defs.map { it.first }

    /** Where the assistant keeps its own files: the tool workspace (`…/zyraxon`). */
    fun workspace(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zyraxon").apply { mkdirs() }

    private fun resolve(context: Context, path: String?): File {
        val p = (path ?: "").trim()
        if (p.isEmpty()) return workspace(context)
        val shared = java.io.File("/storage/emulated/0")
        // Pictures, DCIM, Download… and /sdcard/… mean the phone's shared storage.
        val sdcard = p.removePrefix("~/").let {
            when {
                it.startsWith("/sdcard") -> File(shared, it.removePrefix("/sdcard").trimStart('/'))
                it.startsWith("/storage/self/primary") -> File(shared, it.removePrefix("/storage/self/primary").trimStart('/'))
                it.startsWith("/storage/emulated/0") -> File(it)
                else -> null
            }
        }
        if (sdcard != null) return sdcard
        val f = File(p)
        if (!f.isAbsolute) {
            val first = p.trimStart('/').substringBefore('/')
            val inWork = File(workspace(context), p)
            if (!inWork.exists() && first.isNotEmpty()) {
                val standardFolders = setOf(
                    "pictures", "dcim", "download", "downloads", "documents",
                    "movies", "music", "audio", "podcasts", "recordings",
                    "alarms", "notifications", "ringtones"
                )
                if (first.lowercase() in standardFolders) {
                    return File(shared, p.trimStart('/'))
                }
                val hit = try { shared.listFiles()?.firstOrNull { it.name.equals(first, ignoreCase = true) } } catch (_: Exception) { null }
                if (hit != null) return File(shared, p.trimStart('/').let { rest ->
                    val tail = rest.substringAfter('/', "")
                    if (tail.isEmpty()) hit.name else hit.name + "/" + tail
                })
            }
            return inWork
        }
        val allowed = context.getExternalFilesDir(null)?.absolutePath
            ?: context.filesDir.absolutePath
        val inAppStorage = f.absolutePath.startsWith(context.filesDir.absolutePath) ||
            (allowed.isNotEmpty() && f.absolutePath.startsWith(allowed)) ||
            f.absolutePath.startsWith("/storage/emulated/0") ||
            f.absolutePath.startsWith("/sdcard")
        if (inAppStorage) return f
        // Any other absolute path is the path the agent asked for: /data/local/tmp, an SD card,
        // /mnt, /system… The phone's own permissions decide, and a refusal is reported as it is
        // (and retried at system level when Shizuku runs) — it is never quietly moved elsewhere.
        return f
    }

    suspend fun execute(context: Context, name: String, args: JSONObject): JSONObject =
        execute(context, name, args, live = null)

    /**
     * Runs a tool. When [live] is given, a shell tool streams each line of its
     * output to it as the line is produced, so the chat can show the output of a
     * long command while it runs instead of only after it exits.
     */
    suspend fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        live: ((String) -> Unit)?
    ): JSONObject = try {
        when (name) {
            "open_app" -> openApp(context, args)
            "open_file" -> openFileTool(context, args)
            "youtube_play" -> youtubePlay(context, args)
            "network_status" -> networkStatus(context)
            "list_apps" -> ok(DeviceBridge.listApps(context).take(6000), "ইনস্টল করা অ্যাপের তালিকা")
            "launch_url" -> launchUrl(context, args)
            "verify_url" -> verifyUrl(args)
            "read_screen" -> readScreen()
            "screen_shot" -> screenShot()
            "tap" -> ok(DeviceBridge.tap(args.optDouble("x", 0.0).toFloat(), args.optDouble("y", 0.0).toFloat()), "ট্যাপ")
            "tap_text" -> ok(DeviceBridge.tapText(args.safe("text")), "টেক্সটে ট্যাপ")
            "swipe" -> ok(DeviceBridge.swipe(
                args.optDouble("x1", 0.0).toFloat(), args.optDouble("y1", 0.0).toFloat(),
                args.optDouble("x2", 0.0).toFloat(), args.optDouble("y2", 0.0).toFloat()), "সোয়াইপ")
            "type_text" -> ok(DeviceBridge.typeText(args.safe("text")), "টাইপ")
            "press_key" -> ok(DeviceBridge.pressKey(args.safe("key")), "কী চাপা হয়েছে")
            "notify" -> { DeviceBridge.notify(context, args.safe("title", "ZYRAXON"), args.safe("body")); ok("notified", "নোটিফিকেশন পাঠানো হয়েছে") }
            "clipboard_read" -> ok(clipboard(context).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: "", "ক্লিপবোর্ড")
            "clipboard_write" -> { clipboard(context).setPrimaryClip(ClipData.newPlainText("zyraxon", args.safe("text"))); ok("copied", "ক্লিপবোর্ডে লেখা হয়েছে") }
            "share" -> share(context, args)
            "read_sms" -> readSms(context, args)
            "look_camera" -> lookCamera(context, args)
            "read_media" -> readMedia(context, args)
            "read_url" -> readUrl(args)
            "web_search" -> webSearch(args)
            "http_request" -> httpRequest(args)
            "download_file" -> downloadFile(context, args)
            "make_document" -> makeDocument(context, args)
            "office_template" -> officeTemplate(args)
            "make_design" -> makeDesign(context, args)
            "present_slides" -> {
                val list = ai.zyraxon.assistant.present.Presentation.parse(args.optJSONArray("slides"))
                if (list.isEmpty()) err("no-slides", "স্লাইড দেওয়া হয়নি")
                else {
                    ai.zyraxon.assistant.present.Presentation.start(list, args.optBoolean("auto", true))
                    ok(JSONObject().put("slides", list.size), "${list.size}টি স্লাইডের প্রেজেন্টেশন শুরু হয়েছে")
                }
            }
            "brand_kit" -> brandKit(context, args)
            "write_file" -> writeFile(context, args)
            "edit_file" -> editFile(context, args)
            "read_file" -> readFile(context, args)
            "list_dir" -> listDir(context, args)
            "find_files" -> findFiles(context, args)
            "grep_files" -> grepFiles(context, args)
            "delete_file" -> deleteFile(context, args)
            "run_shell" -> runShell(context, args, live)
            "system_shell" -> systemShell(context, args, live)
            "setup_shizuku" -> setupShizuku(context, args)
            "linux_run" -> linuxRun(context, args, live)
            "background_command" -> backgroundCommand(context, args)
            "wait" -> waitTool(context, args, live)
            "publish_website" -> publish(context, args, tunnel = false)
            "publish_cloudflare" -> publish(context, args, tunnel = true)
            "publish_github" -> publishGithub(context, args)
            "github_actions" -> githubActions(context, args)
            "schedule_command" -> scheduleCommand(context, args)
            "build_app" -> buildApp(context, args, live)
            "sys_info" -> ok(sysInfo(context), "ডিভাইসের তথ্য")
            "platform_info" -> ok(ShellRuntime.describe(context), "প্ল্যাটফর্ম")
            "process_list" -> runShell(context, JSONObject().put("command", "ps -A 2>/dev/null || ps"))
            "call_state" -> ok(DeviceBridge.callState(), "কলের অবস্থা")
            "call_answer" -> ok(DeviceBridge.answerCall(context), "কল রিসিভ")
            "call_hangup" -> ok(DeviceBridge.hangUpCall(context), "কল কেটে দেওয়া")
            "call_dial" -> callDial(context, args)
            "request_permission" -> requestPermission(context, args.safe("name"))
            "open_settings" -> openSettings(context, args.safe("page"))
            "voice_change" -> changeVoice(context, args)
            "channel_post" -> channelPost(context, args)
            "serve_local" -> serveLocal(context, args)
            "device_lock" -> deviceLock(context, args)
            "live_voice" -> liveVoice(args)
            "on_device_model" -> onDeviceModel(context, args)
            "git" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.GitTools.run(context, args, live) }
            "github_pr" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.GitTools.pr(context, args) }
            "generate_image" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.MediaTools.image(context, args, live) }
            "generate_audio" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.MediaTools.audio(context, args, live) }
            "generate_video" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.MediaTools.video(context, args, live) }
            "ffmpeg" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.zyraxon.assistant.tools.MediaTools.ffmpegTool(context, args, live) }
            else -> err("unknown-tool", "অজানা টুল: $name")
        }
    } catch (e: Exception) {
        err((e.message ?: e.toString()), "টুল ব্যর্থ: $name")
    }

    private fun ok(data: Any?, summary: String) = JSONObject().put("ok", true).put("summary", ai.zyraxon.assistant.i18n.Loc.tool(summary)).put("data", data)
    private fun err(error: String, summary: String) = JSONObject().put("ok", false).put("error", error).put("summary", ai.zyraxon.assistant.i18n.Loc.tool(summary))

    /** The newest inbox messages, so a one-time code can be read without the user. */
    private fun readSms(context: Context, args: JSONObject): JSONObject {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return err("no-permission", "SMS পড়ার অনুমতি নেই — open_settings(apps) খুলে অ্যাপ পারমিশন থেকে SMS নিজে চালু করো, তারপর আবার চেষ্টা করো")
        }
        val want = args.safe("contains").lowercase()
        val max = args.optInt("count", 5).coerceIn(1, 50)
        val box = when (args.safe("box", "inbox").lowercase()) {
            "sent" -> "content://sms/sent"
            "all" -> "content://sms"
            else -> "content://sms/inbox"
        }
        val sinceMs = if (args.has("since_minutes"))
            System.currentTimeMillis() - args.optLong("since_minutes", 0L).coerceAtLeast(0L) * 60_000L else 0L
        // `wait_seconds` lets one call cover the gap between "code requested" and
        // "code arrived": it re-reads every 1.5 s until a match shows up.
        val waitMs = args.optLong("wait_seconds", 0L).coerceIn(0L, 120L) * 1000L
        val otpRegex = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
        val deadline = System.currentTimeMillis() + waitMs

        fun readOnce(): JSONArray {
            val out = JSONArray()
            context.contentResolver.query(
                Uri.parse(box),
                arrayOf("address", "body", "date"),
                if (sinceMs > 0) "date >= ?" else null,
                if (sinceMs > 0) arrayOf(sinceMs.toString()) else null,
                "date DESC LIMIT 200"
            )?.use { c ->
                while (c.moveToNext() && out.length() < max) {
                    val from = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    if (want.isNotEmpty() && !from.lowercase().contains(want) && !body.lowercase().contains(want)) continue
                    val item = JSONObject().put("from", from).put("body", body.take(800)).put("time", c.getLong(2))
                    otpRegex.find(body)?.let { item.put("code", it.value) }
                    out.put(item)
                }
            }
            return out
        }

        return try {
            var out = readOnce()
            // Only a wait that was asked for loops; and only until something matches.
            while (out.length() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(1500)
                out = readOnce()
            }
            val newestCode = (0 until out.length()).firstNotNullOfOrNull { out.getJSONObject(it).safe("code").takeIf { s -> s.isNotBlank() } }
            val result = JSONObject().put("messages", out)
            if (newestCode != null) result.put("newest_code", newestCode)
            ok(result, "${out.length()}টি SMS পাওয়া গেছে" + (newestCode?.let { " — সর্বশেষ কোড: $it" } ?: ""))
        } catch (e: Exception) {
            err(e.message ?: e.toString(), "SMS পড়া যায়নি")
        }
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Opens a file for the user. In `app` mode the file goes to the phone's own viewer through a
     * FileProvider link, which brings that app to the front and so puts ZYRAXON in the background;
     * if no app can show the type, the in-app Preview shows it instead, so something always opens.
     */
    private fun openFileTool(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.safe("path"))
        if (!f.isFile) return err("no-file", "ফাইলটি নেই: ${f.path}")
        val inPreview = args.safe("in").equals("preview", true)
        if (!inPreview) {
            val launched = runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ai.zyraxon.assistant.viewer.DocViewer.mimeOf(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }.isSuccess
            if (launched) return ok(JSONObject().put("path", f.absolutePath).put("in", "app"),
                "${f.name} অন্য অ্যাপে খোলা হয়েছে — এখন screen_shot দিয়ে যাচাই করো")
        }
        return runCatching {
            PreviewBridge.openFile(context, f)
            ok(JSONObject().put("path", f.absolutePath).put("in", "preview"),
                if (inPreview) "${f.name} প্রিভিউতে খোলা হয়েছে" else "${f.name} খোলার অ্যাপ নেই — প্রিভিউতে খোলা হয়েছে")
        }.getOrElse { err("open-failed", "ফাইল খোলা যায়নি: ${it.message}") }
    }

    private fun openApp(context: Context, args: JSONObject): JSONObject {
        val target = args.safe("package").ifBlank { args.safe("name") }
            .ifBlank { args.safe("app") }
        if (target.isBlank()) {
            return err("no-target", "কোন অ্যাপ খুলব বলোনি — অ্যাপের নাম বা প্যাকেজ দাও")
        }
        // The model rarely knows a package name, and asking the user "which app?"
        // when they already named one (ইউটিউব, ফেসবুক, ক্যামেরা…) is the defect
        // this table removes. Common names resolve without a list_apps round.
        var resolved = DeviceBridge.resolvePackage(context, target)
        if (resolved.isBlank()) {
            resolved = KNOWN_APPS[target.trim().lowercase()]
                ?.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }
                ?: ""
        }
        if (resolved.isBlank()) {
            return err("app-not-installed", "এই ফোনে \"$target\" ইনস্টল করা নেই")
        }
        DeviceBridge.openApp(context, resolved)
        return ok(resolved, "\"$target\" খোলা হয়েছে")
    }

    /**
     * Plays a song or video on YouTube: builds a real search URL and opens it, so
     * the result page loads without a single tap. The user says "ইউটিউবে অমুক গান
 * প্লে করো" and expects the music, not a question.
     */
    private fun youtubePlay(context: Context, args: JSONObject): JSONObject {
        val query = args.safe("query").ifBlank { args.safe("text") }
            .ifBlank { args.safe("name") }
        if (query.isBlank()) return err("no-query", "কী প্লে করব বলোনি")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        // The YouTube app opens a search result page; the browser does the same
        // when the app is absent. Either way the music is one tap away, which is
        // the honest result — nothing pretends the song is already playing.
        val intent = if (context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE) != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YOUTUBE_PACKAGE)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok(JSONObject().put("url", url), "ইউটিউবে \"$query\" খোলা হয়েছে")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "ইউটিউব খোলা যায়নি: $query")
        }
    }

    /**
     * A real, self-contained starter page, written when the user asked for a
     * website but the model supplied no HTML. Better a working page than a
     * refusal — the user asked for a site, not a lecture about files.
     */
    private fun defaultSite(name: String): String = """
 <!doctype html>
 <html lang="bn">
 <head>
 <meta charset="utf-8">
 <meta name="viewport" content="width=device-width, initial-scale=1">
 <title>$name</title>
 <style>
 :root { color-scheme: dark; }
 body { margin:0; font-family: system-ui, -apple-system, "Noto Sans Bengali", sans-serif;
 background: radial-gradient(1200px 600px at 20% 0%, #12304a, #05070d 60%); color:#eaf6ff;
 min-height:100vh; display:grid; place-items:center; }
 main { text-align:center; padding:48px 24px; }
 h1 { font-size:clamp(28px,7vw,56px); margin:0 0 12px; letter-spacing:-.02em; }
 p { opacity:.8; font-size:clamp(15px,3.4vw,19px); margin:0; }
 .dot { width:10px; height:10px; border-radius:50%; background:#38e8ff; display:inline-block;
 margin-right:8px; box-shadow:0 0 16px #38e8ff; }
 </style>
 </head>
 <body>
 <main>
 <h1><span class="dot"></span>$name</h1>
 <p>ZYRAXON এই সাইটটি তৈরি করেছে।</p>
 </main>
 </body>
 </html>
 """.trimIndent()

    /**
     * The real connection state, read from the system. Asking the assistant "what
 * is my Wi-Fi status?" used to get an invented answer; this returns the facts
     * so it can report them instead.
     */
    private fun networkStatus(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val out = JSONObject()
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        out.put("connected", connected)
        out.put("transport", when {
            wifi -> "wifi"; cellular -> "cellular"; ethernet -> "ethernet"; else -> "none"
        })
        out.put("wifi", wifi)
        out.put("cellular", cellular)
        out.put("wifiEnabled", runCatching {
            (context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
        }.getOrDefault(false))
        val name = runCatching {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wm.connectionInfo?.ssid?.trim('"')
 }.getOrNull()
 out.put("ssid", if (name.isNullOrBlank() || name == "<unknown ssid>") "" else name)
 val label = when {
 !connected -> "ইন্টারনেট নেই"
 wifi -> "ওয়াই-ফাই চালু" + (if (!name.isNullOrBlank() && name != "<unknown ssid>") " ($name)" else "")
 cellular -> "মোবাইল ডেটা চালু"
 else -> "সংযোগ আছে"
 }
 return ok(out, label)
 }

 private fun callDial(context: Context, args: JSONObject): JSONObject {
 val number = args.safe("number").trim()
 if (number.isNotBlank()) {
 return if (DeviceBridge.dial(context, number)) ok(number, "কল করা হচ্ছে: $number")
 else err("dial-failed", "কল করা যায়নি: $number")
 }
 val name = args.safe("name").ifBlank { args.safe("contact") }.trim()
 if (name.isBlank()) return err("no-target", "কাকে কল করব বলোনি")
 // A spoken name is looked up in the contact book, so "মা-কে কল করো" places
 // a real call instead of the assistant announcing a call it never made.
 val found = DeviceBridge.numberForName(context, name)
 if (found.isBlank()) {
 return err("contact-not-found", "কনট্যাক্টে \"$name\" পাওয়া যায়নি — নম্বরটা বলে দাও")
        }
        return if (DeviceBridge.dial(context, found)) ok(found, "কল করা হচ্ছে: $name")
        else err("dial-failed", "কল করা যায়নি: $name")
    }

    private fun launchUrl(context: Context, args: JSONObject): JSONObject {
        var url = args.safe("url")
        if (url.isBlank()) return err("no-url", "কোন লিংক খুলব বলোনি")
        // A path to a local file: serve its folder and open it over loopback, so
        // "write the page, then show it" is one step and no LAN address is shown.
        val localPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("/") -> url
            else -> ""
        }
        if (localPath.isNotBlank()) {
            val f = File(localPath)
            if (f.exists()) {
                val dir = if (f.isDirectory) f else f.parentFile
                if (dir != null && startHttpServer(dir, 8792) != null) {
                    url = "http://127.0.0.1:8792/" + (if (f.isDirectory) "" else f.name)
                }
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok(url, "ব্রাউজারে খোলা হয়েছে: $url")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "লিংকটি খোলা যায়নি: $url")
        }
    }

    private fun verifyUrl(args: JSONObject): JSONObject {
        val url = args.safe("url")
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 0; readTimeout = 0
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val code = c.responseCode
            c.disconnect()
            ok(code, "HTTP $code — $url")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "লিংকটি নেই বা পৌঁছানো যাচ্ছে না: $url")
        }
    }

    private fun share(context: Context, args: JSONObject): JSONObject {
        val text = listOf(args.safe("text"), args.safe("url")).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return err("empty", "শেয়ার করার কিছু দাওনি")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_TITLE, args.safe("title"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(send, args.safe("title", "শেয়ার")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ok("shared", "শেয়ার শিট খোলা হয়েছে")
    }

    private fun fetch(url: String, maxBytes: Int = 300_000): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 0; readTimeout = 0
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            setRequestProperty("Accept", "text/html,application/json,*/*")
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { r ->
            val sb = StringBuilder(); val buf = CharArray(8192)
            while (sb.length < maxBytes) {
                val n = r.read(buf); if (n <= 0) break; sb.append(buf, 0, n)
            }
            sb.toString()
        } ?: ""
        c.disconnect()
        return code to body
    }

    /**
     * Opens the camera, waits for one frame, and returns it as an image the model
     * can actually look at. The capture happens in a headless activity so the
     * user never loses the assistant screen.
     */
    /**
     * Reads the screen as text and, where the platform allows it, as a picture the
     * model can see. Both come back in the same call so a single turn is enough.
     */
    private fun readScreen(): JSONObject {
        val text = DeviceBridge.readScreen()
        val b64 = DeviceBridge.screenShotBase64(2500)
        val result = ok(
            if (b64.isBlank()) text else "text=$text\nimage_url=data:image/jpeg;base64,$b64",
            "স্ক্রিন পড়া হয়েছে"
        )
        if (b64.isNotBlank()) result.put("image_data_url", "data:image/jpeg;base64,$b64")
        return result
    }

    /** Grabs one real screenshot and returns it as an image for the model. */
    private fun screenShot(): JSONObject {
        val b64 = DeviceBridge.screenShotBase64(2500)
        if (b64.isBlank()) {
            // Below API 30 there is no screenshot API at all; the window text is
            // the honest fallback rather than an invented picture.
            return ok(DeviceBridge.readScreen(), "স্ক্রিনশট সম্ভব নয়, শুধু স্ক্রিনের টেক্সট")
        }
        val url = "data:image/jpeg;base64,$b64"
        return ok("image_url=$url", "স্ক্রিনশট নেওয়া হয়েছে").put("image_data_url", url)
    }

    /**
     * The current screen as a data-URL image, or "" when the platform cannot
     * capture it. Called by the engine right after a screen action so the next
     * round sees the result of the tap it just made, without the model having to
     * spend a call on `screen_shot` and without the user having to ask.
     */
    fun screenFrame(): String {
        val b64 = DeviceBridge.screenShotBase64(2500)
        return if (b64.isBlank()) "" else "data:image/jpeg;base64,$b64"
    }

    private suspend fun lookCamera(context: Context, args: JSONObject): JSONObject {
        val facing = args.safe("facing", "back")
        return try {
            val bitmap = CameraCapture.capture(context, facing)
                ?: return err("camera-empty", "ক্যামেরা থেকে কোনো ছবি আসেনি")
            val file = CameraCapture.saveToWorkspace(context, bitmap)
            ok(
                "image_url=" + CameraCapture.toDataUrl(bitmap) +
                    "\nfile=" + file.absolutePath,
                "ক্যামেরার ছবি নেওয়া হয়েছে (${facing})"
            ).put("image_data_url", CameraCapture.toDataUrl(bitmap))
                .put("image_path", file.absolutePath)
        } catch (e: Exception) {
            err("camera-failed", "ক্যামেরা চালু করা গেল না: ${e.message}")
        }
    }

    /**
     * Reads a picture, audio or video for the model.
     *
     * A remote `url` is downloaded first, because the user asked to point at a
     * link and have the model process it itself rather than describe it. A video
     * can yield one frame (`at_second`) or several sampled evenly across the
     * clip (`frames`), which is what makes "read this video" real instead of a
     * single still.
     */
    private fun readMedia(context: Context, args: JSONObject): JSONObject {
        val url = args.safe("url").trim()
        val path = args.safe("path").trim()
        if (url.isBlank() && path.isBlank()) {
            return err("no-source", "কোন ফাইল বা লিংক দেবে বলোনি")
        }

        val file: File
        var downloaded = false
        if (url.isNotBlank()) {
            file = downloadToCache(context, url, args.safe("kind", "auto"))
                ?: return err("download-failed", "লিংক থেকে ফাইল নামানো গেল না: $url")
            downloaded = true
        } else {
            file = resolve(context, path)
            if (!file.exists()) return err("not-found", "ফাইল নেই: ${file.absolutePath}")
        }

        val kind = args.safe("kind", "auto").let {
            if (it != "auto") it else kindOf(file)
        }
        val maxSide = args.optInt("max_side", 1024).coerceIn(256, 2048)

        return when (kind) {
            "image" -> {
                val bitmap = CameraCapture.decodeFile(file, maxSide)
                    ?: return err("decode-failed", "ছবিটি পড়া গেল না")
                val dataUrl = CameraCapture.toDataUrl(bitmap, maxSide)
                val note = if (downloaded) " (লিংক থেকে নামানো)" else ""
                ok("image_url=$dataUrl", "ছবি পড়া হয়েছে: ${file.name}$note")
                    .put("image_data_url", dataUrl)
            }
            "video" -> {
                val requested = args.optInt("frames", 0)
                val times = if (requested > 1) {
                    val duration = videoDurationSeconds(file)
                    val count = requested.coerceIn(2, 8)
                    (0 until count).map { i ->
                        if (duration > 0) (duration * i / (count - 1)) else i.toLong()
                    }
                } else {
                    listOf(args.optDouble("at_second", 0.0).toLong())
                }

                val frames = times.mapNotNull { second ->
                    CameraCapture.videoFrame(file, second)?.let { second to it }
                }
                if (frames.isEmpty()) return err("frame-failed", "ভিডিওর ফ্রেম নেওয়া গেল না")

                val dataUrls = frames.map { (_, bmp) -> CameraCapture.toDataUrl(bmp, maxSide) }
                val note = if (downloaded) " (লিংক থেকে নামানো)" else ""
                val at = frames.joinToString(", ") { "${it.first}s" }
                ok(
                    "video=${file.name} size=${file.length()} frames_at=$at\nimage_url=${dataUrls.first()}",
                    "ভিডিওর ${frames.size}টি ফ্রেম পড়া হয়েছে ($at)$note"
                ).put("image_data_url", dataUrls.first())
                    .put("image_data_urls", JSONArray(dataUrls))
            }
            else -> {
                val meta = android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.absolutePath)
                    fun m(key: Int) = r.extractMetadata(key) ?: "?"
                    "duration_ms=${m(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)} " +
                        "bitrate=${m(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)} " +
                        "channels=${m(android.media.MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)} " +
                        "mime=${m(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}"
                }
                val note = if (downloaded) " (লিংক থেকে নামানো)" else ""
                ok("audio=${file.name} size=${file.length()} $meta", "অডিও ফাইল পড়া হয়েছে$note")
            }
        }
    }

    private fun kindOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "image"
        "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac" -> "audio"
        "mp4", "mkv", "webm", "mov", "3gp", "avi" -> "video"
        else -> "image"
    }

    /** Seconds of a video, or 0 when the retriever cannot say. */
    private fun videoDurationSeconds(file: File): Long = try {
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ms / 1000L
        }
    } catch (_: Exception) {
        0L
    }

    /**
     * Downloads a URL into the app cache and returns the file. The extension is
     * taken from the URL, and from the content type when the URL has none, so the
     * kind sniffing afterwards sees a real extension.
     */
    private fun downloadToCache(context: Context, url: String, kindHint: String): File? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 0; readTimeout = 0
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            null
        } else {
            val ext = extensionFor(url, conn.contentType, kindHint)
            val dir = File(context.cacheDir, "zyraxon/media").apply { mkdirs() }
            val out = File(dir, "media_" + System.currentTimeMillis() + "." + ext)
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (out.length() == 0L) null else out
        }
    } catch (_: Exception) {
        null
    }

    private fun extensionFor(url: String, contentType: String?, kindHint: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/')
            .substringAfterLast('.', "").lowercase()
        if (fromUrl.length in 2..5 && fromUrl.all { it.isLetterOrDigit() }) return fromUrl
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            type.contains("mp4") -> "mp4"
            type.contains("webm") -> "webm"
            type.contains("mpeg") && type.contains("video") -> "mpeg"
            type.contains("quicktime") -> "mov"
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("gif") -> "gif"
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            type.contains("mpeg") || type.contains("mp3") -> "mp3"
            type.contains("wav") -> "wav"
            type.contains("ogg") -> "ogg"
            type.contains("m4a") || type.contains("aac") -> "m4a"
            else -> when (kindHint) {
                "video" -> "mp4"
                "audio" -> "mp3"
                else -> "jpg"
            }
        }
    }

    private fun readUrl(args: JSONObject): JSONObject {
        val url = args.safe("url")
        return try {
            val (code, body) = fetch(url, args.optInt("max_chars", 20000))
            if (code !in 200..299) return err("http-$code", "পেজটি HTTP $code দিচ্ছে: $url")
            val text = body.replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").trim()
                .take(args.optInt("max_chars", 20000))
            ok(text, "পেজ পড়া হয়েছে (${text.length} অক্ষর)")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "পেজটি পড়া যায়নি: $url")
        }
    }

    private fun webSearch(args: JSONObject): JSONObject {
        val q = args.safe("query")
        val out = JSONArray()
        try {
            val (_, body) = fetch("https://html.duckduckgo.com/html/?q=" + Uri.encode(q))
            val re = Regex("""result__a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
 for (m in re.findAll(body)) {
 if (out.length() >= args.optInt("count", 6)) break
 out.put(JSONObject()
 .put("url", m.groupValues[1])
 .put("title", m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()))
 }
 } catch (_: Exception) {}
 if (out.length() == 0) {
 try {
 val (_, body) = fetch("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srsearch=" + Uri.encode(q))
 val arr = JSONObject(body).getJSONObject("query").getJSONArray("search")
 for (i in 0 until minOf(arr.length(), 6)) {
 val o = arr.getJSONObject(i)
 out.put(JSONObject()
 .put("title", o.safe("title"))
 .put("url", "https://en.wikipedia.org/wiki/" + Uri.encode(o.safe("title")))
 .put("snippet", o.safe("snippet").replace(Regex("<[^>]+>"), "")))
 }
 } catch (_: Exception) {}
 }
 return if (out.length() > 0) ok(out.toString(), "ওয়েবে খুঁজে পাওয়া গেছে (${out.length()} ফলাফল)")
 else err("no-results", "\"$q\" এর জন্য কিছু পাওয়া যায়নি")
    }

    private fun httpRequest(args: JSONObject): JSONObject {
        val url = args.safe("url")
        val method = args.safe("method", "GET").uppercase()
        return try {
            val body = args.safe("body")
            val sendBody = body.isNotBlank() && method != "GET" && method != "HEAD"
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 0; readTimeout = 0
                doOutput = sendBody
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                val h = args.optJSONObject("headers")
                if (h != null) for (k in h.keys()) setRequestProperty(k, h.safe(k))
            }
            if (sendBody) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.take(60000) ?: ""
            c.disconnect()
            ok(JSONObject().put("status", code).put("body", text), "HTTP $code")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "রিকোয়েস্ট ব্যর্থ: $url")
        }
    }

    private fun downloadFile(context: Context, args: JSONObject): JSONObject {
        val url = args.safe("url")
        val dest = resolve(context, args.safe("path").ifBlank { "downloads/" + url.substringAfterLast('/').take(80) })
        return try {
            dest.parentFile?.mkdirs()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 0; readTimeout = 0
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            val size = dest.length(); c.disconnect()
            if (size <= 0) err("empty", "ফাইলটি খালি এসেছে") else ok(dest.absolutePath, "ডাউনলোড হয়েছে: ${dest.name} (${size / 1024}KB)")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "ডাউনলোড ব্যর্থ: $url")
        }
    }

    private fun brandFile(context: Context) = File(File(workspace(context), "brand").apply { mkdirs() }, "brand.json")

    /** The saved brand's main colour (#RRGGBB), or null when no kit is set. */
    fun brandPrimary(context: Context): String? = runCatching {
        JSONObject(brandFile(context).readText()).optString("primary").ifBlank { null }
    }.getOrNull()

    /** Recolours the app's own accents from a saved kit; null clears it. */
    fun applyBrandToApp(primary: String?, secondary: String?) {
        fun c(h: String?) = h?.let { ai.zyraxon.assistant.brand.BrandPalette.parse(it) }
            ?.let { androidx.compose.ui.graphics.Color(0xFF000000.toInt() or (it[0] shl 16) or (it[1] shl 8) or it[2]) }
        ai.zyraxon.assistant.ui.theme.Glass.brandPrimary = c(primary)
        ai.zyraxon.assistant.ui.theme.Glass.brandSecondary = c(secondary)
    }

    private fun brandJson(name: String, p: ai.zyraxon.assistant.brand.BrandPalette.Palette) = JSONObject()
        .put("name", name).put("primary", p.primary).put("secondary", p.secondary).put("accent", p.accent)
        .put("background", p.background).put("text", p.text).put("on_primary", p.onPrimary).put("css", p.css())

    private fun brandKit(context: Context, args: JSONObject): JSONObject {
        val prefs = context.getSharedPreferences("zyraxon_brand", Context.MODE_PRIVATE)
        when (args.safe("action", "set").lowercase()) {
            "get" -> {
                val f = brandFile(context)
                if (!f.exists()) return ok(JSONObject().put("set", false), "ব্র্যান্ড কিট এখনো নেই")
                return ok(JSONObject(f.readText()), "ব্র্যান্ড কিট পাওয়া গেছে")
            }
            "reset" -> {
                brandFile(context).delete()
                prefs.edit().clear().apply()
                applyBrandToApp(null, null)
                return ok(JSONObject().put("set", false), "ব্র্যান্ড কিট মুছে ফেলা হয়েছে")
            }
        }
        val palette = run {
            val arr = args.optJSONArray("colors")
            val given = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
            if (given.isNotEmpty()) return@run ai.zyraxon.assistant.brand.BrandPalette.fromColors(given)
            val path = args.safe("image")
            if (path.isBlank()) return@run null
            val file = resolve(context, path)
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0) return@run null
            var sample = 1
            while (opts.outWidth / sample > 400 || opts.outHeight / sample > 400) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(
                file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@run null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            ai.zyraxon.assistant.brand.BrandPalette.fromPixels(px)
        } ?: return err("no-colors", "`image` (লোগোর ফাইল) বা `colors` (hex তালিকা) দাও")
        val json = brandJson(args.safe("name", "brand"), palette)
        brandFile(context).writeText(json.toString(2))
        val toApp = args.optBoolean("apply_to_app", true)
        prefs.edit().putString("primary", palette.primary).putString("secondary", palette.secondary)
            .putBoolean("apply", toApp).apply()
        if (toApp) applyBrandToApp(palette.primary, palette.secondary)
        return ok(
            json.put("applied_to_app", toApp)
                .put("next", "এখন থেকে make_document, make_design-এ এই রং নিজে থেকে বসবে; ওয়েবসাইট/ডিজাইনের CSS-এ json.css ব্যবহার করো।"),
            "ব্র্যান্ড কিট তৈরি: ${palette.primary} · ${palette.secondary} · ${palette.accent}"
        )
    }

    private fun makeDesign(context: Context, args: JSONObject): JSONObject {
        val markup = args.safe("html").ifBlank { args.safe("svg") }
        if (markup.isBlank()) return err("empty", "html বা svg দাও")
        val kind = args.safe("kind", "poster")
        val def = ai.zyraxon.assistant.design.DesignHtml.defaultSize(kind)
        val w = args.optInt("width", def.w).coerceIn(200, 4000)
        val h = args.optInt("height", def.h).coerceIn(200, 4000)
        val transparent = args.optBoolean("transparent", kind.equals("logo", true))
        val format = args.safe("format", "both").lowercase()
        val dir = File(workspace(context), "designs").apply { mkdirs() }
        val base = args.safe("filename").substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { kind.replace(Regex("[^A-Za-z0-9]"), "") + "-" + System.currentTimeMillis() / 1000 }
        val page = ai.zyraxon.assistant.design.DesignHtml.wrap(markup, w, h, transparent)
        val bmp = ai.zyraxon.assistant.design.DesignRender.bitmap(context, page, w, h, transparent)
            ?: return err("render-failed", "ডিজাইন আঁকা যায়নি — html সহজ করে আবার চেষ্টা করো")
        val media = JSONArray()
        val files = JSONArray()
        try {
            if (format != "pdf") {
                val png = File(dir, "$base.png")
                ai.zyraxon.assistant.design.DesignRender.savePng(bmp, png)
                media.put(JSONObject().put("kind", "image").put("path", png.absolutePath).put("title", png.name))
                files.put(png.absolutePath)
            }
            if (format != "png") {
                val pdf = File(dir, "$base.pdf")
                ai.zyraxon.assistant.design.DesignRender.savePdf(bmp, pdf)
                media.put(JSONObject().put("kind", "file").put("path", pdf.absolutePath).put("title", pdf.name))
                files.put(pdf.absolutePath)
            }
            File(dir, "$base.html").writeText(page)
        } catch (e: Exception) {
            return err(e.message ?: e.toString(), "ডিজাইন সেভ করা যায়নি")
        } finally {
            bmp.recycle()
        }
        return ok(
            JSONObject().put("files", files).put("next", "এবার open_file দিয়ে খুলে ইউজারকে দেখাও, তারপর screen_shot দিয়ে যাচাই করো").put("size", "${w}x$h").put("source", File(dir, "$base.html").absolutePath),
            "ডিজাইন তৈরি হয়েছে (${w}×$h)"
        ).put("media", media)
    }

    /** The colour for a document: the `color` the agent passed, else the saved brand's main colour, else the default blue. */
    private fun accentFor(context: Context, args: JSONObject): String {
        val given = ai.zyraxon.assistant.brand.BrandPalette.parse(args.safe("color"))
        val hex = if (given != null) "#%02X%02X%02X".format(given[0], given[1], given[2]) else brandPrimary(context)
        return (hex ?: "#2F5496").removePrefix("#")
    }

    private fun officeTemplate(args: JSONObject): JSONObject {
        val id = args.safe("id")
        if (id.isBlank()) {
            val arr = JSONArray()
            ai.zyraxon.assistant.office.OfficeTemplates.all.forEach {
                arr.put(JSONObject().put("id", it.id).put("title", it.title).put("type", it.type))
            }
            return ok(JSONObject().put("templates", arr), "${arr.length()}টি টেমপ্লেট আছে")
        }
        val t = ai.zyraxon.assistant.office.OfficeTemplates.find(id)
            ?: return err("not-found", "এই টেমপ্লেট নেই: $id — id ছাড়া ডাকলে তালিকা পাবে")
        return ok(
            JSONObject().put("id", t.id).put("title", t.title).put("type", t.type).put("markdown", t.markdown.trim())
                .put("next", "প্রতিটি [ফাঁকা] ব্যবহারকারীর তথ্য দিয়ে ভরো (না জানলে জিজ্ঞেস করো), তারপর make_document(type=${t.type}, markdown/csv=…) ডাকো।"),
            t.title
        )
    }

    private fun makeDocument(context: Context, args: JSONObject): JSONObject {
        val type = args.safe("type").lowercase().removePrefix(".")
        if (type !in listOf("docx", "xlsx", "pptx")) return err("bad-type", "type হবে docx, xlsx বা pptx")
        val md = args.safe("markdown")
        val title = args.safe("title")
        val dir = File(workspace(context), "documents").apply { mkdirs() }
        val base = args.safe("filename").substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifBlank {
                title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-').ifBlank { "document-" + System.currentTimeMillis() }
            }
        val file = File(dir, "$base.$type")
        try {
            val buffer = java.io.ByteArrayOutputStream()
            buffer.let { out ->
                when (type) {
                    "docx" -> {
                        val blocks = ai.zyraxon.assistant.office.OfficeText.docFromMarkdown(md.ifBlank { args.safe("content") })
                        if (blocks.isEmpty()) return err("empty", "markdown-এ লেখা দাও")
                        ai.zyraxon.assistant.office.OfficeFiles.docx(title, blocks, out, accentFor(context, args))
                    }
                    "xlsx" -> {
                        val sheets = mutableListOf<ai.zyraxon.assistant.office.OfficeFiles.Sheet>()
                        val arr = args.optJSONArray("sheets")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val rowsJson = o.optJSONArray("rows") ?: continue
                            val rows = (0 until rowsJson.length()).map { r ->
                                val row = rowsJson.optJSONArray(r)
                                if (row == null) listOf(rowsJson.opt(r)?.toString().orEmpty())
                                else (0 until row.length()).map { c -> if (row.isNull(c)) "" else row.get(c).toString() }
                            }
                            sheets.add(ai.zyraxon.assistant.office.OfficeFiles.Sheet(o.safe("name", "Sheet" + (i + 1)), rows))
                        }
                        if (sheets.isEmpty()) {
                            val rows = when {
                                args.safe("csv").isNotBlank() -> ai.zyraxon.assistant.office.OfficeText.rowsFromCsv(args.safe("csv"))
                                md.isNotBlank() -> ai.zyraxon.assistant.office.OfficeText.rowsFromMarkdownTable(md)
                                else -> emptyList()
                            }
                            if (rows.isEmpty()) return err("empty", "csv, markdown টেবিল বা sheets দাও")
                            sheets.add(ai.zyraxon.assistant.office.OfficeFiles.Sheet(title.ifBlank { "Sheet1" }, rows))
                        }
                        ai.zyraxon.assistant.office.OfficeFiles.xlsx(sheets, out, accentFor(context, args))
                    }
                    else -> {
                        val slides = mutableListOf<ai.zyraxon.assistant.office.OfficeFiles.Slide>()
                        val arr = args.optJSONArray("slides")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val b = o.optJSONArray("bullets")
                            slides.add(ai.zyraxon.assistant.office.OfficeFiles.Slide(
                                o.safe("title"),
                                if (b == null) emptyList() else (0 until b.length()).map { b.get(it).toString() },
                                o.safe("notes")
                            ))
                        }
                        if (slides.isEmpty()) slides.addAll(ai.zyraxon.assistant.office.OfficeText.slidesFromMarkdown(md))
                        if (slides.isEmpty()) return err("empty", "markdown-এ `# শিরোনাম` দিয়ে স্লাইড লেখো, বা slides দাও")
                        ai.zyraxon.assistant.office.OfficeFiles.pptx(slides, out, accentFor(context, args))
                    }
                }
            }
            file.writeBytes(buffer.toByteArray())
        } catch (e: Exception) {
            runCatching { file.delete() }
            return err(e.message ?: e.toString(), "ফাইল বানানো যায়নি: ${file.name}")
        }
        val label = when (type) { "docx" -> "ডকুমেন্ট"; "xlsx" -> "শিট"; else -> "স্লাইড" }
        return ok(
            JSONObject().put("path", file.absolutePath).put("bytes", file.length())
                .put("next", "এবার open_file(path) দিয়ে খুলে ইউজারকে দেখাও, তারপর screen_shot দিয়ে যাচাই করো"),
            "$label তৈরি হয়েছে: ${file.name}"
        ).put("media", JSONArray().put(JSONObject().put("kind", "file").put("path", file.absolutePath).put("title", file.name)))
    }

    private fun writeFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.safe("path"))
        val content = args.safe("content")
        try {
            f.parentFile?.mkdirs()
            if (args.safe("mode", "overwrite") == "append") f.appendText(content)
            else f.writeText(content)
            return ok(JSONObject().put("path", f.absolutePath).put("bytes", f.length()), "${f.name} লেখা হয়েছে (${f.length()} বাইট)")
        } catch (e: Exception) {
            // An absolute path outside the app: try again at system level, then report the real reason.
            if (File(args.safe("path").trim()).isAbsolute) {
                val sysErr = systemWrite(context, f, content, args.safe("mode", "overwrite") == "append")
                if (sysErr == null) return ok(JSONObject().put("path", f.absolutePath).put("via", "system"), "${f.name} সিস্টেম-লেভেলে লেখা হয়েছে")
                return err("write-denied", "${f.path} এ লেখা যায়নি: ${e.message ?: e}. $sysErr")
            }
            // A path the model invented — a bad character, a reserved name, a
            // directory where a file should be — must not end the job with
            // "ফাইল লেখা যায়নি". The content is written to a safe name inside the
            // workspace instead, so the site still gets built and the model keeps
            // going. This is the second level the system prompt alone could not
            // provide: the tool itself refuses to fail.
            val safeName = (args.safe("path").substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "file.txt" })
            val fallback = File(workspace(context), safeName)
            return try {
                fallback.parentFile?.mkdirs()
                fallback.writeText(content)
                ok(
                    JSONObject().put("path", fallback.absolutePath).put("bytes", fallback.length())
                        .put("note", "মূল পাথটি লেখা যায়নি, তাই এখানে লেখা হয়েছে"),
                    "${fallback.name} লেখা হয়েছে (বিকল্প পাথে)"
                )
            } catch (e: Exception) {
                err((e.message ?: e.toString()), "ফাইল লেখা যায়নি: ${args.safe("path")}")
            }
        }
    }

    /** Writes through Shizuku (root or the adb shell user) when the app sandbox refuses a path. Null on success, else the reason. */
    private fun systemWrite(context: Context, target: File, content: String, append: Boolean): String? {
        val tmp = File(context.getExternalFilesDir(null) ?: context.cacheDir, "sys_write.tmp")
        return try {
            tmp.writeText(content)
            val q = { x: String -> "'" + x.replace("'", "'\\''") + "'" }
            val cmd = "mkdir -p ${q(target.parent ?: "/")} && cat ${q(tmp.absolutePath)} ${if (append) ">>" else ">"} ${q(target.absolutePath)}"
            val r = SystemShell.runBest(context, cmd)
                ?: return "সিস্টেম-লেভেল পেতে Shizuku চালু করে ZYRAXON-কে অনুমতি দাও (setup_shizuku), অথবা /sdcard/Download-এর মতো খোলা জায়গায় লেখো।"
            if (r.ok) null else (r.stderr.ifBlank { r.stdout }.trim().take(200))
        } catch (e: Exception) { e.message ?: e.toString() } finally { runCatching { tmp.delete() } }
    }

    private fun editFile(context: Context, args: JSONObject): JSONObject {
        // `content` means write (create, overwrite or append); `find` means patch.
        if (args.has("content") && !args.has("find")) {
            return writeFile(context, JSONObject()
                .put("path", args.safe("path")).put("content", args.safe("content"))
                .put("mode", if (args.optBoolean("append")) "append" else "overwrite"))
        }
        if (!args.has("find")) return err("bad-args", "edit_file-এ `content` (লেখা) বা `find`+`replace` (বদল) দিতে হবে")
        val f = resolve(context, args.safe("path"))
        if (!f.exists()) return err("not-found", "ফাইলটি নেই: ${f.name}")
        return try {
            val text = f.readText()
            val find = args.safe("find")
            if (!text.contains(find)) return err("not-found-in-file", "\"$find\" ফাইলে পাওয়া যায়নি")
            val out = if (args.optBoolean("all")) text.replace(find, args.safe("replace"))
            else text.replaceFirst(find, args.safe("replace"))
            f.writeText(out)
            ok(JSONObject().put("path", f.absolutePath), "${f.name} সম্পাদনা করা হয়েছে")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "সম্পাদনা ব্যর্থ: ${f.name}")
        }
    }

    private fun readFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.safe("path"))
        if (!f.exists()) return err("not-found", "ফাইলটি নেই: ${f.name}")
        if (f.isDirectory) return listDir(context, args)
        return try {
            val from = args.optInt("from_line", 0)
            val to = args.optInt("to_line", 0)
            var text = f.readText()
            if (from > 0) {
                val lines = text.split("\n")
                text = lines.drop(maxOf(from - 1, 0)).let { if (to > 0) it.take(maxOf(to - from + 1, 1)) else it }.joinToString("\n")
            }
            ok(text.take(120000), "${f.name} পড়া হয়েছে")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "ফাইল পড়া যায়নি: ${f.name}")
        }
    }

    private fun listDir(context: Context, args: JSONObject): JSONObject {
        val d = resolve(context, args.safe("path"))
        if (!d.exists()) return err("not-found", "ফোল্ডারটি নেই: ${d.name}")
        val kids = d.listFiles()
            ?: return err("no-access", "ফোল্ডারটি পড়া যায়নি (স্টোরেজ অনুমতি লাগবে): ${d.name}")
        val arr = JSONArray()
        kids.sortedBy { it.name }.forEach {
            arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("bytes", if (it.isDirectory) 0 else it.length()))
        }
        return ok(arr.toString(), "${d.name}/ এ ${arr.length()} টি এন্ট্রি")
    }

    private fun findFiles(context: Context, args: JSONObject): JSONObject {
        val root = resolve(context, args.safe("path"))
        val pattern = args.safe("pattern", "*").substringAfterLast('/')
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*").replace("\\?", ".") + "$", RegexOption.IGNORE_CASE)
        val out = JSONArray()
        root.walkTopDown().take(4000).forEach {
            if (out.length() < 200 && regex.matches(it.name)) out.put(it.absolutePath)
        }
        return ok(out.toString(), "${out.length()} টি ফাইল পাওয়া গেছে")
    }

    private fun grepFiles(context: Context, args: JSONObject): JSONObject {
        val root = resolve(context, args.safe("path"))
        val query = args.safe("query")
        val caseSensitive = args.optBoolean("case_sensitive")
        val matcher: (String) -> Boolean = if (args.optBoolean("regex")) {
            val re = try { Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) } catch (_: Exception) { null }
            { s -> re?.containsMatchIn(s) == true }
        } else {
            { s -> if (caseSensitive) s.contains(query) else s.contains(query, ignoreCase = true) }
        }
        val out = JSONArray()
        root.walkTopDown().take(3000).forEach { f ->
            if (out.length() >= 120 || !f.isFile || f.length() > 2_000_000) return@forEach
            try {
                f.readLines().forEachIndexed { i, line ->
                    if (out.length() < 120 && matcher(line)) {
                        out.put(JSONObject().put("file", f.absolutePath).put("line", i + 1).put("text", line.trim().take(200)))
                    }
                }
            } catch (_: Exception) {}
        }
        return ok(out.toString(), "${out.length()} টি মিল পাওয়া গেছে")
    }

    private fun deleteFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.safe("path"))
        if (!f.exists()) return err("not-found", "ফাইলটি নেই: ${f.name}")
        val okDel = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (okDel) ok(f.absolutePath, "${f.name} মোছে ফেলা হয়েছে") else err("failed", "${f.name} মোছা যায়নি")
    }

    private fun runShell(context: Context, args: JSONObject, live: ((String) -> Unit)? = null): JSONObject {
        val cmd = args.safe("command")
        val cwd = resolve(context, args.safe("cwd").ifBlank { null })
        // No default deadline: a command runs until it decides it is finished, the
        // way a real shell behaves. `apt install`, `git clone` and a long build all
        // run to completion instead of being cut off. Pass `timeout_ms` only when
        // the command itself is expected to hang.
        val timeout = args.optLong("timeout_ms", 0)
        // busybox has no git, so a `git` line is served by JGit here too —
        // otherwise `git clone` would fail with "applet not found".
        GitRuntime.runCommand(context, cmd, cwd)?.let { g ->
            return ok(
                JSONObject()
                    .put("code", if (g.ok) 0 else 1)
                    .put("stdout", if (g.ok) g.message else "")
                    .put("stderr", if (g.ok) "" else g.message)
                    .put("timed_out", false)
                    .put("shell", "jgit"),
                "$ ${cmd.trim()} ${if (g.ok) "ok" else "failed"}"
            )
        }
        var r = ShellRuntime.runStreaming(
            context, cmd,
            cwd = cwd,
            timeoutMs = timeout,
            onChunk = { _, text -> live?.invoke(text) }
        )
        // The app's own shell is sandboxed. When it is refused (permission / not permitted) and
        // Shizuku runs, the same command is repeated at system level, so the agent can work anywhere.
        var viaSystem = false
        if (r.code != 0 && Regex("(?i)permission denied|operation not permitted|read-only file system|EACCES").containsMatchIn(r.stderr + r.stdout)) {
            val sys = runCatching {
                SystemShell.runBest(context, (if (cwd.isDirectory) "cd '${cwd.absolutePath.replace("'", "'\\''")}' && " else "") + cmd, timeout)
            }.getOrNull()
            if (sys != null) {
                viaSystem = true
                r = r.copy(code = sys.code, stdout = sys.stdout, stderr = sys.stderr)
            }
        }
        if (r.code == -1 && r.stderr.isEmpty() && r.stdout.isEmpty()) {
            return err("failed", "Could not run the command: $cmd")
        }
        val tail = r.stdout.trim().lines().lastOrNull()?.take(160)
        val label = buildString {
            append("$ ${r.command} exit ${r.code}")
            if (r.timedOut) append(" (timeout ${timeout}ms)")
            if (!tail.isNullOrBlank()) append(" | $tail")
        }
        return ok(
            JSONObject()
                .put("code", r.code)
                .put("stdout", r.stdout)
                .put("stderr", r.stderr)
                .put("timed_out", r.timedOut)
                .put("shell", if (viaSystem) "system (Shizuku)" else ShellRuntime.shell(context)),
            label
        )
    }

    /**
     * `background_command` — a command that outlives the app. Long jobs go here
     * so closing the app does not kill them, and the user can stop the running
     * one with the notification's button or `action:"stop"`.
     */
    private fun backgroundCommand(context: Context, args: JSONObject): JSONObject {
        return when (args.safe("action", "status").trim().lowercase()) {
            "start" -> {
                val command = args.safe("command").trim()
                if (command.isEmpty()) return err("bad-args", "Provide a command")
                BackgroundCommandService.start(context, command)
                ok(
                    JSONObject().put("running", true).put("command", command),
                    "Running in the background: $command — keeps going after the app is closed; stop it from the notification"
                )
            }
            "stop" -> {
                BackgroundCommandService.stop(context)
                ok(JSONObject().put("running", false), "Background command stopped")
            }
            "kill" -> {
                BackgroundCommandService.kill(context)
                ok(JSONObject().put("running", false), "Background command killed")
            }
            "logs" -> {
                val out = BackgroundCommandService.lastOutput
                ok(
                    JSONObject()
                        .put("running", BackgroundCommandService.running)
                        .put("command", BackgroundCommandService.lastCommand)
                        .put("exit", BackgroundCommandService.lastExit)
                        .put("bytes", out.length)
                        .put("output", out),
                    if (out.isBlank()) "এখনো কোনো আউটপুট নেই"
                    else "সর্বশেষ ${out.length} অক্ষরের আউটপুট"
                )
            }
            "saved" -> {
                val job = BackgroundCommandService.saved(context)
                if (job == null) {
                    ok(JSONObject().put("saved", false), "ডিস্কে সেভ করা কোনো কাজ নেই")
                } else {
                    ok(
                        JSONObject()
                            .put("saved", true)
                            .put("command", job.command)
                            .put("running", job.running)
                            .put("interrupted", job.interrupted)
                            .put("exit", job.exit)
                            .put("started_at", job.startedAt)
                            .put("output", job.output.takeLast(4_000)),
                        if (job.running) "ডিস্কে সেভ করা কাজ এখনো চলছে"
                        else if (job.interrupted) "সেভ করা কাজটি অ্যাপ বন্ধ হওয়ার সময় কাটা পড়েছিল"
                        else "সেভ করা শেষ কাজ: ${job.command}"
                    )
                }
            }
            else -> {
                val out = BackgroundCommandService.lastOutput
                ok(
                    JSONObject()
                        .put("running", BackgroundCommandService.running)
                        .put("command", BackgroundCommandService.lastCommand)
                        .put("exit", BackgroundCommandService.lastExit)
                        .put("elapsed_ms", BackgroundCommandService.elapsedMs())
                        .put("output_tail", out.takeLast(2_000)),
                    if (BackgroundCommandService.running) "একটি কমান্ড চলছে"
                    else "কোনো ব্যাকগ্রাউন্ড কমান্ড চলছে না"
                )
            }
        }
    }

    /**
     * `wait` — the one tool for timing. Either pauses for `seconds`, or polls a
     * command until its output contains `contains` (or until it exits 0 when
     * `contains` is empty). It never gives up on its own: the loop ends when the
     * condition is met, exactly as asked — there is no deadline to race.
     *
     * The condition is checked through the same shell the model's own commands
     * use, so a port, a file, a process or an HTTP reply can all be waited on.
     */
    private suspend fun waitTool(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        val started = System.currentTimeMillis()
        val seconds = args.optDouble("seconds", 0.0)
        val until = args.safe("until").trim().lowercase()
        val command = args.safe("command").trim()
        val contains = args.safe("contains")
        val everyMs = args.optLong("every_ms", 1000L).coerceAtLeast(1L)

        val polls = until.isNotEmpty() && command.isNotEmpty()
        if (!polls && seconds <= 0.0) {
            return err("bad-args", "Give `seconds`, or `until:\"condition\"` with a `command` to poll")
        }

        if (seconds > 0.0) kotlinx.coroutines.delay((seconds * 1000).toLong())

        var attempt = 0
        var lastCode = 0
        var lastOut = ""
        if (polls) {
            while (true) {
                attempt++
                val r = ShellRuntime.runStreaming(context, command, timeoutMs = 0) { _, text -> live?.invoke(text) }
                lastCode = r.code
                lastOut = (r.stdout + r.stderr).trim()
                val met = if (contains.isNotEmpty()) lastOut.contains(contains) else r.code == 0
                if (met) break
                kotlinx.coroutines.delay(everyMs)
            }
        }

        val waited = System.currentTimeMillis() - started
        val tail = lastOut.lines().lastOrNull { it.isNotBlank() }?.take(200)
        return ok(
            JSONObject()
                .put("waited_ms", waited)
                .put("polls", attempt)
                .put("exit", lastCode)
                .put("output", lastOut.takeLast(4_000)),
            if (polls) "অপেক্ষা শেষ — শর্ত পূরণ (${attempt} বার চেক, ${waited}ms)"
            else "অপেক্ষা শেষ — ${waited}ms"
        ).also { if (polls && !tail.isNullOrBlank()) it.put("last", tail) }
    }

    /**
     * `system_shell` — run a command above the app sandbox, as the `shell` user
     * or as root, through Shizuku. The user grants that access; this only uses
     * it. When it is not there, the reply says exactly what to do and the normal
     * `run_shell` still works, so nothing breaks for someone who never enables it.
     */
    private fun systemShell(context: Context, args: JSONObject, live: ((String) -> Unit)? = null): JSONObject {
        val command = args.safe("command").trim()
        if (command.isEmpty()) return err("bad-args", "Provide a command")
        // Same rule as the other shells: no deadline unless the caller sets one.
        val timeout = args.optLong("timeout_ms", 0)
        val requested = args.safe("level", "auto").trim().lowercase()
        if (!SystemShell.available()) {
            val hint = if (SystemShell.needsPermission()) {
                "Shizuku is running but ZYRAXON has not been granted access. Open Settings -> System access and tap Allow."
            } else {
                "Shizuku is not running. This tool reaches real system directories and needs Shizuku or root. " +
                    "The Terminal and Linux do not need it — use those instead."
            }
            return ok(
                JSONObject()
                    .put("available", false)
                    .put("code", -1)
                    .put("stdout", "")
                    .put("stderr", hint),
                "System access is not available — $hint"
            )
        }
        val levels = SystemShell.probe(context)
        val result = when (requested) {
            "root" -> SystemShell.runStreaming(context, command, SystemShell.Level.ROOT, timeout) { _, text -> live?.invoke(text) }
            "shell" -> SystemShell.runStreaming(context, command, SystemShell.Level.SHELL, timeout) { _, text -> live?.invoke(text) }
            else -> SystemShell.runBest(context, command, timeout) { _, text -> live?.invoke(text) }
        } ?: return err("system-shell", "Could not run the command at system level")
        val levelLabel = when {
            levels[SystemShell.Level.ROOT] == true && requested != "shell" -> "root (uid 0)"
            else -> "system (uid 2000)"
        }
        val tail = result.stdout.trim().lines().lastOrNull()?.take(160)
        val label = buildString {
            append("$ ${result.command} exit ${result.code}")
            append(" · ").append(levelLabel)
            if (result.timedOut) append(" (timeout ${timeout}ms)")
            if (!tail.isNullOrBlank()) append(" | ").append(tail)
        }
        return ok(
            JSONObject()
                .put("available", true)
                .put("code", result.code)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("timed_out", result.timedOut)
                .put("level", levelLabel)
                .put("root", levels[SystemShell.Level.ROOT] == true),
            label
        )
    }

    /**
     * Installs and connects Shizuku, the system-access app ZYRAXON carries inside
     * itself. The agent can drive every step except the two Android reserves for
     * the user: confirming the install, and tapping Allow in Shizuku's own dialog.
     */
    private fun setupShizuku(context: Context, args: JSONObject): JSONObject {
        val action = args.safe("action", "status").trim().lowercase()

        fun report(state: ShizukuState): JSONObject {
            val name = when (state) {
                ShizukuState.Unknown -> "unknown"
                ShizukuState.NotInstalled -> "not-installed"
                ShizukuState.Installing -> "installing"
                ShizukuState.InstalledNotRunning -> "installed-not-running"
                ShizukuState.NeedsPermission -> "needs-permission"
                ShizukuState.Active -> "active"
                is ShizukuState.Failed -> "failed"
            }
            val levels = if (state is ShizukuState.Active) SystemShell.probe(context) else emptyMap()
            val data = JSONObject()
                .put("state", name)
                .put("installed", ShizukuBundle.isInstalled(context))
                .put("running", SystemShell.available() || SystemShell.needsPermission())
                .put("granted", SystemShell.available())
                .put("root", levels[SystemShell.Level.ROOT] == true)
                .put("version", ShizukuBundle.VERSION)
            val summary = when (state) {
                ShizukuState.Active -> "সিস্টেম অ্যাক্সেস চালু — সিস্টেম লেভেলে কাজ করা যাবে"
                ShizukuState.NotInstalled -> "Shizuku ইনস্টল করা নেই — বান্ডেল করা কপি ইনস্টল করা যায়"
                ShizukuState.InstalledNotRunning -> "Shizuku ইনস্টল আছে, চালু নেই — খুলে Start চাপতে হবে"
                ShizukuState.NeedsPermission -> "Shizuku চালু, অনুমতি নেই — Allow চাপতে হবে"
                ShizukuState.Installing -> "Shizuku ইনস্টলার খোলা — Android-এর পপ-আপে Install চাপুন"
                is ShizukuState.Failed -> "ব্যর্থ: ${state.message}"
                ShizukuState.Unknown -> "Shizuku অবস্থা অজানা"
            }
            return ok(data, summary)
        }

        return when (action) {
            "status" -> report(ShizukuBundle.state(context))

            "install" -> {
                if (ShizukuBundle.isInstalled(context)) {
                    report(ShizukuBundle.state(context))
                } else {
                    val apk = ShizukuBundle.apkFile(context)
                    if (apk == null) {
                        err("no-bundle", "বান্ডেল করা Shizuku ফাইল খোলা গেল না")
                    } else if (!Updater(context).canInstall()) {
                        err(
                            "needs-install-permission",
                            "ইনস্টল করতে আগে অনুমতি দরকার — Settings আপডেট অনুমতি সেটিংস খুলে ZYRAXON-কে \"এই সোর্স থেকে ইনস্টল\" চালু করুন"
                        )
                    } else {
                        val started = runCatching { Updater(context).installPackage(apk) }.getOrDefault(false)
                        report(
                            if (started) ShizukuState.Installing
                            else ShizukuState.Failed("Shizuku ইনস্টল শুরু করা গেল না")
                        )
                    }
                }
            }

            "open" -> {
                ShizukuBundle.launch(context)
                report(ShizukuBundle.state(context))
            }

            "request" -> {
                SystemShell.requestPermission()
                report(ShizukuBundle.state(context))
            }

            else -> err("bad-args", "action হবে status, install, open বা request")
        }
    }

    /**
     * Runs a command in ZYRAXON's own Linux system, installs it, or reports on it.
     *
     * This is the same userland the Terminal tab drives, exposed as a tool so the
     * assistant can build, run and test inside a real Unix system on the phone —
     * `apt install`, `python`, `node`, `clang` — instead of only the bundled
     * busybox. Everything comes from the APK, so it works with no network.
     */
    private suspend fun linuxRun(context: Context, args: JSONObject, live: ((String) -> Unit)? = null): JSONObject {
        val action = args.safe("action", "status").trim().lowercase()
        return when (action) {
            "install" -> {
                val log = StringBuilder()
                val okInstall = LinuxRuntime.install(context) { msg ->
                    log.append(msg).append('\n')
                }
                ok(
                    JSONObject()
                        .put("installed", okInstall)
                        .put("log", log.toString()),
                    if (okInstall) "ZYRAXON Linux ইনস্টল হয়েছে" else "ZYRAXON Linux ইনস্টল ব্যর্থ"
                )
            }

            "run" -> {
                val command = args.safe("command").trim()
                if (command.isEmpty()) return err("bad-args", "Provide a command")
                // The system ships in the APK and is unpacked on first launch. If a
                // command arrives before that finishes, set it up now instead of
                // asking the user to run an install step they should never need.
                if (!LinuxRuntime.isInstalled(context) && LinuxRuntime.isSupported(context)) {
                    LinuxRuntime.install(context) { }
                }
                if (!LinuxRuntime.isInstalled(context)) {
                    return err(
                        "linux-unavailable",
                        "ZYRAXON Linux could not be set up on this device."
                    )
                }
                val out = StringBuilder()
                val r = LinuxRuntime.runStreaming(context, command, onChunk = { _, text ->
                    out.append(text)
                    live?.invoke(text)
                }, timeoutMs = args.optLong("timeout_ms", 0))
                if (r == null) {
                    return err(
                        "linux-shell",
                        "Could not start ZYRAXON Linux. Run action:\"install\" and try again."
                    )
                }
                val tail = r.stdout.trim().lines().lastOrNull()?.take(160)
                val label = buildString {
                    append("$ linux $command exit ${r.code}")
                    if (!tail.isNullOrBlank()) append(" | ").append(tail)
                }
                ok(
                    JSONObject()
                        .put("code", r.code)
                        .put("stdout", r.stdout)
                        .put("stderr", r.stderr),
                    label
                )
            }

            else -> {
                val installed = LinuxRuntime.isInstalled(context)
                ok(
                    JSONObject()
                        .put("installed", installed)
                        .put("system_access", SystemShell.available())
                        .put("root", SystemShell.probe(context)[SystemShell.Level.ROOT] == true),
                    if (installed) "ZYRAXON Linux ইনস্টল করা আছে" else "ZYRAXON Linux ইনস্টল করা হয়নি"
                )
            }
        }
    }

    /**
     * Serve a site locally and, when asked, put it on the real internet with a
     * Cloudflare quick tunnel — no account, no token. The tunnel client is
     * fetched on first use and the public URL is parsed from its own output.
     */
    /**
     * Publish to a real GitHub repository using the REST API: create the repo if
     * it is missing, then write every file through the contents endpoint. Works
     * with a plain personal access token, no git binary needed.
     */
    private fun publishGithub(context: Context, args: JSONObject): JSONObject {
        val token = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE)
            .getString("gh_token", "")?.trim().orEmpty()
        if (token.isEmpty()) {
            return err("no-github-token", "GitHub টোকেন নেই — সেটিংসে টোকেন দিন, তারপর আবার বলো")
        }
        val repoArg = args.safe("repo").trim()
        if (repoArg.isEmpty()) return err("no-repo", "রিপোজিটরির নাম বলোনি")
        val repo = repoArg.removePrefix("https://github.com/").removeSuffix(".git").trim('/')

        val files = linkedMapOf<String, String>()
        if (args.safe("html").isNotBlank()) files["index.html"] = args.safe("html")
        args.optJSONObject("files")?.let { obj ->
            for (key in obj.keys()) files[key.trimStart('/')] = obj.safe(key)
        }
        if (files.isEmpty()) return err("no-files", "প্রকাশ করার জন্য কোনো ফাইল দাওনি")

        val who = githubRequest(token, "GET", "https://api.github.com/user", null)
        val login = who?.safe("login").orEmpty()
        if (login.isEmpty()) return err("bad-token", "GitHub টোকেন কাজ করছে না")

        val full = if (repo.contains("/")) repo else "$login/$repo"
        val exists = githubRequest(token, "GET", "https://api.github.com/repos/$full", null)
        if (exists == null || exists.has("message")) {
            val body = JSONObject()
                .put("name", full.substringAfter("/"))
                .put("description", args.safe("description", "ZYRAXON দিয়ে প্রকাশিত"))
                .put("private", args.optBoolean("private", false))
                .put("auto_init", true)
            val created = githubRequest(token, "POST", "https://api.github.com/user/repos", body)
            if (created == null || created.has("message")) {
                return err("repo-create-failed", created?.safe("message") ?: "রিপো তৈরি হয়নি")
            }
        }

        var pushed = 0
        val failed = mutableListOf<String>()
        for ((path, content) in files) {
            val url = "https://api.github.com/repos/$full/contents/$path"
            val existing = githubRequest(token, "GET", url, null)
            val body = JSONObject()
                .put("message", "ZYRAXON: $path")
                .put("content", android.util.Base64.encodeToString(
                    content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            if (existing != null && existing.has("sha")) body.put("sha", existing.safe("sha"))
            val res = githubRequest(token, "PUT", url, body)
            if (res != null && !res.has("message")) pushed++ else failed.add(path)
        }

        if (pushed == 0) return err("push-failed", "একটাও ফাইল যেতে পারেনি: ${failed.joinToString()}")
        val repoUrl = "https://github.com/$full"
        return ok(JSONObject().put("url", repoUrl).put("pushed", pushed).put("failed", JSONArray(failed)),
            "GitHub-এ লাইভ: $repoUrl ($pushed ফাইল)")
    }

    private fun githubRequest(token: String, method: String, url: String, body: JSONObject?): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 0
                readTimeout = 0
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "ZYRAXON-Android")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) {
                if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            } else {
                JSONObject().put("message", try { JSONObject(text).safe("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" })
            }
        } catch (e: Exception) {
            JSONObject().put("message", e.message ?: "network-error")
        }
    }

    /**
     * `build_app` — build this project on the phone and install what comes out.
     * The heavy lifting is the shell, so a real build is run rather than a
     * pretend one; when a `KEYSTORE_PATH` and passwords are in the environment a
     * release build is produced, otherwise the debug variant is used. The
     * finished APK is installed through the normal Android installer, so the
     * user always confirms the install.
     */
    private suspend fun buildApp(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        val root = workspace(context)
        val variant = args.safe("variant", "debug").trim().lowercase()
        val release = variant == "release"
        val gradle = if (release) "assembleRelease" else "assembleDebug"
        val out = StringBuilder()
        val r = ShellRuntime.runStreaming(
            context,
            "./gradlew $gradle --console=plain --no-daemon",
            onChunk = { _, text -> out.append(text); live?.invoke(text) },
            timeoutMs = args.optLong("timeout_ms", 0),
            cwd = root
        )
        if (r == null) return err("no-shell", "শেল শুরু করা গেল না")
        if (r.code != 0) {
            return err(
                "build-failed",
                "বিল্ড ব্যর্থ (exit ${r.code})। শেষ আউটপুট:\n" +
                    out.toString().trim().lines().takeLast(25).joinToString("\n")
            )
        }
        val apkDir = File(root, "app/build/outputs/apk/${if (release) "release" else "debug"}")
        val apk = apkDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            ?: return err("no-apk", "বিল্ড শেষ কিন্তু কোনো APK পাওয়া গেল না")
        if (!args.optBoolean("install", true)) {
            return ok(
                JSONObject().put("apk", apk.absolutePath).put("bytes", apk.length()),
                "বিল্ড শেষ: ${apk.name} (${apk.length() / 1024} KB) — ইনস্টল করা হয়নি"
            )
        }
        if (!Updater(context).canInstall()) {
            return err(
                "needs-install-permission",
                "APK তৈরি হয়েছে কিন্তু ইনস্টল করার অনুমতি নেই — Settings আপডেট অনুমতি সেটিংস খুলে \"এই সোর্স থেকে ইনস্টল\" চালু করুন"
            )
        }
        val started = runCatching { Updater(context).installPackage(apk) }.getOrDefault(false)
        if (!started) return err("install-failed", "APK তৈরি হলো কিন্তু ইনস্টলার খোলা গেল না")
        return ok(
            JSONObject().put("apk", apk.absolutePath).put("bytes", apk.length()),
            "বিল্ড শেষ, ইনস্টলার খোলা হয়েছে — Android-এর পপ-আপে Install চাপুন"
        )
    }

    /**
     * `schedule_command` — book a shell command for later. The timer is given to
     * Android's `AlarmManager`, so it fires even when this app is not running;
     * the command then runs through the background service, which wakes the
     * assistant with the result when it ends.
     */
    private fun scheduleCommand(context: Context, args: JSONObject): JSONObject {
        val command = args.safe("command").trim()
        if (command.isEmpty()) return err("bad-args", "কোন কমান্ড চালাবে বলোনি")
        val action = args.safe("action", "schedule").trim().lowercase()
        return when (action) {
            "cancel" -> {
                ScheduledCommandReceiver.cancel(context, command)
                ok(JSONObject().put("cancelled", true), "সময়সূচি বাতিল হলো: $command")
            }
            else -> {
                val inSeconds = args.optDouble("in_seconds", -1.0)
                val at = args.optDouble("at", -1.0)
                val afterMs = when {
                    inSeconds >= 0 -> (inSeconds * 1000).toLong()
                    at > 0 -> (at * 1000).toLong() - System.currentTimeMillis()
                    else -> return err("bad-args", "in_seconds বা at দাও")
                }
                if (afterMs < 0) return err("bad-args", "যে সময় দিয়েছ তা আগেই চলে গেছে")
                ScheduledCommandReceiver.schedule(context, command, afterMs)
                ok(
                    JSONObject()
                        .put("command", command)
                        .put("after_ms", afterMs)
                        .put("fire_at", System.currentTimeMillis() + afterMs),
                    "ঠিক আছে — ${afterMs / 1000} সেকেন্ড পরে কমান্ডটি চলবে, অ্যাপ বন্ধ থাকলেও"
                )
            }
        }
    }

    /**
     * `github_actions` — CI from the phone. Lists workflows, starts one with
     * `workflow_dispatch`, and reports runs with their status, conclusion and
     * logs, so a build can be driven end to end without a desktop. The `wait`
     * tool is what polls `status` until a run finishes.
     */
    private fun githubActions(context: Context, args: JSONObject): JSONObject {
        val token = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE)
            .getString("gh_token", "")?.trim().orEmpty()
        if (token.isEmpty()) {
            return err("no-github-token", "GitHub টোকেন নেই — সেটিংসে টোকেন দিন, তারপর আবার বলো")
        }
        val repoArg = args.safe("repo").trim()
        if (repoArg.isEmpty()) return err("no-repo", "রিপোজিটরির নাম বলোনি")
        val repo = repoArg.removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        val base = "https://api.github.com/repos/$repo/actions"
        val action = args.safe("action", "list").trim().lowercase()

        return when (action) {
            "list" -> {
                val res = githubRequest(token, "GET", "$base/workflows", null)
                    ?: return err("network-error", "GitHub-এ পৌঁছানো গেল না")
                if (res.has("message")) return err("github-error", res.safe("message"))
                val arr = res.optJSONArray("workflows") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    out.put(JSONObject()
                        .put("id", w.optLong("id"))
                        .put("name", w.safe("name"))
                        .put("path", w.safe("path"))
                        .put("state", w.safe("state")))
                }
                ok(JSONObject().put("workflows", out), "${out.length()}টি workflow পাওয়া গেছে")
            }

            "dispatch" -> {
                val workflow = args.safe("workflow").trim()
                if (workflow.isEmpty()) return err("bad-args", "কোন workflow চালাবে বলোনি (file name বা id)")
                val ref = args.safe("ref").trim().ifEmpty { "main" }
                val body = JSONObject().put("ref", ref)
                args.optJSONObject("inputs")?.let { body.put("inputs", it) }
                val res = githubRequest(
                    token, "POST",
                    "$base/workflows/${Uri.encode(workflow)}/dispatches",
                    body
                )
                if (res != null && res.has("message")) return err("dispatch-failed", res.safe("message"))
                ok(
                    JSONObject().put("workflow", workflow).put("ref", ref).put("dispatched", true),
                    "CI চালু হলো: $workflow @ $ref — `runs` বা `status` দিয়ে দেখো"
                )
            }

            "runs" -> {
                val wf = args.safe("workflow").trim()
                val url = if (wf.isEmpty()) "$base/runs?per_page=10"
                else "$base/workflows/${Uri.encode(wf)}/runs?per_page=10"
                val res = githubRequest(token, "GET", url, null)
                    ?: return err("network-error", "GitHub-এ পৌঁছানো গেল না")
                if (res.has("message")) return err("github-error", res.safe("message"))
                val arr = res.optJSONArray("workflow_runs") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    out.put(JSONObject()
                        .put("id", r.optLong("id"))
                        .put("name", r.safe("name"))
                        .put("branch", r.safe("head_branch"))
                        .put("event", r.safe("event"))
                        .put("status", r.safe("status"))
                        .put("conclusion", r.safe("conclusion"))
                        .put("url", r.safe("html_url")))
                }
                ok(JSONObject().put("runs", out), "${out.length()}টি সাম্প্রতিক রান")
            }

            "status" -> {
                val id = args.safe("run_id").trim()
                if (id.isEmpty()) return err("bad-args", "run_id দাও")
                val r = githubRequest(token, "GET", "$base/runs/$id", null)
                    ?: return err("network-error", "GitHub-এ পৌঁছানো গেল না")
                if (r.has("message")) return err("github-error", r.safe("message"))
                val status = r.safe("status")
                val conclusion = r.safe("conclusion")
                ok(
                    JSONObject()
                        .put("id", r.optLong("id"))
                        .put("status", status)
                        .put("conclusion", conclusion)
                        .put("branch", r.safe("head_branch"))
                        .put("url", r.safe("html_url")),
                    "রান $id: $status${if (conclusion.isNotEmpty()) " / $conclusion" else ""}"
                )
            }

            "logs" -> {
                val id = args.safe("run_id").trim()
                if (id.isEmpty()) return err("bad-args", "run_id দাও")
                val jobs = githubRequest(token, "GET", "$base/runs/$id/jobs", null)
                if (jobs == null || jobs.has("message")) {
                    return err("github-error", jobs?.safe("message") ?: "চাকরির তালিকা পাওয়া গেল না")
                }
                val arr = jobs.optJSONArray("jobs") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val steps = j.optJSONArray("steps") ?: JSONArray()
                    val stepList = JSONArray()
                    for (s in 0 until steps.length()) {
                        val st = steps.getJSONObject(s)
                        stepList.put(JSONObject()
                            .put("name", st.safe("name"))
                            .put("status", st.safe("status"))
                            .put("conclusion", st.safe("conclusion")))
                    }
                    out.put(JSONObject()
                        .put("name", j.safe("name"))
                        .put("status", j.safe("status"))
                        .put("conclusion", j.safe("conclusion"))
                        .put("steps", stepList))
                }
                ok(JSONObject().put("jobs", out), "${out.length()}টি চাকরি — প্রতিটার ধাপ ও ফল")
            }

            else -> err("bad-args", "অজানা action: $action")
        }
    }

    private suspend fun publish(context: Context, args: JSONObject, tunnel: Boolean): JSONObject {
        val name = args.safe("name", "site").replace(Regex("[^A-Za-z0-9._-]"), "-")
        val files = args.optJSONObject("files")
        val siteDir = File(workspace(context), "sites/$name").apply { mkdirs() }
        if (args.safe("html").isNotBlank()) File(siteDir, "index.html").writeText(args.safe("html"))
        if (files != null) {
            for (key in files.keys()) {
                val rel = key.trimStart('/')
                val target = File(siteDir, rel)
                target.parentFile?.mkdirs()
                target.writeText(files.safe(key))
            }
        }
        // A model that said "make a website" and then passed no HTML used to be
        // stopped here with "প্রকাশ করার জন্য কোনো ফাইল দাওনি" — the exact defect
        // where the assistant asked the user to hand it files. The job is to
        // build the site, so a real starter page is written and published
        // instead of a refusal.
        if (siteDir.listFiles().isNullOrEmpty()) {
            File(siteDir, "index.html").writeText(defaultSite(name))
        }
        // An index the browser can open even when only other pages were given.
        if (File(siteDir, "index.html").let { !it.exists() } && !File(siteDir, "index.htm").exists()) {
            val first = siteDir.listFiles()?.firstOrNull { it.extension.lowercase() in setOf("html", "htm") }
            if (first != null) File(siteDir, "index.html").writeText(first.readText())
        }

        if (!tunnel) {
            val served = startHttpServer(siteDir, 8791)
            if (served != null) {
                // The finished site is OPENED in the app's own preview, on the
                // phone's loopback address, instead of handing back a 192.168.x.x
                // link nobody can use here — and instead of launching Chrome over
                // the assistant.
                val local = "http://127.0.0.1:8791/"
                showInPreview(context, local)
                return ok(JSONObject().put("url", local).put("lan", served).put("dir", siteDir.absolutePath),
                    "সাইট খুলে দিয়েছি: $local (একই ওয়াই-ফাইয়ের অন্য ডিভাইসে: $served)। পাবলিক লিংক লাগলে publish_cloudflare ব্যবহার করো।")
            }
            return ok(siteDir.absolutePath, "সাইটটি ফোনে সেভ হয়েছে: ${siteDir.absolutePath} (সার্ভার চালু হয়নি)")
        }

        val bin = File(context.filesDir, "cloudflared")
        if (!bin.exists() || bin.length() < 1_000_000) {
            val arch = if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) "arm64"
            else if (Build.SUPPORTED_ABIS.any { it.contains("x86_64") }) "amd64" else "arm"
            val url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$arch"
            val dl = downloadFile(context, JSONObject().put("url", url).put("path", bin.absolutePath))
            if (!dl.optBoolean("ok")) return err("no-tunnel-client", "টানেল ক্লায়েন্ট নামানো যায়নি")
            bin.setExecutable(true)
        }

        // The site is served by the app's own in-process server, so it never
        // depends on python or busybox being present on the phone.
        val served = startHttpServer(siteDir, 8791)
        val local = "http://127.0.0.1:8791/"

        // Two attempts with different transports: the default (QUIC) is often
        // blocked on mobile networks, http2 over 443 usually is not.
        var publicUrl: String? = null
        for (protocol in listOf("http2", "quic")) {
            val proc = ProcessBuilder("sh", "-c",
                "'${bin.absolutePath}' tunnel --url $local --protocol $protocol --no-autoupdate 2>&1")
                .redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                val m = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com").findAll(line)
                    .map { it.value }
                    .firstOrNull { u -> !u.contains("api.trycloudflare.com") }
                if (m != null) { publicUrl = m; break }
            }
            if (publicUrl != null) break
            proc.destroy()
        }
        if (publicUrl == null) {
            // No public tunnel this time. The site is still real and is OPENED in
            // the app's own preview; the tool result says exactly what to try next.
            if (served != null) {
                showInPreview(context, local)
                return ok(JSONObject().put("url", local).put("lan", served).put("dir", siteDir.absolutePath),
                    "টানেল এবার ওঠেনি, তবে সাইট খুলে দিয়েছি: $local। এখন terminal_run/run_shell দিয়ে নিজে cloudflared চালাও বা publish_github (Pages) ব্যবহার করো।")
            }
            return err("tunnel-failed", "পাবলিক লিংক তৈরি হয়নি")
        }
        // The public link is opened in the app's own preview too. A tunnel is not
        // ready the instant cloudflared prints its hostname — the edge still has to
        // route it — so the preview is offered first and the external browser is
        // kept as a fallback the user can reach from the preview's own address bar.
        showInPreview(context, publicUrl)
        return ok(JSONObject().put("url", publicUrl).put("dir", siteDir.absolutePath),
            "সাইটটি লাইভ: $publicUrl (অ্যাপের প্রিভিউতে খুলে দিয়েছি)")
    }

    /**
     * Shows a just-published site in the app's own preview instead of launching an
     * external browser over the assistant. [PreviewBridge] is the app's real
     * WebView, so the page the agent built stays on screen and the agent can keep
     * inspecting it through the `preview` tool.
     */
    private fun showInPreview(context: Context, url: String) {
        PreviewBridge.open(context, url)
        PreviewBridge.offerPublishedSite(url)
    }

    // ---------------------------------------------------------------- channels

    /** Package that owns each channel, or null for a system handler. */
    private val channelPackages = mapOf(
        "telegram" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "whatsapp_business" to "com.whatsapp.w4b",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "x" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "youtube" to "com.google.android.youtube"
    )

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Hands a finished post to the real app so it goes out from the user's own
     * account. The composer is opened pre-filled; when the app is missing the
     * caller is told, never told it worked.
     */
    private fun channelPost(context: Context, args: JSONObject): JSONObject {
        val channel = args.safe("channel").lowercase().trim()
        if (channel.isBlank()) return err("no-channel", "কোন চ্যানেলে দিতে হবে বলোনি")
        val body = listOf(args.safe("text"), args.safe("url"))
            .filter { it.isNotBlank() }.joinToString("\n")
        val to = args.safe("to").trim()
        val imageUri = args.safe("image_path").takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (!f.exists()) null else try {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            } catch (_: Exception) {
                null
            }
        }

        val intent: Intent = when {
            channel.contains("sms") -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + to)).apply {
                putExtra("sms_body", body)
            }
            channel.contains("mail") || channel.contains("gmail") -> Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + to)
                putExtra(Intent.EXTRA_SUBJECT, args.safe("subject", "ZYRAXON"))
                putExtra(Intent.EXTRA_TEXT, body)
            }
            else -> {
                val pkg = channelPackages.entries.firstOrNull { channel.contains(it.key) }?.value
                if (pkg != null && !isInstalled(context, pkg)) {
                    return err("app-not-installed", "এই ফোনে $pkg ইনস্টল করা নেই — অ্যাপটি ইনস্টল করে আবার বলো")
                }
                if (channel.contains("youtube")) {
                    // No Android intent can publish a video. Open the real Studio
                    // upload page instead of pretending the post happened.
                    launchUrl(context, JSONObject().put("url", "https://studio.youtube.com/channel/UC/videos/upload"))
                    return ok(JSONObject().put("manual", true),
                        "ইউটিউবে সরাসরি ইনটেন্ট দিয়ে আপলোড হয় না — Studio খুলে দিয়েছি, ফাইলটা বেছে নাও")
                }
                Intent(Intent.ACTION_SEND).apply {
                    type = if (imageUri != null) "image/*" else "text/plain"
                    putExtra(Intent.EXTRA_TEXT, body)
                    if (imageUri != null) {
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (pkg != null) setPackage(pkg)
                }
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok(JSONObject().put("channel", channel).put("prefilled", true),
                "$channel-এ লেখা ভরে দিয়েছি — Send চাপলেই যাবে")
        } catch (e: Exception) {
            err(e.message ?: e.toString(), "$channel খোলা যায়নি")
        }
    }

    // ------------------------------------------------------------- local server

    private val servers = ConcurrentHashMap<Int, ServerSocket>()

    /**
     * Serves a folder over real HTTP on the LAN. Returns the URL another device
     * on the same Wi-Fi can open, or null when the port is taken.
     */
    private fun startHttpServer(root: File, port: Int): String? = try {
        servers.remove(port)?.let { runCatching { it.close() } }
        val socket = ServerSocket(port).apply { reuseAddress = true }
        servers[port] = socket
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { serveClient(client, root) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        "http://${lanIp()}:$port/"
    } catch (_: Exception) {
        null
    }

    private fun serveClient(client: Socket, root: File) {
        try {
            client.use { s ->
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = input.readLine() ?: return
                var line = input.readLine()
                while (!line.isNullOrEmpty()) line = input.readLine()
                val rawPath = requestLine.split(" ").getOrNull(1)?.substringBefore('?')?.trimStart('/') ?: return
                val target = if (rawPath.isEmpty()) File(root, "index.html") else File(root, rawPath)
                // A crafted path must not be able to read outside the served folder.
                if (!target.canonicalPath.startsWith(root.canonicalPath)) {
                    writeResponse(s, 403, "text/plain", "forbidden".toByteArray())
                    return
                }
                if (!target.exists() || target.isDirectory) {
                    writeResponse(s, 404, "text/plain", "not found".toByteArray())
                    return
                }
                writeResponse(s, 200, mimeOf(target.name), target.readBytes())
            }
        } catch (_: Exception) {
        }
    }

    private fun writeResponse(s: Socket, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        else -> "text/plain; charset=utf-8"
    }

    /** First non-loopback IPv4 address, so the LAN URL is reachable by others. */
    private fun lanIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }

    private fun serveLocal(context: Context, args: JSONObject): JSONObject {
        val name = args.safe("name", "site").replace(Regex("[^A-Za-z0-9._-]"), "-")
        val siteDir = File(workspace(context), "sites/$name").apply { mkdirs() }
        if (args.safe("html").isNotBlank()) File(siteDir, "index.html").writeText(args.safe("html"))
        args.optJSONObject("files")?.let { files ->
            for (key in files.keys()) {
                val target = File(siteDir, key.trimStart('/'))
                target.parentFile?.mkdirs()
                target.writeText(files.safe(key))
            }
        }
        if (siteDir.listFiles().isNullOrEmpty()) return err("no-files", "সার্ভ করার জন্য কোনো ফাইল দাওনি")
        val port = args.optInt("port", 8791)
        val url = startHttpServer(siteDir, port)
        return if (url != null) {
            ok(JSONObject().put("url", url).put("dir", siteDir.absolutePath), "সার্ভার চালু: $url")
        } else {
            err("server-failed", "পোর্ট $port-এ সার্ভার চালু হয়নি")
        }
    }

    private fun sysInfo(context: Context): String {
        val rt = Runtime.getRuntime()
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        return buildString {
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            append("RAM free: ${(rt.freeMemory() / 1048576)}MB of ${(rt.totalMemory() / 1048576)}MB\n")
            append("Storage free: ${(stat.availableBytes / 1073741824)}GB of ${(stat.totalBytes / 1073741824)}GB\n")
            append("Current app: ${DeviceBridge.foregroundLabel()}\n")
            append("Accessibility: ${if (DeviceBridge.accessibilityOn()) "on" else "off"}\n")
            append("Workspace: ${workspace(context).absolutePath}")
        }
    }

    private fun requestPermission(context: Context, name: String): JSONObject {
        val n = name.lowercase()
        val intent = when {
            n.contains("access") -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            n.contains("overlay") -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            n.contains("notif") -> Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            else -> null
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ok(n, "সেটিং স্ক্রিন খোলা হয়েছে — উপরে Allow চাপলেই হবে")
        }
        val perms = when {
            n.contains("camera") -> arrayOf(android.Manifest.permission.CAMERA)
            n.contains("mic") || n.contains("audio") -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
            n.contains("location") -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            n.contains("file") -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyArray()
        }
        if (perms.isEmpty()) return err("unknown-permission", "কোন পারমিশন দরকার সেটা বলো")
        MainActivity.instance?.let { act ->
            act.runOnUiThread { androidx.core.app.ActivityCompat.requestPermissions(act, perms, 9101) }
            return ok(n, "পারমিশন ডায়ালগ আসছে — Allow চাপলেই হবে")
        }
        return err("no-activity", "পারমিশন ডায়ালগ দেখানো যায়নি")
    }

    /**
     * Changes the assistant's own voice. The ViewModel owns the voice manager, so
     * the call is routed there; the result reports what the voice actually is now.
     */
    private fun changeVoice(context: Context, args: JSONObject): JSONObject {
        val gender = args.safe("gender", "").lowercase()
        if (gender != "female" && gender != "male") {
            return err("bad-gender", "gender হবে female অথবা male")
        }
        val activity = MainActivity.instance
            ?: return err("no-activity", "কণ্ঠ বদলানো যায়নি")
        activity.applyVoice(
            gender = gender,
            rate = if (args.has("rate")) args.optDouble("rate").toFloat() else null,
            pitch = if (args.has("pitch")) args.optDouble("pitch").toFloat() else null
        )

        val label = if (gender == "female") "মিষ্টি মেয়ের কণ্ঠ" else "গভীর ছেলের কণ্ঠ"
        return ok(gender, "কণ্ঠ বদলানো হয়েছে: $label")
    }

    /**
     * Screen lock and unlock. Locking needs the admin the user activated once;
     * without it the tool reports `needs-enroll` and opens the system screen so a
     * single "Allow" finishes the job. Unlocking is honest about the one case
     * Android forbids: a phone with a secure lock cannot be opened by any app.
     */
    private fun deviceLock(context: Context, args: JSONObject): JSONObject =
        when (args.safe("action").lowercase()) {
            "lock" -> {
                if (DeviceLocker.lockNow(context)) {
                    ok("locked", "স্ক্রিন লক করা হয়েছে")
                } else if (!DeviceLocker.isAdminActive(context)) {
                    launchOnMain(context, DeviceLocker.enrolIntent(context))
                    err(
                        "needs-enroll",
                        "স্ক্রিন লক করতে আগে অনুমতি দিতে হবে — যে স্ক্রিনটি খুলল, সেখানে Allow চাপুন"
                    )
                } else {
                    err("lock-failed", "স্ক্রিন লক করা যায়নি")
                }
            }
            "unlock" -> {
                val result = DeviceLocker.unlockScreen(context, MainActivity.instance)
                when (result) {
                    DeviceLocker.UnlockResult.ALREADY_UNLOCKED ->
                        ok("already-unlocked", "স্ক্রিন আগেই খোলা ছিল")
                    DeviceLocker.UnlockResult.REQUESTED ->
                        ok("unlocked", "লক খোলার অনুরোধ পাঠানো হয়েছে")
                    DeviceLocker.UnlockResult.UNLOCK_NEEDS_SECURE_LOCK ->
                        err(
                            "secure-lock",
                            "ফোনে পিন/প্যাটার্ন আছে, তাই অ্যাপ নিজে লক খুলতে পারে না — শুধু ইউজারের পিন দিয়েই খুলবে"
                        )
                    DeviceLocker.UnlockResult.FAILED ->
                        err("unlock-failed", "লক খোলা যায়নি")
                }
            }
            "enroll" -> {
                val opened = launchOnMain(context, DeviceLocker.enrolIntent(context))
                if (opened) ok("enroll", "অনুমতির স্ক্রিন খোলা হয়েছে — Allow চাপলেই হবে")
                else err("no-activity", "অনুমতির স্ক্রিন খোলা যায়নি")
            }
            "status" -> ok(
                JSONObject()
                    .put("adminActive", DeviceLocker.isAdminActive(context))
                    .put("secureLock", DeviceLocker.hasSecureLock(context))
                    .put("screenOn", DeviceLocker.isScreenOn(context))
                    .put("canSkipLockScreen", DeviceLocker.canSkipLockScreen(context)),
                if (DeviceLocker.isAdminActive(context)) "লক করার অনুমতি আছে" else "লক করার অনুমতি নেই"
            )
            "secure_lock_settings" ->
                if (launchOnMain(context, DeviceLocker.secureLockSettingsIntent()))
                    ok("settings", "সিকিউরিটি সেটিংস খোলা হয়েছে")
                else err("no-activity", "সেটিংস খোলা যায়নি")
            else -> err("bad-action", "action হবে lock | unlock | enroll | status | secure_lock_settings")
        }

    /**
     * Switches between the two ways ZYRAXON speaks. `on` opens a live
     * speech-to-speech session with a realtime model, whose own voice answers and
     * silences the phone's text-to-speech so only one voice is heard; `off` goes
     * back to text turns read aloud by the phone's voice.
     */
    private fun liveVoice(args: JSONObject): JSONObject {
        val activity = MainActivity.instance
            ?: return err("no-activity", "লাইভ ভয়েস চালু করা যায়নি")
        return when (args.safe("action").lowercase()) {
            "on" -> {
                if (activity.enableLiveVoice()) {
                    ok("on", "রিয়েলটাইম মডেলে সরে গিয়ে লাইভ ভয়েস চালু করা হয়েছে")
                } else {
                    err(
                        "no-realtime-model",
                        "এই মুহূর্তে কোনো রিয়েলটাইম মডেল লোড হয়নি — Settings-এ মডেল লোড করে আবার বলো"
                    )
                }
            }
            "off" -> {
                activity.stopLiveVoice()
                ok("off", "লাইভ ভয়েস বন্ধ করা হয়েছে")
            }
            else -> err("bad-action", "action হবে on অথবা off")
        }
    }

    /**
     * The on-device engine, driven from the tool layer. A bundle must be present
     * before it can answer, so `list` and `download` are the first steps a model
     * takes when the user asks for offline answers.
     */
    private suspend fun onDeviceModel(context: Context, args: JSONObject): JSONObject {
        val engine = OnDeviceModel.get(context)
        return when (args.safe("action").lowercase()) {
            "list" -> {
                val installed = engine.installedModels()
                ok(
                    JSONObject()
                        .put("installed", JSONArray(installed.map { it.name }))
                        .put("loaded", engine.loadedPath?.let { File(it).name } ?: "")
                        .put(
                            "suggested",
                            JSONArray(OnDeviceModel.SUGGESTED.map {
                                JSONObject().put("name", it.name).put("file", it.fileName)
                                    .put("approxMb", it.approxMb).put("requiresToken", it.requiresToken)
                                    .put("licencePage", it.licencePage)
                            })
                        ),
                    if (installed.isEmpty()) "কোনো অন-ডিভাইস মডেল ডাউনলোড করা নেই"
                    else "${installed.size}টি অন-ডিভাইস মডেল পাওয়া গেছে"
                )
            }
            "load" -> {
                val name = args.safe("file")
                val file = engine.installedModels().firstOrNull { it.name == name }
                    ?: engine.installedModels().firstOrNull()
                    ?: return err("no-model", "আগে একটি মডেল ডাউনলোড করতে হবে")
                if (engine.load(file.absolutePath)) ok(file.name, "অন-ডিভাইস মডেল লোড হয়েছে: ${file.name}")
                else err("load-failed", "মডেল লোড করা যায়নি: ${file.name}")
            }
            "unload" -> {
                engine.unload()
                ok("unloaded", "অন-ডিভাইস মডেল আনলোড হয়েছে")
            }
            "generate" -> {
                val prompt = args.safe("prompt")
                if (prompt.isBlank()) return err("no-prompt", "prompt দরকার")
                if (!engine.isReady()) return err("not-loaded", "আগে একটি মডেল লোড করতে হবে")
                val text = StringBuilder()
                engine.generate(prompt).collect { text.append(it) }
                ok(text.toString(), "অন-ডিভাইস উত্তর")
            }
            "download" -> {
                val info = OnDeviceModel.SUGGESTED.firstOrNull {
                    it.fileName == args.safe("file") || it.name == args.safe("file")
                } ?: OnDeviceModel.SUGGESTED.first()
                val token = args.safe("token")
                if (info.requiresToken && token.isBlank()) {
                    return err(
                        "needs-token",
                        "এই মডেলটি লাইসেন্স-গেটেড। ${info.licencePage} পেজে শর্ত মেনে নিয়ে একটি read টোকেন দিন।"
                    )
                }
                val file = engine.download(info.url, info.fileName, token)
                if (file != null) ok(file.name, "ডাউনলোড হয়েছে: ${file.name}")
                else err("download-failed", "ডাউনলোড ব্যর্থ — টোকেন বা ইন্টারনেট দেখুন")
            }
            "import" -> {
                val path = args.safe("file")
                val source = File(path)
                if (!source.isFile) return err("no-file", "ফাইল পাওয়া যায়নি: $path")
                val target = File(engine.modelsDir(), source.name)
                source.copyTo(target, overwrite = true)
                ok(target.name, "ইমপোর্ট হয়েছে: ${target.name}")
            }
            else -> err("bad-action", "action হবে list | load | unload | generate | download | import")
        }
    }

    private fun openSettings(context: Context, page: String): JSONObject {
        val action = when (page.lowercase()) {
            "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            "overlay" -> android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            "notifications" -> "android.settings.APP_NOTIFICATION_SETTINGS"
            "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "play_protect" -> PLAY_PROTECT_ACTIVITY
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // The Play Protect screen lives in Play services and is not on every build.
        // Falling back to the Play Store app page still puts the user (and the AI,
        // which taps through the screen itself) one step from the toggle.
        val launched = launchOnMain(context, intent) ||
            (page.equals("play_protect", ignoreCase = true) && launchOnMain(
                context,
                Intent("android.intent.action.VIEW", Uri.parse("market://details?id=com.android.vending"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ))

        return if (launched) ok(page, "সেটিংস খোলা হয়েছে: $page")
        else err("no-activity", "সেটিংস খোলা যায়নি: $page")
    }

    /**
     * `startActivity` belongs on the main thread; the tools run on IO. Returns
     * whether an activity actually came up, so a missing screen can be retried.
     */
    private fun launchOnMain(context: Context, intent: Intent): Boolean {
        var launched = false
        val latch = CountDownLatch(1)
        val post = {
            try {
                context.startActivity(intent)
                launched = true
            } catch (_: Exception) {
                launched = false
            } finally {
                latch.countDown()
            }
        }
        val handler = Handler(Looper.getMainLooper())
        if (Looper.myLooper() == Looper.getMainLooper()) post() else handler.post(post)
        // No deadline: the posted lambda always counts the latch down in its
        // finally block, so this returns as soon as the launch has really run.
        latch.await()
        return launched
    }

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /**
     * Common app names the user says in Bangla or English, mapped to the package
     * that ships them. `open_app` consults this before giving up, so a named app
     * is opened without a "which app did you mean?" round-trip.
     */
    private val KNOWN_APPS: Map<String, String> = mapOf(
        "youtube" to YOUTUBE_PACKAGE, "ইউটিউব" to YOUTUBE_PACKAGE, "ইউটিউবে" to YOUTUBE_PACKAGE,
        "youtube music" to "com.google.android.apps.youtube.music",
        "facebook" to "com.facebook.katana", "ফেসবুক" to "com.facebook.katana",
        "messenger" to "com.facebook.orca", "মেসেঞ্জার" to "com.facebook.orca",
        "whatsapp" to "com.whatsapp", "হোয়াটসঅ্যাপ" to "com.whatsapp", "হোয়াটসঅ্যাপে" to "com.whatsapp",
        "instagram" to "com.instagram.android", "ইনস্টাগ্রাম" to "com.instagram.android",
        "telegram" to "org.telegram.messenger", "টেলিগ্রাম" to "org.telegram.messenger",
        "chrome" to "com.android.chrome", "ক্রোম" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "জিমেইল" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "গুগল ম্যাপ" to "com.google.android.apps.maps",
        "play store" to "com.android.vending", "প্লে স্টোর" to "com.android.vending",
        "settings" to "com.android.settings", "সেটিংস" to "com.android.settings",
        "camera" to "com.android.camera", "ক্যামেরা" to "com.android.camera",
        "gallery" to "com.google.android.apps.photos", "গ্যালারি" to "com.google.android.apps.photos",
        "calculator" to "com.google.android.calculator", "ক্যালকুলেটর" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock", "ঘড়ি" to "com.google.android.deskclock",
        "phone" to "com.google.android.dialer", "dialer" to "com.google.android.dialer", "ফোন" to "com.google.android.dialer",
        "x" to "com.twitter.android", "twitter" to "com.twitter.android", "টুইটার" to "com.twitter.android",
        "spotify" to "com.spotify.music", "স্পটিফাই" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient", "নেটফ্লিক্স" to "com.netflix.mediaclient"
    )
}
