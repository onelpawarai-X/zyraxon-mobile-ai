// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.permission

import android.content.Context

/**
 * How much the agent may do on its own before it has to stop and ask.
 *
 * This is the code-level gate the Settings section writes to: the level is read
 * here, on every tool call, and a tool that the level marks as "ask" is held
 * until the user answers. The setting alone is not trusted — a prompt cannot
 * widen what this object allows, and a model that names a gated tool still
 * cannot run it.
 *
 * Four levels, from most cautious to fully automatic:
 *
 *  1. LOCKED      — ask before anything that changes the phone.
 *  2. SUPERVISED  — ask before system and personal actions. This is the default,
 *                   because a new user should see what the agent is about to do
 *                   before it does it.
 *  3. BALANCED    — ask only before system-level actions.
 *  4. AUTOMATIC   — never ask; run straight through.
 *
 * A tool the app does not recognise — an MCP tool from a connected server, or
 * anything added later — is treated as system-level, so an unknown tool asks
 * rather than slips through.
 */
object AutomationPolicy {

    const val PREFS = "zyraxon_automation"
    private const val KEY_LEVEL = "level"
    private const val KEY_ON_DEVICE = "on_device_only"

    const val LOCKED = 1
    const val SUPERVISED = 2
    const val BALANCED = 3
    const val AUTOMATIC = 4

    /** A cautious default: the user sees each changing action before it happens. */
    const val DEFAULT_LEVEL = SUPERVISED

    fun level(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(LOCKED, AUTOMATIC)

    fun setLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LEVEL, level.coerceIn(LOCKED, AUTOMATIC)).apply()
    }

    /**
     * When true, the agent may run only the on-device model and the tools that
     * keep the work on the phone: no cloud provider, no upload, no web call.
     */
    fun onDeviceOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON_DEVICE, false)

    fun setOnDeviceOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON_DEVICE, value).apply()
    }

    /**
     * Tools that touch the system itself — a shell, a build, a publish, a delete,
     * a device lock, a schedule. The highest tiers ask before these.
     */
    private val SYSTEM_TOOLS = setOf(
        "system_shell", "linux_run", "run_shell", "background_command",
        "build_app", "setup_shizuku", "device_lock", "schedule_command",
        "serve_local", "publish_website", "publish_cloudflare", "publish_github",
        "github_actions", "github_pr", "git", "delete_file", "ffmpeg",
        "on_device_model", "channel_post",
        // The six engines' rules and removals: an autopilot rule runs work on its
        // own later, and a removal destroys something the user configured, so both
        // sit with the other schedules and deletes.
        "autopilot_add", "autopilot_toggle", "autopilot_remove", "skill_delete"
    )

    /**
     * Tools that reach the user's own data or drive the phone in front of them:
     * messages, calls, the camera, the screen, the clipboard, taps and typing.
     */
    private val PERSONAL_TOOLS = setOf(
        "read_sms", "call_dial", "call_answer", "call_hangup", "call_state",
        "look_camera", "read_media", "read_screen", "screen_shot",
        "clipboard_read", "clipboard_write", "tap", "tap_text", "swipe",
        "type_text", "press_key", "notify", "share", "download_file",
        "voice_change", "floating_bubble",
        // The six engines that touch the user's own data or drive the phone: a
        // recorded skill replays taps and typing, the ledger reads the user's SMS,
        // the call agent answers their calls, and a timeline undo reverses a real
        // change the agent made.
        "skill_record", "skill_run", "skill_import", "ledger_scan", "ledger_report",
        "ledger_budget", "call_agent", "timeline_undo"
    )

    /**
     * Read-only and local: a document built in the workspace, a memory entry, a
     * file read, a status query. None of these changes the phone, so no level
     * ever stops for them — a level that asked here would bury the user in
     * prompts and teach them to tap through without reading.
     */
    private val SAFE_TOOLS = setOf(
        "read_file", "list_dir", "find_files", "grep_files", "sys_info",
        "platform_info", "network_status", "process_list",
        "list_apps", "memory_write", "memory_read", "memory_delete",
        "make_document", "office_template", "make_design", "brand_kit",
        "present_slides", "open_file", "open_settings", "request_permission",
        "wait", "launch_url", "open_app",
        // The engines' read-only and local halves: listing what is configured,
        // searching an index built on the phone, reading the action log and the
        // call log. None of these changes anything, so no level asks for them.
        "skill_list", "autopilot_list", "brain_index", "brain_search",
        "timeline_list", "call_log"
    )

    /**
     * True when [tool] is a system-level action.
     *
     * A tool in none of the three lists — an MCP tool, or one added later — is
     * treated as system-level, so an unknown tool asks rather than slips through.
     * The test is written against the three sets directly; routing it through
     * [isKnown] would make the two call each other forever.
     */
    fun isSystem(tool: String): Boolean =
        tool in SYSTEM_TOOLS || !(tool in SYSTEM_TOOLS || tool in PERSONAL_TOOLS || tool in SAFE_TOOLS)

    /** True when [tool] is a personal or device-driving action. */
    fun isPersonal(tool: String): Boolean = tool in PERSONAL_TOOLS

    /** True when [tool] only reads or only works inside the workspace. */
    fun isSafe(tool: String): Boolean = tool in SAFE_TOOLS

    /**
     * Whether the user must be asked before [tool] runs at [level].
     *
     * An MCP tool carries a `<server>__<tool>` name; it is not in any list here,
     * so [isSystem] treats it as system-level and it asks until the user chooses
     * a level that trusts it.
     */
    fun asks(level: Int, tool: String): Boolean {
        if (isSafe(tool)) return false
        return when (level.coerceIn(LOCKED, AUTOMATIC)) {
            LOCKED -> true
            SUPERVISED -> isSystem(tool) || isPersonal(tool)
            BALANCED -> isSystem(tool)
            else -> false
        }
    }

    /** The tier name of [tool], for the prompt the user reads. */
    fun tierOf(tool: String): Tier = when {
        isSafe(tool) -> Tier.SAFE
        isPersonal(tool) -> Tier.PERSONAL
        else -> Tier.SYSTEM
    }

    enum class Tier { SAFE, PERSONAL, SYSTEM }

    /**
     * Tools that send something off the phone: a search, a fetch, an upload, a
     * publish, a provider call. With "on-device only" on, none of these run, so
     * the promise that nothing leaves the phone is kept in code and not only in
     * the wording of the switch.
     */
    private val NETWORK_TOOLS = setOf(
        "web_search", "read_url", "verify_url", "http_request", "download_file",
        "publish_website", "publish_cloudflare", "publish_github", "github_actions",
        "github_pr", "git", "channel_post", "generate_image", "generate_audio",
        "generate_video", "mcp_connect", "mcp_list", "mcp_disconnect",
        "mcp_resources", "mcp_read_resource", "mcp_prompts", "mcp_prompt",
        "mcp_subscribe", "mcp_complete", "mcp_ping", "mcp_log_level"
    )

    /** True when [tool] would send data off the phone. */
    fun isNetwork(tool: String): Boolean =
        tool in NETWORK_TOOLS || tool.contains("__")

    /** The reason a tool is refused under "on-device only", or blank if it is allowed. */
    fun offlineRefusal(tool: String): String = if (isNetwork(tool)) tool else ""
}
