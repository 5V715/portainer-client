package dev.silas.portainer.app.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton

private val TABS = listOf("Overview", "Logs")

/**
 * [logsViewModel] is called (inside composition) only once the overview has loaded, because logs need the
 * container's `tty` flag to decode the stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailScreen(
    viewModel: ContainerDetailViewModel,
    logsViewModel: @Composable (tty: Boolean) -> LogsViewModel,
    onEditRecreate: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val overview = state.overview

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(overview?.name ?: "Container") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (overview != null && !overview.protectedImage) {
                        TextButton(onClick = onEditRecreate) { Text("Edit & recreate") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when {
                tab == 0 -> OverviewTab(overview, state.loading, viewModel::refresh)
                overview == null -> Text(if (state.loading) "Loading…" else "Logs are unavailable", Modifier.padding(16.dp))
                else -> LogsTab(logsViewModel(overview.tty))
            }
        }
    }
}
