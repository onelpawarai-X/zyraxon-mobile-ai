package ai.zyraxon.assistant.world

import java.util.Calendar
import java.util.Currency
import java.util.Locale
import java.util.TimeZone

/** Reads wallet and bank messages from any country, in many languages, into [Txn]s. */
object SmsParser {
    private val feeWords = "fee|fees|charge|charges|commission|comisión|comision|frais|gebühr|gebuehr|taxa|tarifa|tasse|commissione|ফি|চার্জ|رسوم|عمولة|शुल्क|biaya|ücret|комиссия|手续费|手数料|수수료|opłata|kosten"
    private val balWords = "avl\\.?\\s*bal(?:ance)?|available(?:\\s+balance)?|bal(?:ance)?|saldo|solde|kontostand|bakiye|bakiyeniz|ব্যালেন্স|رصيد|बैलेंस|शेष|остаток|баланс|余额|余額|残高|잔액|saldo\\s+disponible|saldo\\s+atual|sisa saldo"
    private val feeLabel = Regex("(?i)(?<![\\p{L}])(?:$feeWords)(?:\\s+(?:is|of|was))?\\W{0,12}$")
    private val balLabel = Regex("(?i)(?<![\\p{L}])(?:$balWords)(?:\\s+(?:is|of|was))?\\W{0,12}$")
    private val idLabel = Regex("(?i)(?:trx\\s*id|txn\\s*id|trxid|txnid|transaction\\s*(?:id|no\\.?|number)|ref(?:erence)?(?:\\s*(?:no\\.?|number|id))?|utr|rrn|confirmation)\\s*[:#\\-]?\\s*([A-Z0-9]{6,})")
    private val phone = Regex("(?<!\\d)(?<!\\d[.,])(\\+\\d{8,14}|0\\d{9,11})(?!\\d)(?![.,]\\d)")
    private val partyRx = Regex("(?:(?i:to|from|at|a|de|para|von|an|à|aan|kepada|dari|ke|для|от|en|em|bei|chez|di|da)\\s+)(\\p{Lu}[\\p{L}\\p{M} .&'\\-]{2,28}?)(?=[.,;:]|\\s+\\d|\\s+(?i:successful|on|at|trx|txn|fee|balance|ref|via|with|using|el|le|am|em|pada|на|erfolgreich|exitoso|exitosa|réussi|berhasil|başarılı|успешно|bem-sucedido|successfully)\\b|$)")

    fun localCurrency(): String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("")

    fun provider(sender: String, body: String) = Providers.detect(sender, body)

    fun parse(sender: String, bodyRaw: String, smsTimeMs: Long = 0, tz: TimeZone = TimeZone.getDefault(), defaultCurrency: String = localCurrency()): Txn? {
        val body = Money.ascii(bodyRaw).replace(' ', ' ')
        val low = body.lowercase()
        val figures = Money.find(body)
        if (figures.isEmpty()) return null
        // one-time codes are not money movement
        if (Providers.otpOnly.containsMatchIn(low) && figures.all { it.currency.isEmpty() }) return null
        val prov = Providers.detect(sender, body); if (prov.isEmpty()) return null

        val outIdx = Providers.first(low, Providers.outWords)
        val inIdx = minOf(Providers.first(low, Providers.inWords), Providers.inPhrases.find(low)?.range?.first ?: Int.MAX_VALUE)
        if (outIdx == Int.MAX_VALUE && inIdx == Int.MAX_VALUE) return null
        // the earliest keyword decides: "Cash Out … received" is money out
        val dir = if (inIdx <= outIdx) Dir.IN else Dir.OUT

        var fee = 0.0; var balance = 0.0; var amount = 0.0; var cur = ""; var feeCur = ""; var balCur = ""
        for (f in figures) {
            val before = body.substring(maxOf(0, f.start - 22), f.start).lowercase()
            when {
                feeLabel.containsMatchIn(before) -> if (fee == 0.0) { fee = f.amount; feeCur = f.currency }
                balLabel.containsMatchIn(before) -> if (balance == 0.0) { balance = f.amount; balCur = f.currency }
                amount == 0.0 -> { amount = f.amount; cur = f.currency }
            }
        }
        if (amount <= 0.0) return null
        if (cur.isEmpty()) cur = feeCur.ifEmpty { balCur }.ifEmpty { defaultCurrency }

        val trx = idLabel.find(body)?.groupValues?.get(1).orEmpty()
        var party = partyRx.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        if (party.isEmpty()) party = phone.find(body)?.groupValues?.get(1).orEmpty()
        val type = Categories.typeOf(low, dir)
        val time = parseTime(body, tz, cur == "USD" || cur == "PHP") ?: smsTimeMs
        val cat = Categories.of(type, party, body, dir)
        val id = trx.ifEmpty { "h" + (prov + dir + amount + party + time + balance).hashCode().toUInt().toString(16) }
        return Txn(id, prov, dir, type, amount, fee, balance, cur, party, cat, time, bodyRaw)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parseTime(body: String, tz: TimeZone, monthFirst: Boolean = false): Long? {
        val c = Calendar.getInstance(tz); c.clear()
        fun clock(m: MatchResult, h: Int, mi: Int, s: Int, ap: Int): IntArray {
            var hh = m.groupValues[h].toIntOrNull() ?: 0; val a = m.groupValues.getOrElse(ap) { "" }.lowercase()
            if (a == "pm" && hh < 12) hh += 12; if (a == "am" && hh == 12) hh = 0
            return intArrayOf(hh, m.groupValues[mi].toIntOrNull() ?: 0, m.groupValues[s].toIntOrNull() ?: 0)
        }
        // 2026-11-12 14:30
        Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})(?:[T\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?)?", RegexOption.IGNORE_CASE).find(body)?.let { m ->
            val t = clock(m, 4, 5, 6, 7); c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), t[0], t[1], t[2]); return c.timeInMillis
        }
        // 12/11/2026 or 11/12/2026 (day first unless the first number cannot be a day… or the second cannot be a month)
        Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?)?", RegexOption.IGNORE_CASE).find(body)?.let { m ->
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            var d = m.groupValues[1].toInt(); var mo = m.groupValues[2].toInt()
            if ((mo > 12 && d <= 12) || (monthFirst && d <= 12 && mo <= 12)) { val x = d; d = mo; mo = x }
            if (mo !in 1..12 || d !in 1..31) return null
            val t = clock(m, 4, 5, 6, 7); c.set(y, mo - 1, d, t[0], t[1], t[2]); return c.timeInMillis
        }
        // 12 Nov 2026 14:30  /  Nov 12, 2026
        Regex("(\\d{1,2})[- ]([A-Za-z]{3})[a-z]*[- ,]+(\\d{2,4})(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2}))?").find(body)?.let { m ->
            val mo = months.indexOf(m.groupValues[2].lowercase()); if (mo < 0) return null
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            c.set(y, mo, m.groupValues[1].toInt(), m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, 0); return c.timeInMillis
        }
        Regex("([A-Za-z]{3})[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4})(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2}))?").find(body)?.let { m ->
            val mo = months.indexOf(m.groupValues[1].lowercase()); if (mo < 0) return null
            c.set(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt(), m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, 0); return c.timeInMillis
        }
        return null
    }
}
