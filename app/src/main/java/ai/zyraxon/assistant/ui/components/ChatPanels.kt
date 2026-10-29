package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.model.TaskItem
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBorder = Glass.border
private val Pink = Glass.pink
private val Cyan = Glass.cyan
private val TextMain = Glass.textPrimary
private val TextSub = Glass.textSecondary
private val TextFaint = Glass.textMuted

/**
 * The three live panels that sit inside the chat column, under the transcript:
 * the todo board, the tool-call feed and the files the assistant wrote. They
 * collapse to a single header row so the chat keeps the screen when idle.
 */
@Composable
fun ChatPanels(
    tasks: List<TaskItem>,
    toolExecutions: List<ToolExecutionItem>,
    files: List<WorkspaceFile>,
    panelAlpha: Float,
    onDeleteFile: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPublishFile: (WorkspaceFile) -> Unit,
    modifier: Modifier = Modifier
) {
    var openPanel by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Glass.fill(panelAlpha))
            .border(1.dp, PanelBorder, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PanelTab("✅ টুডু", tasks.count { !it.isCompleted }, tasks.size, openPanel == "todo", Pink, panelAlpha) {
                openPanel = if (openPanel == "todo") null else "todo"
            }
            PanelTab("🛠️ টুল", toolExecutions.size, toolExecutions.size, openPanel == "tool", Cyan, panelAlpha) {
                openPanel = if (openPanel == "tool") null else "tool"
            }
            PanelTab("📁 ফাইল", files.size, files.size, openPanel == "files", Glass.violet, panelAlpha) {
                openPanel = if (openPanel == "files") null else "files"
            }
        }

        when (openPanel) {
            "todo" -> TodoPanelBody(tasks)
            "tool" -> ToolPanelBody(toolExecutions)
            "files" -> FilesPanelBody(files, onDeleteFile, onOpenFile, onPublishFile)
        }
    }
}

@Composable
private fun RowScope.PanelTab(
    label: String,
    activeCount: Int,
    total: Int,
    selected: Boolean,
    accent: Color,
    panelAlpha: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Glass.rowFill(panelAlpha))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (total > 0) "$label $activeCount/$total" else label,
            color = if (selected) accent else TextSub,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TodoPanelBody(tasks: List<TaskItem>) {
    if (tasks.isEmpty()) {
        EmptyHint("এখনো কোনো টুডু নেই — কাজ শুরু করলে এখানে ধাপে ধাপে দেখা যাবে")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
        items(tasks, key = { it.id }) { task ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (task.isCompleted) "☑" else "☐",
                    color = if (task.isCompleted) Glass.green else TextFaint,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = task.text,
                    color = if (task.isCompleted) Glass.textDisabled else TextMain,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ToolPanelBody(items: List<ToolExecutionItem>) {
    if (items.isEmpty()) {
        EmptyHint("টুল কল এখনো হয়নি — কাজ করলে এখানে কী চলছে দেখা যাবে")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
        items(items, key = { it.id }) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.icon, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = Cyan,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            color = TextSub,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(item.timestamp, color = TextFaint, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FilesPanelBody(
    files: List<WorkspaceFile>,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onPublish: (WorkspaceFile) -> Unit
) {
    if (files.isEmpty()) {
        EmptyHint("কোনো ফাইল নেই — কোড বা সাইট বানালে এখানে জমা হবে")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
        items(files, key = { it.path }) { file ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = file.relative,
                        color = TextMain,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(file.sizeLabel, color = TextFaint, fontSize = 10.sp)
                }
                MiniButton("খোলো", Cyan) { onOpen(file.path) }
                Spacer(Modifier.width(5.dp))
                MiniButton("প্রকাশ", Pink) { onPublish(file) }
                Spacer(Modifier.width(5.dp))
                MiniButton("মুছো", Glass.red) { onDelete(file.path) }
            }
        }
    }
}

@Composable
private fun MiniButton(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.13f))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = TextFaint,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
    )
}
