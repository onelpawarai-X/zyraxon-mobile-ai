package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).padding(12.dp),
        content = content
    )
}

@Composable fun HubTitle(text: String) { Text(text, color = Glass.cyanFill, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
@Composable fun HubBody(text: String, muted: Boolean = true) { Text(text, color = if (muted) Glass.textSecondary else Glass.textPrimary, fontSize = 12.sp, lineHeight = 18.sp) }
@Composable fun HubGap(h: Int = 8) { Spacer(Modifier.height(h.dp)) }

@Composable
fun HubButton(label: String, onClick: () -> Unit, primary: Boolean = true, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val bg = if (!enabled) Color.White.copy(alpha = 0.04f) else if (primary) Glass.cyanFill.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f)
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg).clickable(enabled = enabled) { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (enabled) Glass.textPrimary else Glass.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun HubField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = singleLine,
        textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), modifier = modifier
    )
}

@Composable
fun HubChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Glass.cyanFill.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = if (selected) Glass.textPrimary else Glass.textSecondary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
fun HubEmpty(text: String) { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Text(text, color = Glass.textSecondary, fontSize = 12.sp) } }
