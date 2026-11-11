package ai.zyraxon.assistant.cloud

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
 * A sandbox id is remembered only as the one this chat used, never as a pool to
 * hand to another chat. That distinction is the whole reason this class no longer
 * offers a "warm" sandbox: a remembered id may have been reclaimed since, and
 * reusing a dead one fails the start. Whether a sandbox is really up is asked of
 * the account, live, by `CloudClient.findWarmSandbox`.
 */
class CloudStore(context: Context) {

    private val prefs = context.getSharedPreferences("zyraxon_cloud", Context.MODE_PRIVATE)

    /** The cloud conversation id for a local chat id, or null if it has none yet. */
    fun conversationFor(chatId: String): String? =
        prefs.getString("conv_$chatId", null)?.takeIf { it.isNotBlank() }

    fun bind(chatId: String, conversationId: String, sandboxId: String) {
        prefs.edit()
            .putString("conv_$chatId", conversationId)
            .putString("sandbox_$chatId", sandboxId)
            .apply()
    }

    /**
     * Drops the binding for a chat.
     *
     * The sandbox id goes with it. It used to be pushed into a pool for other
     * chats to reuse, which is how a chat could inherit a machine that had since
     * been reclaimed and spend a whole failed start on it.
     */
    fun unbind(chatId: String) {
        prefs.edit().remove("conv_$chatId").remove("sandbox_$chatId").apply()
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

    /** Kept for a future migration; the mapping is otherwise plain key/value. */
    fun snapshot(): JSONObject = JSONObject().apply {
        bindings().forEach { (k, v) -> put(k, v) }
    }
}
