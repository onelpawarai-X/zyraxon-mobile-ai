package ai.zyraxon.assistant.world

enum class Dir { IN, OUT }

/**
 * One money movement. [type] and [category] are language-neutral ids (cash_out, bills, food…);
 * the screen and the assistant show them in the user's own language.
 */
data class Txn(
    val id: String, val provider: String, val dir: Dir, val type: String, val amount: Double, val fee: Double,
    val balance: Double, val currency: String, val party: String, val category: String, val timeMs: Long, val raw: String
)

data class Summary(
    val currency: String, val from: Long, val to: Long, val income: Double, val spent: Double, val fees: Double, val count: Int,
    val byCategory: List<Pair<String, Double>>, val topParties: List<Pair<String, Double>>, val lastBalance: Double
)

data class Alert(val category: String, val currency: String, val limit: Double, val used: Double, val over: Boolean, val percent: Int)
