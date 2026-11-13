package ai.zyraxon.assistant.finance

import ai.zyraxon.assistant.core.MiniJson
import java.util.Calendar
import java.util.TimeZone

enum class Dir { IN, OUT }

data class Txn(
    val id: String, val provider: String, val dir: Dir, val type: String, val amount: Double, val fee: Double,
    val balance: Double, val party: String, val category: String, val timeMs: Long, val raw: String
)

/** Reads bKash, Nagad, Rocket, Upay and bank SMS (Bangla or English) into transactions. */
object SmsParser {
    private val bnDigits = "০১২৩৪৫৬৭৮৯"
    fun digits(s: String): String { val sb = StringBuilder(); for (c in s) { val i = bnDigits.indexOf(c); sb.append(if (i >= 0) ('0' + i) else c) }; return sb.toString() }

    private val money = Regex("""(?i)(?:tk\.?|bdt|৳|taka|টাকা)\s*[:\-]?\s*([0-9][0-9,]*(?:\.[0-9]+)?)|([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:tk\b|bdt\b|taka\b|৳|টাকা)""")
    private fun num(s: String) = s.replace(",", "").toDoubleOrNull() ?: 0.0

    private fun after(label: Regex, t: String): Double {
        val m = label.find(t) ?: return 0.0
        val rest = t.substring(m.range.last + 1).take(40)
        val mm = money.find(rest) ?: Regex("""^\s*[:\-]?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""").find(rest)
        val g = mm?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: return 0.0
        return num(g)
    }

    private val inWords = listOf("received", "cash in", "cashin", "credited", "credit", "deposit", "refund", "cashback", "salary", "money received", "পেয়েছেন", "জমা", "ক্যাশ ইন", "রিসিভ", "ক্রেডিট", "ফেরত")
    private val outWords = listOf("send money", "sent", "cash out", "cashout", "payment", "paid", "bill", "recharge", "debited", "debit", "withdraw", "purchase", "transfer", "pay bill", "পাঠিয়েছেন", "ক্যাশ আউট", "পেমেন্ট", "বিল", "রিচার্জ", "ডেবিট", "উত্তোলন", "ট্রান্সফার")

    fun provider(sender: String, body: String): String {
        val s = (sender + " " + body).lowercase()
        return when {
            "bkash" in s || "বিকাশ" in s -> "bKash"; "nagad" in s || "নগদ" in s -> "Nagad"; "rocket" in s || "dbbl" in s || "16216" in s -> "Rocket"
            "upay" in s -> "Upay"; "ucb" in s -> "UCB"; "brac" in s -> "BRAC Bank"; "city bank" in s || "citybank" in s -> "City Bank"
            "islami" in s -> "Islami Bank"; "a/c" in s || "account" in s || "atm" in s || "avl" in s -> "Bank"
            else -> ""
        }
    }

