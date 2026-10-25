package dev.silas.portainer.app.containers

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.silas.portainer.app.theme.SelectableChip
import dev.silas.portainer.app.theme.StatusLabel
import dev.silas.portainer.app.theme.statusOf
import dev.silas.portainer.core.containers.ContainerFilter
import dev.silas.portainer.core.model.ContainerState
import dev.silas.portainer.core.model.ContainerSummary
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE
import dev.silas.portainer.core.recreate.isProtectedImage

private val FILTER_STATES = listOf(ContainerState.Running, ContainerState.Exited, ContainerState.Paused)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerListScreen(
    viewModel: ContainerListViewModel,
    onOpen: (containerId: String) -> Unit,
    onRegistries: () -> Unit,
    cleanup: CleanupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    // Back from detail or recreate, containers may have changed or gone: reload (the VM skips the first resume).
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose {}
    }
    val snackbar = remember { SnackbarHostState() }
    var showCleanup by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<ContainerSummary?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Containers") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = onRegistries) { Text("Registries") }
                    CleanupOverflow {
                        cleanup.open()
                        showCleanup = true
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = { viewModel.setFilter(state.filter.copy(query = it)) },
                label = { Text("Search name or image") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            FilterRow(state.filter, state.stacks, viewModel::setFilter)
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val visible = state.visible
                if (visible.isEmpty() && !state.loading) {
                    Text(
                        if (state.items.isEmpty()) "No containers" else "No containers match",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { c ->
                        ContainerRow(
                            c = c,
                            busy = c.id in state.busyIds,
                            onClick = { onOpen(c.id) },
                            onStart = { viewModel.start(c.id) },
                            onStop = { viewModel.stop(c.id) },
                            onRestart = { viewModel.restart(c.id) },
                            onRemove = { pendingRemove = c },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingRemove?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove ${c.name}?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        viewModel.remove(c.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }

    if (showCleanup) CleanupSheet(cleanup, onDismiss = { showCleanup = false })
}

@Composable
private fun FilterRow(filter: ContainerFilter, stacks: List<String>, onChange: (ContainerFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FILTER_STATES.forEach { s ->
            SelectableChip(
                selected = s in filter.states,
                onClick = { onChange(filter.copy(states = if (s in filter.states) filter.states - s else filter.states + s)) },
                label = { Text(s.name) },
            )
        }
        if (stacks.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                SelectableChip(
                    selected = filter.stack != null,
                    onClick = { open = true },
                    label = { Text(filter.stack?.let { "Stack: $it" } ?: "Stack") },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("All stacks") }, onClick = {
                        open = false
                        onChange(filter.copy(stack = null))
                    })
                    stacks.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            open = false
                            onChange(filter.copy(stack = name))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    c: ContainerSummary,
    busy: Boolean,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .alpha(if (busy) 0.5f else 1f)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            StatusLabel(statusOf(c.state, c.health))
            Text(c.image, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            c.stack?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        RowMenu(c, busy, onStart, onStop, onRestart, onRemove)
    }
}

@Composable
private fun RowMenu(
    c: ContainerSummary,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val protected = isProtectedImage(c.image)
    val actions = availableActions(c.state)
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${c.name}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                val locked = protected && (action == ContainerAction.Stop || action == ContainerAction.Remove)
                DropdownMenuItem(
                    text = { Text(action.name) },
                    trailingIcon = if (locked) {
                        { Text("Locked", style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                    enabled = !locked,
                    onClick = {
                        open = false
                        when (action) {
                            ContainerAction.Start -> onStart()
                            ContainerAction.Stop -> onStop()
                            ContainerAction.Restart -> onRestart()
                            ContainerAction.Remove -> onRemove()
                        }
                    },
                )
            }
            if (protected && actions.any { it == ContainerAction.Stop || it == ContainerAction.Remove }) {
                HorizontalDivider()
                Text(
                    PROTECTED_IMAGE_MESSAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
