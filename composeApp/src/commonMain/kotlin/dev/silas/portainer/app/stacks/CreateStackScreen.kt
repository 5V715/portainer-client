package dev.silas.portainer.app.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.codeeditor.CodeEditor
import dev.silas.portainer.app.support.rememberFilePicker
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.core.api.CustomTemplate

private enum class CreatePart { Main, Environment }

private const val SHOWN_CREATE_WARNINGS = 2

private fun methodLabel(m: CreateMethod) = when (m) {
    CreateMethod.Editor -> "Editor"
    CreateMethod.Upload -> "Upload"
    CreateMethod.Repository -> "Git"
    CreateMethod.Template -> "Template"
}

/**
 * New stack: name, then the compose file from the web editor, an uploaded file, a git repository or a custom
 * template, and the env vars. [onCreated] opens the new stack; [onOpenStack] opens a stack whose deploy failed.
 */
@Suppress("DEPRECATION") // BackHandler, as in App.kt
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CreateStackScreen(
    viewModel: CreateStackViewModel,
    onCreated: (CreatedStack) -> Unit,
    onOpenStack: (CreatedStack) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val back = { if (viewModel.requestBack()) onBack() }
    BackHandler(enabled = state.hasUnsavedChanges && !state.busy) { back() }

    LaunchedEffect(state.created) {
        state.created?.let {
            onCreated(it)
            viewModel.navigated()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    state.failure?.let { message ->
        val failedId = state.failedStackId
        AlertDialog(
            onDismissRequest = viewModel::clearFailure,
            title = { Text("Couldn't create the stack") },
            text = {
                val more = if (failedId != null) {
                    "\n\nPortainer created the stack but its deployment failed."
                } else {
                    "\n\nYour form is kept" + (if (state.git.authEnabled) "; type the password again." else ".")
                }
                Text(message + more)
            },
            confirmButton = {
                if (failedId != null) {
                    TextButton(onClick = {
                        val name = state.name
                        viewModel.clearFailure()
                        onOpenStack(CreatedStack(failedId, name))
                    }) { Text("Open the stack") }
                } else {
                    TextButton(onClick = viewModel::clearFailure) { Text("OK") }
                }
            },
            dismissButton = if (failedId != null) {
                { TextButton(onClick = viewModel::clearFailure) { Text("Close") } }
            } else {
                null
            },
        )
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDiscard,
            title = { Text("Discard the new stack?") },
            text = { Text("The stack hasn't been created; what you entered is lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissDiscard()
                    onBack()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDiscard) { Text("Keep editing") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("New stack") }, navigationIcon = { BackButton(back) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { CreateBar(state, viewModel::create) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            CreateHeader(state, viewModel)
            CreateBody(state, viewModel, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CreateHeader(state: CreateStackState, viewModel: CreateStackViewModel) {
    val enabled = !state.busy
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val nameError = state.nameError.takeIf { state.name.isNotEmpty() }
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Name") },
            supportingText = nameError?.let { { Text(it) } },
            isError = nameError != null,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CreateMethod.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = state.method == m,
                    onClick = { viewModel.setMethod(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, CreateMethod.entries.size),
                    enabled = enabled,
                    icon = {},
                ) { Text(methodLabel(m), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun CreateBody(state: CreateStackState, viewModel: CreateStackViewModel, modifier: Modifier) {
    when (state.method) {
        CreateMethod.Upload -> UploadPane(state, viewModel, modifier)
        CreateMethod.Template -> TemplatePane(state, viewModel, modifier)
        CreateMethod.Editor, CreateMethod.Repository -> {
            var part by rememberSaveable { mutableStateOf(CreatePart.Main) }
            Column(modifier) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CreatePart.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = part == p,
                            onClick = { part = p },
                            shape = SegmentedButtonDefaults.itemShape(i, CreatePart.entries.size),
                        ) {
                            Text(
                                when {
                                    p == CreatePart.Environment -> "Environment (${state.effectiveEnv.size})"
                                    state.method == CreateMethod.Repository -> "Repository"
                                    else -> "Compose file"
                                },
                            )
                        }
                    }
                }
                when {
                    part == CreatePart.Environment -> {
                        EnvVarsEditor(
                            mode = state.envMode,
                            rows = state.env,
                            block = state.envBlock,
                            errors = state.envErrors,
                            enabled = !state.busy,
                            onMode = viewModel::setEnvMode,
                            onRow = viewModel::setEnvRow,
                            onAdd = viewModel::addEnvRow,
                            onRemove = viewModel::removeEnvRow,
                            onBlock = viewModel::setEnvBlock,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    state.method == CreateMethod.Repository -> RepositoryFields(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                    else -> {
                        CreateValidationSummary(state)
                        CodeEditor(
                            value = state.text,
                            onValueChange = viewModel::setText,
                            errorLine = state.yamlError?.line,
                            readOnly = state.busy,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The YAML error (it blocks the create), the protected-image warning and the structural warnings (they don't). */
@Composable
private fun CreateValidationSummary(state: CreateStackState) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.sourceName?.let { Hint("From $it") }
        val error = state.yamlError
        if (error != null) {
            Text(
                "Line ${error.line}: ${error.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            state.protectedWarning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            val warnings = state.warnings
            val shown = if (expanded) warnings else warnings.take(SHOWN_CREATE_WARNINGS)
            shown.forEach { Text(composeWarningText(it), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            if (warnings.size > shown.size) TextButton(onClick = { expanded = true }) { Text("${warnings.size - shown.size} more warnings") }
        }
        if (state.envErrors.isNotEmpty()) {
            Text("Environment: ${state.envErrors.first()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun UploadPane(state: CreateStackState, viewModel: CreateStackViewModel, modifier: Modifier) {
    val pick = rememberFilePicker(viewModel::loadFile)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose a compose file (up to 1 MB). It opens in the editor, where you can check and change it before creating the stack.")
        if (state.text.isNotEmpty()) Hint("The file replaces what is in the editor now.")
        Button(onClick = pick, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Choose file") }
    }
}

@Composable
private fun TemplatePane(state: CreateStackState, viewModel: CreateStackViewModel, modifier: Modifier) {
    val selected = state.selectedTemplate
    when {
        selected != null -> TemplateVariables(state, selected, viewModel, modifier)
        state.templates == null -> Box(modifier, contentAlignment = Alignment.Center) {
            if (state.loadingTemplates) CircularProgressIndicator() else TextButton(onClick = viewModel::loadTemplates) { Text("Load templates") }
        }
        state.templates.isEmpty() -> Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No custom templates for Docker standalone stacks.")
        }
        else -> LazyColumn(modifier) {
            items(state.templates, key = { it.id }) { t ->
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = !state.busy) { viewModel.selectTemplate(t) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (t.description.isNotEmpty()) Text(t.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TemplateVariables(state: CreateStackState, template: CustomTemplate, viewModel: CreateStackViewModel, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Title(template.title)
        if (template.description.isNotEmpty()) Text(template.description)
        if (template.variables.isEmpty()) Hint("This template has no variables.")
        template.variables.forEach { v ->
            TextInput(v.label.ifEmpty { v.name }, state.templateValues[v.name].orEmpty(), !state.busy, supporting = v.name) {
                viewModel.setTemplateValue(v.name, it)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::clearTemplate, modifier = Modifier.weight(1f)) { Text("Other template") }
            Button(onClick = viewModel::useTemplate, enabled = state.templateFile != null && !state.busy, modifier = Modifier.weight(1f)) {
                Text("Open in editor")
            }
        }
        if (state.templateFile == null) CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun RepositoryFields(state: CreateStackState, viewModel: CreateStackViewModel, modifier: Modifier) {
    val form = state.git
    val enabled = !state.busy
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GitErrors(state)
        TextInput("Repository URL", form.url, enabled, supporting = "e.g. https://github.com/org/repo") { viewModel.editGit { copy(url = it) } }
        // CE has no ref listing (Business Edition only): a text field.
        TextInput("Reference", form.referenceName, enabled, supporting = "e.g. refs/heads/main; empty = the default branch") {
            viewModel.editGit { copy(referenceName = it) }
        }
        TextInput("Compose path", form.configFilePath, enabled, supporting = "Empty = docker-compose.yml") { viewModel.editGit { copy(configFilePath = it) } }
        TextInput("Additional compose paths", form.additionalFiles, enabled, singleLine = false, supporting = "One per line") {
            viewModel.editGit { copy(additionalFiles = it) }
        }

        HorizontalDivider()
        SwitchLine("Authentication", form.authEnabled, enabled) { viewModel.editGit { copy(authEnabled = it) } }
        if (form.authEnabled) {
            TextInput("Username", form.username, enabled) { viewModel.editGit { copy(username = it) } }
            OutlinedTextField(
                value = form.password,
                onValueChange = { viewModel.editGit { copy(password = it) } },
                label = { Text("Password or token") },
                supportingText = { Text("Sent to Portainer only; never stored here.") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwitchLine("Skip TLS verification", form.tlsSkipVerify, enabled) { viewModel.editGit { copy(tlsSkipVerify = it) } }

        HorizontalDivider()
        AutoUpdateFields(state, viewModel, enabled)
    }
}

@Composable
private fun GitErrors(state: CreateStackState) {
    val f = state.git
    // Only what was touched: a blank form isn't shown as all errors.
    val shown = state.gitErrors.filter { it != REPOSITORY_URL_REQUIRED_MESSAGE || f.url.isNotEmpty() }
    shown.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun AutoUpdateFields(state: CreateStackState, viewModel: CreateStackViewModel, enabled: Boolean) {
    val form = state.git
    Title("Automatic updates")
    SwitchLine("Update automatically", form.autoUpdateEnabled, enabled, viewModel::setAutoUpdateEnabled)
    if (!form.autoUpdateEnabled) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AutoUpdateMechanism.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = form.mechanism == m,
                onClick = { viewModel.setMechanism(m) },
                shape = SegmentedButtonDefaults.itemShape(i, AutoUpdateMechanism.entries.size),
                enabled = enabled && (m != AutoUpdateMechanism.Polling || state.pollingAvailable),
            ) { Text(m.name) }
        }
    }
    if (!state.pollingAvailable) Hint("This Portainer version sets polling on the stack's git source: set it in the Git tab once the stack exists.")
    when (form.mechanism) {
        AutoUpdateMechanism.Polling -> TextInput("Interval", form.interval, enabled, supporting = "e.g. 5m or 1h") { viewModel.editGit { copy(interval = it) } }
        AutoUpdateMechanism.Webhook -> state.webhookUrl?.let {
            WebhookUrl(it, saved = true)
            Hint("The webhook works once the stack is created.")
        }
    }
    SwitchLine("Re-pull images", form.forcePullImage, enabled) { viewModel.editGit { copy(forcePullImage = it) } }
    SwitchLine("Force redeployment", form.forceUpdate, enabled) { viewModel.editGit { copy(forceUpdate = it) } }
    Hint("Force redeployment redeploys even when the repository hasn't changed.")
}

/** Bottom bar: Deploy (the primary action, under the thumb), or the create running. */
@Composable
private fun CreateBar(state: CreateStackState, onCreate: () -> Unit) {
    if (state.method == CreateMethod.Upload || state.method == CreateMethod.Template) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(if (state.running) "Creating the stack…" else "Checking…")
                return@Row
            }
            Text(
                when {
                    state.name.isEmpty() -> "Enter a name"
                    state.nameError != null -> "Check the name"
                    state.validating -> "Checking the file…"
                    state.yamlError != null -> "Fix the YAML error"
                    else -> ""
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate, enabled = state.canCreate, modifier = Modifier.weight(1f)) { Text("Deploy the stack") }
        }
    }
}
