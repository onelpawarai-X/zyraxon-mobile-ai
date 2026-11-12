package ai.zyraxon.assistant.design

/**
 * Wraps the poster / banner / logo markup an agent writes into one page of an
 * exact pixel size, so what is drawn to PNG or PDF is the same size every time.
 */
object DesignHtml {

    data class Size(val w: Int, val h: Int)

    /** Default sizes (pixels) for each kind of design. */
    fun defaultSize(kind: String): Size = when (kind.lowercase()) {
        "poster" -> Size(1080, 1528)
        "banner" -> Size(1500, 500)
        "logo" -> Size(1024, 1024)
        "card", "social", "post" -> Size(1080, 1080)
        "story" -> Size(1080, 1920)
        "cover" -> Size(1640, 924)
        else -> Size(1080, 1080)
    }

    private fun looksLikeSvg(s: String) = s.trimStart().startsWith("<svg", ignoreCase = true) ||
        (s.trimStart().startsWith("<?xml", ignoreCase = true) && s.contains("<svg", ignoreCase = true))

    /** A complete HTML document of exactly [w] x [h] CSS pixels. */
    fun wrap(markup: String, w: Int, h: Int, transparent: Boolean): String {
        val body = markup.trim()
        val svg = looksLikeSvg(body)
        val css = "html,body{margin:0;padding:0;width:${w}px;height:${h}px;overflow:hidden;" +
            (if (transparent) "background:transparent;" else "background:#ffffff;") +
            "font-family:'Noto Sans Bengali','Hind Siliguri','Noto Sans','Roboto',sans-serif;" +
            "-webkit-font-smoothing:antialiased}*{box-sizing:border-box}" +
            (if (svg) "svg{display:block;width:${w}px;height:${h}px}" else "")
        // A full document the agent wrote is kept, with the size rules added to its head.
        if (!svg && Regex("<html", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            val inject = "<meta name=\"viewport\" content=\"width=$w\"><style>$css</style>"
            val at = body.indexOf("</head>", ignoreCase = true)
            return if (at >= 0) body.substring(0, at) + inject + body.substring(at)
            else Regex("<html[^>]*>", RegexOption.IGNORE_CASE).replace(body) { it.value + "<head>$inject</head>" }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=$w\"><style>$css</style></head><body>$body</body></html>"
    }
}
