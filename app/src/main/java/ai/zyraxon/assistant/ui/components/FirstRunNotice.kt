// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.i18n.setLanguage
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one screen a user sees on the very first launch, and never again.
 *
 * It says what the app is, warns plainly that it acts on its own and that the
 * user carries the responsibility, and points at the Settings section that
 * decides how much it may do alone. A language selector sits at the top, because
 * the warning is worth nothing in a language the reader does not have.
 *
 * Whether it was shown is remembered in preferences, so it appears once after
 * install and stays out of the way from then on.
 */
object FirstRunNotice {
    const val PREFS = "zyraxon_first_run"
    const val KEY_SHOWN = "notice_shown"

    fun shouldShow(context: android.content.Context): Boolean =
        !context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN, false)

    fun markShown(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }
}

@Composable
fun FirstRunNoticeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var languageOpen by remember { mutableStateOf(false) }
    val activeLang = Loc.languages.firstOrNull { it.code == Loc.current }

    Box(
        Modifier.fillMaxSize().background(Color(0xFA070709)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(22.dp))
                .background(Glass.fill(0.9f))
                .border(1.dp, Glass.pinkFill, RoundedCornerShape(22.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ZYRAXON", color = Glass.pink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // Language first: the warning below is only useful once it can be read.
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Glass.rowFill(0.4f))
                        .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { languageOpen = !languageOpen }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(activeLang?.flag ?: "🌐", fontSize = 15.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(activeLang?.name ?: "English", color = Glass.textPrimary, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    AppIcon(AppIcons.chevronDown, null, size = 14.dp, tint = Glass.textMuted)
                }
            }

            if (languageOpen) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Glass.rowFill(0.3f))
                        .border(1.dp, Glass.borderSoft, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Loc.languages.forEach { language ->
                        val active = language.code == Loc.current
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (active) Glass.pinkFill.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable { setLanguage(context, language.code); languageOpen = false }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(language.flag, fontSize = 15.sp)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                language.name,
                                color = if (active) Glass.textPrimary else Glass.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Text(
                t("এটাই একদম সর্বোচ্চ পাওয়ারের অল-ইন-ওয়ান অটোমেশন মোবাইল এজেন্ট।"),
                color = Glass.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                lineHeight = 23.sp
            )
            Text(
                t("সবাই সতর্কতার সাথে ব্যবহার করুন।"),
                color = Glass.amber, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                t("এই এজেন্ট আপনার ফোনে নিজে থেকে কাজ করতে পারে — ফাইল লেখা, কমান্ড চালানো, অ্যাপ চালানো। এটি দিয়ে কোনো ক্ষতি হলে তার সম্পূর্ণ দায়ভার আপনার।"),
                color = Glass.textSecondary, fontSize = 13.sp, lineHeight = 20.sp
            )
            Text(
                t("সেটিংস → অটোমেশন থেকে আপনি পারমিশন অন করে দিতে পারেন। অন থাকলে এজেন্ট সম্পূর্ণ অটোমেশন কাজ করবে; বন্ধ থাকলে প্রতিটি ধাপে আপনাকে জিজ্ঞাসা করবে।"),
                color = Glass.textSecondary, fontSize = 13.sp, lineHeight = 20.sp
            )

            Spacer(Modifier.height(2.dp))
            Button(
                onClick = { FirstRunNotice.markShown(context); onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(AppIcons.check, null, size = 17.dp, tint = Glass.textPrimary)
                Spacer(Modifier.width(7.dp))
                Text(t("বুঝেছি, শুরু করুন"), color = Glass.textPrimary, fontSize = 14.sp)
            }
        }
    }
}
