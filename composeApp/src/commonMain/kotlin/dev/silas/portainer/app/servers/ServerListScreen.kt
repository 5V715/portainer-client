package dev.silas.portainer.app.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.FeedClaudeBox
import dev.silas.portainer.core.storage.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServersViewModel,
    onOpen: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerConfig) -> Unit,
    onAbout: () -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    if (servers.isNotEmpty()) TextButton(onClick = onAdd) { Text("Add server") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; onAbout() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.padding(top = 16.dp)) { Button(onClick = onAdd) { Text("Add server") } }
                }
                FeedClaudeBox(Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(servers, key = { it.id }) { server ->
                    ServerRow(server, onOpen = { onOpen(server) }, onEdit = { onEdit(server) }, onDelete = { pendingDelete = server })
                    HorizontalDivider()
                }
                item { FeedClaudeBox(Modifier.padding(16.dp)) }
            }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete server?") },
            text = { Text("${server.name} and its stored API key will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(server.id); pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerRow(server: ServerConfig, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium)
            Text(server.baseUrl, style = MaterialTheme.typography.bodyMedium)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${server.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
