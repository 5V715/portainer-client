package dev.silas.portainer.app.networks

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    viewModel: NetworkDetailViewModel,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val network = state.network

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(network?.name ?: "Network") },
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
            if (network == null) {
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
                    Field("Name", network.name, mono = true)
                    Field("Id", network.id, mono = true)
                    if (network.predefined) Row { Chip("built-in") }
                    Field("Driver", network.driver)
                    Field("Scope", network.scope.ifEmpty { "Unknown" })
                    Field("Internal", if (network.internal) "Yes" else "No")
                    Field("Attachable", if (network.attachable) "Yes" else "No")
                    if (network.labels.isNotEmpty()) {
                        Field("Labels", network.labels.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }, mono = true)
                    }
                    HorizontalDivider()
                    Text("IPAM", style = MaterialTheme.typography.titleMedium)
                    if (network.subnets.isEmpty()) Text("No subnets configured.")
                    network.subnets.forEach { s ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Subnet: ${s.subnet}", fontFamily = FontFamily.Monospace)
                            Text("Gateway: ${s.gateway ?: "none"}", fontFamily = FontFamily.Monospace)
                            s.ipRange?.let { Text("IP range: $it", fontFamily = FontFamily.Monospace) }
                        }
                    }
                    HorizontalDivider()
                    Text("Connected containers", style = MaterialTheme.typography.titleMedium)
                    if (state.connected.isEmpty()) Text("None. This network is unused.")
                    state.connected.forEach { m ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenContainer(m.id) }.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(m.name.ifEmpty { m.id.take(12) }, style = MaterialTheme.typography.titleSmall)
                            if (!m.running) {
                                Text("Stopped", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            m.ipv4?.let { Text("IPv4: $it", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                            m.ipv6?.let { Text("IPv6: $it", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
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
