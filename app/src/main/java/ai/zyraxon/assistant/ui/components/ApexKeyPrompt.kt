// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
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
import androidx.compose.foundation.layout.heightIn
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
 * The key a user may give to reach the ZYRAXON cloud.
 *
 * It is offered, not demanded: the cross closes it and the app is used without
 * any key, on the on-device model and the local tools. It is shown once, over
 * everything, on the first launch that has no key — asking here rather than
 * burying it in settings, because a user who wants the cloud has no idea an
 * account exists. A user who does not want it is never held up.
 *
 * The key is verified before the prompt closes. A typo therefore fails here, in
 * front of the user, with the reason; it does not become a mystery failure in
 * the middle of their first real task.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ZYRAXON", color = Glass.pink, fontSize = 26.sp)
                Spacer(Modifier.weight(1f))
                // The cross is the whole point: the app is usable without a key.
                Box(
                    Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Glass.rowFill(0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDone() },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(AppIcons.close, t("বন্ধ করো"), size = 16.dp, tint = Glass.textPrimary)
                }
            }
            Text(
                t("ক্লাউড ব্যবহার করে ডেটা সেভিং রাখতে চাইলে আপনার ZYRAXON API কী দিন। কী ছাড়াও অ্যাপ চলবে — তখন অন-ডিভাইস মডেল ও লোকাল টুল দিয়ে কাজ হবে।"),
                color = Glass.textMuted, fontSize = 12.5.sp, lineHeight = 18.sp
            )

            // A language selector here too: the instruction above is worth nothing
            // in a language the reader does not have, and this is the first screen
            // that carries instructions.
            var languageOpen by remember { mutableStateOf(false) }
            val activeLang = ai.zyraxon.assistant.i18n.Loc.languages
                .firstOrNull { it.code == ai.zyraxon.assistant.i18n.Loc.current }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Glass.rowFill(0.3f))
                    .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { languageOpen = !languageOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(activeLang?.flag ?: "🌐", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    activeLang?.name ?: "English",
                    color = Glass.textPrimary, fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(t("ভাষা বদলাও"), color = Glass.textMuted, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                AppIcon(AppIcons.chevronDown, null, size = 14.dp, tint = Glass.textMuted)
            }
            if (languageOpen) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Glass.rowFill(0.25f))
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    ai.zyraxon.assistant.i18n.Loc.languages.forEach { language ->
                        val active = language.code == ai.zyraxon.assistant.i18n.Loc.current
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                                .background(if (active) Glass.pinkFill.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    ai.zyraxon.assistant.i18n.setLanguage(context, language.code)
                                    languageOpen = false
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(language.flag, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                language.name,
                                color = if (active) Glass.textPrimary else Glass.textSecondary,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))

            // The steps, in order, because a new user has no idea an account is
            // needed or where the key lives.
            Text(
                t("কী কোথায় পাবেন:"),
                color = Glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            listOf(
                t("নিচের বোতামে চাপ দিয়ে zyraxonai.lovable.app সাইটে যান।"),
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
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://zyraxonai.lovable.app"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Glass.rowFill(0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(AppIcons.link, null, size = 16.dp, tint = Glass.textPrimary)
                Spacer(Modifier.width(7.dp))
                Text(
                    t("ওয়েবসাইট খুলুন — zyraxonai.lovable.app"),
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
                if (busy) AppIcon(AppIcons.sync, null, size = 17.dp, tint = Glass.textPrimary)
                else AppIcon(AppIcons.bolt, null, size = 17.dp, tint = Glass.textPrimary)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (busy) t("যাচাই করা হচ্ছে…") else t("কানেক্ট করুন"),
                    color = Glass.textPrimary, fontSize = 14.sp
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        reveal = !reveal
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    if (reveal) AppIcons.visibilityOff else AppIcons.visibility,
                    null, size = 14.dp, tint = Glass.textMuted
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (reveal) t("কী লুকাও") else t("কী দেখাও"),
                    color = Glass.textMuted, fontSize = 11.sp
                )
            }
            // The escape hatch, spelled out, so no one thinks a key is required.
            Text(
                t("কী ছাড়াও ব্যবহার করতে চাইলে উপরের ✕ চাপুন — অ্যাপ অন-ডিভাইস মডেল ও লোকাল টুল দিয়ে চলবে।"),
                color = Glass.textMuted, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    }
}
