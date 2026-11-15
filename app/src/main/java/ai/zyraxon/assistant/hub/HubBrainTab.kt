package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.i18n.t
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HubBrainTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    HubCard {
        HubTitle(t("ব্রেন"))
        HubBody(t("আপনার ফাইল (টেক্সট, Word, Excel, PowerPoint, CSV) আর চাইলে SMS ইন্টারনেট ছাড়াই খুঁজে দেয়, যেকোনো ভাষায়। আগে একবার 'ইনডেক্স করুন' চাপুন, নতুন ফাইল এলে আবার।"))
        HubGap(6)
        Row {
            HubButton(t("ফাইল ইনডেক্স করুন"), { scope.launch { busy = true; info = HubApi.text(HubApi.call(c, "brain_index")); busy = false } }, enabled = !busy)
            Spacer(Modifier.width(6.dp))
            HubButton(t("ফাইল + SMS"), { scope.launch { busy = true; info = HubApi.text(HubApi.call(c, "brain_index", "sms" to true)); busy = false } }, primary = false, enabled = !busy)
        }
        if (info.isNotBlank()) { HubGap(6); HubBody(info, muted = false) }
    }
    HubField(q, { q = it }, t("কী খুঁজছেন? (যেমন: বাড়ি ভাড়ার চুক্তি)"))
    HubGap(6)
    HubButton(t("খুঁজুন"), { scope.launch { busy = true; results = HubApi.list(HubApi.call(c, "brain_search", "query" to q, "limit" to 8)); searched = true; busy = false } }, enabled = q.isNotBlank() && !busy)
    HubGap(6)
    if (searched && results.isEmpty()) HubEmpty(t("কিছু পাওয়া যায়নি"))
    results.forEach { r ->
        HubCard {
            val path = r.optString("where")
            HubTitle(r.optString("title").ifBlank { path.substringAfterLast('/') })
            HubBody(r.optString("snippet").take(240), muted = false)
            if (path.startsWith("/") && File(path).isFile) { HubGap(6); HubButton(t("খুলুন"), { HubApi.openFile(c, File(path)) }, primary = false) }
        }
    }
}
