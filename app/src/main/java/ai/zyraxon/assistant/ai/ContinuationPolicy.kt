package ai.zyraxon.assistant.ai

/**
 * Decides whether the model actually finished, or only stopped talking.
 *
 * The user's core complaint: after one step the assistant says "পারলাম না",
 * "লিখছি", or "খুলেছি" and ends the turn — one step short of the goal. A
 * system-prompt rule cannot fix that, because the turn is ended by the *engine*
 * the moment the model returns text with no tool call. This policy is what the
 * engine consults before it ends the turn: when the reply is a failure, a bare
 * promise, or a narration right after the screen changed, the turn is not over
 * and the model is pushed to take the next step itself.
 *
 * Pure, so the rule is testable without a model, a network or a phone.
 */
object ContinuationPolicy {

    /** A failure report. Never an ending — it is the signal to try another way. */
    private val FAILURE = listOf(
        "পারলাম না", "পারি না", "পারব না", "পারিনি", "পারি নি",
        "যায়নি", "হয়নি", "হয় নাই", "ব্যর্থ", "সম্ভব না", "সম্ভব নয়",
        "লেখা যায়নি", "খোলা যায়নি", "খুলতে পারিনি", "পাইনি", "পাই নি",
        "could not", "can't", "cannot", "failed", "unable", "won't work"
    )

    /** A promise of future action, with nothing done yet. */
    private val PROMISE = listOf(
        "করছি", "যাচ্ছি", "দিচ্ছি", "লিখছি", "খুলছি", "আনছি",
        "চেষ্টা করছি", "একটু পর", "এখনই", "শুরু করছি",
        "i will", "i'll", "let me", "working on", "starting"
    )

    /** A real completion: the goal the user named has actually happened. */
    private val DONE = listOf(
        "হয়ে গেছে", "হয় গেছে", "শেষ", "বাজছে", "বাজতেছে", "প্লে হচ্ছে",
        "খোলা হয়েছে", "খুলে গেছে", "সম্পন্ন", "ডান", "তৈরি হয়েছে",
        "পাবলিশ হয়েছে", "লাইভ", "done", "playing", "opened", "published", "live"
    )

    /**
     * True when the turn must not end here.
     *
     * @param reply the model's own final text for this round.
     * @param lastActionWasScreenChange the previous round moved the phone to a new
     *   screen (opened an app, followed a link, played on YouTube), so a silent
     *   stop is almost always "one step short".
     * @param nudgesUsed how many times this turn has already been pushed on.
     */
    fun shouldContinue(
        reply: String,
        lastActionWasScreenChange: Boolean,
        nudgesUsed: Int,
        maxNudges: Int = 4
    ): Boolean {
        if (nudgesUsed >= maxNudges) return false
        val r = reply.lowercase().trim()
        if (r.isEmpty()) return false

        // A failure or a bare promise is never an ending.
        if (FAILURE.any { r.contains(it) }) return true
        if (DONE.any { r.contains(it) }) return false
        if (PROMISE.any { r.contains(it) }) return true

        // A screen just changed and the model stopped without confirming the goal.
        // This is the YouTube case: results came back, the model said "খুলেছি" and
        // stopped one tap short of pressing play.
        if (lastActionWasScreenChange) return true

        return false
    }

    /** The nudge handed back to the model when the turn is not allowed to end. */
    fun nudge(reply: String): String {
        val r = reply.lowercase()
        val failed = FAILURE.any { r.contains(it) }
        return if (failed) {
            "⚠️ তুমি এইমাত্র ব্যর্থতার কথা বলেছ, কিন্তু কাজ এখনো শেষ হয়নি। " +
                "ব্যর্থতা থামার কারণ নয় — ভিন্ন উপায়ে আবার চেষ্টা করো (অন্য টুল, অন্য আর্গুমেন্ট, " +
                "অন্য পথ)। এখনই পরের ধাপের টুল কল করো, ব্যবহারকারীকে কিছু জিজ্ঞাসা কোরো না।"
        } else {
            "⚠️ কাজ এখনো শেষ হয়নি। স্ক্রিন বদলেছে — এখন পরের ধাপ নিজেই বেছে নাও এবং " +
                "এখনই টুল কল করো। ব্যবহারকারীকে জিজ্ঞাসা কোরো না, শুধু কাজ চালিয়ে যাও। " +
                "লক্ষ্য পূরণ হয়েছে বলে নিশ্চিত হলে তবেই থামো।"
        }
    }
}
