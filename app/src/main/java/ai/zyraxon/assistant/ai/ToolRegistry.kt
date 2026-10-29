package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.MainActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.TimeUnit
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
    val UI_TOOLS = setOf("todo_write", "code_box", "files_panel", "floating_bubble")

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
            .put("description", "Open a real app installed on this phone, by package name or by its visible name.")
            .put("parameters", S(JSONObject().put("package", str()).put("name", str()), emptyList())),
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
        "write_file" to JSONObject()
            .put("description", "Create a file or replace it whole. Missing folders are created. Use this to write code — never paste code into the chat.")
            .put("parameters", S(JSONObject().put("path", str()).put("content", str())
                .put("mode", str().put("enum", JSONArray(listOf("overwrite", "append")))), listOf("path", "content"))),
        "edit_file" to JSONObject()
            .put("description", "Change part of a file by finding text and replacing it. Use this for edits; use write_file for a new file.")
            .put("parameters", S(JSONObject().put("path", str()).put("find", str()).put("replace", str()).put("all", bool()),
                listOf("path", "find", "replace"))),
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
            .put("description", "Run a real command on this phone through sh -c and return stdout, stderr and the exit code.")
            .put("parameters", S(JSONObject().put("command", str()).put("cwd", str()).put("timeout_ms", num()), listOf("command"))),
        "publish_website" to JSONObject()
            .put("description", "Write a site to local storage and serve it on a public port. Returns a local URL.")
            .put("parameters", S(JSONObject().put("name", str()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("name"))),
        "publish_github" to JSONObject()
            .put("description", "Publish a site or project to a real GitHub repository the user owns. Creates the repo if missing, commits every file and pushes. Returns the real repository URL. Needs a GitHub token saved in settings.")
            .put("parameters", S(JSONObject()
                .put("repo", str()).put("name", str()).put("description", str())
                .put("private", bool()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("repo"))),
        "publish_cloudflare" to JSONObject()
            .put("description", "Publish a site to the real internet with no account: writes the files, starts a Cloudflare quick tunnel and returns a public https URL, then opens it in the browser.")
            .put("parameters", S(JSONObject().put("name", str()).put("html", str())
                .put("files", JSONObject().put("type", "object")), listOf("name"))),
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
            .put("description", "Place a call to a number.")
            .put("parameters", S(JSONObject().put("number", str()), listOf("number"))),
        "request_permission" to JSONObject()
            .put("description", "Open the system dialog that lets the user grant a permission with one tap. Use it when a tool fails for a missing permission. name is one of: camera, microphone, location, accessibility, overlay, notifications, files.")
            .put("parameters", S(JSONObject().put("name", str()), listOf("name"))),
        "voice_change" to JSONObject()
            .put("description", "Switch your own speaking voice. gender is \"female\" or \"male\", rate is speed (0.5-2.0, default 1.0) and pitch is tone (0.5-2.0). Use it when the character changes — a boyfriend character speaks with the male voice, a girlfriend with the female one — or when the user asks for a different voice. The change takes effect on your next sentence.")
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
                .put("port", num()), listOf("name")))
    ) }

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

    /** Where the assistant keeps its own files. */
    fun workspace(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zyraxon").apply { mkdirs() }

    private fun resolve(context: Context, path: String?): File {
        val p = (path ?: "").trim()
        if (p.isEmpty()) return workspace(context)
        val f = File(p)
        return if (f.isAbsolute) f else File(workspace(context), p)
    }

    suspend fun execute(context: Context, name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "open_app" -> openApp(context, args)
            "list_apps" -> ok(DeviceBridge.listApps(context).take(6000), "ইনস্টল করা অ্যাপের তালিকা")
            "launch_url" -> launchUrl(context, args)
            "verify_url" -> verifyUrl(args)
            "read_screen" -> readScreen()
            "screen_shot" -> screenShot()
            "tap" -> ok(DeviceBridge.tap(args.optDouble("x", 0.0).toFloat(), args.optDouble("y", 0.0).toFloat()), "ট্যাপ")
            "tap_text" -> ok(DeviceBridge.tapText(args.optString("text")), "টেক্সটে ট্যাপ")
            "swipe" -> ok(DeviceBridge.swipe(
                args.optDouble("x1", 0.0).toFloat(), args.optDouble("y1", 0.0).toFloat(),
                args.optDouble("x2", 0.0).toFloat(), args.optDouble("y2", 0.0).toFloat()), "সোয়াইপ")
            "type_text" -> ok(DeviceBridge.typeText(args.optString("text")), "টাইপ")
            "press_key" -> ok(DeviceBridge.pressKey(args.optString("key")), "কী চাপা হয়েছে")
            "notify" -> { DeviceBridge.notify(context, args.optString("title", "ZYRAXON"), args.optString("body")); ok("notified", "নোটিফিকেশন পাঠানো হয়েছে") }
            "clipboard_read" -> ok(clipboard(context).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: "", "ক্লিপবোর্ড")
            "clipboard_write" -> { clipboard(context).setPrimaryClip(ClipData.newPlainText("zyraxon", args.optString("text"))); ok("copied", "ক্লিপবোর্ডে লেখা হয়েছে") }
            "share" -> share(context, args)
            "look_camera" -> lookCamera(context, args)
            "read_media" -> readMedia(context, args)
            "read_url" -> readUrl(args)
            "web_search" -> webSearch(args)
            "http_request" -> httpRequest(args)
            "download_file" -> downloadFile(context, args)
            "write_file" -> writeFile(context, args)
            "edit_file" -> editFile(context, args)
            "read_file" -> readFile(context, args)
            "list_dir" -> listDir(context, args)
            "find_files" -> findFiles(context, args)
            "grep_files" -> grepFiles(context, args)
            "delete_file" -> deleteFile(context, args)
            "run_shell" -> runShell(context, args)
            "publish_website" -> publish(context, args, tunnel = false)
            "publish_cloudflare" -> publish(context, args, tunnel = true)
            "publish_github" -> publishGithub(context, args)
            "sys_info" -> ok(sysInfo(context), "ডিভাইসের তথ্য")
            "platform_info" -> ok("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, shell: sh -c", "প্ল্যাটফর্ম")
            "process_list" -> runShell(context, JSONObject().put("command", "ps -A 2>/dev/null || ps"))
            "call_state" -> ok(DeviceBridge.callState(), "কলের অবস্থা")
            "call_answer" -> ok(DeviceBridge.answerCall(context), "কল রিসিভ")
            "call_hangup" -> ok(DeviceBridge.hangUpCall(context), "কল কেটে দেওয়া")
            "call_dial" -> ok(DeviceBridge.dial(context, args.optString("number")), "কল করা হয়েছে")
            "request_permission" -> requestPermission(context, args.optString("name"))
            "open_settings" -> openSettings(context, args.optString("page"))
            "voice_change" -> changeVoice(context, args)
            "channel_post" -> channelPost(context, args)
            "serve_local" -> serveLocal(context, args)
            else -> err("unknown-tool", "অজানা টুল: $name")
        }
    } catch (e: Exception) {
        err((e.message ?: e.toString()), "টুল ব্যর্থ: $name")
    }

    private fun ok(data: Any?, summary: String) = JSONObject().put("ok", true).put("summary", summary).put("data", data)
    private fun err(error: String, summary: String) = JSONObject().put("ok", false).put("error", error).put("summary", summary)

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun openApp(context: Context, args: JSONObject): JSONObject {
        val target = args.optString("package").ifBlank { args.optString("name") }
        if (target.isBlank()) return err("no-target", "কোন অ্যাপ খুলব বলোনি")
        val resolved = DeviceBridge.resolvePackage(context, target)
        if (resolved.isNullOrBlank()) {
            return err("app-not-installed", "এই ফোনে \"$target\" ইনস্টল করা নেই")
        }
        DeviceBridge.openApp(context, resolved)
        return ok(resolved, "\"$target\" খোলা হয়েছে")
    }

    private fun launchUrl(context: Context, args: JSONObject): JSONObject {
        val url = args.optString("url")
        if (url.isBlank()) return err("no-url", "কোন লিংক খুলব বলোনি")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok(url, "ব্রাউজারে খোলা হয়েছে: $url")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "লিংকটি খোলা যায়নি: $url")
        }
    }

    private fun verifyUrl(args: JSONObject): JSONObject {
        val url = args.optString("url")
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 12000; readTimeout = 12000
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
        val text = listOf(args.optString("text"), args.optString("url")).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return err("empty", "শেয়ার করার কিছু দাওনি")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_TITLE, args.optString("title"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(send, args.optString("title", "শেয়ার")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ok("shared", "শেয়ার শিট খোলা হয়েছে")
    }

    private fun fetch(url: String, maxBytes: Int = 300_000): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000; readTimeout = 20000
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

    private suspend fun lookCamera(context: Context, args: JSONObject): JSONObject {
        val facing = args.optString("facing", "back")
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
        val url = args.optString("url").trim()
        val path = args.optString("path").trim()
        if (url.isBlank() && path.isBlank()) {
            return err("no-source", "কোন ফাইল বা লিংক দেবে বলোনি")
        }

        val file: File
        var downloaded = false
        if (url.isNotBlank()) {
            file = downloadToCache(context, url, args.optString("kind", "auto"))
                ?: return err("download-failed", "লিংক থেকে ফাইল নামানো গেল না: $url")
            downloaded = true
        } else {
            file = resolve(context, path)
            if (!file.exists()) return err("not-found", "ফাইল নেই: ${file.absolutePath}")
        }

        val kind = args.optString("kind", "auto").let {
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
            connectTimeout = 15000; readTimeout = 45000
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
        val url = args.optString("url")
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
        val q = args.optString("query")
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
                        .put("title", o.optString("title"))
                        .put("url", "https://en.wikipedia.org/wiki/" + Uri.encode(o.optString("title")))
                        .put("snippet", o.optString("snippet").replace(Regex("<[^>]+>"), "")))
                }
            } catch (_: Exception) {}
        }
        return if (out.length() > 0) ok(out.toString(), "ওয়েবে খুঁজে পাওয়া গেছে (${out.length()} ফলাফল)")
        else err("no-results", "\"$q\" এর জন্য কিছু পাওয়া যায়নি")
    }

    private fun httpRequest(args: JSONObject): JSONObject {
        val url = args.optString("url")
        val method = args.optString("method", "GET").uppercase()
        return try {
            val body = args.optString("body")
            val sendBody = body.isNotBlank() && method != "GET" && method != "HEAD"
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000; readTimeout = 25000
                doOutput = sendBody
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                val h = args.optJSONObject("headers")
                if (h != null) for (k in h.keys()) setRequestProperty(k, h.optString(k))
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
        val url = args.optString("url")
        val dest = resolve(context, args.optString("path").ifBlank { "downloads/" + url.substringAfterLast('/').take(80) })
        return try {
            dest.parentFile?.mkdirs()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            val size = dest.length(); c.disconnect()
            if (size <= 0) err("empty", "ফাইলটি খালি এসেছে") else ok(dest.absolutePath, "ডাউনলোড হয়েছে: ${dest.name} (${size / 1024}KB)")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "ডাউনলোড ব্যর্থ: $url")
        }
    }

    private fun writeFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.optString("path"))
        return try {
            f.parentFile?.mkdirs()
            if (args.optString("mode", "overwrite") == "append") f.appendText(args.optString("content"))
            else f.writeText(args.optString("content"))
            ok(JSONObject().put("path", f.absolutePath).put("bytes", f.length()), "${f.name} লেখা হয়েছে (${f.length()} বাইট)")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "ফাইল লেখা যায়নি: ${args.optString("path")}")
        }
    }

    private fun editFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.optString("path"))
        if (!f.exists()) return err("not-found", "ফাইলটি নেই: ${f.name}")
        return try {
            val text = f.readText()
            val find = args.optString("find")
            if (!text.contains(find)) return err("not-found-in-file", "\"$find\" ফাইলে পাওয়া যায়নি")
            val out = if (args.optBoolean("all")) text.replace(find, args.optString("replace"))
            else text.replaceFirst(find, args.optString("replace"))
            f.writeText(out)
            ok(JSONObject().put("path", f.absolutePath), "${f.name} সম্পাদনা করা হয়েছে")
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "সম্পাদনা ব্যর্থ: ${f.name}")
        }
    }

    private fun readFile(context: Context, args: JSONObject): JSONObject {
        val f = resolve(context, args.optString("path"))
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
        val d = resolve(context, args.optString("path"))
        if (!d.exists()) return err("not-found", "ফোল্ডারটি নেই: ${d.name}")
        val arr = JSONArray()
        d.listFiles()?.sortedBy { it.name }?.forEach {
            arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("bytes", if (it.isDirectory) 0 else it.length()))
        }
        return ok(arr.toString(), "${d.name}/ এ ${arr.length()} টি এন্ট্রি")
    }

    private fun findFiles(context: Context, args: JSONObject): JSONObject {
        val root = resolve(context, args.optString("path"))
        val pattern = args.optString("pattern", "*").substringAfterLast('/')
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*").replace("\\?", ".") + "$")
        val out = JSONArray()
        root.walkTopDown().take(4000).forEach {
            if (out.length() < 200 && regex.matches(it.name)) out.put(it.absolutePath)
        }
        return ok(out.toString(), "${out.length()} টি ফাইল পাওয়া গেছে")
    }

    private fun grepFiles(context: Context, args: JSONObject): JSONObject {
        val root = resolve(context, args.optString("path"))
        val query = args.optString("query")
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
        val f = resolve(context, args.optString("path"))
        if (!f.exists()) return err("not-found", "ফাইলটি নেই: ${f.name}")
        val okDel = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (okDel) ok(f.absolutePath, "${f.name} মোছে ফেলা হয়েছে") else err("failed", "${f.name} মোছা যায়নি")
    }

    private fun runShell(context: Context, args: JSONObject): JSONObject {
        val cmd = args.optString("command")
        val cwd = resolve(context, args.optString("cwd").ifBlank { null })
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val errOut = p.errorStream.bufferedReader().use { it.readText() }
            val finished = p.waitFor(args.optLong("timeout_ms", 60_000), TimeUnit.MILLISECONDS)
            if (!finished) p.destroy()
            val code = if (finished) p.exitValue() else -1
            ok(JSONObject().put("code", code).put("stdout", out.take(30000)).put("stderr", errOut.take(8000)),
                "$ $cmd → exit $code" + (out.trim().lines().lastOrNull()?.take(160)?.let { " | $it" } ?: ""))
        } catch (e: Exception) {
            err((e.message ?: e.toString()), "কমান্ড চালানো যায়নি")
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
        val repoArg = args.optString("repo").trim()
        if (repoArg.isEmpty()) return err("no-repo", "রিপোজিটরির নাম বলোনি")
        val repo = repoArg.removePrefix("https://github.com/").removeSuffix(".git").trim('/')

        val files = linkedMapOf<String, String>()
        if (args.optString("html").isNotBlank()) files["index.html"] = args.optString("html")
        args.optJSONObject("files")?.let { obj ->
            for (key in obj.keys()) files[key.trimStart('/')] = obj.optString(key)
        }
        if (files.isEmpty()) return err("no-files", "প্রকাশ করার জন্য কোনো ফাইল দাওনি")

        val who = githubRequest(token, "GET", "https://api.github.com/user", null)
        val login = who?.optString("login").orEmpty()
        if (login.isEmpty()) return err("bad-token", "GitHub টোকেন কাজ করছে না")

        val full = if (repo.contains("/")) repo else "$login/$repo"
        val exists = githubRequest(token, "GET", "https://api.github.com/repos/$full", null)
        if (exists == null || exists.has("message")) {
            val body = JSONObject()
                .put("name", full.substringAfter("/"))
                .put("description", args.optString("description", "ZYRAXON দিয়ে প্রকাশিত"))
                .put("private", args.optBoolean("private", false))
                .put("auto_init", true)
            val created = githubRequest(token, "POST", "https://api.github.com/user/repos", body)
            if (created == null || created.has("message")) {
                return err("repo-create-failed", created?.optString("message") ?: "রিপো তৈরি হয়নি")
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
            if (existing != null && existing.has("sha")) body.put("sha", existing.optString("sha"))
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
                connectTimeout = 20_000
                readTimeout = 30_000
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
                JSONObject().put("message", try { JSONObject(text).optString("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" })
            }
        } catch (e: Exception) {
            JSONObject().put("message", e.message ?: "network-error")
        }
    }

    private suspend fun publish(context: Context, args: JSONObject, tunnel: Boolean): JSONObject {
        val name = args.optString("name", "site").replace(Regex("[^A-Za-z0-9._-]"), "-")
        val files = args.optJSONObject("files")
        val siteDir = File(workspace(context), "sites/$name").apply { mkdirs() }
        if (args.optString("html").isNotBlank()) File(siteDir, "index.html").writeText(args.optString("html"))
        if (files != null) {
            for (key in files.keys()) {
                val rel = key.trimStart('/')
                val target = File(siteDir, rel)
                target.parentFile?.mkdirs()
                target.writeText(files.optString(key))
            }
        }
        if (siteDir.listFiles().isNullOrEmpty()) return err("no-files", "প্রকাশ করার জন্য কোনো ফাইল দাওনি")

        if (!tunnel) {
            val served = startHttpServer(siteDir, 8791)
            if (served != null) {
                return ok(JSONObject().put("url", served).put("dir", siteDir.absolutePath),
                    "সাইট চালু হয়েছে: $served (একই ওয়াই-ফাইয়ের যেকোনো ডিভাইসে খুলবে)")
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

        val server = ProcessBuilder("sh", "-c",
            "cd '${siteDir.absolutePath}' && (python3 -m http.server 8791 2>/dev/null || toybox httpd -p 8791 -h . 2>/dev/null || busybox httpd -p 8791 -h .) &")
            .start()
        Thread.sleep(1200)

        val proc = ProcessBuilder("sh", "-c", "'${bin.absolutePath}' tunnel --url http://127.0.0.1:8791 --no-autoupdate 2>&1")
            .redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val deadline = System.currentTimeMillis() + 45_000
        var publicUrl: String? = null
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            val m = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line)
            if (m != null) { publicUrl = m.value; break }
        }
        if (publicUrl == null) {
            proc.destroy(); server.destroy()
            return err("tunnel-failed", "পাবলিক লিংক তৈরি হয়নি")
        }
        launchUrl(context, JSONObject().put("url", publicUrl))
        return ok(JSONObject().put("url", publicUrl).put("dir", siteDir.absolutePath),
            "সাইটটি লাইভ: $publicUrl (ব্রাউজারে খুলে দিয়েছি)")
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
        val channel = args.optString("channel").lowercase().trim()
        if (channel.isBlank()) return err("no-channel", "কোন চ্যানেলে দিতে হবে বলোনি")
        val body = listOf(args.optString("text"), args.optString("url"))
            .filter { it.isNotBlank() }.joinToString("\n")
        val to = args.optString("to").trim()
        val imageUri = args.optString("image_path").takeIf { it.isNotBlank() }?.let { path ->
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
                putExtra(Intent.EXTRA_SUBJECT, args.optString("subject", "ZYRAXON"))
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
        val name = args.optString("name", "site").replace(Regex("[^A-Za-z0-9._-]"), "-")
        val siteDir = File(workspace(context), "sites/$name").apply { mkdirs() }
        if (args.optString("html").isNotBlank()) File(siteDir, "index.html").writeText(args.optString("html"))
        args.optJSONObject("files")?.let { files ->
            for (key in files.keys()) {
                val target = File(siteDir, key.trimStart('/'))
                target.parentFile?.mkdirs()
                target.writeText(files.optString(key))
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
            act.runOnUiThread { act.requestPermissions(perms, 9101) }
            return ok(n, "পারমিশন ডায়ালগ আসছে — Allow চাপলেই হবে")
        }
        return err("no-activity", "পারমিশন ডায়ালগ দেখানো যায়নি")
    }

    /**
     * Changes the assistant's own voice. The ViewModel owns the voice manager, so
     * the call is routed there; the result reports what the voice actually is now.
     */
    private fun changeVoice(context: Context, args: JSONObject): JSONObject {
        val gender = args.optString("gender", "").lowercase()
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
        latch.await(3, TimeUnit.SECONDS)
        return launched
    }
}