    fun parse(sender: String, bodyRaw: String, smsTimeMs: Long = 0, tz: TimeZone = TimeZone.getDefault()): Txn? {
        val body = digits(bodyRaw).replace(' ', ' ')
        val low = body.lowercase()
        // one-time codes and promotions are not money movement
        if (Regex("(?i)\\b(otp|verification code|one time|pin)\\b").containsMatchIn(low) && !money.containsMatchIn(low)) return null
        val prov = provider(sender, body)
        if (prov.isEmpty()) return null
        val outIdx = outWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
        val inIdx = inWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
        if (outIdx == Int.MAX_VALUE && inIdx == Int.MAX_VALUE) return null
        // "Cash Out" must win over the word "received" appearing later, so the earliest keyword decides
        val dir = if (inIdx < outIdx) Dir.IN else Dir.OUT
        val fee = after(Regex("(?i)\\bfee\\b|charge|ফি"), body)
        val balance = after(Regex("(?i)\\b(?:avl\\.?\\s*)?bal(?:ance)?\\b|ব্যালেন্স"), body)
        // the amount is the first money figure that is not the fee or the balance
        var amount = 0.0
        val feeLabel = Regex("(?i)(?<![\\p{L}])(fee|charge|bal(?:ance)?|avl|ফি|ব্যালেন্স)\\W{0,12}$")
        for (m in money.findAll(body)) {
            val before = body.substring(maxOf(0, m.range.first - 14), m.range.first)
            if (feeLabel.containsMatchIn(before)) continue
            amount = num(m.groupValues.drop(1).first { it.isNotEmpty() }); break
        }
        if (amount <= 0.0) return null
        val trx = Regex("(?i)(?:trx\\s*id|txn\\s*id|trxid|txnid|transaction id|ref(?:erence)?)\\s*[:\\-]?\\s*([A-Z0-9]{6,})").find(body)?.groupValues?.get(1).orEmpty()
        val phone = Regex("(?<!\\d)(?:\\+?88)?(01[3-9]\\d{8})(?!\\d)").find(body)?.groupValues?.get(1).orEmpty()
        var party = phone
        if (party.isEmpty()) {
            val m = Regex("(?i)\\b(?:to|from|at)\\s+([A-Za-z][A-Za-z0-9 .&'\\-]{2,28}?)(?=[.,;]|\\s+(?:successful|on|at|trx|txn|fee|balance|ref)\\b|$)").find(body)
            party = m?.groupValues?.get(1)?.trim().orEmpty()
        }
        val type = when {
            "cash out" in low || "cashout" in low || "ক্যাশ আউট" in low || "withdraw" in low || "উত্তোলন" in low -> "ক্যাশ আউট"
            "cash in" in low || "cashin" in low || "ক্যাশ ইন" in low -> "ক্যাশ ইন"
            "send money" in low || "sent" in low || "পাঠিয়েছেন" in low -> "সেন্ড মানি"
            "bill" in low || "বিল" in low -> "বিল"
            "recharge" in low || "রিচার্জ" in low -> "রিচার্জ"
            "payment" in low || "paid" in low || "purchase" in low || "পেমেন্ট" in low -> "পেমেন্ট"
            "refund" in low || "cashback" in low || "ফেরত" in low -> "ফেরত"
            dir == Dir.IN -> "প্রাপ্তি" else -> "খরচ"
        }
        val time = parseTime(body, tz) ?: smsTimeMs
        val cat = Categories.of(type, party, body, dir)
        val id = trx.ifEmpty { "h" + (prov + dir + amount + party + time + balance).hashCode().toUInt().toString(16) }
        return Txn(id, prov, dir, type, amount, fee, balance, party, cat, time, bodyRaw)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    fun parseTime(body: String, tz: TimeZone): Long? {
        val c = Calendar.getInstance(tz); c.clear()
        Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?)?", RegexOption.IGNORE_CASE).find(body)?.let { m ->
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            var h = m.groupValues[4].toIntOrNull() ?: 0; val ap = m.groupValues[7].lowercase()
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            c.set(y, m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), h, m.groupValues[5].toIntOrNull() ?: 0, m.groupValues[6].toIntOrNull() ?: 0)
            return c.timeInMillis
        }
        Regex("(\\d{1,2})[- ]([A-Za-z]{3})[a-z]*[- ,]+(\\d{2,4})(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2}))?").find(body)?.let { m ->
            val mo = months.indexOf(m.groupValues[2].lowercase()); if (mo < 0) return null
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            c.set(y, mo, m.groupValues[1].toInt(), m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, 0); return c.timeInMillis
        }
        return null
    }
}

