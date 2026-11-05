package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.ui.theme.ZyThemeMode
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The small left drawer: a New chat button and every chat. Chats keep running while you look at another. */
@Composable
fun Sidebar(vm: ZyraxonViewModel) {
    val chats by vm.chats.collectAsState()
    val active by vm.activeConversationId.collectAsState()
    val theme by vm.themeMode.collectAsState()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.setSidebarOpen(false) })
        Column(
            Modifier.fillMaxHeight().width(268.dp).statusBarsPadding().navigationBarsPadding().padding(8.dp)
                .clip(RoundedCornerShape(24.dp)).background(Glass.sheetFill(0.55f)).border(1.dp, Glass.border, RoundedCornerShape(24.dp)).padding(12.dp)
        ) {
            Text("ZYRAXON", color = Glass.textPrimary, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                t("＋  নতুন চ্যাট"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.pinkFill)
                    .clickable { vm.newChat(); vm.setSidebarOpen(false) }.padding(12.dp).androidTag("new_chat_button")
            )
            Spacer(Modifier.height(10.dp))
            Text(t("চ্যাট ({0})", chats.size), color = Glass.textMuted, fontSize = 11.sp)
            LazyColumn(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(chats, key = { it.id }) { c ->
                    val sel = c.id == active
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Glass.pinkFill.copy(alpha = 0.22f) else Glass.rowFill(0.25f))
                            .border(1.dp, if (sel) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp))
                            .clickable { vm.switchChat(c.id); vm.setSidebarOpen(false) }.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(c.mode.icon, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(c.title, color = Glass.textPrimary, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (c.busy) Text("●", color = Glass.green, fontSize = 11.sp)
                        Text("✕", color = Glass.textMuted, fontSize = 12.sp, modifier = Modifier.clickable { confirmDelete = c.id }.padding(start = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(t("থিম"), color = Glass.textMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ZyThemeMode.values().forEach { m ->
                    Text(
                        "${m.icon} ${m.title}", fontSize = 11.sp, maxLines = 1,
                        color = if (m == theme) Color.White else Glass.textSecondary,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (m == theme) Glass.pinkFill else Glass.rowFill(0.3f))
                            .clickable { vm.setThemeMode(m) }.padding(vertical = 8.dp, horizontal = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = Glass.sheetFill(0.9f),
            title = { Text(t("চ্যাট মুছবে?"), color = Glass.textPrimary) },
            text = { Text(t("এই চ্যাটের সব বার্তা মুছে যাবে।"), color = Glass.textMuted) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.deleteChat(id); confirmDelete = null }) { Text(t("মোছো"), color = Glass.red) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = null }) { Text(t("না"), color = Glass.textMuted) } }
        )
    }
}

private fun Modifier.androidTag(tag: String): Modifier = this.then(Modifier.testTag(tag))
