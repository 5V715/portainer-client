package dev.silas.portainer.app.volumes

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
import dev.silas.portainer.app.images.formatCreatedRfc3339
import dev.silas.portainer.app.theme.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumesScreen(
    viewModel: VolumesViewModel,
    nowEpochSeconds: () -> Long,
    onOpen: (name: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose {}
    }
    val snackbar = remember { SnackbarHostState() }
    var pendingRemove by remember { mutableStateOf<VolumeItem?>(null) }
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
                title = { Text("Volumes") },
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
                        if (state.items.isEmpty()) "No volumes" else "No volumes match",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.volume.name }) { item ->
                        VolumeRow(
                            item = item,
                            created = formatCreatedRfc3339(item.volume.createdAt, nowEpochSeconds()),
                            busy = item.volume.name in state.busyNames,
                            onClick = { onOpen(item.volume.name) },
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
            title = { Text("Delete volume ${item.title}?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("Delete volume ${item.volume.name}? Everything stored in it is deleted permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        viewModel.remove(item.volume.name)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }

    if (showCreate) {
        CreateVolumeSheet(state.create, viewModel::create, onDismiss = { showCreate = false })
    }
}

@Composable
private fun VolumeRow(item: VolumeItem, created: String, busy: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .alpha(if (busy) 0.5f else 1f)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text("${item.volume.driver} · $created", style = MaterialTheme.typography.bodyMedium)
            item.stack?.let { Text("Stack: $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.volume.anonymous) Chip("anonymous")
                if (item.unused) Chip("Unused")
            }
        }
        if (item.removable) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, enabled = !busy) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${item.title}")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
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