object Categories {
    private val rules = listOf(
        "বিল" to listOf("desco", "dpdc", "wasa", "titas", "gas", "electric", "bill", "বিল", "palli", "nesco", "westzone", "internet", "broadband", "wifi"),
        "মোবাইল রিচার্জ" to listOf("recharge", "রিচার্জ", "grameenphone", "robi", "banglalink", "airtel", "teletalk", "gp "),
        "যাতায়াত" to listOf("pathao", "uber", "obhai", "ticket", "shohoz", "bus", "train", "fuel", "cng", "metro"),
        "খাবার" to listOf("foodpanda", "food", "kfc", "pizza", "burger", "restaurant", "cafe", "hungrynaki", "shwapno", "agora", "meena", "grocery", "bazar"),
        "কেনাকাটা" to listOf("daraz", "shop", "mart", "store", "bata", "aarong", "ryans", "startech", "evaly", "chaldal"),
        "শিক্ষা" to listOf("school", "college", "university", "tuition", "exam", "fee", "course", "বিদ্যালয়"),
        "স্বাস্থ্য" to listOf("hospital", "pharmacy", "medic", "clinic", "doctor", "lab"),
        "বিনোদন" to listOf("netflix", "spotify", "youtube", "cinema", "bioscope", "hoichoi", "game", "steam")
    )
    fun of(type: String, party: String, body: String, dir: Dir): String {
        val s = (party + " " + body).lowercase()
        if (dir == Dir.IN) return when { type == "ফেরত" -> "ফেরত"; "salary" in s || "বেতন" in s -> "বেতন"; else -> "প্রাপ্তি" }
        if (type == "ক্যাশ আউট") return "ক্যাশ আউট"
        if (type == "বিল") return "বিল"
        if (type == "রিচার্জ") return "মোবাইল রিচার্জ"
        // the other side's name decides the category (the body also has words like "balance")
        val p = party.lowercase()
        for ((cat, ws) in rules) if (ws.any { it in p }) return cat
        if (type == "সেন্ড মানি") return "পাঠানো"
        return "অন্যান্য"
    }
}

data class Summary(
    val from: Long, val to: Long, val income: Double, val spent: Double, val fees: Double, val count: Int,
    val byCategory: List<Pair<String, Double>>, val topParties: List<Pair<String, Double>>, val lastBalance: Double
)

class Ledger(val txns: MutableList<Txn> = ArrayList(), val budgets: MutableMap<String, Double> = LinkedHashMap()) {
    /** Adds the transaction unless it is already there (same id). Returns true when new. */
    fun add(t: Txn): Boolean { if (txns.any { it.id == t.id && it.provider == t.provider }) return false; txns += t; txns.sortBy { it.timeMs }; return true }

    fun between(from: Long, to: Long) = txns.filter { it.timeMs in from until to }

    fun summarize(from: Long, to: Long): Summary {
        val l = between(from, to)
        val out = l.filter { it.dir == Dir.OUT }
        val cats = out.filter { it.category != "ক্যাশ আউট" || true }.groupBy { it.category }.map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second }
        val parties = out.filter { it.party.isNotBlank() }.groupBy { it.party }.map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second }.take(5)
        return Summary(from, to, l.filter { it.dir == Dir.IN }.sumOf { it.amount }, out.sumOf { it.amount }, l.sumOf { it.fee }, l.size, cats, parties,
            l.lastOrNull { it.balance > 0 }?.balance ?: 0.0)
    }

    /** Categories whose spending in the period has passed (or nears) the budget. */
    fun alerts(from: Long, to: Long): List<String> {
        val s = summarize(from, to).byCategory.toMap(); val out = ArrayList<String>()
        for ((cat, limit) in budgets) {
            val used = s[cat] ?: 0.0
            if (limit > 0 && used >= limit) out += "$cat: বাজেট ${Fmt.money(limit)} ছাড়িয়ে গেছে (খরচ ${Fmt.money(used)})"
            else if (limit > 0 && used >= limit * 0.8) out += "$cat: বাজেটের ${(used / limit * 100).toInt()}% শেষ (${Fmt.money(used)} / ${Fmt.money(limit)})"
        }
        return out
    }

    fun toJson(): String = MiniJson.stringify(mapOf("budgets" to budgets, "txns" to txns.map {
        mapOf("id" to it.id, "p" to it.provider, "d" to it.dir.name, "t" to it.type, "a" to it.amount, "f" to it.fee, "b" to it.balance, "party" to it.party, "c" to it.category, "ms" to it.timeMs, "raw" to it.raw.take(300))
    }))

    companion object {
        fun fromJson(src: String): Ledger {
            if (src.isBlank()) return Ledger()
            val o = MiniJson.obj(MiniJson.parse(src)); val l = Ledger()
            MiniJson.obj(o["budgets"]).forEach { (k, v) -> l.budgets[k] = MiniJson.dbl(v) }
            MiniJson.list(o["txns"]).forEach { e -> val m = MiniJson.obj(e)
                l.txns += Txn(MiniJson.str(m["id"]), MiniJson.str(m["p"]), Dir.valueOf(MiniJson.str(m["d"], "OUT")), MiniJson.str(m["t"]), MiniJson.dbl(m["a"]), MiniJson.dbl(m["f"]),
                    MiniJson.dbl(m["b"]), MiniJson.str(m["party"]), MiniJson.str(m["c"]), MiniJson.long(m["ms"]), MiniJson.str(m["raw"])) }
            return l
        }
    }
}

