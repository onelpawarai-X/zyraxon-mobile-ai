package ai.zyraxon.assistant.world

/** Currency detection, number reading and formatting that work for any country's messages. */
object Money {
    /** Any decimal digit of any script (Bangla, Arabic-Indic, Devanagari, Thai…) → 0-9. */
    fun ascii(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) { if (c.isDigit() && c !in '0'..'9') { val d = Character.digit(c, 10); sb.append(if (d >= 0) ('0' + d) else c) } else sb.append(c) }
        return sb.toString().replace('٫', '.').replace('٬', ',')
    }

    private val symbols = mapOf(
        "$" to "USD", "US$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR", "Rs" to "INR", "Rs." to "INR", "₨" to "PKR", "৳" to "BDT", "Tk" to "BDT", "Tk." to "BDT",
        "₱" to "PHP", "₦" to "NGN", "₩" to "KRW", "₺" to "TRY", "₽" to "RUB", "₫" to "VND", "฿" to "THB", "R$" to "BRL", "Rp" to "IDR", "RM" to "MYR", "zł" to "PLN",
        "Kč" to "CZK", "Ft" to "HUF", "kr" to "SEK", "CHF" to "CHF", "KSh" to "KES", "GH₵" to "GHS", "₪" to "ILS", "د.إ" to "AED", "ر.س" to "SAR", "元" to "CNY", "円" to "JPY", "원" to "KRW"
    )
    private val words = mapOf(
        "taka" to "BDT", "টাকা" to "BDT", "rupees" to "INR", "rupee" to "INR", "रुपये" to "INR", "रुपए" to "INR", "dollars" to "USD", "dollar" to "USD", "euros" to "EUR", "euro" to "EUR",
        "pesos" to "MXN", "reais" to "BRL", "rupiah" to "IDR", "dirham" to "AED", "riyal" to "SAR", "ringgit" to "MYR", "baht" to "THB", "shillings" to "KES", "naira" to "NGN"
    )
    private val codes = ("USD EUR GBP INR BDT PKR NPR LKR AED SAR QAR KWD OMR BHD EGP TRY NGN KES GHS ZAR UGX TZS BRL MXN ARS CLP COP PEN IDR MYR SGD THB PHP VND JPY CNY KRW HKD TWD AUD NZD CAD CHF SEK NOK DKK PLN CZK HUF RON RUB UAH ILS IRR MAD DZD TND XOF XAF RWF ETB").split(" ")

    private val symAlt = symbols.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val codeAlt = codes.joinToString("|")
    private val wordAlt = words.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private const val NUM = "\\d{1,2}(?:,\\d{2})+,\\d{3}(?:\\.\\d{1,2})?|\\d{1,3}(?:[ ,.\\u00a0\\u202f]\\d{3})*(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"
    private val before = Regex("(?i)(?<![\\p{L}])($codeAlt|$symAlt)\\s*[:\\-]?\\s*($NUM)")
    private val after = Regex("(?i)($NUM)\\s*($codeAlt|$wordAlt|$symAlt)(?![A-Za-z])")

    fun codeOf(token: String): String {
        val t = token.trim()
        return if (t.uppercase() in codes) t.uppercase() else symbols[t] ?: symbols.entries.firstOrNull { it.key.equals(t, true) }?.value ?: words[t.lowercase()] ?: words[t] ?: ""
    }

    /** "1,234.56", "1.234,56", "1 234,56", "1,50,000" → number. */
    fun number(raw: String): Double {
        var s = raw.trim().replace(' ', ' ').replace(' ', ' ')
        if (s.isEmpty()) return 0.0
        val lastDot = s.lastIndexOf('.'); val lastComma = s.lastIndexOf(',')
        s = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) s.replace(",", "").replace(" ", "") else s.replace(".", "").replace(" ", "").replace(',', '.')
            lastComma >= 0 -> if (Regex("^\\d{1,3}(,\\d{3})+$").matches(s.replace(" ", ""))) s.replace(",", "").replace(" ", "")
                else if (Regex("^\\d+,\\d{1,2}$").matches(s.replace(" ", ""))) s.replace(" ", "").replace(',', '.') else s.replace(",", "").replace(" ", "")
            lastDot >= 0 -> if (Regex("^\\d{1,3}(\\.\\d{3})+$").matches(s)) s.replace(".", "") else s.replace(" ", "")
            else -> s.replace(" ", "")
        }
        return s.toDoubleOrNull() ?: 0.0
    }

    data class Found(val amount: Double, val currency: String, val start: Int, val end: Int)

    /** Every money figure in the text, in order, with its currency when one is written next to it. */
    fun find(textRaw: String): List<Found> {
        val text = ascii(textRaw); val out = ArrayList<Found>()
        for (m in before.findAll(text)) out += Found(number(m.groupValues[2]), codeOf(m.groupValues[1]), m.range.first, m.range.last + 1)
        for (m in after.findAll(text)) if (out.none { it.start < m.range.last + 1 && m.range.first < it.end }) out += Found(number(m.groupValues[1]), codeOf(m.groupValues[2]), m.range.first, m.range.last + 1)
        return out.filter { it.amount > 0 }.sortedBy { it.start }
    }

    fun symbol(code: String) = when (code) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "INR" -> "₹"; "BDT" -> "৳"; "JPY" -> "¥"; "PHP" -> "₱"; "NGN" -> "₦"; "KRW" -> "₩"; "TRY" -> "₺"; "RUB" -> "₽"; "BRL" -> "R$"; "" -> ""; else -> "$code " }

    fun format(v: Double, code: String): String {
        val neg = v < 0; val a = Math.abs(v); val whole = a.toLong(); val frac = Math.round((a - whole) * 100).toInt()
        val g = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + symbol(code) + g + if (frac > 0) ".%02d".format(frac) else ""
    }
}
