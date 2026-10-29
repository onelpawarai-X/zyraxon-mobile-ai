package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.mcp.McpAuth
import ai.zyraxon.assistant.mcp.McpConnection
import ai.zyraxon.assistant.mcp.McpServer
import ai.zyraxon.assistant.mcp.McpStatus
import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The MCP screen: the whole page is a list of servers, each one either connected
 * (and then its tools are already inside the assistant) or one tap away.
 */
@Composable
fun McpScreen(
    connections: List<McpConnection>,
    onConnect: (McpServer) -> Unit,
    onDisconnect: (String) -> Unit,
    onAddCustom: (String, String, String) -> Unit,
    onRemoveCustom: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val connected = connections.count { it.status == McpStatus.CONNECTED }
    val totalTools = connections.sumOf { it.toolCount }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                McpHeader(
                    connected = connected,
                    total = connections.size,
                    tools = totalTools,
                    onAdd = { showAddSheet = true }
                )
            }

            items(connections, key = { it.server.id }) { conn ->
                McpServerCard(
                    connection = conn,
                    expanded = expanded == conn.server.id,
                    onToggleExpand = {
                        expanded = if (expanded == conn.server.id) null else conn.server.id
                    },
                    onConnect = { onConnect(conn.server) },
                    onDisconnect = { onDisconnect(conn.server.id) },
                    onRemove = { onRemoveCustom(conn.server.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }

        if (showAddSheet) {
            AddCustomServerDialog(
                onDismiss = { showAddSheet = false },
                onAdd = { url, name, token ->
                    onAddCustom(url, name, token)
                    showAddSheet = false
                }
            )
        }
    }
}

@Composable
private fun McpHeader(connected: Int, total: Int, tools: Int, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Glass.fill(0.26f))
            .border(1.dp, Glass.border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔌", fontSize = 26.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "MCP সার্ভার",
                    color = Glass.textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "কানেক্টেড সার্ভারের টুল অটোমেটিক এসিস্ট্যান্টে যোগ হয়",
                    color = Glass.textMuted,
                    fontSize = 11.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Glass.violetFill, Glass.pinkFill)))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("+ যোগ", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McpStat("$connected/$total", "কানেক্টেড", Glass.greenFill)
            McpStat("$tools", "টুল", Glass.cyanFill)
        }
    }
}

@Composable
private fun McpStat(value: String, label: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Glass.rowFill(0.34f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(value, color = Glass.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = Glass.textMuted, fontSize = 11.5.sp)
    }
}

@Composable
private fun McpServerCard(
    connection: McpConnection,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit
) {
    val server = connection.server
    val isConnected = connection.status == McpStatus.CONNECTED
    val isBusy = connection.status == McpStatus.CONNECTING

    val pulse = rememberInfiniteTransition(label = "mcp-pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Glass.fill(if (isConnected) 0.30f else 0.20f))
            .border(
                1.dp,
                if (isConnected) Glass.greenFill.copy(alpha = 0.45f) else Glass.border,
                RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Glass.rowFill(0.40f)),
                contentAlignment = Alignment.Center
            ) {
                Text(server.emoji, fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.name,
                        color = Glass.textPrimary,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(7.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Glass.greenFill.copy(alpha = dotAlpha))
                        )
                    }
                }
                Text(
                    connection.error.ifBlank { server.tagline },
                    color = if (connection.error.isNotBlank() && !isConnected) Glass.red else Glass.textMuted,
                    fontSize = 11.5.sp,
                    maxLines = 2
                )
                if (isConnected && connection.account.isNotBlank()) {
                    Text(
                        connection.account,
                        color = Glass.textDisabled,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            StatusPill(connection)
        }

        if (server.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(11.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Glass.rowFill(0.26f))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "কী কী পারবে ▾" else "কী কী পারবে ▸",
                    color = Glass.textSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (isConnected) {
                    Text(
                        "${connection.toolCount} টুল",
                        color = Glass.greenFill,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    server.highlights.forEach { line ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("•", color = Glass.cyanFill, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(line, color = Glass.textSecondary, fontSize = 11.5.sp)
                        }
                    }
                    if (isConnected && connection.tools.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "সার্ভার থেকে পাওয়া টুল",
                            color = Glass.textMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        connection.tools.take(14).forEach { tool ->
                            Text(
                                "· ${tool.name}",
                                color = Glass.textSecondary,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                        if (connection.toolCount > 14) {
                            Text(
                                "আরও ${connection.toolCount - 14}টি…",
                                color = Glass.textDisabled,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isConnected) {
                McpButton(
                    label = "বিচ্ছিন্ন করো",
                    accent = Glass.red,
                    modifier = Modifier.weight(1f),
                    onClick = onDisconnect
                )
            } else {
                McpButton(
                    label = when {
                        isBusy -> "অপেক্ষা করো…"
                        server.auth == McpAuth.OAUTH -> "কানেক্ট (ব্রাউজারে)"
                        else -> "কানেক্ট"
                    },
                    accent = Glass.pinkFill,
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                    onClick = onConnect
                )
            }
            if (server.custom) {
                McpButton(
                    label = "মুছে ফেলো",
                    accent = Glass.textMuted,
                    modifier = Modifier.width(96.dp),
                    onClick = onRemove
                )
            }
        }
    }
}

@Composable
private fun StatusPill(connection: McpConnection) {
    val (label, color) = when (connection.status) {
        McpStatus.CONNECTED -> "কানেক্টেড" to Glass.greenFill
        McpStatus.CONNECTING -> "কানেক্ট হচ্ছে" to Glass.amber
        McpStatus.NEEDS_AUTH -> "অনুমতি দাও" to Glass.amber
        McpStatus.FAILED -> "ব্যর্থ" to Glass.red
        McpStatus.DISCONNECTED -> "বন্ধ" to Glass.textMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun McpButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (enabled) 0.20f else 0.08f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.45f else 0.2f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Glass.textPrimary else Glass.textDisabled,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** Lets the user add any MCP server by URL, with an optional bearer token. */
@Composable
private fun AddCustomServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.sheetFill(0.92f),
        title = {
            Text("নিজের MCP সার্ভার", color = Glass.textPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "যেকোনো MCP সার্ভারের ঠিকানা দাও। টোকেন ছাড়া চললে খালি রাখো।",
                    color = Glass.textMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                McpField(url, { url = it }, "সার্ভার URL", "https://example.com/mcp")
                Spacer(modifier = Modifier.height(9.dp))
                McpField(name, { name = it }, "নাম (ইচ্ছা হলে)", "My Server")
                Spacer(modifier = Modifier.height(9.dp))
                McpField(token, { token = it }, "অ্যাক্সেস টোকেন (ইচ্ছা হলে)", "••••••", secret = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onAdd(url.trim(), name.trim(), token.trim()) },
                enabled = url.isNotBlank()
            ) {
                Text("যোগ করো", color = Glass.pinkFill, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল", color = Glass.textMuted) }
        }
    )
}

@Composable
private fun McpField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    secret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.5.sp) },
        placeholder = { Text(placeholder, fontSize = 12.sp, color = Glass.textDisabled) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}
