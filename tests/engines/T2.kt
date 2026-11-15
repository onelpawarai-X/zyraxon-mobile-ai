import ai.zyraxon.assistant.autopilot.*
import ai.zyraxon.assistant.world.*
import java.util.TimeZone
import java.util.Calendar

var fails = 0; var passes = 0
fun check(name: String, c: Boolean) { if (c) passes++ else { fails++; println("FAIL: $name") } }
fun near(a: Double, b: Double) = Math.abs(a - b) < 0.01

fun main() {
    val dhaka = TimeZone.getTimeZone("Asia/Dhaka")
    fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(dhaka).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    // ================= Autopilot
    val smsRule = Rule("r1", "বিকাশ হিসাব", Trigger(Kind.SMS, from = "bkash", any = listOf("received", "পেয়েছেন")), "হিসাবে তোলো", createdAt = 0)
    check("sms match", Autopilot.matches(smsRule, Event(Kind.SMS, "bKash", "You have received Tk 500", timeMs = 1000)))
    check("sms bangla match", Autopilot.matches(smsRule, Event(Kind.SMS, "BKASH", "আপনি ৫০০ টাকা পেয়েছেন", timeMs = 1000)))
    check("sms other sender", !Autopilot.matches(smsRule, Event(Kind.SMS, "Nagad", "You have received Tk 500", timeMs = 1000)))
    check("sms other text", !Autopilot.matches(smsRule, Event(Kind.SMS, "bKash", "Cash Out Tk 500", timeMs = 1000)))
    check("kind mismatch", !Autopilot.matches(smsRule, Event(Kind.CALL, "bKash", "received")))
    val num = Rule("r2", "মা-র কল", Trigger(Kind.CALL, from = "01712345678"), "মনে করিয়ে দাও")
    check("phone +88 prefix", Autopilot.matches(num, Event(Kind.CALL, "+8801712345678")))
    check("phone bangla digits", Autopilot.matches(num, Event(Kind.CALL, "০১৭১২৩৪৫৬৭৮")))
    check("phone different", !Autopilot.matches(num, Event(Kind.CALL, "01812345678")))
    val cool = smsRule.copy(lastFired = 5000)
    check("cooldown", !Autopilot.matches(cool, Event(Kind.SMS, "bKash", "received", timeMs = 6000)) && Autopilot.matches(cool, Event(Kind.SMS, "bKash", "received", timeMs = 30000)))
    check("disabled", !Autopilot.matches(smsRule.copy(enabled = false), Event(Kind.SMS, "bKash", "received", timeMs = 1000)))
    val none = Rule("r3", "x", Trigger(Kind.SMS, any = listOf("otp"), none = listOf("promo")), "t")
    check("none words", Autopilot.matches(none, Event(Kind.SMS, "x", "your OTP 1234", timeMs = 1)) && !Autopilot.matches(none, Event(Kind.SMS, "x", "OTP promo", timeMs = 1)))
    val bat = Rule("r4", "ব্যাটারি", Trigger(Kind.BATTERY_LOW, below = 15), "সেভার চালাও")
    check("battery", Autopilot.matches(bat, Event(Kind.BATTERY_LOW, battery = 12)) && !Autopilot.matches(bat, Event(Kind.BATTERY_LOW, battery = 40)))
    val app = Rule("r5", "ফেসবুক", Trigger(Kind.APP_OPENED, pkg = "facebook"), "টাইমার")
    check("app opened", Autopilot.matches(app, Event(Kind.APP_OPENED, pkg = "com.facebook.katana")))

    // time rules
    val daily = Rule("d", "সকাল", Trigger(Kind.DAILY, hour = 8, minute = 30), "খবর শোনাও", createdAt = ms(2026, 11, 10, 0, 0))
    check("daily not yet", !Autopilot.due(daily, ms(2026, 11, 12, 8, 29), dhaka))
    check("daily due", Autopilot.due(daily, ms(2026, 11, 12, 8, 31), dhaka))
    check("daily fired today", !Autopilot.due(daily.copy(lastFired = ms(2026, 11, 12, 8, 31)), ms(2026, 11, 12, 9, 0), dhaka))
    check("daily next day", Autopilot.due(daily.copy(lastFired = ms(2026, 11, 12, 8, 31)), ms(2026, 11, 13, 8, 31), dhaka))
    check("daily stale", !Autopilot.due(daily, ms(2026, 11, 12, 20, 0), dhaka))
    check("daily next fire", Autopilot.nextFire(daily, ms(2026, 11, 12, 9, 0), dhaka) == ms(2026, 11, 13, 8, 30))
    val once = Rule("o", "মিটিং", Trigger(Kind.ONCE, atMs = ms(2026, 11, 12, 15, 0)), "জানাও")
    check("once", !Autopilot.due(once, ms(2026, 11, 12, 14, 59)) && Autopilot.due(once, ms(2026, 11, 12, 15, 1)) && !Autopilot.due(once.copy(lastFired = 1), ms(2026, 11, 12, 15, 2)))
    val iv = Rule("i", "পানি", Trigger(Kind.INTERVAL, everyMin = 60), "বলো", createdAt = ms(2026, 11, 12, 10, 0))
    check("interval", !Autopilot.due(iv, ms(2026, 11, 12, 10, 59)) && Autopilot.due(iv, ms(2026, 11, 12, 11, 0)))
    // json
    val all = listOf(smsRule, num, daily, once, bat)
    check("rules json", Autopilot.fromJson(Autopilot.toJson(all)) == all)
    // trigger from agent args
    val t = Autopilot.triggerFrom(mapOf("kind" to "sms", "from" to "bkash", "any" to "received, পেয়েছেন"))
    check("triggerFrom sms", t.kind == Kind.SMS && t.any == listOf("received", "পেয়েছেন"))
    val td = Autopilot.triggerFrom(mapOf("kind" to "daily", "time" to "07:45"))
    check("triggerFrom daily", td.hour == 7 && td.minute == 45)
    check("triggerFrom bad", runCatching { Autopilot.triggerFrom(mapOf("kind" to "xyz")) }.isFailure && runCatching { Autopilot.triggerFrom(mapOf("kind" to "daily")) }.isFailure)
    check("prompt", Autopilot.taskPrompt(smsRule, Event(Kind.SMS, "bKash", "Tk 500")).let { it.contains("Autopilot") && it.contains("Tk 500") && it.contains("হিসাবে তোলো") })

    // ================= SMS parser: realistic messages, many countries
    fun p(sender: String, body: String, cur: String = "BDT") = SmsParser.parse(sender, body, 0, dhaka, cur)
    val a = p("bKash", "You have received Tk 1,500.00 from 01712345678. Fee Tk 0.00. Balance Tk 2,340.50. TrxID 9KJ3H2LM at 12/11/2026 14:30")!!
    check("bkash in", a.dir == Dir.IN && near(a.amount, 1500.0) && near(a.balance, 2340.5) && a.party == "01712345678" && a.id == "9KJ3H2LM" && a.provider == "bKash" && a.currency == "BDT")
    check("bkash time", a.timeMs == ms(2026, 11, 12, 14, 30))
    val b = p("bKash", "Send Money Tk 500.00 to 01898765432. Fee Tk 5.00. Balance Tk 1,835.50. TrxID AB12CD34EF at 12/11/2026 15:10")!!
    check("send money", b.dir == Dir.OUT && near(b.amount, 500.0) && near(b.fee, 5.0) && b.type == "send" && b.category == "transfer")
    val c = p("bKash", "Cash Out Tk 1,000.00 to 01XXXXXXXXX successful. Fee Tk 18.50. Balance Tk 500.00. TrxID CD99XX11YY at 13/11/2026 10:00")
    check("cash out parsed", c != null && c.dir == Dir.OUT && c.type == "cash_out" && near(c.fee, 18.5) && near(c.amount, 1000.0))
    val d = p("bKash", "Payment Tk 250.00 to Shwapno successful. Fee Tk 0.00. Balance Tk 1,585.50. TrxID PP11ZZ22 at 12/11/2026 18:05")!!
    check("payment party+category", d.party == "Shwapno" && d.category == "food" && d.type == "payment")
    val e = p("NAGAD", "Money Received. Amount: Tk 700.00 Sender: 01911223344 Ref: N7ABC12 TxnID: 7ABC1234 Balance: Tk 1,200.00 12/11/2026 09:15")!!
    check("nagad in", e.dir == Dir.IN && near(e.amount, 700.0) && e.provider == "Nagad" && near(e.balance, 1200.0))
    val f = p("16216", "Bill Payment Tk 1,850 to DESCO successful. Fee Tk 10. Balance Tk 3,000. TrxID BB77CC88 12-Nov-26 11:00 Rocket")!!
    check("rocket bill", f.category == "bills" && near(f.amount, 1850.0) && f.provider == "Rocket")
    val g = p("bKash", "আপনি ৫০০ টাকা পেয়েছেন 01712345678 থেকে। ফি ০ টাকা। ব্যালেন্স ২,০০০ টাকা। TrxID BN12345678")!!
    check("bangla sms", g.dir == Dir.IN && near(g.amount, 500.0) && near(g.balance, 2000.0) && g.currency == "BDT")
    val h = p("UCB", "Tk 2,500.00 debited from A/C ****1234 at ATM Gulshan on 12-Nov-26. Avl Bal Tk 10,000.00")!!
    check("bank debit", h.dir == Dir.OUT && near(h.amount, 2500.0) && near(h.balance, 10000.0) && h.provider == "Bank")
    val i = p("CityBank", "Tk 15,000.00 credited to A/C ****5678 salary. Avl Bal Tk 25,000.00")!!
    check("bank credit salary", i.dir == Dir.IN && i.category == "salary" && near(i.amount, 15000.0))
    val r = p("bKash", "Recharge Tk 100.00 to 01712345678 successful. Fee Tk 0.00. Balance Tk 900.00. TrxID RR55")!!
    check("recharge", r.category == "mobile" && near(r.amount, 100.0))
    check("otp ignored", p("bKash", "Your bKash verification code is 123456. Do not share.") == null)
    check("random sms ignored", p("Mom", "আজ বাজার করে আসিস") == null)
    check("promo without money ignored", p("bKash", "Dear customer enjoy cashback on your next payment") == null)
    val x = p("bKash", "Cash Out Tk 20.00 to 01711111111. Fee Tk 0.37. Balance Tk 50.00. TrxID X1Y2Z3A4")!!
    check("fee not amount", near(x.amount, 20.0) && near(x.fee, 0.37))

    // ---- the rest of the world
    val us = p("Chase", "Chase: You paid $42.50 to Starbucks on 11/12/2026 at 08:15 AM. Available balance $1,204.33.", "USD")!!
    check("us card", us.dir == Dir.OUT && near(us.amount, 42.5) && us.currency == "USD" && us.party == "Starbucks" && us.category == "food" && near(us.balance, 1204.33))
    check("us date mm/dd", us.timeMs == ms(2026, 11, 12, 8, 15))
    val paypal = p("PayPal", "You received $120.00 USD from Anna Smith. Transaction ID 5TY12345AB", "USD")!!
    check("paypal in", paypal.dir == Dir.IN && near(paypal.amount, 120.0) && paypal.currency == "USD" && paypal.provider == "PayPal" && paypal.party == "Anna Smith")
    val eu = p("Revolut", "Zahlung von 1.234,56 € an Amazon erfolgreich. Saldo: 2.000,00 €", "EUR")!!
    check("german eur", eu.dir == Dir.OUT && near(eu.amount, 1234.56) && eu.currency == "EUR" && eu.category == "shopping" && near(eu.balance, 2000.0))
    val inr = p("PAYTM", "Rs. 1,50,000.00 credited to your account via UPI from ramesh@okbank. UPI Ref 412345678901", "INR")!!
    check("india lakh", inr.dir == Dir.IN && near(inr.amount, 150000.0) && inr.currency == "INR")
    val mp = p("MPESA", "QJ12ABC345 Confirmed. Ksh2,500.00 sent to John Kamau 0712345678 on 12/11/26 at 3:30 PM. M-PESA balance is Ksh1,200.00", "KES")!!
    check("mpesa out", mp.dir == Dir.OUT && near(mp.amount, 2500.0) && mp.currency == "KES" && mp.provider == "M-Pesa" && near(mp.balance, 1200.0))
    val pix = p("Nubank", "Você recebeu um Pix de R$ 350,00 de Maria Souza. Saldo: R$ 1.020,40", "BRL")!!
    check("pix in", pix.dir == Dir.IN && near(pix.amount, 350.0) && pix.currency == "BRL" && pix.provider == "Pix" || pix.provider == "Nubank")
    val ar = p("STC", "تم خصم 150.00 SAR من حسابك لشراء من Jarir. رصيد 3,000.00 SAR", "SAR")!!
    check("arabic debit", ar.dir == Dir.OUT && near(ar.amount, 150.0) && ar.currency == "SAR")
    val es = p("BBVA", "BBVA: Compra por 89,90 EUR en Mercadona. Saldo disponible 1.500,00 EUR", "EUR")!!
    check("spanish purchase", es.dir == Dir.OUT && near(es.amount, 89.9) && es.currency == "EUR")
    val jp = p("PayPay", "PayPayで1,200円を支払いました。残高 8,000円", "JPY")!!
    check("japanese", jp.dir == Dir.OUT && near(jp.amount, 1200.0) && jp.currency == "JPY")
    check("money formats", Money.number("1,234.56") == 1234.56 && Money.number("1.234,56") == 1234.56 && Money.number("1 234,56") == 1234.56 && Money.number("1,50,000") == 150000.0 && Money.number("12,5") == 12.5 && Money.number("1.234") == 1234.0)
    check("digits of any script", Money.ascii("١٢٣٤") == "1234" && Money.ascii("१२३") == "123" && Money.ascii("๑๒") == "12")
    check("format", Money.format(1234.5, "USD") == "$1,234.50" && Money.format(10.0, "EUR") == "€10")
    check("word boundary: ing/sending", Providers.detect("x", "consenting sending") == "")

    // ================= Ledger
    val led = Ledger()
    check("add new", led.add(a) && led.add(b) && led.add(d) && led.add(e) && led.add(f))
    check("dedupe", !led.add(a))
    val from = ms(2026, 11, 12, 0, 0); val to = ms(2026, 11, 13, 0, 0)
    val s = led.summarize(from, to)
    check("summary totals", s.currency == "BDT" && near(s.income, 2200.0) && near(s.spent, 500.0 + 250.0 + 1850.0) && s.count == 5 && near(s.fees, 15.0))
    check("top category", s.byCategory.first().first == "bills")
    check("last balance", near(s.lastBalance, led.txns.maxByOrNull { it.timeMs }!!.balance))
    led.budgets["bills"] = 1500.0; led.budgets["food"] = 300.0
    val al = led.alerts(from, to)
    check("alerts", al.size == 2 && al.any { it.category == "bills" && it.over } && al.any { it.category == "food" && !it.over && it.percent >= 80 })
    // two currencies never mix
    led.add(us); led.add(paypal)
    val both = led.summaries(from, to)
    check("per currency", both.map { it.currency }.toSet() == setOf("BDT", "USD") && near(both.first { it.currency == "USD" }.spent, 42.5) && near(both.first { it.currency == "BDT" }.spent, 2600.0))
    led.budgets["food@USD"] = 40.0
    check("budget per currency", led.alerts(from, to).any { it.category == "food" && it.currency == "USD" && it.over })
    check("ledger json", Ledger.fromJson(led.toJson()).let { it.txns.size == 7 && it.budgets == led.budgets && near(it.summarize(from, to, "BDT").spent, s.spent) && it.txns.any { t -> t.currency == "USD" } })
    val facts = Report.facts(led, from, to, from, to)
    check("facts", (facts["per_currency"] as List<*>).size == 2 && (facts["alerts"] as List<*>).isNotEmpty())
    val (tx, sum) = Report.rows(led, from, to, { it }, dhaka)
    check("report rows", tx.size == 7 && tx[0][0] == "h_date" && tx[0].size == 10 && sum.any { it[0] == "h_total_spent" })
    check("report line", Report.line(us) { it }.contains("$42.50") && Report.line(us) { it }.contains("cat_food"))
    check("empty period", led.summarize(1, 2).count == 0)

    println("T2 passed=$passes failed=$fails"); if (fails > 0) System.exit(1)
}
