package ai.zyraxon.assistant.world

/** Wallets, apps and banks from around the world, and the words that tell money in from money out in many languages. */
object Providers {
    private val names = listOf(
        // South Asia
        "bkash" to "bKash", "বিকাশ" to "bKash", "nagad" to "Nagad", "নগদ" to "Nagad", "rocket" to "Rocket", "upay" to "Upay", "paytm" to "Paytm", "phonepe" to "PhonePe", "gpay" to "Google Pay", "google pay" to "Google Pay",
        "upi" to "UPI", "bhim" to "UPI", "jazzcash" to "JazzCash", "easypaisa" to "Easypaisa", "esewa" to "eSewa", "khalti" to "Khalti", "dialog" to "Dialog Genie",
        // Africa
        "m-pesa" to "M-Pesa", "mpesa" to "M-Pesa", "airtel money" to "Airtel Money", "mtn" to "MTN MoMo", "momo" to "MoMo", "opay" to "OPay", "palmpay" to "PalmPay", "flutterwave" to "Flutterwave", "orange money" to "Orange Money", "wave" to "Wave",
        // Asia-Pacific
        "gcash" to "GCash", "maya" to "Maya", "gopay" to "GoPay", "ovo" to "OVO", "dana" to "DANA", "shopeepay" to "ShopeePay", "touch 'n go" to "Touch 'n Go", "grabpay" to "GrabPay", "paynow" to "PayNow",
        "alipay" to "Alipay", "wechat" to "WeChat Pay", "paypay" to "PayPay", "kakao" to "KakaoPay", "momo.vn" to "MoMo",
        // Americas
        "paypal" to "PayPal", "venmo" to "Venmo", "cash app" to "Cash App", "cashapp" to "Cash App", "zelle" to "Zelle", "apple cash" to "Apple Cash", "chase" to "Chase", "wells fargo" to "Wells Fargo",
        "bank of america" to "Bank of America", "citi" to "Citi", "capital one" to "Capital One", "pix" to "Pix", "nubank" to "Nubank", "mercado pago" to "Mercado Pago", "interac" to "Interac", "rbc" to "RBC",
        // Europe / global
        "wise" to "Wise", "revolut" to "Revolut", "monzo" to "Monzo", "n26" to "N26", "klarna" to "Klarna", "stripe" to "Stripe", "barclays" to "Barclays", "hsbc" to "HSBC", "lloyds" to "Lloyds",
        "santander" to "Santander", "bbva" to "BBVA", "ing" to "ING", "sepa" to "SEPA", "bizum" to "Bizum", "twint" to "TWINT", "swish" to "Swish", "mobilepay" to "MobilePay", "blik" to "BLIK", "sber" to "Sber", "tinkoff" to "Tinkoff",
        // Middle East
        "stc pay" to "stc pay", "apple pay" to "Apple Pay", "mada" to "mada", "fawry" to "Fawry", "vodafone cash" to "Vodafone Cash", "papara" to "Papara", "tikkie" to "Tikkie"
    ).sortedByDescending { it.first.length }

    private val financeMarks = Regex("(?i)\\b(a/c|acct|account|acc\\.?|card|txn|trx|trxid|transaction|ref(?:erence)?|balance|bal|avl|iban|upi|neft|imps|rtgs|pos|atm|wallet)\\b|رصيد|حساب|हिसाब|खाता|सल्डो|saldo|solde|kontostand|ব্যালেন্স|余额|残高|잔액")

    private val cache = HashMap<String, Regex>()

    /** Position of [w] in the already-lowercased text, matching whole words for Latin text and plain substrings for scripts without spaces. -1 when absent. */
    fun idx(low: String, w: String): Int {
        val k = w.trim(); if (k.isEmpty()) return -1
        val latin = k.all { it.code < 128 && (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '\'' || it == '.') }
        if (!latin) return low.indexOf(k)
        val r = synchronized(cache) { cache.getOrPut(k) { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(k) + "(?![\\p{L}\\p{N}])") } }
        return r.find(low)?.range?.first ?: -1
    }

    fun has(low: String, vararg ws: String) = ws.any { idx(low, it) >= 0 }
    fun first(low: String, ws: List<String>): Int = ws.mapNotNull { w -> idx(low, w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE

    fun detect(sender: String, body: String): String {
        val s = (sender + " " + body).lowercase()
        for ((k, v) in names) if (idx(s, k) >= 0) return v
        return if (financeMarks.containsMatchIn(s)) "Bank" else ""
    }

    val inWords = listOf(
        "received", "credited", "credit alert", "deposit", "deposited", "refund", "cashback", "cash in", "cashin", "salary", "paid you", "sent you", "money in", "added to", "top up", "topped up", "incoming",
        "recibiste", "recibido", "abono", "depósito", "deposito", "acreditado", "ingreso", "reçu", "crédité", "credite", "versement reçu", "erhalten", "gutschrift", "eingang", "empfangen",
        "recebeu", "recebido", "crédito", "credito", "recebida", "استلمت", "تم إيداع", "إيداع", "استلام", "تم استلام", "प्राप्त", "जमा", "क्रेडिट", "diterima", "masuk", "kredit", "setoran", "alındı", "yatırıldı", "gelen", "hesabınıza",
        "поступление", "зачислено", "получен", "зачисление", "পেয়েছেন", "জমা", "ক্যাশ ইন", "রিসিভ", "ক্রেডিট", "ফেরত", "موصول", "umepokea", "收到", "入账", "存入", "入金", "受取", "입금", "otrzymano", "wpływ", "ontvangen", "bijgeschreven"
    )
    val outWords = listOf(
        "sent", "send money", "paid", "payment", "purchase", "debited", "debit", "withdrawn", "withdrawal", "withdraw", "cash out", "cashout", "spent", "transferred", "transfer to", "bill", "recharge", "charged", "pos ", "atm", "outgoing", "you paid", "payment of",
        "enviado", "pago", "compra", "débito", "debito", "retiro", "cargo", "envoyé", "paiement", "retrait", "achat", "débité", "virement émis", "abbuchung", "zahlung", "belastet", "überweisung", "überwiesen", "bezahlt",
        "pagamento", "saque", "enviou", "enviada", "سحب", "دفع", "تحويل", "خصم", "شراء", "निकासी", "डेबिट", "भुगतान", "खर्च", "keluar", "pembayaran", "penarikan", "pembelian", "ödeme", "harcama", "çekim", "gönderildi",
        "списание", "оплата", "покупка", "снятие", "перевод", "পাঠিয়েছেন", "ক্যাশ আউট", "পেমেন্ট", "বিল", "রিচার্জ", "ডেবিট", "উত্তোলন", "ট্রান্সফার", "支出", "消费", "转出", "取款", "出金", "支払", "引落", "출금", "결제", "płatność", "obciążenie", "betaling", "afgeschreven"
    )
    /** "Payment received", "amount credited": the noun comes first but the money is coming in. */
    val inPhrases = Regex("(?i)(payment|transfer|deposit|money|amount|funds|salary)\\s+(received|credited|deposited)|credited with|credit of|has been credited|you('ve| have) received|paiement reçu|pago recibido|zahlung erhalten|pagamento recebido")
    val otpOnly = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|security code|código|codice|mot de passe|رمز)\\b")
}
