package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.components.AvatarRoster
import ai.zyraxon.assistant.ui.components.AvatarView
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
        Text("🎨 থিম", color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
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
                    Text(when (m) { ZyThemeMode.HORROR -> "রক্ত, আগুন ও ছায়া — আসল ভয়ঙ্কর লুক"; ZyThemeMode.DARK -> "পরিষ্কার কালচে গ্লাস, চোখে আরাম"; ZyThemeMode.LIGHT -> "উজ্জ্বল ও পরিচ্ছন্ন গ্লাস" },
                        color = Glass.textMuted, fontSize = 11.5.sp)
                }
                if (sel) Text("✓", color = Glass.pink, fontWeight = FontWeight.Bold)
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
    // The face that matches the voice: nearest age and same gender among the seven people.
    val wantAge = when ((current?.age ?: age).ifBlank { "adult" }) { "child", "kid" -> 10; "teen" -> 17; "senior", "old", "elder" -> 62; else -> 28 }
    val female = (current?.gender ?: "female").startsWith("f")
    val face = AvatarRoster.filter { it.female == female }.minByOrNull { kotlin.math.abs(it.age - wantAge) } ?: AvatarRoster.first()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🎙 ভয়েস চেঞ্জার", color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass.rowFill(0.3f)).border(1.dp, Glass.pink.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                AvatarView(style = face.id, onClick = { vm.speak("হ্যালো") }, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(face.name + " · " + face.age + " বছর", color = Glass.textPrimary, fontWeight = FontWeight.Bold)
                Text(current?.name ?: "ডিফল্ট কণ্ঠ", color = Glass.textSecondary, fontSize = 12.sp)
                Text(listOfNotNull(current?.gender?.takeIf { it.isNotBlank() }, current?.age?.takeIf { it.isNotBlank() }, current?.locale?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Glass.textMuted, fontSize = 11.sp)
                if (picked.isNotBlank()) Text("↺ ডিফল্টে ফেরো", color = Glass.pink, fontSize = 11.5.sp, modifier = Modifier.clickable { vm.clearVoicePick() }.padding(top = 4.dp))
            }
        }
        FilterRow("লিঙ্গ", listOf("" to "সব", "f" to "মেয়ে", "m" to "ছেলে"), gender) { gender = it }
        if (ages.isNotEmpty()) FilterRow("বয়স", listOf("" to "সব") + ages.map { it to when (it) { "child" -> "শিশু"; "teen" -> "কিশোর"; "adult" -> "প্রাপ্তবয়স্ক"; "senior" -> "বয়স্ক"; else -> it } }, ageF) { ageF = it }
        else FilterRow("বয়স", listOf("" to "ডিফল্ট", "child" to "শিশু", "teen" to "কিশোর", "adult" to "প্রাপ্তবয়স্ক", "senior" to "বয়স্ক"), age) { vm.setVoiceAge(it) }
        FilterRow("ভাষা", listOf("" to "সব") + langs.map { it to langName(it) }, lang) { lang = it }
        when {
            state == "loading" -> Text("⏳ সার্ভার থেকে কণ্ঠ আনছি…", color = Glass.textMuted)
            state.isNotBlank() -> Text(state + "  ↻", color = Glass.red, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadVoices() })
        }
        Text("${shown.size}টি কণ্ঠ", color = Glass.textMuted, fontSize = 11.sp)
        shown.forEach { v ->
            val sel = v.id == picked
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) Glass.pinkFill.copy(alpha = 0.22f) else Glass.rowFill(0.25f))
                    .border(1.dp, if (sel) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp)).clickable { vm.pickVoice(v) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (v.gender.startsWith("m")) "👨" else "👩", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(listOf(langName(v.locale.substringBefore('-').lowercase()), v.age, v.label).filter { it.isNotBlank() }.joinToString(" · "), color = Glass.textMuted, fontSize = 10.5.sp, maxLines = 1)
                }
                Text("▶", color = Glass.pink)
            }
        }
        Text("একাধিক কণ্ঠে অডিও বানাতে এজেন্টকে বলো — সংলাপের প্রতিটা অংশে আলাদা কণ্ঠ, ভাষা ও বয়স সে নিজে বেছে নেয়।", color = Glass.textMuted, fontSize = 11.sp)
    }
}

private fun langName(c: String) = when (c) {
    "bn" -> "বাংলা"; "hi" -> "হিন্দি"; "en" -> "ইংরেজি"; "ur" -> "উর্দু"; "ar" -> "আরবি"; "ta" -> "তামিল"; "ne" -> "নেপালি"
    "es" -> "স্প্যানিশ"; "fr" -> "ফরাসি"; "de" -> "জার্মান"; "ja" -> "জাপানি"; "ko" -> "কোরিয়ান"; "zh" -> "চীনা"; "ru" -> "রুশ"; "tr" -> "তুর্কি"
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
