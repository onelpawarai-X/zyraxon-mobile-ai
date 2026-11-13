package ai.zyraxon.assistant.callagent

import ai.zyraxon.assistant.autopilot.Autopilot

enum class Stage { GREET, ASK_NAME, ASK_PURPOSE, ASK_MORE, CLOSING, ENDED }

data class Line(val who: String, val text: String, val t: Long)

/** What the call agent does next: say [say], ask the model to answer ([model]), and/or hang up. */
data class Reply(val say: String = "", val model: Boolean = false, val hangUp: Boolean = false)

data class CallSummary(
    val number: String, val caller: String, val purpose: String, val urgent: Boolean, val spam: Boolean,
    val seconds: Int, val messages: List<String>, val transcript: List<Line>
) {
    fun text(owner: String): String = buildString {
        append("📞 কল সারাংশ\n")
        append("কে: ").append(caller.ifBlank { "নাম বলেননি" }).append(" (").append(number.ifBlank { "নম্বর অজানা" }).append(")\n")
        if (spam) append("ধরন: সম্ভাব্য স্প্যাম/বিজ্ঞাপন\n")
        if (urgent) append("জরুরি: হ্যাঁ\n")
        append("কী চান: ").append(purpose.ifBlank { "স্পষ্ট করে বলেননি" }).append('\n')
        messages.forEach { append("বার্তা: ").append(it).append('\n') }
        append("সময়: ").append(seconds / 60).append(" মিনিট ").append(seconds % 60).append(" সেকেন্ড\n")
        append("\nকথোপকথন:\n"); transcript.forEach { append(if (it.who == "caller") "কলার: " else "$owner-এর সহকারী: ").append(it.text).append('\n') }
    }

    fun smsToOwner(): String = (if (urgent) "জরুরি কল: " else "কল: ") + (caller.ifBlank { number }) + " — " + purpose.ifBlank { "বার্তা রেখেছেন" }.take(120)
}

/**
 * Answers a phone call for the owner. The caller's words arrive as text (from speech recognition),
 * the replies go back as text (to the voice). The usual flow — greet, name, purpose, anything else,
 * goodbye — is handled by rules so it is instant; a question the rules cannot answer goes to the model.
 */
class CallSession(val owner: String, val number: String = "", knownName: String = "", private val now: () -> Long = { System.currentTimeMillis() }) {
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

    private val goodbye = listOf("ধন্যবাদ", "রাখছি", "রাখি", "বাই", "আল্লাহ হাফেজ", "খোদা হাফেজ", "bye", "thank you", "thanks", "আর কিছু না", "আর কিছু নাই", "আর কিছু নেই", "না, আর", "that's all", "no that's it")
    private val urgentWords = listOf("জরুরি", "জরুরী", "ইমার্জেন্সি", "emergency", "urgent", "হাসপাতাল", "দুর্ঘটনা", "এক্সিডেন্ট", "accident", "সিরিয়াস", "মারা", "অসুস্থ")
    private val spamWords = listOf("লোন", "loan", "অফার", "offer", "congratulations", "অভিনন্দন", "আপনি জিতেছেন", "lottery", "লটারি", "প্যাকেজ", "package", "বীমা", "insurance", "প্রমোশন", "promotion", "ডিসকাউন্ট", "discount", "ক্রেডিট কার্ড", "credit card", "বিনিয়োগ")
    private val questionMarks = listOf("কখন", "কোথায়", "কবে", "কেন", "কি আছে", "আছে কি", "কেমন", "কত", "when", "where", "is he", "is she", "will he", "will she", "কি করছে", "ফোন ধরছে না", "?")

    fun transcript(): List<Line> = lines.toList()

    private fun say(text: String): Reply { lines += Line("agent", text, now()); return Reply(say = text) }

    fun greeting(): String {
        val g = if (callerName.isNotBlank()) "আসসালামু আলাইকুম ${callerName}। আমি $owner-এর সহকারী। $owner এখন কথা বলতে পারছেন না। আপনি কী বলতে চান?"
        else "আসসালামু আলাইকুম। আমি $owner-এর সহকারী। $owner এখন কথা বলতে পারছেন না। আপনার নামটা বলবেন?"
        stage = if (callerName.isNotBlank()) Stage.ASK_PURPOSE else Stage.ASK_NAME
        lines += Line("agent", g, now()); return g
    }

