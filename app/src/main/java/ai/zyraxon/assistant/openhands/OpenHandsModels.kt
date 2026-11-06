package ai.zyraxon.assistant.openhands

import ai.zyraxon.assistant.core.safe

import org.json.JSONArray
import org.json.JSONObject

/**
 * One remote conversation as the cloud reports it. `id` is the cloud's own id and
 * is what every later call is addressed by; `sandboxId` is kept so a new chat can
 * reuse an already-warm sandbox instead of paying the cold-start again.
 */
data class OhConversation(
    val id: String,
    val title: String,
    val sandboxId: String,
    val sandboxStatus: String,
    val executionStatus: String,
    /** Base of the per-conversation agent server, e.g. `https://x.prod-runtime…`. */
    val agentBase: String,
    /** Rotates every time the sandbox resumes, so it is never cached across a wake. */
    val sessionKey: String,
    val llmModel: String
) {
    companion object {
        fun from(o: JSONObject): OhConversation {
            val url = o.str("conversation_url", "")
            // The agent server base is everything before the conversation path;
            // it is a different host from the account API and must be used for
            // every live call (events, interrupt, confirmation policy).
            val base = url.substringBefore("/api/conversations/").ifBlank { url }
            return OhConversation(
                id = o.str("id"),
                title = o.str("title"),
                sandboxId = o.str("sandbox_id"),
                sandboxStatus = o.str("sandbox_status"),
                executionStatus = o.str("execution_status"),
                agentBase = base,
                sessionKey = o.str("session_api_key"),
                llmModel = o.str("llm_model")
            )
        }
    }
}

/**
 * Android's `optString` returns the four letters "null" for a JSON null. The cloud
 * sends null for a conversation id that does not exist yet, and that string was
 * being used as a real id: `GET /app-conversations?ids=null` -> "Invalid UUID",
 * the chat never got bound, and every message started a brand-new conversation.
 */
internal fun JSONObject.str(key: String, default: String = ""): String = safe(key, default)

/** A conversation that is still being provisioned; the sandbox is not up yet. */
data class OhTask(
    val id: String,
    val status: String,
    val detail: String?,
    val conversationId: String?,
    val sandboxId: String?
) {
    val isReady: Boolean get() = !conversationId.isNullOrBlank()
    val isFailed: Boolean get() = status == "ERROR" || status == "FAILED"

    companion object {
        fun from(o: JSONObject): OhTask = OhTask(
            id = o.str("id"),
            status = o.str("status"),
            detail = o.str("detail").ifBlank { null },
            conversationId = o.str("app_conversation_id").ifBlank { null },
            sandboxId = o.str("sandbox_id").ifBlank { null }
        )
    }
}

/**
 * Everything the live socket can deliver, already classified.
 *
 * The cloud sends a single JSON stream; classifying it here keeps the engine
 * free of the wire format, so a new event kind is one branch rather than a
 * change at every call site.
 */
sealed class OhEvent {
    /** The server's id for this event, used to drop a duplicate delivery. */
    abstract val id: String

    /** A token (or a few) of the answer, and/or of the thinking that precedes it. */
    data class Delta(override val id: String, val text: String, val reasoning: String) : OhEvent()

    /** The agent decided to call a tool. */
    data class Action(override val id: String, val name: String, val args: JSONObject, val callId: String) : OhEvent()

    /** What that tool returned. */
    data class Observation(override val id: String, val name: String, val text: String) : OhEvent()

    /** A finished message (user, assistant or tool). */
    data class Message(override val id: String, val role: String, val text: String, val reasoning: String) : OhEvent()

    /** A lifecycle change: running, finished, paused, idle. */
    data class Status(override val id: String, val value: String) : OhEvent()

    /** The turn died; [detail] carries the cloud's own words. */
    data class Failure(override val id: String, val code: String, val detail: String) : OhEvent()

    /** Anything else, so nothing is silently dropped. */
    data class Other(override val id: String, val kind: String, val json: JSONObject) : OhEvent()

    companion object {
        fun parse(raw: String): OhEvent? {
            val o = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            val id = o.str("id")
            return when (val kind = o.str("kind")) {
                "StreamingDeltaEvent" -> Delta(
                    id = id,
                    text = o.str("content"),
                    reasoning = o.str("reasoning_content")
                )

                "ActionEvent" -> {
                    val call = o.optJSONObject("tool_call") ?: JSONObject()
                    Action(
                        id = id,
                        name = o.str("tool_name").ifBlank { call.str("name") },
                        args = parseArgs(call.opt("arguments")),
                        callId = call.str("id")
                    )
                }

                "ObservationEvent" -> Observation(
                    id = id,
                    name = o.str("tool_name"),
                    text = observationText(o.opt("observation"))
                )

                "MessageEvent" -> {
                    val m = o.optJSONObject("llm_message") ?: JSONObject()
                    Message(
                        id = id,
                        role = m.str("role"),
                        text = contentText(m.opt("content")),
                        reasoning = m.str("reasoning_content")
                            .ifBlank { m.str("reasoning") }
                    )
                }

                "ConversationStateUpdateEvent" ->
                    if (o.str("key") == "execution_status") Status(id, o.str("value"))
                    else Other(id, kind, o)

                "ConversationErrorEvent" -> Failure(
                    id = id,
                    code = o.str("code"),
                    detail = o.str("detail")
                )

                else -> Other(id, kind, o)
            }
        }

        /**
         * Tool arguments arrive as an object, but a provider that streams them can
         * leave a bare string while the call is still being written. Both are
         * accepted so a partially-arrived call is still visible.
         */
        private fun parseArgs(v: Any?): JSONObject = when (v) {
            is JSONObject -> v
            is String -> try {
                JSONObject(v)
            } catch (_: Exception) {
                JSONObject()
            }
            else -> JSONObject()
        }

        /** A message body is a list of typed parts; the text ones are joined. */
        fun contentText(v: Any?): String = when (v) {
            is String -> v
            is JSONArray -> buildString {
                for (i in 0 until v.length()) {
                    val part = v.optJSONObject(i) ?: continue
                    if (part.str("type") == "text") append(part.str("text"))
                }
            }
            else -> ""
        }

        /**
         * An observation is usually a message body, but a failed command reports
         * its reason in other fields, so those are read too rather than showing an
         * empty result for the very calls the user most needs to see.
         */
        private fun observationText(v: Any?): String {
            val o = v as? JSONObject ?: return contentText(v)
            val direct = contentText(o.opt("content"))
            if (direct.isNotBlank()) return direct
            for (key in listOf("text", "output", "stdout", "error", "message", "detail")) {
                val s = o.str(key)
                if (s.isNotBlank()) return s
            }
            return o.toString()
        }
    }
}
