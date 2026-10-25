package dev.silas.portainer.app.images

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import dev.silas.portainer.app.cleanup.CleanupOverflow
import dev.silas.portainer.app.cleanup.CleanupSheet
import dev.silas.portainer.app.cleanup.CleanupViewModel
import dev.silas.portainer.app.theme.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(
    viewModel: ImagesViewModel,
    nowEpochSeconds: () -> Long,
    onOpen: (imageId: String) -> Unit,
    cleanup: CleanupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose {}
    }
    val snackbar = remember { SnackbarHostState() }
    // The view model reopens the sheet for a run that is going on or finished meanwhile.
    val showCleanup by cleanup.sheetVisible.collectAsState()
    var pendingRemove by remember { mutableStateOf<ImageItem?>(null) }
    var showPull by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Images") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = {
                        viewModel.openPull()
                        showPull = true
                    }) { Text("Pull") }
                    CleanupOverflow {
                        cleanup.open()
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search tag or ID") },
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
                        if (state.items.isEmpty()) "No images" else "No images match",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.image.id }) { item ->
                        ImageRow(
                            item = item,
                            created = formatCreated(item.image.created, nowEpochSeconds()),
                            busy = item.image.id in state.busyIds,
                            onClick = { onOpen(item.image.id) },
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
            title = { Text("Remove image ${item.label}?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        viewModel.remove(item.image.id, force = false)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }

    state.forcePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissForce,
            title = { Text("Can't remove ${prompt.label}", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.message)
                    Text(FORCE_REMOVE_EXPLANATION)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmForce,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Force remove") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissForce) { Text("Cancel") } },
        )
    }

    if (showPull) {
        PullImageSheet(state.pull, viewModel, onDismiss = { showPull = false })
    }

    if (showCleanup) {
        CleanupSheet(cleanup, onDismiss = cleanup::acknowledge)
    }
}

@Composable
private fun ImageRow(item: ImageItem, created: String, busy: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .alpha(if (busy) 0.5f else 1f)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            item.image.repoTags.drop(1).takeIf { it.isNotEmpty() }?.let {
                Text("+${it.size} more tag${if (it.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
            }
            Text("${formatBytes(item.image.size)} · $created", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!item.used) Badge("Unused")
                if (item.protected) Badge("Portainer")
            }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, enabled = !busy) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${item.label}")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Remove") },
                    trailingIcon = if (item.protected) {
                        { Text("Locked", style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                    enabled = !item.protected,
                    onClick = {
                        open = false
                        onRemove()
                    },
                )
                if (item.protected) {
                    HorizontalDivider()
                    Text(
                        PROTECTED_IMAGE_REMOVE_MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
