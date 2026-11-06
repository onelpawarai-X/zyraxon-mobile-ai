package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel

/**
 * The one thing asked of a new user: their ZYRAXON key.
 *
 * Shown once, over everything, on the first launch that has no key. It is asked
 * for here rather than buried in settings because without it the agent has no
 * brain — a new user would otherwise open the app, send a message, and get a
 * failure that looks like the app is broken.
 *
 * The key is verified before the prompt closes. A typo therefore fails here, in
 * front of the user, with the reason; it does not become a mystery failure in the
 * middle of their first real task.
 *
 * The steps are spelled out and the site opens from here on purpose. A bare box
 * labelled "key" assumes the user already knows there is an account to make and a
 * settings page to make it on; a first-time user does not, and the box alone
 * leaves them stuck with no idea what to paste.
 */
@Composable
fun ApexKeyPrompt(vm: ZyraxonViewModel, onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        Modifier.fillMaxSize().background(Color(0xF2070709)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Glass.fill(0.85f))
                .border(1.dp, Glass.borderSoft, RoundedCornerShape(20.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ZYRAXON", color = Glass.pink, fontSize = 26.sp)
            Text(
                t("শুরু করতে আপনার ZYRAXON API কী দিন। একবার দিলে এটাই সবসময় কানেক্টেড থাকবে।"),
                color = Glass.textMuted, fontSize = 12.5.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(2.dp))

            // The steps, in order, because a new user has no idea an account is
            // needed or where the key lives.
            Text(
                t("কী কোথায় পাবেন:"),
                color = Glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            listOf(
                t("নিচের বোতামে চাপ দিয়ে app.all-hands.dev সাইটে যান।"),
                t("সেখানে একাউন্ট খুলুন (ইমেইল বা গুগল দিয়ে)।"),
                t("ঢুকে সেটিংসে যান।"),
                t("API Key / Access Token অংশে ঢুকে নতুন কী তৈরি করুন।"),
                t("সেই কী কপি করে এখানে বসান।")
            ).forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Glass.pinkFill),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}", color = Glass.textPrimary,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        step, color = Glass.textSecondary, fontSize = 13.5.sp,
                        lineHeight = 20.sp, modifier = Modifier.weight(1f)
                    )
                }
            }

            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://app.all-hands.dev"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Glass.rowFill(0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    t("ওয়েবসাইট খুলুন — app.all-hands.dev"),
                    color = Glass.textPrimary, fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                t("আপনার API কী এখানে বসান:"),
                color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Glass.rowFill(0.35f))
                    .border(1.dp, Glass.borderSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it; error = "" },
                    singleLine = true,
                    textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Glass.pink),
                    visualTransformation = if (reveal) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (key.isBlank()) Text(t("এখানে আপনার কী পেস্ট করুন"), color = Glass.textMuted, fontSize = 13.sp)
                        inner()
                    }
                )
            }
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }
            Button(
                onClick = {
                    if (key.isBlank() || busy) return@Button
                    busy = true
                    error = ""
                    vm.saveProviderKeyAwait("zyraxon_apex", key) { ok, message ->
                        busy = false
                        if (ok) onDone() else error = message
                    }
                },
                enabled = key.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (busy) t("যাচাই করা হচ্ছে…") else t("কানেক্ট করুন"),
                    color = Glass.textPrimary, fontSize = 14.sp
                )
            }
            Text(
                if (reveal) t("কী লুকাও") else t("কী দেখাও"),
                color = Glass.textMuted, fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        reveal = !reveal
                    }
            )
        }
    }
}
