package ai.zyraxon.assistant.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** One permission the assistant needs, with the exact wording shown to the user. */
data class PermissionRow(
    val key: String,
    val label: String,
    val why: String,
    val androidPermission: String? = null,
    val specialAction: String? = null
)

/**
 * Every permission in one place. Tapping a row raises the system dialog on top of
 * this screen, so the user only ever presses Allow — they never have to walk into
 * a settings screen themselves.
 */
@Composable
fun PermissionPanel(
    context: Context,
    accessibilityOn: Boolean,
    onRequestPermission: (PermissionRow) -> Unit,
    onOpenSpecial: (String) -> Unit
) {
    var refresh by remember { mutableStateOf(0) }

    val rows = remember(refresh, accessibilityOn) {
        listOf(
            PermissionRow("mic", "🎙️ মাইক্রোফোন", "কথা শোনার জন্য — মাইক সবসময় চালু থাকবে", Manifest.permission.RECORD_AUDIO),
            PermissionRow("camera", "📷 ক্যামেরা", "ছবি ও ভিডিও দেখতে", Manifest.permission.CAMERA),
            PermissionRow("location", "📍 লোকেশন", "ম্যাপ ও স্থানীয় তথ্যের জন্য", Manifest.permission.ACCESS_FINE_LOCATION),
            PermissionRow("notifications", "🔔 নোটিফিকেশন", "ব্যাকগ্রাউন্ডে কাজ চললে জানাতে", Manifest.permission.POST_NOTIFICATIONS),
            PermissionRow("files", "📁 ফাইল", "ছবি, ভিডিও ও ডকুমেন্ট পড়তে", if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else Manifest.permission.READ_EXTERNAL_STORAGE),
            PermissionRow("overlay", "🪟 ওভারলে", "অন্য অ্যাপের উপরে কাজ দেখাতে", specialAction = "overlay"),
            PermissionRow("accessibility", "♿ অ্যাক্সেসিবিলিটি", "স্ক্রিন দেখে ট্যাপ করতে — এটাই আসল অটোমেশন", specialAction = "accessibility"),
            PermissionRow("battery", "🔋 ব্যাটারি অপটিমাইজেশন", "ব্যাকগ্রাউন্ডে থেমে না যাওয়ার জন্য", specialAction = "battery")
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Glass.fill(0.30f))
            .border(1.dp, Glass.pinkFill.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(
            "পারমিশন",
            color = Glass.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "প্রতিটি লাইনে চাপ দিলে উপরে সিস্টেমের পপ-আপ আসবে — শুধু Allow চাপো, ভিতরে ঢুকতে হবে না।",
            color = Glass.textSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))

        rows.forEach { row ->
            val granted = when {
                row.key == "accessibility" -> accessibilityOn
                row.key == "overlay" -> canDrawOverlays(context)
                row.key == "battery" -> isBatteryUnrestricted(context)
                row.androidPermission != null ->
                    ContextCompat.checkSelfPermission(context, row.androidPermission) ==
                        PackageManager.PERMISSION_GRANTED
                else -> false
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (granted) Glass.greenFill.copy(alpha = 0.14f) else Glass.rowFill(0.30f))
                    .clickable {
                        if (!granted) {
                            if (row.specialAction != null) onOpenSpecial(row.specialAction)
                            else onRequestPermission(row)
                            refresh++
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(row.why, color = Glass.textSecondary, fontSize = 10.5.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (granted) Glass.greenFill.copy(alpha = 0.24f) else Glass.pinkFill.copy(alpha = 0.20f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (granted) "✓ দেওয়া আছে" else "Allow",
                        color = if (granted) Glass.greenFill else Glass.pinkFill,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // The Play Protect note: the AI can walk the screen itself, so the panel
        // offers the shortcut and states the trade-off instead of declaring it
        // impossible. The user keeps the final say.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Glass.amber.copy(alpha = 0.14f))
                .border(1.dp, Glass.amber.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .padding(11.dp)
        ) {
            Column {
                Text(
                    "⚠️ গুগল প্লে প্রটেক্টর",
                    color = Glass.amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "প্লে প্রটেক্টর কোনো কাজ আটকে দিলে এআই নিজেই এই পেজে গিয়ে স্ক্রিন দেখে ধাপে ধাপে বন্ধ করে দিতে পারে — " +
                        "কিন্তু সিদ্ধান্তটা তোমার, তাই সে আগে জিজ্ঞেস করবে। বন্ধ করলে ফোনে কোনো অ্যাপ চেক ছাড়াই চলবে। " +
                        "নিজে করতে চাইলে: Play Store → প্রোফাইল ছবি → Play Protect → ⚙️ সেটিং → " +
                        "\"Scan apps with Play Protect\" বন্ধ করো।",
                    color = Glass.amber,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Play Store খুলে দাও →",
                    color = Glass.amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Glass.amber.copy(alpha = 0.24f))
                        .clickable { onOpenSpecial("play_protect") }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun canDrawOverlays(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(context) else true

private fun isBatteryUnrestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
