package com.abhijit.hisinchan.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhijit.hisinchan.HiSinchanApp
import com.abhijit.hisinchan.data.MessageEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as HiSinchanApp
    val dao = app.database.messageDao()
    val sessions by dao.observeSessions().collectAsState(initial = emptyList())
    val saveHistory by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    SubScreen(
        title = "Conversation history",
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = sessions.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all history")
            }
        },
    ) {
        if (saveHistory?.saveHistory == false) {
            item {
                GlassCard { Text("History saving is turned off in Settings → Privacy, so new conversations aren't stored.") }
            }
        }
        if (sessions.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("No saved conversations yet.", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(sessions, key = { it.sessionId }) { s ->
            GlassCard(Modifier.fillMaxWidth().clickable { expanded = if (expanded == s.sessionId) null else s.sessionId }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.firstUserText ?: "Conversation", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt))} · ${s.messageCount} messages",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { dao.deleteSession(s.sessionId) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                    }
                }
                AnimatedVisibility(expanded == s.sessionId) {
                    var msgs by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
                    LaunchedEffect(s.sessionId) { msgs = dao.getSession(s.sessionId) }
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        msgs.forEach { m ->
                            Text(
                                (if (m.role == "user") "You: " else "Sinchan: ") + m.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (m.role == "user") FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all conversation history?") },
            text = { Text("This removes every saved conversation. Your memories are NOT affected.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { dao.deleteAll(); confirmClear = false } }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
