// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.permission.AutomationPolicy
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The card the agent stops at when the automation level says it must ask.
 *
 * It names the tool, the tier the tool belongs to and one line of what it will
 * touch, then offers Allow and Refuse. The turn is suspended behind it, so
 * nothing happens until the user answers — this is the point the user asked for,
 * where the agent checks in instead of charging ahead.
 */
@Composable
fun ToolApprovalCard(vm: ZyraxonViewModel) {
    val approval by vm.pendingApproval.collectAsState()
    val pending = approval ?: return

    Box(
        Modifier.fillMaxSize().background(Color(0xC4070709)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 430.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Glass.fill(0.92f))
                .border(1.dp, Glass.amber, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.warning, null, size = 20.dp, tint = Glass.amber)
                Spacer(Modifier.width(8.dp))
                Text(
                    t("অনুমতি দরকার"),
                    color = Glass.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
            Text(
                t("এজেন্ট এই কাজটি করতে চায়। আপনি অনুমতি দিলে এটি চলবে, নাহলে বন্ধ থাকবে।"),
                color = Glass.textSecondary, fontSize = 12.5.sp, lineHeight = 18.sp
            )

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Glass.rowFill(0.35f))
                    .border(1.dp, Glass.borderSoft, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pending.tool,
                        color = Glass.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (pending.detail.isNotBlank()) {
                        Text(
                            pending.detail,
                            color = Glass.textMuted, fontSize = 12.sp, lineHeight = 17.sp
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                val (tierLabel, tierColor) = when (pending.tier) {
                    AutomationPolicy.Tier.SYSTEM -> t("সিস্টেম") to Glass.bloodRed
                    AutomationPolicy.Tier.PERSONAL -> t("ব্যক্তিগত") to Glass.amber
                    AutomationPolicy.Tier.SAFE -> t("নিরাপদ") to Glass.green
                }
                Text(
                    tierLabel,
                    color = tierColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(Glass.rowFill(0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.resolveApproval(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Glass.rowFill(0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    AppIcon(AppIcons.close, null, size = 16.dp, tint = Glass.textPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(t("না, বন্ধ রাখো"), color = Glass.textPrimary, fontSize = 13.sp)
                }
                Button(
                    onClick = { vm.resolveApproval(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill),
                    modifier = Modifier.weight(1f)
                ) {
                    AppIcon(AppIcons.check, null, size = 16.dp, tint = Glass.textPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(t("হ্যাঁ, চালাও"), color = Glass.textPrimary, fontSize = 13.sp)
                }
            }

            Text(
                t("সবসময় এই অনুমতি লাগবে কি না, সেটিংস → অটোমেশন থেকে বদলাতে পারেন।"),
                color = Glass.textMuted, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    }
}
