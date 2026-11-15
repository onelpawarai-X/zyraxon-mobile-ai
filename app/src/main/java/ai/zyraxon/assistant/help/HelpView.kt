package ai.zyraxon.assistant.help

import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Turns a line with `code` spans into styled text: the spans are shown in a monospace face. */
private fun styled(line: String, code: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var open = false
    line.split('`').forEach { part ->
        if (open) withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code)) { append(part) } else append(part)
        open = !open
    }
}

/** Draws a chapter body: "## " headings, "- " bullets and plain paragraphs. */
@Composable
fun HelpBody(body: String) {
    Column {
        body.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(8.dp))
                    Text(line.removePrefix("## "), color = Glass.pink, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Spacer(Modifier.height(3.dp))
                }
                line.startsWith("- ") -> Row(Modifier.padding(start = 4.dp, bottom = 3.dp)) {
                    Text("•", color = Glass.textMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(styled(line.removePrefix("- "), Glass.cyan), color = Glass.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
                else -> Text(styled(line, Glass.cyan), color = Glass.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
