package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.auto.AutoTools
import ai.zyraxon.assistant.i18n.t
import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HubMoneyTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("week") }
    var report by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("food") }
    var limit by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(period, tick) { report = HubApi.obj(HubApi.call(c, "ledger_report", "period" to period)) }

    HubCard {
        HubTitle(t("হিসাব"))
        HubBody(t("যেকোনো দেশের ব্যাংক বা ওয়ালেটের (PayPal, M-Pesa, UPI, Pix, bKash, কার্ড…) SMS পড়ে আপনার আয়, খরচ, ফি আর ব্যালেন্স হিসাব করে। প্রতিটি মুদ্রা আলাদা থাকে, মিশে যায় না।"))
        HubGap(6)
        Row {
            HubButton(t("পুরনো SMS থেকে হিসাব তোলো"), {
                if (androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) info = t("SMS অনুমতি দিন (সেটিংস → পারমিশন)")
                else scope.launch { busy = true; info = HubApi.text(HubApi.call(c, "ledger_scan", "days" to 60, "auto" to true)); busy = false; tick++ }
            }, enabled = !busy)
        }
        if (info.isNotBlank()) { HubGap(6); HubBody(info, muted = false) }
    }

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("today" to t("আজ"), "yesterday" to t("গতকাল"), "week" to t("৭ দিন"), "month" to t("এই মাস"), "all" to t("সব")).forEach { (k, l) -> HubChip(l, period == k) { period = k } }
    }
    HubGap(4)
    val r = report
    val list = r?.optJSONArray("per_currency")
    if (r == null || list == null || list.length() == 0) HubEmpty(t("এই সময়ে কোনো লেনদেন নেই"))
    else for (i in 0 until list.length()) {
        val s = list.getJSONObject(i)
        HubCard {
            HubTitle(s.optString("currency").ifBlank { "—" } + " · " + s.optInt("count") + " " + t("লেনদেন"))
            HubBody(t("আয়") + ": " + s.optString("income_text") + "   " + t("খরচ") + ": " + s.optString("spent_text"), muted = false)
            HubBody(t("ফি") + ": " + s.optString("fees_text") + (if (s.optDouble("balance") > 0) "   " + t("ব্যালেন্স") + ": " + s.optString("balance_text") else ""))
            val cats = s.optJSONArray("by_category")
            if (cats != null) for (j in 0 until minOf(cats.length(), 6)) { val x = cats.getJSONObject(j); HubBody("• " + Names.of("cat_" + x.optString("category")) + ": " + x.optString("text")) }
        }
    }
    val alerts = r?.optJSONArray("alerts")
    if (alerts != null && alerts.length() > 0) HubCard {
        HubTitle(t("বাজেট সতর্কতা"))
        for (i in 0 until alerts.length()) { val a = alerts.getJSONObject(i); HubBody((if (a.optBoolean("over")) "⚠ " else "• ") + Names.of("cat_" + a.optString("category")) + ": " + a.optString("used_text") + " / " + a.optString("limit_text") + " (" + a.optInt("percent") + "%)", muted = false) }
    }

    HubCard {
        HubTitle(t("বাজেট ঠিক করুন"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("food", "bills", "transport", "shopping", "mobile", "health", "education", "entertainment", "housing").forEach { k -> HubChip(Names.of("cat_$k"), cat == k) { cat = k } }
        }
        HubField(limit, { limit = it }, t("মাসে সর্বোচ্চ (০ দিলে বাদ)"))
        HubField(cur, { cur = it.uppercase() }, t("মুদ্রা কোড (যেমন USD) — ঐচ্ছিক"))
        HubGap(6)
        HubButton(t("সেভ"), { scope.launch { HubApi.call(c, "ledger_budget", "category" to cat, "limit" to (limit.toDoubleOrNull() ?: 0.0), "currency" to cur); info = t("বাজেট সেভ হয়েছে"); tick++ } }, enabled = limit.isNotBlank())
    }
    HubButton(t("Excel রিপোর্ট বানান"), {
        scope.launch {
            val res = HubApi.call(c, "ledger_report", "period" to period, "format" to "xlsx")
            val p = HubApi.obj(res).optString("path"); if (p.isNotBlank()) HubApi.shareFile(c, File(p), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") else info = res.optString("summary")
        }
    }, primary = false)
}
