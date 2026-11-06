package ai.zyraxon.assistant.voice

/**
 * Prepares model text for a speech engine. The engine reads markdown and emoji
 * aloud as words ("asterisk asterisk", "smiling face"), so both are removed
 * before the text is spoken. Pure Kotlin, no Android types, so it is unit tested.
 */
object SpeechTextCleaner {

    /**
     * A link, wherever it appears in a sentence. The trailing punctuation is
     * excluded so "see https://x.dev." drops the address and keeps the period.
     */
    private val URL = Regex(
        "(?i)\\b(?:https?://|www\\.)[^\\s<>\"']*[^\\s<>\"'.,;:!?)\\]}।॥]"
    )

    fun clean(text: String): String {
        var out = text
        // The engine injects a status line for every tool it runs ("`type_text`
 // ...") into the reply stream so the user can watch the work happen.
        // It is shown, never spoken: reading tool names aloud is noise.
        out = out.replace(Regex("(?m)^[ \\t]*\\u2699\\uFE0F?.*$"), " ")
        out = out.replace(Regex("```[\\s\\S]*?```"), " ")
        // Backticked spans are code — tool names (`open_app`, `run_shell`), file
        // names, flags. Unwrapping them made the speaker read the tool aloud, so
        // the whole span is dropped instead. Only prose is ever spoken.
        out = out.replace(Regex("`[^`]*`"), " ")
        out = out.replace(Regex("!?\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        // A raw URL is not speech. The engine spells it out letter by letter
        // ("h-t-t-p-s colon slash slash…"), which is minutes of noise in the
        // middle of an answer, so every link is dropped before the text is
        // spoken. The user still sees it: only this copy is cleaned.
        out = out.replace(URL, " ")
        out = out.replace(Regex("[#*_>~|]"), "")
        out = out.replace(Regex("(?m)^[ \\t]*[-\\u2022][ \\t]+"), "")
        out = out.replace(Regex("[\\u200B-\\u200D\\uFE0F]"), "")
        out = out.replace(Regex("[\\u2190-\\u21FF\\u2300-\\u27BF\\u2B00-\\u2BFF]"), "")
        out = out.replace(Regex("[\\uD83C\\uD83D\\uD83E][\\uDC00-\\uDFFF]"), "")
        out = out.replace(Regex("[\\u2600-\\u26FF\\u2700-\\u27BF]"), "")
        return out.replace(Regex("\\s+"), " ").trim()
    }
}
