package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.ui.theme.ZyThemeMode
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ThemePane(vm: ZyraxonViewModel) {
    val theme by vm.themeMode.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("থিম"), color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
        ZyThemeMode.values().forEach { m ->
            val sel = m == theme
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sel) Glass.pinkFill.copy(alpha = 0.2f) else Glass.rowFill(0.3f))
                    .border(1.dp, if (sel) Glass.pink else Glass.borderSoft, RoundedCornerShape(16.dp))
                    .clickable { vm.setThemeMode(m) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.icon, fontSize = 24.sp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, color = Glass.textPrimary, fontWeight = FontWeight.Bold)
                    Text(when (m) { ZyThemeMode.HORROR -> t("রক্ত, আগুন ও ছায়া — আসল ভয়ঙ্কর লুক"); ZyThemeMode.DARK -> t("পরিষ্কার কালচে গ্লাস, চোখে আরাম"); ZyThemeMode.LIGHT -> t("উজ্জ্বল ও পরিচ্ছন্ন গ্লাস") },
                        color = Glass.textMuted, fontSize = 11.5.sp)
                }
                if (sel) AppIcon(AppIcons.check, t("নির্বাচিত"), size = 16.dp, tint = Glass.pink)
            }
        }
    }
}

/** The voice changer. Every voice, gender, age and language comes from the website's endpoint. */
@Composable
fun VoiceLab(vm: ZyraxonViewModel) {
    val voices by vm.voices.collectAsState()
    val state by vm.voicesState.collectAsState()
    val picked by vm.voiceId.collectAsState()
    val age by vm.voiceAge.collectAsState()
    LaunchedEffect(Unit) { if (voices.isEmpty()) vm.loadVoices() }
    var gender by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("") }
    var ageF by remember { mutableStateOf("") }
    val langs = voices.map { it.locale.substringBefore('-').lowercase() }.filter { it.isNotBlank() }.distinct().sorted()
    val ages = voices.map { it.age }.filter { it.isNotBlank() }.distinct()
    val shown = voices.filter {
        (gender.isBlank() || it.gender.startsWith(gender)) &&
            (lang.isBlank() || it.locale.lowercase().startsWith(lang)) && (ageF.isBlank() || it.age == ageF)
    }.take(120)
    val current = voices.firstOrNull { it.id == picked }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("ভয়েস চেঞ্জার"), color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass.rowFill(0.3f)).border(1.dp, Glass.pink.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: t("ডিফল্ট কণ্ঠ"), color = Glass.textPrimary, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(current?.gender?.takeIf { it.isNotBlank() }, current?.age?.takeIf { it.isNotBlank() }, current?.locale?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Glass.textMuted, fontSize = 11.sp)
                if (picked.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.clearVoicePick() }.padding(top = 4.dp)) {
                    AppIcon(AppIcons.restart, null, size = 13.dp, tint = Glass.pink)
                    Spacer(Modifier.width(4.dp))
                    Text(t("ডিফল্টে ফেরো"), color = Glass.pink, fontSize = 11.5.sp)
                }
            }
        }
        FilterRow(t("লিঙ্গ"), listOf("" to t("সব"), "f" to t("মেয়ে"), "m" to t("ছেলে")), gender) { gender = it }
        if (ages.isNotEmpty()) FilterRow(t("বয়স"), listOf("" to t("সব")) + ages.map { it to when (it) { "child" -> t("শিশু"); "teen" -> t("কিশোর"); "adult" -> t("প্রাপ্তবয়স্ক"); "senior" -> t("বয়স্ক"); else -> it } }, ageF) { ageF = it }
        else FilterRow(t("বয়স"), listOf("" to t("ডিফল্ট"), "child" to t("শিশু"), "teen" to t("কিশোর"), "adult" to t("প্রাপ্তবয়স্ক"), "senior" to t("বয়স্ক")), age) { vm.setVoiceAge(it) }
        FilterRow(t("ভাষা"), listOf("" to t("সব")) + langs.map { it to langName(it) }, lang) { lang = it }
        when {
            state == "loading" -> Text(t("সার্ভার থেকে কণ্ঠ আনছি…"), color = Glass.textMuted)
            state.isNotBlank() -> Text(state, color = Glass.red, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadVoices() })
        }
        Text(t("{0}টি কণ্ঠ", shown.size), color = Glass.textMuted, fontSize = 11.sp)
        shown.forEach { v ->
            val sel = v.id == picked
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) Glass.pinkFill.copy(alpha = 0.22f) else Glass.rowFill(0.25f))
                    .border(1.dp, if (sel) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp)).clickable { vm.pickVoice(v) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(if (v.gender.startsWith("m")) AppIcons.person else AppIcons.female, null, size = 20.dp, tint = Glass.textPrimary); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(listOf(langName(v.locale.substringBefore('-').lowercase()), v.age, v.label).filter { it.isNotBlank() }.joinToString(" · "), color = Glass.textMuted, fontSize = 10.5.sp, maxLines = 1)
                }
                AppIcon(AppIcons.play, t("শুনো"), size = 18.dp, tint = Glass.pink)
            }
        }
        Text(t("একাধিক কণ্ঠে অডিও বানাতে এজেন্টকে বলো — সংলাপের প্রতিটা অংশে আলাদা কণ্ঠ, ভাষা ও বয়স সে নিজে বেছে নেয়।"), color = Glass.textMuted, fontSize = 11.sp)
    }
}

private fun langName(c: String) = when (c) {
    "bn" -> t("বাংলা"); "hi" -> t("হিন্দি"); "en" -> t("ইংরেজি"); "ur" -> t("উর্দু"); "ar" -> t("আরবি"); "ta" -> t("তামিল"); "ne" -> t("নেপালি")
    "es" -> t("স্প্যানিশ"); "fr" -> t("ফরাসি"); "de" -> t("জার্মান"); "ja" -> t("জাপানি"); "ko" -> t("কোরিয়ান"); "zh" -> t("চীনা"); "ru" -> t("রুশ"); "tr" -> t("তুর্কি")
    else -> c
}

@Composable
private fun FilterRow(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column {
        Text(title, color = Glass.textMuted, fontSize = 11.sp)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
            items(options.size) { i ->
                val (k, label) = options[i]
                val sel = k == selected
                Text(label, fontSize = 12.sp, color = if (sel) Color.White else Glass.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) Glass.pinkFill else Glass.rowFill(0.3f)).clickable { onPick(k) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
