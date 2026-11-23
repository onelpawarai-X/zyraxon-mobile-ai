package ai.zyraxon.assistant.world

import ai.zyraxon.assistant.core.MiniJson

/** All the user's money movements, in any currency, plus budgets. Nothing here is tied to a language or a country. */
class Ledger(val txns: MutableList<Txn> = ArrayList(), val budgets: MutableMap<String, Double> = LinkedHashMap()) {
    /** Adds the transaction unless it is already there (same id and provider). Returns true when new. */
    fun add(t: Txn): Boolean { if (txns.any { it.id == t.id && it.provider == t.provider }) return false; txns += t; txns.sortBy { it.timeMs }; return true }

    fun between(from: Long, to: Long) = txns.filter { it.timeMs in from until to }

    /** The currency used most in the period (the one the user thinks in). */
    fun primaryCurrency(from: Long, to: Long): String = between(from, to).groupBy { it.currency }.maxByOrNull { it.value.size }?.key.orEmpty()

    fun currencies(from: Long, to: Long): List<String> = between(from, to).groupBy { it.currency }.entries.sortedByDescending { it.value.size }.map { it.key }

    fun summarize(from: Long, to: Long, currency: String? = null): Summary {
        val cur = currency ?: primaryCurrency(from, to)
        val l = between(from, to).filter { it.currency == cur }
        val out = l.filter { it.dir == Dir.OUT }
        val cats = out.groupBy { it.category }.map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second }
        val parties = out.filter { it.party.isNotBlank() }.groupBy { it.party }.map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second }.take(5)
        return Summary(cur, from, to, l.filter { it.dir == Dir.IN }.sumOf { it.amount }, out.sumOf { it.amount }, l.sumOf { it.fee }, l.size, cats, parties,
            l.lastOrNull { it.balance > 0 }?.balance ?: 0.0)
    }

    fun summaries(from: Long, to: Long): List<Summary> = currencies(from, to).map { summarize(from, to, it) }

    /** Budget for a category: "food@USD" for one currency, otherwise "food" for the main one. */
    private fun limitFor(cat: String, cur: String, primary: String): Double = budgets["$cat@$cur"] ?: if (cur == primary) budgets[cat] ?: 0.0 else 0.0

    fun alerts(from: Long, to: Long): List<Alert> {
        val primary = primaryCurrency(from, to); val out = ArrayList<Alert>()
        for (s in summaries(from, to)) for ((cat, used) in s.byCategory) {
            val limit = limitFor(cat, s.currency, primary)
            if (limit > 0 && used >= limit * 0.8) out += Alert(cat, s.currency, limit, used, used >= limit, (used / limit * 100).toInt())
        }
        return out
    }

    fun toJson(): String = MiniJson.stringify(mapOf("v" to 2, "budgets" to budgets, "txns" to txns.map {
        mapOf("id" to it.id, "p" to it.provider, "d" to it.dir.name, "t" to it.type, "a" to it.amount, "f" to it.fee, "b" to it.balance, "cur" to it.currency, "party" to it.party, "c" to it.category, "ms" to it.timeMs, "raw" to it.raw.take(300))
    }))

    companion object {
        fun fromJson(src: String): Ledger {
            if (src.isBlank()) return Ledger()
            val o = MiniJson.obj(MiniJson.parse(src)); val l = Ledger()
            MiniJson.obj(o["budgets"]).forEach { (k, v) -> l.budgets[k] = MiniJson.dbl(v) }
            MiniJson.list(o["txns"]).forEach { e -> val m = MiniJson.obj(e)
                l.txns += Txn(MiniJson.str(m["id"]), MiniJson.str(m["p"]), Dir.valueOf(MiniJson.str(m["d"], "OUT")), MiniJson.str(m["t"]), MiniJson.dbl(m["a"]), MiniJson.dbl(m["f"]),
                    MiniJson.dbl(m["b"]), MiniJson.str(m["cur"]), MiniJson.str(m["party"]), MiniJson.str(m["c"]), MiniJson.long(m["ms"]), MiniJson.str(m["raw"])) }
            return l
        }
    }
}
