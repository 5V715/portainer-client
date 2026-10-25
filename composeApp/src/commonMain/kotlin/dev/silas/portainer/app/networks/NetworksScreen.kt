package dev.silas.portainer.app.networks

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.silas.portainer.app.theme.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    viewModel: NetworksViewModel,
    onOpen: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose {}
    }
    val snackbar = remember { SnackbarHostState() }
    var pendingRemove by remember { mutableStateOf<NetworkItem?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Networks") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = {
                        viewModel.openCreate()
                        showCreate = true
                    }) { Text("Create") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val visible = state.visible
                if (visible.isEmpty() && !state.loading) {
                    Text(
                        if (state.items.isEmpty()) "No networks" else "No networks match",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.network.id }) { item ->
                        NetworkRow(
                            item = item,
                            busy = item.network.id in state.busyIds,
                            onClick = { onOpen(item.network.id) },
                            onRemove = { pendingRemove = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove network ${item.network.name}?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        viewModel.remove(item.network.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }

    if (showCreate) {
        CreateNetworkSheet(state.create, state.drivers, viewModel::create, onDismiss = { showCreate = false })
    }
}

@Composable
private fun NetworkRow(item: NetworkItem, busy: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val n = item.network
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .alpha(if (busy) 0.5f else 1f)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(n.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text("${n.driver} · ${n.scope}", style = MaterialTheme.typography.bodyMedium)
            n.subnets.firstOrNull()?.let { Text(it.subnet, style = MaterialTheme.typography.bodySmall) }
            Text(
                if (item.containerCount == 1) "1 container" else "${item.containerCount} containers",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (n.predefined) Chip("built-in")
                if (item.unused) Chip("Unused")
            }
        }
        if (item.removable) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, enabled = !busy) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${n.name}")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            open = false
                            onRemove()
                        },
                    )
                }
            }
        } else {
            Box(Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
internal fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
