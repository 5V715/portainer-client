package dev.silas.portainer.app.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.app.theme.StatusLabel
import dev.silas.portainer.app.theme.statusOf
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackDetailScreen(
    viewModel: StackDetailViewModel,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val stack = state.stack

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            viewModel.navigated()
            onBack() // the list reloads when it shows again
        }
    }
    state.confirm?.let { confirm ->
        StackConfirmSheet(confirm, viewModel::setTypedName, viewModel::confirm, viewModel::dismissConfirm)
    }
    state.failure?.let { (op, message) ->
        AlertDialog(
            onDismissRequest = viewModel::clearFailure,
            title = { Text(failureTitle(op)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearFailure) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stack?.name ?: viewModel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = { StackActionBar(state.ops, state.running, state.starting || state.loading, viewModel::request) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (stack == null) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    if (!state.loading) Text("Nothing to show. Pull down to retry.", Modifier.padding(16.dp))
                }
                return@PullToRefreshBox
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("Status")
                    StatusLabel(stackStatus(stack))
                    if (stack.status == StackStatus.Error) {
                        Text(stack.statusMessage ?: "The last deployment failed", color = MaterialTheme.colorScheme.error)
                    }
                }
                Field("Deployment", stack.deploymentLabel)
                Field("Control", stack.controlLabel)
                if (stack.control == StackControl.Limited) {
                    Text(
                        EXTERNAL_STACK_MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Field("Created", formatStackDate(stack.createdAt))
                    stack.updatedAt?.let { Field("Updated", formatStackDate(it)) }
                    stack.updatedBy?.let { Field("Updated by", it) }
                }
                if (state.protected) {
                    Text(PROTECTED_IMAGE_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else if (state.protectionUnknown) {
                    Text(PROTECTION_UNKNOWN_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                stack.git?.let { git ->
                    HorizontalDivider()
                    Text("Git repository", style = MaterialTheme.typography.titleMedium)
                    Field("Repository URL", git.url, mono = true)
                    Field("Reference", git.referenceName.ifEmpty { "Default branch" }, mono = true)
                    Field("Compose path", git.configFilePath, mono = true)
                    if (git.additionalFiles.isNotEmpty()) Field("Additional paths", git.additionalFiles.joinToString("\n"), mono = true)
                }

                HorizontalDivider()
                Text("Containers", style = MaterialTheme.typography.titleMedium)
                if (state.containers.isEmpty()) Text("None. The stack has no containers (it is not running).")
                state.containers.forEach { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenContainer(c.id) }.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        StatusLabel(statusOf(c.state, c.health))
                        Text(c.image, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (stack.control == StackControl.Full) {
                    HorizontalDivider()
                    Text("Compose file", style = MaterialTheme.typography.titleMedium)
                    when {
                        state.file != null -> ComposeFileText(state.file!!)
                        state.fileError != null -> Text(state.fileError!!, color = MaterialTheme.colorScheme.error)
                        else -> Text("Loading…")
                    }
                }
            }
        }
    }
}

/** Read-only compose file; it scrolls sideways inside its own box so the page never does. */
@Composable
private fun ComposeFileText(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
