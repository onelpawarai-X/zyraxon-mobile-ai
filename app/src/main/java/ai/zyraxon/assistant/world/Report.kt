package ai.zyraxon.assistant.world

import java.util.TimeZone

/**
 * Turns the ledger into plain facts. The assistant tells these to the user in whatever language the
 * user speaks, so nothing here is a sentence in any one language.
 */
object Report {
    /** Facts the model narrates: totals per currency, top categories, alerts, and the biggest expenses. */
    fun facts(l: Ledger, from: Long, to: Long, alertFrom: Long, alertTo: Long): Map<String, Any> {
        val sums = l.summaries(from, to)
        return mapOf(
            "transactions" to sums.sumOf { it.count },
            "per_currency" to sums.map { s -> mapOf(
                "currency" to s.currency, "income" to s.income, "spent" to s.spent, "fees" to s.fees, "count" to s.count, "balance" to s.lastBalance,
                "income_text" to Money.format(s.income, s.currency), "spent_text" to Money.format(s.spent, s.currency), "fees_text" to Money.format(s.fees, s.currency),
                "balance_text" to Money.format(s.lastBalance, s.currency),
                "by_category" to s.byCategory.map { mapOf("category" to it.first, "amount" to it.second, "text" to Money.format(it.second, s.currency)) },
                "top_parties" to s.topParties.map { mapOf("party" to it.first, "amount" to it.second, "text" to Money.format(it.second, s.currency)) }) },
            "alerts" to l.alerts(alertFrom, alertTo).map { alertMap(it) }
        )
    }

    fun alertMap(it: Alert): Map<String, Any> = mapOf("category" to it.category, "currency" to it.currency, "limit" to it.limit, "used" to it.used, "over" to it.over, "percent" to it.percent,
        "limit_text" to Money.format(it.limit, it.currency), "used_text" to Money.format(it.used, it.currency))

    /** One-line text for a new transaction: "+$20 · income (PayPal)". [name] turns an id into the user's language. */
    fun line(t: Txn, name: (String) -> String = { it }): String =
        (if (t.dir == Dir.IN) "+" else "−") + Money.format(t.amount, t.currency) + " " + name("type_" + t.type) + (if (t.party.isNotBlank()) " (${t.party})" else "") + " · " + name("cat_" + t.category)

    /** Rows for the Excel report; [name] gives the words in the user's language. */
    fun rows(l: Ledger, from: Long, to: Long, name: (String) -> String, tz: TimeZone = TimeZone.getDefault()): Pair<List<List<String>>, List<List<String>>> {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = tz }
        val tx = listOf(listOf(name("h_date"), name("h_provider"), name("h_type"), name("h_party"), name("h_category"), name("h_in"), name("h_out"), name("h_fee"), name("h_balance"), name("h_currency"))) +
            l.between(from, to).map { listOf(sdf.format(java.util.Date(it.timeMs)), it.provider, name("type_" + it.type), it.party, name("cat_" + it.category),
                if (it.dir == Dir.IN) it.amount.toString() else "", if (it.dir == Dir.OUT) it.amount.toString() else "", it.fee.toString(), it.balance.toString(), it.currency) }
        val sum = ArrayList<List<String>>(); sum += listOf(name("h_category"), name("h_total_spent"), name("h_budget"), name("h_currency"))
        val primary = l.primaryCurrency(from, to)
        for (s in l.summaries(from, to)) {
            s.byCategory.forEach { sum += listOf(name("cat_" + it.first), it.second.toString(), (l.budgets["${it.first}@${s.currency}"] ?: if (s.currency == primary) l.budgets[it.first] ?: 0.0 else 0.0).toString(), s.currency) }
            sum += listOf(name("h_total_spent"), s.spent.toString(), "", s.currency); sum += listOf(name("h_total_income"), s.income.toString(), "", s.currency); sum += listOf(name("h_total_fees"), s.fees.toString(), "", s.currency)
        }
        return tx to sum
    }
}
