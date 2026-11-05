package ai.zyraxon.assistant.openhands

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
            val url = o.optString("conversation_url", "")
            // The agent server base is everything before the conversation path;
            // it is a different host from the account API and must be used for
            // every live call (events, interrupt, confirmation policy).
            val base = url.substringBefore("/api/conversations/").ifBlank { url }
            return OhConversation(
                id = o.optString("id"),
                title = o.optString("title"),
                sandboxId = o.optString("sandbox_id"),
                sandboxStatus = o.optString("sandbox_status"),
                executionStatus = o.optString("execution_status"),
                agentBase = base,
                sessionKey = o.optString("session_api_key"),
                llmModel = o.optString("llm_model")
            )
        }
    }
}

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
            id = o.optString("id"),
            status = o.optString("status"),
            detail = o.optString("detail").ifBlank { null },
            conversationId = o.optString("app_conversation_id").ifBlank { null },
            sandboxId = o.optString("sandbox_id").ifBlank { null }
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
    /** A token (or a few) of the answer, and/or of the thinking that precedes it. */
    data class Delta(val text: String, val reasoning: String) : OhEvent()

    /** The agent decided to call a tool. */
    data class Action(val name: String, val args: JSONObject, val id: String) : OhEvent()

    /** What that tool returned. */
    data class Observation(val name: String, val text: String) : OhEvent()

    /** A finished message (user, assistant or tool). */
    data class Message(val role: String, val text: String, val reasoning: String) : OhEvent()

    /** A lifecycle change: running, finished, paused, idle. */
    data class Status(val value: String) : OhEvent()

    /** The turn died; [detail] carries the cloud's own words. */
    data class Failure(val code: String, val detail: String) : OhEvent()

    /** Anything else, so nothing is silently dropped. */
    data class Other(val kind: String, val json: JSONObject) : OhEvent()

    companion object {
        fun parse(raw: String): OhEvent? {
            val o = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            return when (val kind = o.optString("kind")) {
                "StreamingDeltaEvent" -> Delta(
                    text = o.optString("content"),
                    reasoning = o.optString("reasoning_content")
                )

                "ActionEvent" -> {
                    val call = o.optJSONObject("tool_call") ?: JSONObject()
                    Action(
                        name = o.optString("tool_name").ifBlank { call.optString("name") },
                        args = parseArgs(call.opt("arguments")),
                        id = call.optString("id")
                    )
                }

                "ObservationEvent" -> Observation(
                    name = o.optString("tool_name"),
                    text = observationText(o.opt("observation"))
                )

                "MessageEvent" -> {
                    val m = o.optJSONObject("llm_message") ?: JSONObject()
                    Message(
                        role = m.optString("role"),
                        text = contentText(m.opt("content")),
                        reasoning = m.optString("reasoning_content")
                            .ifBlank { m.optString("reasoning") }
                    )
                }

                "ConversationStateUpdateEvent" ->
                    if (o.optString("key") == "execution_status") Status(o.optString("value"))
                    else Other(kind, o)

                "ConversationErrorEvent" -> Failure(
                    code = o.optString("code"),
                    detail = o.optString("detail")
                )

                else -> Other(kind, o)
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
                    if (part.optString("type") == "text") append(part.optString("text"))
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
                val s = o.optString(key)
                if (s.isNotBlank()) return s
            }
            return o.toString()
        }
    }
}
