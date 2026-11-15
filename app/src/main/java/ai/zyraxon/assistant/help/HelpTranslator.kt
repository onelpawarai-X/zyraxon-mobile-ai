package ai.zyraxon.assistant.help

/** Translates one English chapter into another language with the model the user chose. */
object HelpTranslator {

    private fun prompt(languageName: String, english: String): String =
        "Translate the documentation chapter below into $languageName.\n" +
            "Rules:\n" +
            "- Keep the exact structure: the first line is the title; a line starting with \"## \" is a heading; a line starting with \"- \" is a bullet; a blank line separates paragraphs.\n" +
            "- Keep everything inside backticks exactly as it is (tool and setting names such as `open_app`), and keep numbers, product names and the arrow \"→\".\n" +
            "- Translate menu and button names naturally for that language.\n" +
            "- Write natural, warm, clear $languageName. Do not add, remove or summarise anything.\n" +
            "- Reply with the translation only, no comments and no code fences.\n\n" +
            english

    /** Strips a fence a model sometimes adds, and rejects an answer that is plainly too short to be the whole chapter. */
    fun clean(answer: String, english: String): String? {
        var t = answer.trim()
        if (t.startsWith("```")) t = t.removePrefix("```").substringAfter('\n', t).removeSuffix("```").trim()
        val want = english.lines().count { it.isNotBlank() }
        val got = t.lines().count { it.isNotBlank() }
        return t.takeIf { it.isNotBlank() && got * 10 >= want * 6 }
    }

    suspend fun translate(languageName: String, english: String, ask: suspend (String) -> String): String? =
        clean(ask(prompt(languageName, english)), english)
}
