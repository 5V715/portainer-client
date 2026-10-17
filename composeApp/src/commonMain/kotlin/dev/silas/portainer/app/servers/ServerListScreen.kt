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
import androidx.compose.material3.AlertDialog
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
import dev.silas.portainer.core.storage.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServersViewModel,
    onOpen: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerConfig) -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = { if (servers.isNotEmpty()) TextButton(onClick = onAdd) { Text("Add server") } },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.padding(top = 16.dp)) { Button(onClick = onAdd) { Text("Add server") } }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(servers, key = { it.id }) { server ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(server) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                        Text(server.baseUrl, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { onEdit(server) }) { Text("Edit") }
                            TextButton(onClick = { pendingDelete = server }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete server?") },
            text = { Text("${server.name} and its stored API key will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(server.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}