    fun extractName(text: String): String {
        val t = text.trim().trimEnd('।', '.', '!', '?')
        val pats = listOf(
            Regex("^(?:আমি|আমার নাম(?: হলো)?|নাম)\\s+([\\p{L}\\p{M}.]{2,20})\\s*[,،।]"),
            Regex("(?i)^(?:i am|i'm|this is|my name is)\\s+([A-Za-z.]{2,20})\\s*[,.]"),
            Regex("(?:আমি|আমার নাম|নাম)\\s+([\\p{L}\\p{M} .]{2,30}?)(?:\\s+(?:বলছি|বলতেছি|বলতেছি|হই|থেকে|ভাই|আপা))?$"),
            Regex("(?i)(?:my name is|this is|i am|i'm)\\s+([A-Za-z .]{2,30})$"),
            Regex("^([\\p{L}\\p{M} .]{2,24}?)\\s+(?:বলছি|বলতেছি|বলছিলাম)$")
        )
        for (p in pats) p.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.split(' ').size <= 4 }?.let { return it }
        // a bare name: one to three words and no verbs of asking
        val w = t.split(Regex("\\s+")); if (w.size in 1..3 && w.none { it in questionMarks || goodbye.any { g -> it.contains(g) } } && t.length in 2..28 && t.none { it.isDigit() }) return t
        return ""
    }

    fun isGoodbye(text: String): Boolean { val x = Autopilot.norm(text); return goodbye.any { x.contains(it) } && x.length < 60 }

    /** The caller said something. */
    fun onCaller(text: String): Reply {
        val raw = text.trim(); if (raw.isEmpty()) return onSilence()
        silence = 0; turns++; lines += Line("caller", raw, now())
        val x = Autopilot.norm(raw)
        if (urgentWords.any { x.contains(it) }) urgent = true
        if (stage == Stage.ENDED) return Reply(hangUp = true)
        if (turns > 14) return finish("আমি আপনার বার্তাটা পৌঁছে দিচ্ছি। ধন্যবাদ।")
        if (spamWords.count { x.contains(it) } >= 1 && stage != Stage.ASK_NAME) { spam = true; if (purpose.isEmpty()) purpose = raw.take(140); return finish("ধন্যবাদ, আমরা এখন আগ্রহী নই। আল্লাহ হাফেজ।") }
        return when (stage) {
            Stage.GREET, Stage.ASK_NAME -> {
                nameTries++
                val n = extractName(raw)
                if (n.isNotBlank()) {
                    callerName = n
                    // the name and the reason often come in one breath: "আমি রহিম, কাল মিটিংয়ের ব্যাপারে"
                    val rest = raw.substringAfter(n, "").trim(' ', ',', '।', '.')
                    if (rest.length > 12) { purpose = rest.take(200); stage = Stage.ASK_MORE; say("ধন্যবাদ $callerName। আমি $owner-কে জানিয়ে দেব। আর কিছু বলার আছে?") }
                    else if (purpose.isNotEmpty()) { stage = Stage.ASK_MORE; say("ধন্যবাদ $callerName। আমি $owner-কে জানিয়ে দেব। আর কিছু বলার আছে?") }
                    else { stage = Stage.ASK_PURPOSE; say("ধন্যবাদ $callerName। আপনি কী বলতে চান?") }
                } else if (nameTries >= 2) {
                    // no name after asking: take what was said as the reason and go on
                    if (purpose.isEmpty()) purpose = raw.take(200); stage = Stage.ASK_MORE
                    say("ঠিক আছে, বার্তাটা জানিয়ে দেব। আর কিছু বলার আছে?")
                } else {
                    // the caller began with the reason: keep it, then ask for the name once
                    purpose = raw.take(200); stage = Stage.ASK_NAME
                    say("বুঝেছি। আপনার নামটা একবার বলবেন?")
                }
            }
            Stage.ASK_PURPOSE -> {
                if (isGoodbye(raw)) return finish("ঠিক আছে, ধন্যবাদ। আল্লাহ হাফেজ।")
                purpose = raw.take(200); stage = Stage.ASK_MORE
                if (questionMarks.any { x.contains(it) }) Reply(say = "", model = true).also { stage = Stage.ASK_MORE }
                else say("ধন্যবাদ। আমি $owner-কে বার্তাটা জানিয়ে দেব। আর কিছু বলার আছে?")
            }
            Stage.ASK_MORE -> {
                if (isGoodbye(raw) || x == "না" || x.startsWith("না ") || x == "no") return finish("ঠিক আছে, আমি বার্তাটা পৌঁছে দেব। ধন্যবাদ, আল্লাহ হাফেজ।")
                if (questionMarks.any { x.contains(it) } && raw.length > 6) return Reply(model = true)
                extra += raw.take(200); say("আচ্ছা, লিখে নিলাম। আর কিছু?")
            }
            Stage.CLOSING -> finish("ধন্যবাদ, আল্লাহ হাফেজ।")
            Stage.ENDED -> Reply(hangUp = true)
        }
    }

    /** The model's answer to a free question; it is spoken and the call goes on. */
    fun modelSaid(text: String): Reply { val t = text.trim(); if (t.isEmpty()) return Reply(); stage = Stage.ASK_MORE; return say(t.take(300)) }

    /** The caller said nothing for a while. */
    fun onSilence(): Reply {
        silence++
        return if (silence == 1) say("হ্যালো, আপনি কি আছেন?") else if (silence >= 2) finish("লাইনে আপনাকে পাচ্ছি না। পরে আবার কল করবেন। ধন্যবাদ।") else Reply()
    }

    private fun finish(text: String): Reply { stage = Stage.ENDED; lines += Line("agent", text, now()); return Reply(say = text, hangUp = true) }

    fun summary(): CallSummary = CallSummary(number, callerName, purpose, urgent, spam, ((now() - started) / 1000).toInt().coerceAtLeast(0), extra.toList(), transcript())

    /** The instructions for the model when it must answer a question during the call. */
    fun modelPrompt(question: String): String = buildString {
        append("তুমি $owner-এর ফোন-সহকারী; এখন একটা কলে কলারের সাথে কথা বলছ।\n")
        append("নিয়ম: ১) শুধু বাংলায়, সর্বোচ্চ দুটি ছোট বাক্যে। ২) $owner-এর ব্যক্তিগত তথ্য (ঠিকানা, টাকা, অবস্থান, কর্মসূচি) কখনো বলবে না। ৩) কিছু কথা দেবে না; বলবে \"আমি জানিয়ে দেব\"। ৪) নতুন কিছু জানতে চাইলে বার্তা রেখে যেতে বলো।\n")
        if (callerName.isNotBlank()) append("কলার: $callerName\n")
        append("এ পর্যন্ত কথা:\n"); lines.takeLast(8).forEach { append(if (it.who == "caller") "কলার: " else "তুমি: ").append(it.text).append('\n') }
        append("কলার এখন বলল: ").append(question).append("\nএকটি সংক্ষিপ্ত উত্তর দাও (শুধু উত্তরটুকু):")
    }
}
