package ai.zyraxon.assistant.ai

/**
 * Decides whether the model actually finished, or only stopped talking.
 *
 * The user's core complaint: after one step the assistant says "পারলাম না",
 * "লিখছি", or "খুলেছি" and ends the turn — one step short of the goal. A
 * system-prompt rule cannot fix that, because the turn is ended by the *engine*
 * the moment the model returns text with no tool call. This policy is what the
 * engine consults before it ends the turn: when the reply is a failure, a bare
 * promise, or a narration of an *intermediate* state ("YouTube খোলা হয়েছে"), the
 * turn is not over and the model is pushed to take the next step itself.
 *
 * v4 changes (the reasons the earlier version still stopped early):
 *  - "খোলা হয়েছে", "opened", "লাইভ", "শেষ" used to count as DONE. They describe
 *    a step on the way ("YouTube is open"), not the goal ("the video plays"),
 *    so a reply like "ইউটিউব খোলা হয়েছে" ended the turn before the video was
 *    ever started. Only words that name a finished *goal* count now.
 *  - A promise is checked before a completion, so "ফাইল লেখা হয়েছে, এখন সাইট
 *    বানাচ্ছি" is read as unfinished.
 *  - A real human-only blocker (OTP, captcha, password, a permission only the
 *    user can grant) ends the turn instead of being nudged into a loop.
 *  - The nudge now repeats the user's original goal, so the model is pulled back
 *    to the whole job rather than the last small step.
 *
 * Pure, so the rule is testable without a model, a network or a phone.
 */
object ContinuationPolicy {

    /**
     * The assistant reads OTPs, taps checkboxes and grants permissions itself, so
     * those never end a turn. The one real stop is a secret that exists nowhere on
     * the phone and was never given — and the assistant has to ask for it.
     */
    private val NEEDS_HUMAN = listOf(
        "তোমার পাসওয়ার্ডটা দাও", "পাসওয়ার্ডটা বলো", "পাসওয়ার্ড দিন",
        "please enter your password", "what is your password"
    )

    /** A failure report. Never an ending — it is the signal to try another way. */
    private val FAILURE = listOf(
        "পারলাম না", "পারি না", "পারব না", "পারিনি", "পারি নি",
        "যায়নি", "হয়নি", "হয় নাই", "ব্যর্থ", "সম্ভব না", "সম্ভব নয়",
        "লেখা যায়নি", "খোলা যায়নি", "খুলতে পারিনি", "পাইনি", "পাই নি",
        "could not", "can't", "cannot", "failed", "unable", "won't work"
    )

    /** A promise of future action, with nothing done yet. */
    private val PROMISE = listOf(
        "করছি", "যাচ্ছি", "দিচ্ছি", "লিখছি", "খুলছি", "আনছি", "খুঁজছি",
        "বানাচ্ছি", "চালাচ্ছি", "পাঠাচ্ছি", "দেখছি", "বসাচ্ছি", "নিচ্ছি",
        "চেষ্টা করছি", "একটু পর", "এখনই", "শুরু করছি",
        "i will", "i'll", "let me", "working on", "starting"
    )

    /** A step on the way. The goal the user named has not happened yet. */
    private val INTERMEDIATE = listOf(
        "খুলেছি", "খোলা হয়েছে", "খুলে গেছে", "খুলে দিয়েছি", "সার্চ করেছি",
        "সার্চ করে দিলাম", "সার্চ রেজাল্ট",
        "opened", "search results", "is open", "has opened", "navigated"
    )

    /** A real completion: the goal the user named has actually happened. */
    private val GOAL_DONE = listOf(
        "হয়ে গেছে", "হয়ে গেল", "সম্পন্ন", "বাজছে", "বাজতেছে", "প্লে হচ্ছে",
        "তৈরি হয়েছে", "বানানো হয়েছে", "পাবলিশ হয়েছে", "ডিপ্লয় হয়েছে",
        "পাঠানো হয়েছে", "পাঠিয়ে দিয়েছি", "সব ঠিক আছে",
        "all done", "task complete", "completed", "playing",
        "published", "deployed", "successfully"
    )

    /**
     * True when the turn must not end here.
     *
     * @param reply the model's own final text for this round.
     * @param lastActionWasScreenChange the previous round moved the phone to a new
     *   screen (opened an app, followed a link, played on YouTube), so a silent
     *   stop is almost always "one step short".
     * @param nudgesUsed how many times in a row the model has been pushed on
     *   *without making progress*. The engine resets it whenever the model makes a
     *   real tool call, so a long job is not starved of nudges after the first few.
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

        // Only the user can unblock this; pushing the model would loop.
        if (NEEDS_HUMAN.any { r.contains(it) }) return false

        // A failure or a bare promise is never an ending.
        if (FAILURE.any { r.contains(it) }) return true
        if (PROMISE.any { r.contains(it) }) return true

        // The goal itself, not a step towards it.
        if (GOAL_DONE.any { r.contains(it) }) return false

        // A step on the way ("YouTube খোলা হয়েছে") is not the end.
        if (INTERMEDIATE.any { r.contains(it) }) return true

        // A screen just changed and the model stopped without confirming the goal.
        // This is the YouTube case: results came back, the model said so and
        // stopped one tap short of pressing play.
        if (lastActionWasScreenChange) return true

        return false
    }

    /** The nudge handed back to the model when the turn is not allowed to end. */
    fun nudge(reply: String, goal: String = ""): String {
        val r = reply.lowercase()
        val failed = FAILURE.any { r.contains(it) }
        val anchor = goal.trim().takeIf { it.isNotEmpty() }
            ?.let { "\nব্যবহারকারীর মূল লক্ষ্য: «${it.take(400)}»\nএই পুরো লক্ষ্যটা পূরণ না হওয়া পর্যন্ত থামবে না।" }
            ?: ""
        return if (failed) {
            "⚠️ তুমি এইমাত্র ব্যর্থতার কথা বলেছ, কিন্তু কাজ এখনো শেষ হয়নি। " +
                "ব্যর্থতা থামার কারণ নয় — ভিন্ন উপায়ে আবার চেষ্টা করো (অন্য টুল, অন্য আর্গুমেন্ট, " +
                "অন্য পথ)। এখনই পরের ধাপের টুল কল করো, ব্যবহারকারীকে কিছু জিজ্ঞাসা কোরো না।" + anchor
        } else {
            "⚠️ কাজ এখনো শেষ হয়নি। তুমি একটা মাঝের ধাপের কথা বলেছ, লক্ষ্য পূরণের কথা নয়। " +
                "পরের ধাপ নিজেই বেছে নাও এবং এখনই টুল কল করো। ব্যবহারকারীকে জিজ্ঞাসা কোরো না, " +
                "শুধু কাজ চালিয়ে যাও। লক্ষ্য সত্যিই পূরণ হয়েছে বলে নিশ্চিত হলে তবেই থামো।" + anchor
        }
    }
}
