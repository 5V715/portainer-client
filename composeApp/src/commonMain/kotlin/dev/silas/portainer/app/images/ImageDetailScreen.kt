package dev.silas.portainer.app.images

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.app.theme.color
import dev.silas.portainer.app.theme.statusOf
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    viewModel: ImageDetailViewModel,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val detail = state.detail

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.tags?.firstOrNull() ?: detail?.id?.let { "<untagged> ${shortId(it)}" } ?: "Image") },
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
            if (detail == null) {
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
                    if (detail.protectedImage) {
                        Text(PROTECTED_IMAGE_MESSAGE, color = StatusTone.Warn.color())
                    }
                    Field("Tags", detail.tags.joinToString("\n").ifEmpty { "None" })
                    Field("ID", detail.id, mono = true)
                    Field("Size", formatBytes(detail.size))
                    Field("Created", detail.created.ifEmpty { "Unknown" })
                    Field("Architecture / OS", listOf(detail.architecture, detail.os).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "Unknown" })
                    detail.config.forEach { (label, value) -> Field(label, value, mono = true) }
                    HorizontalDivider()
                    Text("Containers using this image", style = MaterialTheme.typography.titleMedium)
                    if (detail.containers.isEmpty()) {
                        Text("None. This image is unused.")
                    }
                    detail.containers.forEach { c ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenContainer(c.id) }.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            StatusLabel(statusOf(c.state, c.health))
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
