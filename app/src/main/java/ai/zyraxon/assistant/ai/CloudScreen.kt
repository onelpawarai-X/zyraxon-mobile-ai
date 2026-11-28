// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.core.safe

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

    /**
     * The line every screen block opens with. It is a wire format: the block is
     * appended to the end of each message sent to the cloud, and [clean] finds it
     * again by this exact text when the conversation is read back.
     */
    const val MARK = "এখনকার স্ক্রিন (লাইভ, প্রতি ২ সেকেন্ডে হালনাগাদ):"

    /** The history seed the first message of a re-bound chat opens with. */
    const val SEED_OPEN = "[এই চ্যাটে আগে যা বলা হয়েছে"
    const val SEED_CLOSE = "[এখান থেকে আগের কাজ চালিয়ে যাও]"

    /** A phone tool's result is sent to the cloud as a user message; it is the app talking, not the person. */
    const val TOOL_RESULT_PREFIX = "[phone tool result]"

    /**
     * What the person actually typed, out of a user message read back from the cloud.
     *
     * The app sends the agent more than the person wrote: the live screen digest
     * (every label, coordinate and OCR line), a seed of earlier turns, and the
     * result of each phone tool as its own user message. All of that is for the
     * model. Reading the conversation back showed it as the person's own words —
     * a screenful of coordinates under every message. This keeps only the typed
     * text, and returns "" for a message that is purely the app's own.
     */
    fun clean(text: String): String {
        var t = text
        val at = t.indexOf(MARK)
        if (at >= 0) t = t.substring(0, at)
        val lead = t.trimStart()
        if (lead.startsWith(SEED_OPEN)) {
            val close = lead.indexOf(SEED_CLOSE)
            t = if (close >= 0) lead.substring(close + SEED_CLOSE.length) else ""
        }
        t = t.trim()
        if (t.startsWith(TOOL_RESULT_PREFIX)) return ""
        return t
    }

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
        var bodyCount = 0
        var truncated = false
        val arr = runCatching { JSONArray(nodesJson) }.getOrNull()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (bodyCount >= maxLines) { truncated = true; break }
                val o = arr.optJSONObject(i) ?: continue
                val text = o.safe("text").trim()
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
                bodyCount++
            }
        }

        if (bodyCount <= 0) return ""
        // The agent is told the list was cut, so it knows there is more on the
        // screen than it was shown rather than assuming it saw everything.
        if (truncated) lines.add("- … (আরও আছে)")

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
        return "\n\n$MARK\n$body"
    }
}
