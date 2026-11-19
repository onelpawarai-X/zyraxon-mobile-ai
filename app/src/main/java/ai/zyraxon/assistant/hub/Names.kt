package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.i18n.Loc

/**
 * The words of the money ledger and the call summary in the user's own language.
 * Ids are language-neutral; each id points at the text the translation files know it by.
 */
object Names {
    private val src = mapOf(
        "cat_bills" to "বিল", "cat_mobile" to "মোবাইল রিচার্জ", "cat_transport" to "যাতায়াত", "cat_food" to "খাবার", "cat_shopping" to "কেনাকাটা",
        "cat_education" to "শিক্ষা", "cat_health" to "স্বাস্থ্য", "cat_entertainment" to "বিনোদন", "cat_housing" to "বাসা ও ভাড়া", "cat_cash_out" to "ক্যাশ আউট",
        "cat_transfer" to "টাকা পাঠানো", "cat_refund" to "ফেরত", "cat_salary" to "বেতন", "cat_income" to "আয়", "cat_other" to "অন্যান্য",
        "type_cash_out" to "ক্যাশ আউট", "type_cash_in" to "ক্যাশ ইন", "type_send" to "পাঠানো", "type_bill" to "বিল পরিশোধ", "type_recharge" to "রিচার্জ",
        "type_payment" to "পেমেন্ট", "type_refund" to "ফেরত", "type_income" to "আয়", "type_expense" to "খরচ",
        "h_date" to "তারিখ", "h_provider" to "মাধ্যম", "h_type" to "ধরন", "h_party" to "কার সাথে", "h_category" to "ক্যাটাগরি", "h_in" to "আয়", "h_out" to "খরচ",
        "h_fee" to "ফি", "h_balance" to "ব্যালেন্স", "h_currency" to "মুদ্রা", "h_total_spent" to "মোট খরচ", "h_total_income" to "মোট আয়", "h_total_fees" to "মোট ফি", "h_budget" to "বাজেট",
        "sheet_transactions" to "লেনদেন", "sheet_summary" to "সারাংশ",
        "call_title" to "📞 কল সারাংশ", "call_who" to "কে", "call_no_name" to "নাম বলেননি", "call_no_number" to "নম্বর অজানা", "call_spam" to "স্প্যাম/বিজ্ঞাপন মনে হচ্ছে",
        "call_urgent" to "জরুরি: হ্যাঁ", "call_wants" to "কী চান", "call_unclear" to "স্পষ্ট বলেননি", "call_msg" to "বার্তা", "call_time" to "সময়", "call_min" to "মিনিট", "call_sec" to "সেকেন্ড",
        "call_talk" to "কথোপকথন", "call_caller" to "কলার", "call_agent" to "সহকারী"
    )

    /** For code that runs without the app's screen (a receiver): loads the language the user chose, if it is not loaded yet. */
    fun prepare(context: android.content.Context) {
        val saved = ai.zyraxon.assistant.i18n.savedLanguage(context) ?: return
        if (Loc.current != saved) runCatching { Loc.load(context, saved) }
    }

    /** [key] is an id from the table above or any text the translation files already know. */
    fun of(key: String): String = Loc.t(src[key] ?: key)

    /** Works as the `t` of [ai.zyraxon.assistant.world.CallSummary.text]: unknown keys come back unchanged. */
    val callWords: (String) -> String = { k -> if (src.containsKey(k)) Loc.t(src.getValue(k)) else k }

    val allSources: Collection<String> get() = src.values
}
