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
import kotlinx.coroutines.withContext

/**
 * Settings → Documentation (its own section). Every part of ZYRAXON explained chapter by chapter.
 * Every language the picker offers ships its chapters inside the app, so a chapter opens at once,
 * with no translation on the phone and nothing fetched.
 */
@Composable
fun HelpPane(viewModel: ZyraxonViewModel) {
    val c = LocalContext.current
    val languages = remember { Loc.loadLanguages(c) }
    var lang by remember { mutableStateOf(Loc.current) }
    var query by remember { mutableStateOf("") }
    val open = remember { mutableStateListOf<String>() }
    val docs = remember { mutableStateMapOf<String, HelpDoc>() }

    suspend fun ensure(ch: HelpChapter) {
        if (docs.containsKey(ch.id)) return
        // Every language the picker offers ships its chapters in the app, so this is a read.
        // The English text is the fallback for a chapter a language somehow lacks.
        val ready = withContext(Dispatchers.IO) { HelpText.load(c, lang, ch) ?: HelpText.english(c, ch) }
        ready?.let { docs[ch.id] = it }
    }

    // A new language starts from what is already on the phone.
    LaunchedEffect(lang) {
        docs.clear()
        HelpIndex.chapters.forEach { ch -> withContext(Dispatchers.IO) { HelpText.load(c, lang, ch) }?.let { docs[ch.id] = it } }
    }
    // A chapter that is open is loaded when it is opened.
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
                        if (doc != null) HelpBody(doc.body)
                        else en?.let { HelpBody(it.body) }
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
