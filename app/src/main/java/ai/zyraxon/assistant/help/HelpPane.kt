package ai.zyraxon.assistant.help

import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Documentation (its own section). Every part of ZYRAXON explained chapter by chapter.
 * English and Bengali are written by hand and open at once; any other language is translated
 * by the user's own model the first time a chapter is opened and then kept on the phone.
 */
@Composable
fun HelpPane(viewModel: ZyraxonViewModel) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val languages = remember { Loc.loadLanguages(c) }
    var lang by remember { mutableStateOf(Loc.current) }
    var query by remember { mutableStateOf("") }
    val open = remember { mutableStateListOf<String>() }
    val docs = remember { mutableStateMapOf<String, HelpDoc>() }
    val state = remember { mutableStateMapOf<String, String>() }   // chapter id → "loading" | "failed"
    var bulk by remember { mutableStateOf(false) }

    fun languageName(): String = languages.firstOrNull { it.code == lang }?.name ?: lang

    suspend fun ensure(ch: HelpChapter) {
        if (docs.containsKey(ch.id) || state[ch.id] == "loading") return
        val ready = withContext(Dispatchers.IO) { HelpText.load(c, lang, ch) }
        if (ready != null) { docs[ch.id] = ready; return }
        val english = withContext(Dispatchers.IO) { HelpText.english(c, ch) } ?: return
        state[ch.id] = "loading"
        val source = english.title + "\n" + english.body
        val out = HelpTranslator.translate(languageName(), source) { viewModel.askLong(it) }
        if (out != null) {
            withContext(Dispatchers.IO) { HelpText.save(c, lang, ch, out) }
            docs[ch.id] = HelpText.parse(out)
            state.remove(ch.id)
        } else state[ch.id] = "failed"
    }

    // A new language starts from what is already on the phone.
    LaunchedEffect(lang) {
        docs.clear(); state.clear()
        HelpIndex.chapters.forEach { ch -> withContext(Dispatchers.IO) { HelpText.load(c, lang, ch) }?.let { docs[ch.id] = it } }
        // Every language gets the whole guide, with nothing to tap: a language that is
        // not hand-made is translated chapter after chapter in the background and each
        // chapter is kept on the phone the moment it is done, so a closed screen or a
        // lost connection only pauses the work and the next visit carries on from there.
        if (!HelpText.handMade(lang)) {
            bulk = true
            HelpIndex.chapters.forEach { ch -> if (!docs.containsKey(ch.id)) ensure(ch) }
            bulk = false
        }
    }
    // A chapter that is open is loaded, or translated, when it is opened.
    LaunchedEffect(lang, open.toList()) { open.toList().forEach { id -> HelpIndex.chapters.firstOrNull { it.id == id }?.let { ensure(it) } } }

    val english = remember { mutableStateMapOf<String, HelpDoc>() }
    LaunchedEffect(Unit) { HelpIndex.chapters.forEach { ch -> withContext(Dispatchers.IO) { HelpText.english(c, ch) }?.let { english[ch.id] = it } } }

    Column {
        Text(t("ডকুমেন্টেশন"), color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        Text(t("ZYRAXON-এর প্রতিটা বিষয় অধ্যায় ধরে। নিচে আপনার ভাষা বাছুন।"), color = Glass.textMuted, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))

        Text(t("ডকুমেন্টেশনের ভাষা"), color = Glass.textSecondary, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            languages.forEach { l ->
                val on = lang == l.code
                Box(
                    Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50))
                        .background(if (on) Glass.pink else Glass.rowFill(0.7f))
                        .clickable { lang = l.code }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) { Text(l.flag + " " + l.name, color = if (on) Glass.textOnAccent else Glass.textPrimary, fontSize = 11.5.sp) }
            }
        }
        if (!HelpText.handMade(lang)) {
            Text(
                t("এই ভাষায় পুরো গাইড আপনার মডেল নিজে থেকেই অনুবাদ করে ফোনে রেখে দেয়; একবার হলে পরের বার সঙ্গে সঙ্গে খোলে।") +
                    "  " + docs.size + "/" + HelpIndex.chapters.size,
                color = Glass.textMuted, fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Pill(if (bulk) t("অনুবাদ চলছে…") else t("বাকি অধ্যায় অনুবাদ করো")) {
                    if (!bulk) { bulk = true; state.clear(); scope.launch { HelpIndex.chapters.forEach { if (!docs.containsKey(it.id)) ensure(it) }; bulk = false } }
                }
                Spacer(Modifier.width(6.dp))
                Pill(t("আবার অনুবাদ করো")) { HelpText.forget(c, lang); docs.clear(); state.clear(); open.clear() }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.7f)).padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (query.isEmpty()) Text(t("খুঁজুন… (যেমন: অটোপাইলট, আনডু, পারমিশন)"), color = Glass.textMuted, fontSize = 12.sp)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Pill(t("সব খুলুন")) { open.clear(); open.addAll(HelpIndex.chapters.map { it.id }) }
            Spacer(Modifier.width(6.dp))
            Pill(t("সব বন্ধ করুন")) { open.clear() }
        }
        Spacer(Modifier.height(8.dp))

        val q = query.trim().lowercase()
        var shown = 0
        HelpIndex.chapters.forEach { ch ->
            val doc = docs[ch.id]
            val en = english[ch.id]
            val title = doc?.title ?: en?.title ?: ch.id
            if (q.isNotEmpty()) {
                val hay = ((doc?.title ?: "") + " " + (doc?.body ?: "") + " " + (en?.title ?: "") + " " + (en?.body ?: "")).lowercase()
                if (!hay.contains(q)) return@forEach
            }
            shown++
            val isOpen = open.contains(ch.id) || (q.isNotEmpty() && docs.containsKey(ch.id))
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.7f))
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { if (open.contains(ch.id)) open.remove(ch.id) else open.add(ch.id) }.padding(14.dp)
                ) {
                    Text(ch.icon + "  " + title, color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (isOpen) "▾" else "▸", color = Glass.textMuted, fontSize = 14.sp)
                }
                if (isOpen) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                        when {
                            doc != null -> HelpBody(doc.body)
                            state[ch.id] == "failed" -> {
                                Text(t("অনুবাদ করা গেল না। কী ও ইন্টারনেট দেখুন।"), color = Glass.red, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Pill(t("আবার চেষ্টা")) { state.remove(ch.id); scope.launch { ensure(ch) } }
                                en?.let { Spacer(Modifier.height(8.dp)); HelpBody(it.body) }
                            }
                            else -> {
                                Text(t("অনুবাদ হচ্ছে…"), color = Glass.textMuted, fontSize = 12.sp)
                                en?.let { Spacer(Modifier.height(8.dp)); HelpBody(it.body) }
                            }
                        }
                    }
                }
            }
        }
        if (shown == 0) Text(t("কিছু পাওয়া যায়নি।"), color = Glass.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Glass.pinkFill).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = Glass.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
}
