package ai.zyraxon.assistant.world

import ai.zyraxon.assistant.autopilot.Autopilot

enum class Stage { GREET, ASK_NAME, ASK_PURPOSE, ASK_MORE, CLOSING, ENDED }

data class Line(val who: String, val text: String, val t: Long)

/** What the call agent does next: say [say], ask the model ([model]), and/or hang up. */
data class Reply(val say: String = "", val model: Boolean = false, val hangUp: Boolean = false)

data class CallSummary(
    val number: String, val caller: String, val purpose: String, val urgent: Boolean, val spam: Boolean,
    val seconds: Int, val messages: List<String>, val transcript: List<Line>
) {
    private val en = mapOf("call_title" to "📞 Call summary", "call_who" to "From", "call_no_name" to "name not given", "call_no_number" to "number unknown", "call_spam" to "Looks like spam / advertising",
        "call_urgent" to "Urgent: yes", "call_wants" to "Wants", "call_unclear" to "not clearly said", "call_msg" to "Message", "call_time" to "Duration", "call_min" to "min", "call_sec" to "sec",
        "call_talk" to "Conversation", "call_caller" to "Caller", "call_agent" to "Assistant")

    /** [t] gives words in the user's language; a missing key falls back to English. */
    fun text(owner: String, t: (String) -> String = { it }): String {
        fun w(k: String): String { val v = t(k); return if (v == k || v.isBlank()) en.getValue(k) else v }
        return buildString {
            append(w("call_title")).append('\n')
            append(w("call_who")).append(": ").append(caller.ifBlank { w("call_no_name") }).append(" (").append(number.ifBlank { w("call_no_number") }).append(")\n")
            if (spam) append(w("call_spam")).append('\n')
            if (urgent) append(w("call_urgent")).append('\n')
            append(w("call_wants")).append(": ").append(purpose.ifBlank { w("call_unclear") }).append('\n')
            messages.forEach { append(w("call_msg")).append(": ").append(it).append('\n') }
            append(w("call_time")).append(": ").append(seconds / 60).append(' ').append(w("call_min")).append(' ').append(seconds % 60).append(' ').append(w("call_sec")).append('\n')
            append('\n').append(w("call_talk")).append(":\n"); transcript.forEach { append(if (it.who == "caller") w("call_caller") else w("call_agent") + " ($owner)").append(": ").append(it.text).append('\n') }
        }
    }

    fun smsToOwner(): String = (if (urgent) "‼ " else "☎ ") + (caller.ifBlank { number }) + " — " + purpose.ifBlank { messages.firstOrNull().orEmpty() }.take(120)
}

/**
 * Answers a phone call for the owner, in any language. The caller's words arrive as text (from speech recognition)
 * and the replies go back as text (to the voice).
 *
 * With a [LangPack] the usual flow (greet, name, purpose, anything else, goodbye) is rule-based and instant.
 * With no pack — or when the caller needs a real answer — the model talks, in the caller's own language, and
 * marks the call with small tags: [NAME: x], [PURPOSE: x], [URGENT], [SPAM], [END].
 */
class CallSession(val owner: String, val number: String = "", knownName: String = "", private val now: () -> Long = { System.currentTimeMillis() }, val pack: LangPack? = null) {
    var stage = Stage.GREET; private set
    var callerName = knownName; private set
    var purpose = ""; private set
    var urgent = false; private set
    var spam = false; private set
    private val extra = ArrayList<String>()
    private val lines = ArrayList<Line>()
    private val started = now()
    private var silence = 0
    private var turns = 0
    private var nameTries = 0
    private var note = ""          // what the model should react to next (silence, wrap-up)
    private var forceEnd = false

    /** True when the model does all the talking (no rule pack for this language). */
    val modelDriven get() = pack == null

    fun transcript(): List<Line> = lines.toList()
    private fun say(text: String): Reply { lines += Line("agent", text, now()); return Reply(say = text) }
    private fun lower(s: String) = Autopilot.norm(s)
    private fun any(x: String, ws: List<String>) = ws.any { x.contains(it) }

