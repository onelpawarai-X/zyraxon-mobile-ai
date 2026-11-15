package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.auto.AutoTools
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.DeviceBridge
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun HubSkillsTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(HumanRecorder.recording) }
    var steps by remember { mutableStateOf(0) }
    var accessOn by remember { mutableStateOf(HubApi.accessibilityOn(c)) }
    var literal by remember { mutableStateOf("") }
    var paramName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val runArgs = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(tick) { rows = HubApi.list(HubApi.call(c, "skill_list")) }
    LaunchedEffect(recording) { while (recording) { steps = HumanRecorder.count; accessOn = HubApi.accessibilityOn(c); delay(700) } }

    HubCard {
        HubTitle(t("স্কিল শেখানো"))
        HubBody(t("ZYRAXON-কে একটা কাজ শেখাতে পারেন দুইভাবে: চ্যাটে বলুন 'এই কাজটা শিখে নাও', অথবা নিচের বাটনে নিজে হাতে করে দেখান। শেখার পর যেকোনো সময় এক ট্যাপে বা কথায় সেটা নিজে চলবে।"))
        HubGap()
        if (!accessOn) {
            HubBody(t("আগে অ্যাক্সেসিবিলিটি সার্ভিস চালু করতে হবে, নইলে আপনার ট্যাপ দেখতে পাবে না।"), muted = false)
            HubGap(6); HubButton(t("অ্যাক্সেসিবিলিটি চালু করুন"), { HubApi.openAccessibilitySettings(c) })
        } else if (!recording) {
            HubField(name, { name = it }, t("স্কিলের নাম (যেমন: রোজের মেসেজ)"))
            HubGap(6)
            HubButton(t("● নিজে করে শেখান"), {
                if (name.isBlank()) message = t("আগে স্কিলের একটা নাম দিন")
                else if (HumanRecorder.start(name)) {
                    recording = true; message = ""
                    DeviceBridge.notify(c, t("স্কিল রেকর্ড চলছে"), t("যে কাজটা শেখাতে চান সেটা হাতে করুন, তারপর ZYRAXON-এ ফিরে Stop চাপুন।"))
                    HubApi.goHome(c)
                }
            })
        } else {
            HubBody("● " + t("রেকর্ড চলছে") + " — " + steps + " " + t("ধাপ"), muted = false)
            HubBody(t("এখন যে অ্যাপে কাজটা করতে চান সেখানে গিয়ে হাতে করুন। শেষ হলে ZYRAXON-এ ফিরে এসে Stop চাপুন।"))
            HubGap(6)
            HubField(literal, { literal = it }, t("প্যারামিটার: যে লেখাটা বদলাবে (যেমন নম্বর) — ঐচ্ছিক"))
            HubField(paramName, { paramName = it }, t("তার নাম (যেমন number) — ঐচ্ছিক"))
            HubGap(6)
            Row {
                HubButton(t("Stop ও সেভ"), {
                    var sk = HumanRecorder.stop()
                    recording = false
                    if (sk == null) message = t("কোনো ধাপ ধরা পড়েনি")
                    else {
                        if (literal.isNotBlank() && paramName.isNotBlank()) sk = sk.parameterize(literal, paramName.trim())
                        AutoTools.saveSkill(c, sk); message = t("স্কিল সেভ হয়েছে") + ": " + sk.name + " (" + sk.steps.size + " " + t("ধাপ") + ")"; name = ""; literal = ""; paramName = ""; tick++
                    }
                })
                Spacer(Modifier.width(8.dp))
                HubButton(t("বাতিল"), { HumanRecorder.cancel(); recording = false }, primary = false)
            }
        }
        if (message.isNotBlank()) { HubGap(6); HubBody(message, muted = false) }
    }

    HubGap(4)
    HubTitle(t("শেখা স্কিল"))
    if (rows.isEmpty()) HubEmpty(t("এখনো কোনো স্কিল নেই"))
    rows.forEach { r ->
        val sn = r.optString("name"); val params: JSONArray = r.optJSONArray("params") ?: JSONArray()
        HubCard {
            HubTitle(sn)
            HubBody(r.optInt("steps").toString() + " " + t("ধাপ") + " · " + t("চলেছে") + " " + r.optInt("runs") + " " + t("বার") + (if (r.optString("app").isNotBlank()) " · " + r.optString("app") else ""))
            if (r.optString("description").isNotBlank()) HubBody(r.optString("description"))
            for (i in 0 until params.length()) { val pn = params.optString(i); HubField(runArgs[sn + "/" + pn].orEmpty(), { runArgs[sn + "/" + pn] = it }, pn) }
            HubGap(6)
            Row {
                HubButton(t("চালাও"), {
                    scope.launch {
                        val a = JSONObject(); for (i in 0 until params.length()) a.put(params.optString(i), runArgs[sn + "/" + params.optString(i)].orEmpty())
                        HubApi.goHome(c)
                        val res = HubApi.call(c, "skill_run", "name" to sn, "args" to a)
                        Toast.makeText(c, res.optString("summary"), Toast.LENGTH_LONG).show(); tick++
                    }
                })
                Spacer(Modifier.width(6.dp))
                HubButton(t("শেয়ার"), { HubApi.shareFile(c, AutoTools.skillFile(c, sn)) }, primary = false)
                Spacer(Modifier.width(6.dp))
                HubButton(t("মুছুন"), { scope.launch { HubApi.call(c, "skill_delete", "name" to sn); tick++ } }, primary = false)
            }
        }
    }
}
