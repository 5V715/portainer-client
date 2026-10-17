package dev.silas.portainer.app.registries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.model.RegistryType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistriesScreen(
    viewModel: RegistriesViewModel,
    /** Bumps when a form saved, so the list reloads when it comes back on screen. */
    refreshSignal: Int,
    onAdd: () -> Unit,
    onEdit: (Registry) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Registry?>(null) }

    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) viewModel.refresh() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registries") },
                navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = onAdd) { Text("Add registry") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (state.items.isEmpty() && !state.loading) {
                Text("No registries", Modifier.align(Alignment.Center).padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { registry ->
                    RegistryRow(registry, onEdit = { onEdit(registry) }, onDelete = { pendingDelete = registry })
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { registry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete registry ${registry.name}?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("Pulls that need it will fail until you add it again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(registry.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RegistryRow(registry: Registry, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(registry.name, style = MaterialTheme.typography.titleMedium)
            Text(typeLabel(registry.type), style = MaterialTheme.typography.bodyMedium)
            Text(registry.url, style = MaterialTheme.typography.bodyMedium)
            Text(authLabel(registry), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${registry.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (registry.type != RegistryType.Unknown) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