    /** The first thing to do when the call is answered. */
    fun start(): Reply {
        if (pack == null) { note = "start"; return Reply(model = true) }
        val g = if (callerName.isNotBlank()) pack.greetKnown(owner, callerName) else pack.greetUnknown(owner)
        stage = if (callerName.isNotBlank()) Stage.ASK_PURPOSE else Stage.ASK_NAME
        return say(g)
    }

    fun extractName(text: String): String {
        val t = text.trim().trimEnd('。', '।', '.', '!', '?', '؟', '！', '？')
        val intros = (pack?.nameIntros.orEmpty() + LangPack.packs.getValue("en").nameIntros).distinct().sortedByDescending { it.length }
        val sufs = (pack?.nameSuffixes.orEmpty() + LangPack.packs.getValue("en").nameSuffixes).distinct()
        val iAlt = intros.joinToString("|") { Regex.escape(it) }; val sAlt = sufs.joinToString("|") { Regex.escape(it) }
        val pats = listOf(
            Regex("(?i)^(?:$iAlt)\\s+([\\p{L}\\p{M}.]{2,20})\\s*[,،、。.]"),
            Regex("(?i)(?:$iAlt)\\s*([\\p{L}\\p{M} .]{2,30}?)(?:\\s*(?:$sAlt))?$"),
            Regex("(?i)^([\\p{L}\\p{M} .]{2,24}?)\\s*(?:$sAlt)$")
        )
        for (p in pats) p.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.split(' ').size <= 4 && !any(lower(it), LangPack.question) }?.let { return it }
        // a bare name: one to three words and no question or goodbye in it
        val w = t.split(Regex("\\s+"))
        if (w.size in 1..3 && t.length in 2..28 && t.none { it.isDigit() } && !any(lower(t), LangPack.question) && !any(lower(t), LangPack.goodbye)) return t
        return ""
    }

    fun isGoodbye(text: String): Boolean { val x = lower(text); return any(x, LangPack.goodbye) && x.length < 60 }

    /** The caller said something. */
    fun onCaller(text: String): Reply {
        val raw = text.trim(); if (raw.isEmpty()) return onSilence()
        silence = 0; turns++; lines += Line("caller", raw, now())
        val x = lower(raw)
        if (any(x, LangPack.urgent)) urgent = true
        if (stage == Stage.ENDED) return Reply(hangUp = true)
        if (turns > 14) { forceEnd = true; if (pack == null) return Reply(model = true); return finish(pack.tooLong()) }
        if (pack == null) { if (callerName.isBlank() && turns == 1 && purpose.isEmpty()) purpose = raw.take(200); if (any(x, LangPack.spam)) spam = true; return Reply(model = true) }
        if (any(x, LangPack.spam) && (stage != Stage.ASK_NAME || extractName(raw).isBlank())) { spam = true; if (purpose.isEmpty()) purpose = raw.take(140); return finish(pack.spamClose()) }
        return when (stage) {
            Stage.GREET, Stage.ASK_NAME -> {
                nameTries++
                val n = extractName(raw)
                if (n.isNotBlank()) {
                    callerName = n
                    // name and reason often come in one breath: "I'm Rahim, about tomorrow's meeting"
                    val rest = raw.substringAfter(n, "").trim(' ', ',', '،', '。', '।', '.')
                    if (rest.length > 12) { purpose = rest.take(200); stage = Stage.ASK_MORE; say(pack.noted(owner, callerName)) }
                    else if (purpose.isNotEmpty()) { stage = Stage.ASK_MORE; say(pack.noted(owner, callerName)) }
                    else { stage = Stage.ASK_PURPOSE; say(pack.askPurpose(callerName)) }
                } else if (nameTries >= 2) {
                    if (purpose.isEmpty()) purpose = raw.take(200); stage = Stage.ASK_MORE
                    say(pack.takeMessage())
                } else {
                    // the caller began with the reason: keep it, then ask for the name once
                    purpose = raw.take(200); stage = Stage.ASK_NAME
                    say(pack.askNameOnce())
                }
            }
            Stage.ASK_PURPOSE -> {
                if (isGoodbye(raw)) return finish(pack.byeNormal())
                purpose = raw.take(200); stage = Stage.ASK_MORE
                if (any(x, LangPack.question)) Reply(model = true) else say(pack.thanksPurpose(owner))
            }
            Stage.ASK_MORE -> {
                if (isGoodbye(raw) || LangPack.no.contains(x)) return finish(pack.byeMessage())
                if (any(x, LangPack.question) && raw.length > 6) return Reply(model = true)
                extra += raw.take(200); say(pack.writtenDown())
            }
            Stage.CLOSING -> finish(pack.byeNormal())
            Stage.ENDED -> Reply(hangUp = true)
        }
    }

    /** The model's answer; tags in it update the call record, and [END] hangs up. */
    fun modelSaid(textRaw: String): Reply {
        var t = textRaw.trim(); if (t.isEmpty()) return if (pack != null) say(pack.fallbackAnswer()) else Reply()
        Regex("\\[NAME:\\s*([^\\]]{1,40})]").find(t)?.let { if (callerName.isBlank()) callerName = it.groupValues[1].trim() }
        Regex("\\[PURPOSE:\\s*([^\\]]{1,200})]").find(t)?.let { purpose = it.groupValues[1].trim() }
        if (t.contains("[URGENT]")) urgent = true
        if (t.contains("[SPAM]")) spam = true
        val end = t.contains("[END]") || forceEnd
        t = t.replace(Regex("\\[(?:NAME|PURPOSE):[^\\]]*]"), "").replace("[URGENT]", "").replace("[SPAM]", "").replace("[END]", "").trim()
        note = ""
        if (t.isEmpty()) return if (end) { stage = Stage.ENDED; Reply(hangUp = true) } else Reply()
        stage = if (end) Stage.ENDED else Stage.ASK_MORE
        lines += Line("agent", t.take(300), now())
        return Reply(say = t.take(300), hangUp = end)
    }

    /** The caller said nothing for a while. */
    fun onSilence(): Reply {
        silence++
        if (pack == null) { if (silence >= 3) { forceEnd = true }; note = if (silence >= 2) "silent-twice" else "silent"; return Reply(model = true) }
        return if (silence == 1) say(pack.areYouThere()) else if (silence >= 2) finish(pack.noLine()) else Reply()
    }

    private fun finish(text: String): Reply { stage = Stage.ENDED; lines += Line("agent", text, now()); return Reply(say = text, hangUp = true) }

    fun summary(): CallSummary = CallSummary(number, callerName, purpose.ifBlank { lines.firstOrNull { it.who == "caller" }?.text?.take(140).orEmpty() }, urgent, spam,
        ((now() - started) / 1000).toInt().coerceAtLeast(0), extra.toList(), transcript())

    /**
     * The prompt for one turn of a call.
     *
     * The rules are not here: [contract] is the agent contract the app downloaded
     * on first open, which carries the phone-call section. Only the facts of this
     * call — who, what number, what has been said — are added.
     */
    fun modelPrompt(question: String, contract: String): String = buildString {
        if (contract.isNotBlank()) append(contract.trim()).append("\n\n")
        append("## This call\n")
        append("You are the assistant of $owner, on a live call with a caller right now.\n")
        if (callerName.isNotBlank()) append("Caller: $callerName\n")
        if (number.isNotBlank()) append("Number: $number\n")
        if (lines.isNotEmpty()) {
            append("Conversation so far:\n")
            lines.takeLast(10).forEach {
                append(if (it.who == "caller") "Caller: " else "You: ").append(it.text).append('\n')
            }
        }
        when {
            forceEnd -> append("The call has gone on long enough: thank the caller, say you will pass the message on, say goodbye, and add [END].\n")
            note == "start" -> append("The call was just answered. Greet the caller, say you are $owner's assistant and $owner cannot take the call now, and ask for their name. Greet in the language that is most likely for this phone number's country.\n")
            note == "silent" -> append("The caller has said nothing. Ask briefly if they are still there.\n")
            note == "silent-twice" -> append("The caller is silent again. Say you cannot hear them, ask them to call back later, say goodbye, and add [END].\n")
            else -> append("The caller just said: ").append(question).append('\n')
        }
        append("Reply with only what you say to the caller, then the tags.")
    }
}
