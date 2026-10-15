package com.abhijit.hisinchan.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abhijit.hisinchan.HiSinchanApp
import com.abhijit.hisinchan.data.MemoryEntity
import com.abhijit.hisinchan.memory.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HiSinchanApp
    val repo = app.memories
    val memories by repo.memories.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<MemoryEntity?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val json = repo.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("Can't open file")
                }
            }.isSuccess
            toast(if (ok) "Memories exported" else "Export failed")
        }
    }

    SubScreen(
        title = "Sinchan's memory",
        onBack = onBack,
        actions = {
            IconButton(onClick = { exporter.launch("sinchan-memories.json") }, enabled = memories.isNotEmpty()) {
                Icon(Icons.Filled.FileDownload, contentDescription = "Export memories")
            }
            IconButton(onClick = { confirmClear = true }, enabled = memories.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all memories")
            }
        },
        floating = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add memory") },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp),
            )
        },
    ) {
        item {
            GlassCard {
                Text("Sinchan only saves something when you explicitly ask — for example “Remember that my favourite colour is blue”. " +
                    "Say “Forget my favourite colour” or “What do you remember about me?” any time. " +
                    "Memories are stored only on this tablet and survive restarts.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (memories.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("No memories yet.", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(memories, key = { it.id }) { m ->
            GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            (if (m.updatedAt > m.createdAt + 1000) "Updated " else "Saved ") +
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.updatedAt)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, contentDescription = "Edit memory") }
                    IconButton(onClick = { confirmDelete = m }) { Icon(Icons.Filled.Delete, contentDescription = "Delete memory") }
                }
            }
        }
    }

    editing?.let { m ->
        MemoryEditDialog("Edit memory", m.text, onDismiss = { editing = null }) { text ->
            scope.launch {
                toast(if (repo.update(m.id, text)) "Memory updated" else "Couldn't update memory")
                editing = null
            }
        }
    }
    if (adding) {
        MemoryEditDialog("Add a memory", "", onDismiss = { adding = false }) { text ->
            scope.launch {
                when (repo.remember(text)) {
                    is SaveResult.Saved -> toast("Saved")
                    is SaveResult.Duplicate -> toast("Already remembered")
                    is SaveResult.Failed -> toast("Couldn't save — nothing was stored")
                }
                adding = false
            }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Forget this?") },
            text = { Text("“${m.text}”") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { toast(if (repo.delete(m.id)) "Forgotten" else "Couldn't delete"); confirmDelete = null }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memories?") },
            text = { Text("Sinchan will forget all ${memories.size} saved memories. This can't be undone. Export first if you want a copy.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.clearAll(); toast("All memories cleared"); confirmClear = false }
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemoryEditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Write it as a fact about you, e.g. “My favourite colour is blue”.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
