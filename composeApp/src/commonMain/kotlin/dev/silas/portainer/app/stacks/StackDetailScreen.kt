package dev.silas.portainer.app.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.app.theme.StatusLabel
import dev.silas.portainer.app.theme.statusOf
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

/** The detail screen's tabs: web-editor stacks add the Editor, git stacks a read-only File and Git; external stacks none. */
enum class StackDetailTab { Overview, Editor, File, Git }

fun stackDetailTabs(state: StackDetailState): List<StackDetailTab> {
    val stack = state.stack ?: return listOf(StackDetailTab.Overview)
    return when {
        stack.control != StackControl.Full || stack.id == null -> listOf(StackDetailTab.Overview)
        stack.git != null -> listOf(StackDetailTab.Overview, StackDetailTab.File, StackDetailTab.Git)
        else -> listOf(StackDetailTab.Overview, StackDetailTab.Editor)
    }
}

@Suppress("DEPRECATION") // BackHandler, as in App.kt
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun StackDetailScreen(
    viewModel: StackDetailViewModel,
    editor: StackEditorViewModel,
    git: GitSettingsViewModel,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val editorState by editor.state.collectAsState()
    val gitState by git.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val stack = state.stack
    val tabs = stackDetailTabs(state)
    var selected by rememberSaveable { mutableStateOf(StackDetailTab.Overview) }
    val tab = selected.takeIf { it in tabs } ?: StackDetailTab.Overview
    val back = { if (editor.requestBack() && git.requestBack()) onBack() }
    BackHandler(
        enabled = (editorState.hasUnsavedChanges && !editorState.updating) || (gitState.hasUnsavedChanges && gitState.running == null),
    ) { back() }

    LaunchedEffect(gitState.error) {
        gitState.error?.let {
            snackbar.showSnackbar(it)
            git.clearError()
        }
    }
    LaunchedEffect(gitState.notice) {
        gitState.notice?.let {
            snackbar.showSnackbar(it)
            git.clearNotice()
        }
    }
    gitState.saveConfirm?.let { GitSaveSheet(it, git::confirmSave, git::dismissSave) }
    gitState.redeployReview?.let { GitRedeploySheet(it, git::setRedeployPullImage, git::confirmRedeploy, git::dismissRedeploy) }
    gitState.detachConfirm?.let { GitDetachSheet(it, git::confirmDetach, git::dismissDetach) }
    gitState.failure?.let { (op, message) ->
        AlertDialog(
            onDismissRequest = git::clearFailure,
            title = { Text(failureTitle(op)) },
            text = {
                val keep = if (op is StackOp.GitSettings) "\n\nYour other changes are kept; type the password again if you changed it." else ""
                Text(message + keep)
            },
            confirmButton = { TextButton(onClick = git::clearFailure) { Text("OK") } },
        )
    }
    if (gitState.confirmDiscard) {
        AlertDialog(
            onDismissRequest = git::dismissDiscard,
            title = { Text("Discard changes?") },
            text = { Text("Your git settings and environment changes are not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    git.dismissDiscard()
                    onBack()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = git::dismissDiscard) { Text("Keep editing") } },
        )
    }

    LaunchedEffect(editorState.error) {
        editorState.error?.let {
            snackbar.showSnackbar(it)
            editor.clearError()
        }
    }
    LaunchedEffect(editorState.notice) {
        editorState.notice?.let {
            snackbar.showSnackbar(it)
            editor.clearNotice()
        }
    }
    editorState.review?.let { review ->
        StackReviewSheet(review, editor::setPullImage, editor::setPrune, editor::confirmUpdate, editor::dismissReview)
    }
    editorState.updateError?.let { message ->
        AlertDialog(
            onDismissRequest = editor::clearUpdateError,
            title = { Text("Couldn't update the stack") },
            text = { Text("$message\n\nYour changes are kept in the editor.") },
            confirmButton = { TextButton(onClick = editor::clearUpdateError) { Text("OK") } },
        )
    }
    if (editorState.confirmDiscard) {
        AlertDialog(
            onDismissRequest = editor::dismissDiscard,
            title = { Text("Discard changes?") },
            text = { Text("Your edits to the compose file and environment are not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    editor.dismissDiscard()
                    onBack()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = editor::dismissDiscard) { Text("Keep editing") } },
        )
    }

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
    state.transfer?.let { form ->
        TransferSheet(
            form,
            viewModel::setTransferTarget,
            viewModel::setTransferName,
            viewModel::setTransferTypedName,
            viewModel::setTransferPassword,
            viewModel::submitTransfer,
            viewModel::dismissTransfer,
        )
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
            Column {
                TopAppBar(
                    title = { Text(stack?.name ?: viewModel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { BackButton(back) },
                )
                if (tabs.size > 1) {
                    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                        tabs.forEach { t ->
                            Tab(selected = t == tab, onClick = { selected = t }, text = { Text(tabLabel(t)) })
                        }
                    }
                }
            }
        },
        bottomBar = {
            when (tab) {
                StackDetailTab.Overview -> StackActionBar(state.ops, state.running, state.starting || state.loading) { op ->
                    viewModel.request(op, unsavedEdits = editorState.hasUnsavedChanges)
                }
                StackDetailTab.Editor -> StackEditorBar(editorState, editor::requestUpdate)
                StackDetailTab.File -> Unit
                StackDetailTab.Git -> GitSettingsBar(gitState, git::requestRedeploy, git::requestSave)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The editor tabs get a bounded height (no scrolling parent) that shrinks above the keyboard.
        val bounded = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        when (tab) {
            StackDetailTab.Editor -> {
                StackEditorContent(editorState, editor, state.fileError, bounded)
                return@Scaffold
            }
            StackDetailTab.File -> {
                StackFileView(state.file, state.fileError, bounded)
                return@Scaffold
            }
            StackDetailTab.Git -> {
                GitSettingsContent(gitState, git, bounded)
                return@Scaffold
            }
            StackDetailTab.Overview -> Unit
        }
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

                if (stack.control == StackControl.Full && stack.id != null) {
                    HorizontalDivider()
                    Text("Copy or move", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val idle = state.running == null && !state.starting && !state.loading
                        OutlinedButton({ viewModel.openTransfer(TransferMode.Duplicate) }, Modifier.weight(1f), enabled = idle) { Text("Duplicate") }
                        OutlinedButton({ viewModel.openTransfer(TransferMode.Migrate) }, Modifier.weight(1f), enabled = idle) { Text("Migrate") }
                    }
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

                // The file itself is on the Editor / File tab; a failure to read it matters here (protection unknown).
                state.fileError?.let {
                    HorizontalDivider()
                    Text("Compose file", style = MaterialTheme.typography.titleMedium)
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun tabLabel(tab: StackDetailTab): String = when (tab) {
    StackDetailTab.Overview -> "Overview"
    StackDetailTab.Editor -> "Editor"
    StackDetailTab.File -> "Compose file"
    StackDetailTab.Git -> "Git"
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
