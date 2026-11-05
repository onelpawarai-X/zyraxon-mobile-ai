package ai.zyraxon.assistant.openhands

import android.content.Context
import org.json.JSONObject

/**
 * Which cloud conversation backs which chat on this phone.
 *
 * Kept beside the API key rather than in the chat database on purpose: a mapping
 * is only meaningful together with the key it was made with, and the two must be
 * cleared together. Signing in with a different account and finding stale ids
 * would point every chat at someone else's sandbox.
 *
 * A warm sandbox id is also remembered, because creating a sandbox is rationed
 * per day while reusing one is free. It is kept as a pool: any chat that opens
 * while a warm sandbox is free takes it, which is what keeps the second, third
 * and fourth chat of a day instant.
 */
class OpenHandsStore(context: Context) {

    private val prefs = context.getSharedPreferences("zyraxon_openhands", Context.MODE_PRIVATE)

    /** The cloud conversation id for a local chat id, or null if it has none yet. */
    fun conversationFor(chatId: String): String? =
        prefs.getString("conv_$chatId", null)?.takeIf { it.isNotBlank() }

    fun bind(chatId: String, conversationId: String, sandboxId: String) {
        prefs.edit()
            .putString("conv_$chatId", conversationId)
            .putString("sandbox_$chatId", sandboxId)
            .apply()
    }

    fun unbind(chatId: String) {
        val sandbox = prefs.getString("sandbox_$chatId", null)
        prefs.edit().remove("conv_$chatId").remove("sandbox_$chatId").apply()
        if (!sandbox.isNullOrBlank() && sandbox !in allSandboxes()) warmSandboxes.add(sandbox)
    }

    /**
     * A sandbox that is already up and can host a new conversation.
     *
     * The cloud reports a sandbox per conversation, so every chat that has ever
     * started leaves one behind. The most recent is the likeliest to still be
     * warm, so it is offered first.
     */
    fun takeWarmSandbox(): String? {
        val fromChats = allSandboxes().toMutableList()
        fromChats.addAll(warmSandboxes)
        return fromChats.lastOrNull { it.isNotBlank() }
    }

    fun rememberWarmSandbox(sandboxId: String?) {
        if (!sandboxId.isNullOrBlank()) warmSandboxes.add(sandboxId)
    }

    /** The last conversation the user had open, so a relaunch lands where they left. */
    var lastConversationId: String?
        get() = prefs.getString("last_conversation", null)
        set(v) = prefs.edit().putString("last_conversation", v).apply()

    /** Every binding, for a one-shot resync after a fresh launch. */
    fun bindings(): Map<String, String> = prefs.all.entries
        .filter { it.key.startsWith("conv_") && it.value is String }
        .associate { it.key.removePrefix("conv_") to it.value as String }

    fun clear() = prefs.edit().clear().apply()

    private fun allSandboxes(): List<String> = prefs.all.entries
        .filter { it.key.startsWith("sandbox_") && it.value is String }
        .mapNotNull { it.value as? String }

    private val warmSandboxes = mutableListOf<String>()

    /** Kept for a future migration; the mapping is otherwise plain key/value. */
    fun snapshot(): JSONObject = JSONObject().apply {
        bindings().forEach { (k, v) -> put(k, v) }
    }
}
