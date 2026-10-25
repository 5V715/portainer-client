package dev.silas.portainer.app.volumes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.app.theme.StatusLabel
import dev.silas.portainer.app.theme.statusOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeDetailScreen(
    viewModel: VolumeDetailViewModel,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val volume = state.volume

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(volume?.let { if (it.anonymous) it.name.take(12) else it.name } ?: "Volume") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (volume == null) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    if (!state.loading) Text("Nothing to show. Pull down to retry.", Modifier.padding(16.dp))
                }
                return@PullToRefreshBox
            }
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Field("Name", volume.name, mono = true)
                    if (volume.anonymous) Row { Chip("anonymous") }
                    Field("Driver", volume.driver)
                    Field("Scope", volume.scope.ifEmpty { "Unknown" })
                    Field("Mountpoint", volume.mountpoint.ifEmpty { "Unknown" }, mono = true)
                    Field("Created", volume.createdAt ?: "Unknown")
                    if (volume.options.isNotEmpty()) {
                        Field("Options", volume.options.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }, mono = true)
                    }
                    if (volume.labels.isNotEmpty()) {
                        Field("Labels", volume.labels.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }, mono = true)
                    }
                    HorizontalDivider()
                    Text("Containers using this volume", style = MaterialTheme.typography.titleMedium)
                    if (state.users.isEmpty()) Text("None. This volume is unused.")
                    state.users.forEach { user ->
                        val c = user.container
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenContainer(c.id) }.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            StatusLabel(statusOf(c.state, c.health))
                            when {
                                user.mounts == null -> Text("Loading mount…", style = MaterialTheme.typography.bodySmall)
                                user.mounts.isEmpty() -> Text("Mount details unavailable", style = MaterialTheme.typography.bodySmall)
                                else -> user.mounts.forEach {
                                    Text(
                                        "${it.path} · ${if (it.readOnly) "read-only" else "read-write"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
