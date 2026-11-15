// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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
     *
     * The read cursor goes too, and it has to: a cursor is a position in *one*
     * conversation's event log. The caller drops the binding precisely because a
     * fresh conversation is about to be built, and that new log starts empty — a
     * cursor kept from the dead one would ask the new log for events after a
     * timestamp it has never reached, so the first messages of the new
     * conversation would be silently skipped.
     */
    fun unbind(chatId: String) {
        prefs.edit()
            .remove("conv_$chatId").remove("sandbox_$chatId")
            .remove("cursor_$chatId").remove("running_$chatId")
            .apply()
    }

    /**
     * How far this chat's cloud transcript has been read, as an event timestamp.
     *
     * This is the one piece of cloud state that must outlive the process. It used
     * to live in a local `var` inside a turn, so closing the app — or a network
     * that died with the app still in the background — threw it away, and every
     * event the cloud produced while the phone was away was simply never asked
     * for again. Written down here, a relaunch resumes from the exact event the
     * phone last handled, however long it was gone.
     */
    fun cursorFor(chatId: String): String? =
        prefs.getString("cursor_$chatId", null)?.takeIf { it.isNotBlank() }

    fun setCursor(chatId: String, at: String) {
        if (at.isBlank()) return
        prefs.edit().putString("cursor_$chatId", at).apply()
    }

    /**
     * True while the cloud last reported this chat's turn still running.
     *
     * Also survives the process. A turn does not stop because the phone went
     * away, so on relaunch this is what says "there is work still happening over
     * there" — without it the chat opened as idle and the user had no way to know
     * their task was still being carried out.
     */
    fun runningFor(chatId: String): Boolean = prefs.getBoolean("running_$chatId", false)

    fun setRunning(chatId: String, running: Boolean) =
        prefs.edit().putBoolean("running_$chatId", running).apply()

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
