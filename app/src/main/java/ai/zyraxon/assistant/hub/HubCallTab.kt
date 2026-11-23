package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.auto.AutoTools
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.world.LangPack
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun HubCallTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(AutoTools.prefs(c).getBoolean("call_agent", false)) }
    var owner by remember { mutableStateOf(AutoTools.prefs(c).getString("owner", "").orEmpty()) }
    var delay by remember { mutableStateOf("6") }
    var info by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { logs = HubApi.list(HubApi.call(c, "call_log", "limit" to 10)) }
    val fast = LangPack.forLanguage(Loc.current) != null

    HubCard {
        HubTitle(t("কল-এজেন্ট"))
        HubBody(t("আপনি ফোন ধরতে না পারলে ZYRAXON কল ধরে, কলারের নিজের ভাষায় কথা বলে, নাম ও কারণ জেনে নেয়, কল শেষে আপনাকে সারাংশ দেয়।"))
        HubBody(if (fast) t("আপনার ভাষায় দ্রুত বিল্ট-ইন নিয়মে চলবে।") else t("আপনার ভাষায় এআই মডেল কথা বলবে (একটু ধীর হতে পারে)।"))
        HubGap(6)
        HubField(owner, { owner = it }, t("আপনার নাম (কলারকে যা বলবে)"))
        HubField(delay, { delay = it.filter { ch -> ch.isDigit() }.take(2) }, t("কত সেকেন্ড পরে ধরবে (আগে আপনি ধরার সুযোগ পাবেন)"))
        HubGap(6)
        androidx.compose.foundation.layout.Row {
            HubButton(if (on) t("বন্ধ করুন") else t("চালু করুন"), {
                scope.launch {
                    val r = HubApi.call(c, "call_agent", "action" to if (on) "off" else "on", "owner" to owner, "answer_after_seconds" to (delay.toIntOrNull() ?: 6))
                    on = HubApi.obj(r).optBoolean("enabled"); info = r.optString("summary")
                    val needs = HubApi.obj(r).optJSONArray("needs"); if (needs != null && needs.length() > 0) info += " — " + t("ফোন ও মাইক্রোফোন অনুমতি লাগবে (সেটিংস → পারমিশন)")
                }
            })
        }
        HubGap(6); HubBody((if (on) "● " + t("চালু আছে") else "○ " + t("বন্ধ আছে")), muted = false)
        if (info.isNotBlank()) HubBody(info)
    }
    HubTitle(t("ধরা কলের সারাংশ"))
    if (logs.isEmpty()) HubEmpty(t("এখনো কোনো কল ধরা হয়নি"))
    logs.forEach { l -> HubCard { HubBody(l.optString("text"), muted = false) } }
}
