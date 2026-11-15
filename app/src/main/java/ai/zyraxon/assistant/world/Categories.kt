package ai.zyraxon.assistant.world

/** Spending categories as language-neutral ids, with merchants and words from many countries. */
object Categories {
    val ids = listOf("bills", "mobile", "transport", "food", "shopping", "education", "health", "entertainment", "housing", "cash_out", "transfer", "refund", "salary", "income", "other")

    private val rules = listOf(
        "bills" to listOf("desco", "dpdc", "wasa", "titas", "gas", "electric", "electricity", "water", "utility", "utilities", "bill", "palli", "nesco", "internet", "broadband", "wifi", "comcast", "xfinity", "spectrum",
            "pge", "con edison", "british gas", "edf", "e.on", "enel", "iberdrola", "eskom", "kplc", "bescom", "tata power", "বিল", "फाइबर"),
        "mobile" to listOf("recharge", "airtime", "grameenphone", "robi", "banglalink", "airtel", "teletalk", "jio", "vodafone", "safaricom", "mtn", "t-mobile", "verizon", "at&t", "o2", "orange", "telkomsel", "globe", "smart", "claro", "movistar", "turkcell", "রিচার্জ"),
        "transport" to listOf("pathao", "uber", "lyft", "ola", "grab", "bolt", "careem", "gojek", "didi", "obhai", "shohoz", "ticket", "bus", "train", "metro", "fuel", "petrol", "gasoline", "shell", "cng", "parking", "airline", "airways", "flight", "railway", "toll", "99", "cabify"),
        "food" to listOf("foodpanda", "doordash", "ubereats", "uber eats", "deliveroo", "swiggy", "zomato", "glovo", "ifood", "rappi", "grubhub", "mcdonald", "kfc", "pizza", "burger", "restaurant", "cafe", "coffee", "starbucks", "hungrynaki", "grocery", "supermarket", "shwapno", "agora", "chaldal", "bazar", "tesco", "carrefour", "lidl", "aldi"),
        "shopping" to listOf("amazon", "daraz", "ebay", "aliexpress", "shopee", "lazada", "flipkart", "myntra", "zalando", "walmart", "target", "ikea", "shop", "mart", "store", "mall", "aarong", "ryans", "startech", "evaly", "mercadolibre", "jumia", "temu", "shein", "etsy"),
        "education" to listOf("school", "college", "university", "tuition", "exam", "course", "udemy", "coursera", "academy", "বিদ্যালয়", "institute"),
        "health" to listOf("hospital", "pharmacy", "medic", "medical", "clinic", "doctor", "dental", "cvs", "walgreens", "apollo", "lab"),
        "entertainment" to listOf("netflix", "spotify", "youtube", "cinema", "bioscope", "hoichoi", "game", "steam", "playstation", "xbox", "disney", "hbo", "prime video", "apple.com", "google play", "twitch", "subscription"),
        "housing" to listOf("rent", "landlord", "mortgage", "lease", "housing", "apartment", "hoa")
    )
    private val salaryWords = listOf("salary", "payroll", "wage", "wages", "sueldo", "nómina", "nomina", "gehalt", "salaire", "salário", "salario", "зарплата", "maaş", "gaji", "বেতন", "راتب", "वेतन", "工资", "給与", "급여")

    /** The type of a message as an id, from the words in it. */
    fun typeOf(low: String, dir: Dir): String {
        fun has(vararg w: String) = Providers.has(low, *w)
        return when {
            has("cash out", "cashout", "withdraw", "withdrawn", "withdrawal", "atm", "retiro", "retrait", "saque", "ক্যাশ আউট", "উত্তোলন", "سحب", "निकासी", "penarikan", "çekim", "снятие", "取款", "出金", "출금") -> "cash_out"
            has("cash in", "cashin", "ক্যাশ ইন", "top up", "topped up") && dir == Dir.IN -> "cash_in"
            has("refund", "cashback", "reembolso", "remboursement", "erstattung", "ফেরত", "استرداد", "возврат", "返金", "환불") -> "refund"
            has("recharge", "airtime", "recarga", "রিচার্জ", "रिचार्ज", "pulsa", "شحن") -> "recharge"
            has("bill", "factura", "facture", "rechnung", "fatura", "fattura", "বিল", "बिल", "فاتورة", "tagihan", "счет", "счёт") -> "bill"
            has("send money", "sent", "transferred", "transfer", "enviado", "envoyé", "überweisung", "überwiesen", "gesendet", "перевод", "تحويل", "পাঠিয়েছেন", "ট্রান্সফার", "转出", "送金", "pix enviado") -> "send"
            has("payment", "paid", "purchase", "pago", "compra", "paiement", "achat", "zahlung", "pagamento", "ödeme", "pembayaran", "pembelian", "оплата", "покупка", "পেমেন্ট", "دفع", "شراء", "भुगतान", "支付", "消费", "支払", "결제") -> "payment"
            dir == Dir.IN -> "income" else -> "expense"
        }
    }

    fun of(type: String, party: String, body: String, dir: Dir): String {
        val s = (party + " " + body).lowercase()
        if (dir == Dir.IN) return when { type == "refund" -> "refund"; salaryWords.any { Providers.idx(s, it) >= 0 } -> "salary"; else -> "income" }
        when (type) { "cash_out" -> return "cash_out"; "bill" -> return "bills"; "recharge" -> return "mobile" }
        // the other side's name decides the category (the body also has words like "balance")
        val p = party.lowercase()
        for ((cat, ws) in rules) if (ws.any { Providers.idx(p, it) >= 0 }) return cat
        if (type == "send") return "transfer"
        return "other"
    }
}
