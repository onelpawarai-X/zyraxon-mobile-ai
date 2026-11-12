package ai.zyraxon.assistant.present

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.theme.Glass
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One page of a spoken presentation: what is shown and what the character says. */
data class PresentSlide(val title: String, val bullets: List<String>, val speech: String)

/** The running presentation. The agent's `present_slides` tool fills it; the overlay plays it. */
object Presentation {
    private val _slides = MutableStateFlow<List<PresentSlide>>(emptyList())
    val slides: StateFlow<List<PresentSlide>> = _slides.asStateFlow()
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    fun parse(arr: JSONArray?): List<PresentSlide> {
        val out = ArrayList<PresentSlide>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val b = o.optJSONArray("bullets")
            val bullets = (0 until (b?.length() ?: 0)).map { b!!.optString(it).trim() }.filter { it.isNotEmpty() }
            val speech = o.optString("speech").ifBlank { o.optString("notes") }.trim()
                .ifBlank { (listOf(title) + bullets).filter { it.isNotBlank() }.joinToString(". ") }
            if (title.isNotEmpty() || bullets.isNotEmpty()) out += PresentSlide(title, bullets, speech)
        }
        return out
    }

    fun start(list: List<PresentSlide>, auto: Boolean = true) {
        _index.value = 0; _auto.value = auto; _slides.value = list
    }
    fun next() { if (_index.value < _slides.value.size - 1) _index.value += 1 else close() }
    fun prev() { if (_index.value > 0) _index.value -= 1 }
    fun toggleAuto() { _auto.value = !_auto.value }
    fun close() { _slides.value = emptyList(); _index.value = 0 }
}

/**
 * The slide card over the home screen: the 3D character stays visible above it and speaks each
 * slide's script; when the voice finishes, the next slide opens by itself.
 */
@Composable
fun PresentationOverlay(vm: ZyraxonViewModel) {
    val slides by Presentation.slides.collectAsState()
    val index by Presentation.index.collectAsState()
    val auto by Presentation.auto.collectAsState()
    val speaking by vm.isSpeaking.collectAsState()
    if (slides.isEmpty()) return
    val i = index.coerceIn(0, slides.size - 1)
    val s = slides[i]

    LaunchedEffect(slides) { vm.setTab(ai.zyraxon.assistant.model.AppTab.HOME) }
    LaunchedEffect(slides, i) {
        vm.stopSpeaking()
        delay(350)
        vm.speak(s.speech)
        // Wait for the voice to start, then for it to end, before moving on.
        val started = withTimeoutOrNull(6_000) { snapshotFlow { speaking }.first { it } } != null
        if (started) withTimeoutOrNull(180_000) { snapshotFlow { speaking }.first { !it } }
        if (auto) {
            delay(if (started) 900 else 4_000)
            if (Presentation.auto.value && Presentation.slides.value.isNotEmpty() && Presentation.index.value == i) Presentation.next()
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.52f).padding(horizontal = 10.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp)).background(Glass.fill(0.9f))
                .border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp)).padding(16.dp)
        ) {
            Text("${i + 1} / ${slides.size}", color = Glass.textMuted, fontSize = 11.sp)
            Text(s.title, color = Glass.pink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.bullets.take(6).forEach { Text("•  $it", color = Glass.textPrimary, fontSize = 15.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn(t("আগের স্লাইড")) { Presentation.prev() }
                Btn(if (auto) t("অটো চালু") else t("অটো বন্ধ")) { Presentation.toggleAuto() }
                Btn(if (i == slides.size - 1) t("প্রেজেন্টেশন শেষ") else t("পরের স্লাইড")) { Presentation.next() }
                Spacer(Modifier.weight(1f))
                Btn(t("প্রেজেন্টেশন বন্ধ")) { Presentation.close() }
            }
        }
    }
}

@Composable
private fun Btn(label: String, onClick: () -> Unit) {
    Text(
        label, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.35f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