object Fmt {
    private val bn = "০১২৩৪৫৬৭৮৯"
    fun bnDigits(s: String) = s.map { if (it in '0'..'9') bn[it - '0'] else it }.joinToString("")
    fun money(v: Double): String {
        val whole = v.toLong(); val frac = Math.round((v - whole) * 100).toInt()
        val g = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        return bnDigits("৳" + g + if (frac > 0) ".%02d".format(frac) else "")
    }

    /** The weekly summary the character reads aloud. */
    fun spoken(s: Summary, alerts: List<String>, label: String): String = buildString {
        if (s.count == 0) { append("$label কোনো লেনদেন পাইনি।"); return@buildString }
        append("$label আপনি ${money(s.spent)} খরচ করেছেন এবং ${money(s.income)} পেয়েছেন, মোট ${bnDigits(s.count.toString())}টি লেনদেন। ")
        s.byCategory.firstOrNull()?.let { append("সবচেয়ে বেশি গেছে ${it.first}-এ, ${money(it.second)}। ") }
        if (s.fees > 0) append("চার্জ বাবদ গেছে ${money(s.fees)}। ")
        if (s.lastBalance > 0) append("এখন ব্যালেন্স ${money(s.lastBalance)}। ")
        alerts.forEach { append(it).append("। ") }
    }

    /** Rows for the Excel report. */
    fun rows(l: Ledger, from: Long, to: Long, tz: TimeZone = TimeZone.getDefault()): Pair<List<List<String>>, List<List<String>>> {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").apply { timeZone = tz }
        val tx = listOf(listOf("তারিখ", "মাধ্যম", "ধরন", "পক্ষ", "খাত", "আসা", "যাওয়া", "চার্জ", "ব্যালেন্স")) +
            l.between(from, to).map { listOf(sdf.format(java.util.Date(it.timeMs)), it.provider, it.type, it.party, it.category,
                if (it.dir == Dir.IN) it.amount.toString() else "", if (it.dir == Dir.OUT) it.amount.toString() else "", it.fee.toString(), it.balance.toString()) }
        val s = l.summarize(from, to)
        val sum = listOf(listOf("খাত", "মোট খরচ", "বাজেট")) + s.byCategory.map { listOf(it.first, it.second.toString(), (l.budgets[it.first] ?: 0.0).toString()) } +
            listOf(listOf("মোট খরচ", s.spent.toString(), ""), listOf("মোট আয়", s.income.toString(), ""), listOf("মোট চার্জ", s.fees.toString(), ""))
        return tx to sum
    }
}
