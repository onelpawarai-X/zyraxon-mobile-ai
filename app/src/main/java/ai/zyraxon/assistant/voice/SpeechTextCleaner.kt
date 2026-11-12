// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Prepares model text for natural human-like speech.
 *
 * 1. Speaks URLs naturally: Instead of spelling out raw http/slash/dot,
 *    converts URLs into natural spoken phrases like "ইউটিউব লিঙ্ক", "গুগল ড্রাইভ লিঙ্ক",
 *    or "ওয়েবসাইট লিঙ্ক". Markdown links [Title](url) speak the natural title.
 * 2. Strips internal tool markers, gear emojis, and JSON payloads.
 * 3. Strips raw code blocks and technical file paths.
 * 4. Strips markdown symbols, asterisks, hashtags, and noisy emoji.
 */
object SpeechTextCleaner {

    private val PROTOCOL_URL = Regex("(?i)\\b(?:https?://|ftp://|www\\.)[^\\s<>\"']+")

    private val NAMED_SERVICES = listOf(
        Regex("(?i)\\bhttps?://(?:www\\.)?(?:m\\.)?youtube\\.com/\\S+|https?://youtu\\.be/\\S+") to "ইউটিউব লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?facebook\\.com/\\S+|https?://fb\\.watch/\\S+") to "ফেসবুক লিঙ্ক",
        Regex("(?i)\\bhttps?://drive\\.google\\.com/\\S+") to "গুগল ড্রাইভ লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?google\\.com/\\S+") to "গুগল লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?github\\.com/\\S+") to "গিটহাব লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?instagram\\.com/\\S+") to "ইনস্টাগ্রাম লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?(?:twitter\\.com|x\\.com)/\\S+") to "টুইটার লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?wikipedia\\.org/\\S+") to "উইকিপিডিয়া লিঙ্ক",
        Regex("(?i)\\bhttps?://(?:www\\.)?zyraxon[^\\s/]*\\S*") to "জাইরাক্সন লিঙ্ক"
    )

    private val FILE_PATH = Regex("(?:/[a-zA-Z0-9._-]+){2,}")

    fun clean(text: String): String {
        if (text.isBlank()) return ""
        var out = text

        // 1. Drop tool lines: lines starting with gear icon ⚙ or tool indicators
        out = out.replace(Regex("(?m)^[ \\t]*\\u2699\\uFE0F?.*$"), " ")
        out = out.replace(Regex("(?i)<tool_call>[\\s\\S]*?(?:</tool_call>|$)"), " ")
        out = out.replace(Regex("(?i)\\[tool:[^\\]]*\\]"), " ")

        // 2. Drop fenced code blocks (even unclosed at stream end)
        out = out.replace(Regex("```[\\s\\S]*?(?:```|$)"), " ")

        // 3. Drop inline code spans
        out = out.replace(Regex("`[^`]*`"), " ")
        out = out.replace(Regex("`.*$"), " ") // Unclosed trailing backtick

        // 4. Transform markdown images and links
        out = out.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        // Markdown link [Label](url): speak the label naturally
        out = out.replace(Regex("\\[([^\\]]+)\\]\\((?:https?://[^)]+)\\)")) { match ->
            val label = match.groupValues[1].trim()
            if (!label.startsWith("http", ignoreCase = true) && !label.startsWith("www.", ignoreCase = true)) {
                label
            } else {
                "ওয়েবসাইটের লিঙ্ক"
            }
        }

        // 5. Convert known service URLs into natural spoken names
        for ((pattern, spokenName) in NAMED_SERVICES) {
            out = out.replace(pattern, spokenName)
        }

        // 6. Generic remaining URLs: extract domain or speak natural "ওয়েবসাইটের লিঙ্ক"
        out = out.replace(PROTOCOL_URL) { match ->
            val url = match.value
            val domainMatch = Regex("(?i)https?://(?:www\\.)?([a-zA-Z0-9-]+)\\.").find(url)
            if (domainMatch != null) {
                "${domainMatch.groupValues[1]} ওয়েবসাইটের লিঙ্ক"
            } else {
                "ওয়েবসাইটের লিঙ্ক"
            }
        }

        // 7. Drop raw file paths
        out = out.replace(FILE_PATH, " ")

        // 8. Drop markdown formatting symbols
        out = out.replace(Regex("[#*_>~|\\\\{}\\[\\]^]"), " ")
        out = out.replace(Regex("(?m)^[ \\t]*[-\\u2022\\u2023\\u25E6][ \\t]+"), " ")

        // 9. Drop invisible characters and noisy emoji
        out = out.replace(Regex("[\\u200B-\\u200D\\uFE0F]"), "")
        out = out.replace(Regex("[\\u2190-\\u21FF\\u2300-\\u27BF\\u2B00-\\u2BFF]"), "")
        out = out.replace(Regex("[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]"), "")
        out = out.replace(Regex("[\\u2600-\\u26FF\\u2700-\\u27BF]"), "")

        // Normalize spaces and punctuation
        out = out.replace(Regex("[ \\t]+"), " ")
        out = out.replace(Regex(" +([।,.?!;])"), "$1")
        return out.trim()
    }
}
