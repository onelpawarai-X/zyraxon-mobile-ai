// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import ai.zyraxon.assistant.i18n.t
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
 * Modern Blood-Cyber Bottom Floating Panel that displays To-Do, Tool-calls, or Files
 * cleanly above the bottom navigation panel without cluttering the main conversation screen.
 */
@Composable
fun BottomFloatingPanel(
    panelType: String,
    tasks: List<TaskItem>,
    toolExecutions: List<ToolExecutionItem>,
    files: List<WorkspaceFile>,
    onDeleteFile: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPublishFile: (WorkspaceFile) -> Unit,
    onClose: () -> Unit,
    activeToolCalls: List<ToolExecutionItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF0120206))
            .border(1.2.dp, Color(0x88FF1E44), RoundedCornerShape(20.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = when (panelType) {
                "todo" -> t("টুডু বোর্ড") + " (${tasks.count { !it.isCompleted }}/${tasks.size})"
                "tool" -> t("টুল এক্সিকিউশন") + " (${toolExecutions.size + activeToolCalls.size})"
                "files" -> t("ফাইল ম্যানেজার") + " (${files.size})"
                else -> t("প্যানেল")
            }
            AppIcon(
                when (panelType) { "todo" -> AppIcons.checkCircle; "tool" -> AppIcons.build; "files" -> AppIcons.folder; else -> AppIcons.viewModule },
                null, size = 15.dp, tint = Color(0xFFFF4D6D)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = Color(0xFFFF4D6D),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x33FF1744))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                AppIcon(
                    AppIcons.close, t("বন্ধ করো"),
                    size = 14.dp, tint = Color(0xFFFF1E44)
                )
            }
        }

        when (panelType) {
            "todo" -> TodoPanelBody(tasks)
            "tool" -> ToolPanelBody(activeToolCalls + toolExecutions)
            "files" -> FilesPanelBody(files, onDeleteFile, onOpenFile, onPublishFile)
        }
    }
}

/**
 * Blood-Cyber Luxury Unified Dock Bar at the bottom of the screen.
 * Contains: Chat, To-Do, Tool, Files, and Terminal.
 */
@Composable
fun BloodCyberDockBar(
    activePanel: String?,
    todoCount: Int,
    toolCount: Int,
    fileCount: Int,
    onPanelSelect: (String?) -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE6100205))
            .border(1.dp, Color(0x66FF1E44), RoundedCornerShape(22.dp))
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockItem(
            label = t("চ্যাট"),
            icon = AppIcons.chat,
            badge = null,
            isSelected = activePanel == null,
            onClick = { onPanelSelect(null) }
        )
        DockItem(
            label = t("টুডু"),
            icon = AppIcons.checkCircle,
            badge = if (todoCount > 0) todoCount.toString() else null,
            isSelected = activePanel == "todo",
            onClick = { onPanelSelect(if (activePanel == "todo") null else "todo") }
        )
        DockItem(
            label = t("টুল"),
            icon = AppIcons.build,
            badge = if (toolCount > 0) toolCount.toString() else null,
            isSelected = activePanel == "tool",
            onClick = { onPanelSelect(if (activePanel == "tool") null else "tool") }
        )
        DockItem(
            label = t("ফাইল"),
            icon = AppIcons.folder,
            badge = if (fileCount > 0) fileCount.toString() else null,
            isSelected = activePanel == "files",
            onClick = { onPanelSelect(if (activePanel == "files") null else "files") }
        )
        DockItem(
            label = t("টার্মিনাল"),
            icon = AppIcons.terminal,
            badge = null,
            isSelected = false,
            onClick = { onOpenTerminal() }
        )
    }
}

@Composable
private fun DockItem(
    label: String,
    icon: ImageVector?,
    badge: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0x44FF1E44) else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Color(0xFFFF1E44) else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, null, size = 13.dp, tint = if (isSelected) Color(0xFFFF4D6D) else Color(0xFFD0BAC0))
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                color = if (isSelected) Color(0xFFFF4D6D) else Color(0xFFD0BAC0),
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
            if (badge != null) {
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF1E44))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badge,
                        color = Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

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
            PanelTab(AppIcons.checkCircle, t("টুডু"), tasks.count { !it.isCompleted }, tasks.size, openPanel == "todo", Pink, panelAlpha) {
                openPanel = if (openPanel == "todo") null else "todo"
            }
            PanelTab(AppIcons.build, t("টুল"), toolExecutions.size, toolExecutions.size, openPanel == "tool", Cyan, panelAlpha) {
                openPanel = if (openPanel == "tool") null else "tool"
            }
            PanelTab(AppIcons.folder, t("ফাইল"), files.size, files.size, openPanel == "files", Glass.violet, panelAlpha) {
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
    icon: ImageVector?,
    label: String,
    activeCount: Int,
    total: Int,
    selected: Boolean,
    accent: Color,
    panelAlpha: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Glass.rowFill(panelAlpha))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
    ) {
        AppIcon(icon, null, size = 14.dp, tint = if (selected) accent else TextSub)
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
        EmptyHint(t("এখনো কোনো টুডু নেই — কাজ শুরু করলে এখানে ধাপে ধাপে দেখা যাবে"))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
        items(tasks, key = { it.id }) { task ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    if (task.isCompleted) AppIcons.checkBox else AppIcons.checkBoxBlank,
                    null, size = 15.dp, tint = if (task.isCompleted) Glass.green else TextFaint
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
        EmptyHint(t("টুল কল এখনো হয়নি — কাজ করলে এখানে কী চলছে দেখা যাবে"))
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
                    // The command's own output, so the panel shows what a call
                    // actually printed and not only its one-line summary.
                    if (item.output.isNotBlank()) {
                        Text(
                            text = item.output.trimEnd('\n'),
                            color = Cyan.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = if (item.running) t("চলছে…") else item.timestamp,
                    color = if (item.running) Cyan else TextFaint,
                    fontSize = 10.sp
                )
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
        EmptyHint(t("কোনো ফাইল নেই — কোড বা সাইট বানালে এখানে জমা হবে"))
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
                MiniButton(t("খোলো"), Cyan) { onOpen(file.path) }
                Spacer(Modifier.width(5.dp))
                MiniButton(t("প্রকাশ"), Pink) { onPublish(file) }
                Spacer(Modifier.width(5.dp))
                MiniButton(t("মুছো"), Glass.red) { onDelete(file.path) }
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
