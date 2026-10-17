package dev.silas.portainer.app.environments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentsScreen(
    viewModel: EnvironmentsViewModel,
    onSelect: (endpointId: Int) -> Unit,
    onRegistries: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Environments") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onRegistries) { Text("Registries") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = viewModel.state) {
                EnvironmentsState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is EnvironmentsState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::load) { Text("Retry") }
                }
                is EnvironmentsState.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.endpoint.id }) { item ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = item.enabled) { viewModel.select(item, onSelect) }
                                .alpha(if (item.enabled) 1f else 0.5f)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(item.endpoint.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !item.enabled -> "Not supported yet"
                                    item.endpoint.online -> "Online"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
