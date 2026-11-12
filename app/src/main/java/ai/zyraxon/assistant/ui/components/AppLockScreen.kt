// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The cover shown over the app while it is locked.
 *
 * It does not check a password itself. Android's own credential screen does
 * that, and it is the only thing that may: an app that drew its own PIN pad
 * would be guessing at a secret it has no right to see. So this screen asks the
 * system to confirm the device credential and steps aside when it does.
 *
 * On a phone with no PIN, pattern or password there is nothing for the system to
 * ask for. Rather than sit on a cover that can never be lifted, it says so and
 * offers the settings page where a lock can be set.
 */
@Composable
fun AppLockScreen(vm: ZyraxonViewModel) {
    val context = LocalContext.current

    val hasCredential = run {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.isDeviceSecure == true
    }

    // Confirming the device credential is a system screen, and its answer comes
    // back through this launcher. Cancelled means the user backed out, so the
    // cover stays up.
    val confirm = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.unlockApp()
    }

    // A cover with no way off it is worse than no cover: if the system will not
    // show its credential screen at all, the lock is turned back off rather than
    // leaving the app sealed behind a button that cannot open it.
    fun ask() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = runCatching {
            km?.createConfirmDeviceCredentialIntent(
                t("ZYRAXON আনলক করো"),
                t("অ্যাপ খুলতে আপনার ফোনের পিন, প্যাটার্ন বা ফিঙ্গারপ্রিন্ট দিন।")
            )
        }.getOrNull()
        if (intent == null) {
            vm.setAppLockEnabled(false)
            return
        }
        try {
            confirm.launch(intent)
        } catch (_: Exception) {
            vm.setAppLockEnabled(false)
        }
    }

    // Ask as soon as the cover appears: the user came back to the app to use it,
    // not to tap a button saying they want to. Keyed on the credential so that a
    // user who leaves to set a phone lock and comes back is challenged at once.
    LaunchedEffect(hasCredential) { if (hasCredential) ask() }

    // The back gesture must not lift the cover. There is nothing behind it to
    // return to.
    BackHandler(enabled = true) { }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF070709)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 400.dp).fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Glass.fill(0.9f))
                .border(1.dp, Glass.borderSoft, RoundedCornerShape(20.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(AppIcons.lock, null, size = 44.dp, tint = Glass.pink)
            Text(
                t("অ্যাপ লক করা আছে"),
                color = Glass.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Text(
                if (hasCredential) {
                    t("ফোনের পিন, প্যাটার্ন বা ফিঙ্গারপ্রিন্ট দিয়ে আনলক করুন।")
                } else {
                    t("আপনার ফোনে কোনো পিন, প্যাটার্ন বা পাসওয়ার্ড সেট করা নেই। ফোনে লক সেট করলে অ্যাপ লক কাজ করবে।")
                },
                color = Glass.textMuted, fontSize = 12.5.sp,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (hasCredential) ask()
                    else {
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (hasCredential) AppIcon(AppIcons.lockOpen, null, size = 17.dp, tint = Glass.textPrimary)
                else AppIcon(AppIcons.settings, null, size = 17.dp, tint = Glass.textPrimary)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (hasCredential) t("আনলক করো") else t("ফোনের লক সেটিংস খুলুন"),
                    color = Glass.textPrimary, fontSize = 14.sp
                )
            }
            // Only when the phone has nothing to ask for. There is no secret to
            // protect in that state — the lock is already inert — so offering the
            // way out cannot be a bypass, and it is what keeps a user whose phone
            // lock was removed from being sealed out of their own app.
            if (!hasCredential) {
                TextButton(onClick = { vm.setAppLockEnabled(false) }) {
                    AppIcon(AppIcons.power, null, size = 15.dp, tint = Glass.textMuted)
                    Spacer(Modifier.width(5.dp))
                    Text(t("অ্যাপ লক বন্ধ করুন"), color = Glass.textMuted, fontSize = 12.5.sp)
                }
            }
        }
    }
}
