package ai.zyraxon.assistant.ai

import org.json.JSONArray

/**
 * The phone's screen as text a model that cannot see can still read.
 *
 * The cloud agent runs a model that has no eyes — measured live, the default is
 * a DeepSeek text model — so a screenshot sent to it is a large blob it cannot
 * interpret. What it *can* read is the accessibility tree: every visible element
 * with its own label and the centre point of its bounds. That is strictly better
 * than OCR for driving the phone, because the same coordinates that name a
 * button also tap it, and the labels are produced by Android itself, so Bangla
 * text arrives as Bangla with no recognition step.
 *
 * OCR is still layered on top for the one thing the tree cannot see — words
 * drawn inside a picture, a video or a canvas — but this digest stands alone
 * without it.
 */
object CloudScreen {

    /** Longest digest sent in one message. A screen longer than this is cut. */
    const val DEFAULT_MAX_CHARS = 1800

    /** Most elements listed. A deep list is truncated rather than dropped whole. */
    const val DEFAULT_MAX_LINES = 60

    /**
     * Builds the digest.
     *
     * @param nodesJson the array [readScreen] returns: `{text, x, y, click?, editable?}`.
     * @return the digest, or "" when there is nothing worth sending — a blank
     *   screen must not add an empty block to every message.
     */
    fun digest(
        foregroundLabel: String,
        foregroundPackage: String,
        nodesJson: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxLines: Int = DEFAULT_MAX_LINES
    ): String {
        val lines = mutableListOf<String>()
        val where = when {
            foregroundLabel.isNotBlank() && foregroundPackage.isNotBlank() ->
                "অ্যাপ: $foregroundLabel ($foregroundPackage)"
            foregroundLabel.isNotBlank() -> "অ্যাপ: $foregroundLabel"
            foregroundPackage.isNotBlank() -> "অ্যাপ: $foregroundPackage"
            else -> ""
        }
        if (where.isNotEmpty()) lines.add(where)

        val seen = HashSet<String>()
        val arr = runCatching { JSONArray(nodesJson) }.getOrNull()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (lines.size >= maxLines + 1) break
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                // The same label repeats all over a list (a row of "Songs", a
                // column of "Follow"); one entry per distinct label keeps the
                // digest readable and stops a long list eating the whole budget.
                if (!seen.add(text.lowercase())) continue
                val flags = buildString {
                    if (o.optBoolean("click")) append("tap")
                    if (o.optBoolean("editable")) {
                        if (isNotEmpty()) append(' ')
                        append("editable")
                    }
                }
                val suffix = if (flags.isEmpty()) "" else " [$flags]"
                lines.add("- \"$text\" @(${o.optInt("x")},${o.optInt("y")})$suffix")
            }
        }

        val bodyLines = if (where.isNotEmpty()) lines.size - 1 else lines.size
        if (bodyLines <= 0) return ""

        var out = lines.joinToString("\n")
        if (out.length > maxChars) {
            out = out.take(maxChars).substringBeforeLast('\n') + "\n…"
        }
        return out
    }

    /** The digest inside the block the agent is sent, or "" when there is none. */
    fun block(
        foregroundLabel: String,
        foregroundPackage: String,
        nodesJson: String,
        ocrText: String = ""
    ): String {
        val body = buildString {
            append(digest(foregroundLabel, foregroundPackage, nodesJson))
            val ocr = ocrText.trim()
            if (ocr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                // Marked apart on purpose: these words were read out of a picture,
                // so the agent knows they have no tap coordinate behind them.
                append("ছবির ভিতরের লেখা (OCR, ট্যাপ কোঅর্ডিনেট নেই):\n").append(ocr)
            }
        }
        if (body.isBlank()) return ""
        return "\n\n📱 এখনকার স্ক্রিন (লাইভ, প্রতি ২ সেকেন্ডে হালনাগাদ):\n$body"
    }
}
