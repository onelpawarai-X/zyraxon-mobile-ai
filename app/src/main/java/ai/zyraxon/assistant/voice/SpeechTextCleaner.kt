package ai.zyraxon.assistant.voice

/**
 * Prepares model text for a speech engine. The engine reads markdown and emoji
 * aloud as words ("asterisk asterisk", "smiling face"), so both are removed
 * before the text is spoken. Pure Kotlin, no Android types, so it is unit tested.
 */
object SpeechTextCleaner {

    fun clean(text: String): String {
        var out = text
        // The engine injects a status line for every tool it runs ("⚙️ `type_text`
        // → ...") into the reply stream so the user can watch the work happen.
        // It is shown, never spoken: reading tool names aloud is noise.
        out = out.replace(Regex("(?m)^[ \\t]*\\u2699\\uFE0F?.*$"), " ")
        out = out.replace(Regex("```[\\s\\S]*?```"), " ")
        out = out.replace(Regex("`([^`]*)`"), "$1")
        out = out.replace(Regex("!?\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        out = out.replace(Regex("[#*_>~|]"), "")
        out = out.replace(Regex("(?m)^[ \\t]*[-\\u2022][ \\t]+"), "")
        out = out.replace(Regex("[\\u200B-\\u200D\\uFE0F]"), "")
        out = out.replace(Regex("[\\u2190-\\u21FF\\u2300-\\u27BF\\u2B00-\\u2BFF]"), "")
        out = out.replace(Regex("[\\uD83C\\uD83D\\uD83E][\\uDC00-\\uDFFF]"), "")
        out = out.replace(Regex("[\\u2600-\\u26FF\\u2700-\\u27BF]"), "")
        return out.replace(Regex("\\s+"), " ").trim()
    }
}
