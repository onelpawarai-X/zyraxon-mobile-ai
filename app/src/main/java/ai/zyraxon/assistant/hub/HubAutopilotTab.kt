package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.auto.AutoTools
import ai.zyraxon.assistant.i18n.t
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HubAutopilotTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf("sms") }
    var title by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var words by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("08:00") }
    var minutes by remember { mutableStateOf("60") }
    var below by remember { mutableStateOf("15") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(tick) { rows = HubApi.list(HubApi.call(c, "autopilot_list")) }

    HubCard {
        HubTitle(t("অটোপাইলট"))
        HubBody(t("'কিছু ঘটলে এটা করো' ধরনের নিয়ম। ঘটনা ঘটলে ZYRAXON নিজে জেগে উঠে কাজটা করে, আপনাকে জিজ্ঞেস করে না। চ্যাটে বললেও নিয়ম বানাতে পারে।"))
        HubGap()
        HubBody(t("কখন?"), muted = false)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("sms" to t("SMS এলে"), "notification" to t("নোটিফিকেশন এলে"), "call" to t("কল এলে"), "daily" to t("প্রতিদিন"), "interval" to t("প্রতি কয়েক মিনিটে"), "battery" to t("ব্যাটারি কমলে"), "app" to t("অ্যাপ খুললে"))
                .forEach { (k, l) -> HubChip(l, kind == k) { kind = k } }
        }
        HubGap(6)
        when (kind) {
            "sms", "call" -> HubField(from, { from = it }, t("কার কাছ থেকে (নাম বা নম্বর) — ঐচ্ছিক"))
            "notification", "app" -> HubField(from, { from = it }, t("অ্যাপের নাম বা প্যাকেজ"))
            "daily" -> HubField(time, { time = it }, t("সময় (HH:MM)"))
            "interval" -> HubField(minutes, { minutes = it }, t("কত মিনিট পরপর"))
            "battery" -> HubField(below, { below = it }, t("কত শতাংশের নিচে"))
        }
        if (kind == "sms" || kind == "notification") HubField(words, { words = it }, t("এই শব্দগুলোর যেকোনো একটা থাকলে (কমা দিয়ে) — ঐচ্ছিক"))
        HubField(task, { task = it }, t("তখন কী করবে?"), singleLine = false)
        HubField(title, { title = it }, t("নিয়মের নাম — ঐচ্ছিক"))
        HubGap(6)
        HubButton(t("নিয়ম যোগ করুন"), {
            scope.launch {
                val args = mutableListOf<Pair<String, Any?>>("task" to task, "kind" to kind, "title" to title)
                if (from.isNotBlank() && kind != "daily" && kind != "interval" && kind != "battery") args += (if (kind == "notification" || kind == "app") "pkg" else "from") to from.trim()
                if (words.isNotBlank()) args += "any" to org.json.JSONArray(words.split(',', '،', '、').map { it.trim() }.filter { it.isNotEmpty() })
                if (kind == "daily") args += "time" to time
                if (kind == "interval") args += "every_minutes" to (minutes.toIntOrNull() ?: 60)
                if (kind == "battery") args += "below" to (below.toIntOrNull() ?: 15)
                val r = HubApi.call(c, "autopilot_add", *args.toTypedArray())
                message = r.optString("summary")
                val needs = HubApi.obj(r).optJSONArray("needs")
                if (needs != null && needs.length() > 0) { message += " — " + t("কিছু অনুমতি লাগবে") }
                if (r.optBoolean("ok")) { task = ""; title = ""; tick++ }
            }
        }, enabled = task.isNotBlank())
        if (message.isNotBlank()) { HubGap(6); HubBody(message, muted = false) }
        HubGap(6)
        Row {
            HubButton(t("নোটিফিকেশন অ্যাক্সেস"), { HubApi.openListenerSettings(c) }, primary = false)
        }
    }

    HubTitle(t("আপনার নিয়মগুলো"))
    if (rows.isEmpty()) HubEmpty(t("এখনো কোনো নিয়ম নেই"))
    rows.forEach { r ->
        HubCard {
            HubBody(r.optString("rule"), muted = false)
            HubBody(t("চলেছে") + " " + r.optInt("fired") + " " + t("বার"))
            HubGap(6)
            Row {
                HubButton(if (r.optBoolean("enabled")) t("বন্ধ করুন") else t("চালু করুন"), { scope.launch { HubApi.call(c, "autopilot_toggle", "id" to r.optString("id"), "enabled" to !r.optBoolean("enabled")); tick++ } }, primary = false)
                Spacer(Modifier.width(6.dp))
                HubButton(t("মুছুন"), { scope.launch { HubApi.call(c, "autopilot_remove", "id" to r.optString("id")); tick++ } }, primary = false)
            }
        }
    }
}
