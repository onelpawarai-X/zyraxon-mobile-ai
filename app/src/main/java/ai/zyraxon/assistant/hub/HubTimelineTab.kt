package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.i18n.t
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HubTimelineTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(tick) { rows = HubApi.list(HubApi.call(c, "timeline_list", "limit" to 40)) }

    HubCard {
        HubTitle(t("টাইমলাইন ও আনডু"))
        HubBody(t("ZYRAXON যা যা করেছে সব এখানে লেখা থাকে। ফাইল লেখা, বদল বা মোছা এক বাটনে ফিরিয়ে আনা যায়। কমান্ড ফেরানো যায় না।"))
        if (message.isNotBlank()) { HubGap(6); HubBody(message, muted = false) }
    }
    if (rows.isEmpty()) HubEmpty(t("এখনো কিছু করা হয়নি"))
    rows.forEach { r ->
        HubCard {
            HubBody(r.optString("time") + " · " + r.optString("tool") + (if (!r.optBoolean("ok")) " ✗" else ""))
            HubBody(r.optString("what"), muted = false)
            if (r.optBoolean("undone")) HubBody(t("ফেরানো হয়েছে"))
            else if (r.optBoolean("undoable")) {
                HubGap(6)
                Row {
                    HubButton(t("এটা ফেরান"), { scope.launch { message = HubApi.text(HubApi.call(c, "timeline_undo", "id" to r.optLong("id"))); tick++ } }, primary = false)
                    Spacer(Modifier.width(6.dp))
                    HubButton(t("এখান থেকে সব ফেরান"), { scope.launch { message = HubApi.text(HubApi.call(c, "timeline_undo", "since_id" to r.optLong("id"))); tick++ } }, primary = false)
                }
            }
        }
    }
}
