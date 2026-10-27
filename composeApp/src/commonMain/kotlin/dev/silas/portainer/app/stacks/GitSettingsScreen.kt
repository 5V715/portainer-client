package dev.silas.portainer.app.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private enum class GitPart { Settings, Environment }

/** The Git tab: repository, authentication and automatic-update settings, the env vars, and detach. */
@Composable
fun GitSettingsContent(state: GitSettingsState, viewModel: GitSettingsViewModel, modifier: Modifier = Modifier) {
    val form = state.form
    if (!state.available || form == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var part by rememberSaveable { mutableStateOf(GitPart.Settings) }
    val enabled = !state.busy
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GitPart.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = part == p,
                    onClick = { part = p },
                    shape = SegmentedButtonDefaults.itemShape(i, GitPart.entries.size),
                ) { Text(if (p == GitPart.Settings) "Settings" else "Environment") }
            }
        }
        when (part) {
            GitPart.Environment -> EnvVarsEditor(
                mode = form.envMode,
                rows = form.env,
                block = form.envBlock,
                errors = form.envErrors,
                enabled = enabled,
                onMode = viewModel::setEnvMode,
                onRow = viewModel::setEnvRow,
                onAdd = viewModel::addEnvRow,
                onRemove = viewModel::removeEnvRow,
                onBlock = viewModel::setEnvBlock,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            GitPart.Settings -> GitSettingsFields(state, form, enabled, viewModel, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private val plainKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

@Composable
private fun GitSettingsFields(state: GitSettingsState, form: GitSettingsForm, enabled: Boolean, viewModel: GitSettingsViewModel, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Title("Repository")
        if (state.sourceId != null) {
            val others = state.otherStacksOnSource
            Hint(
                "Credentials and TLS" + (if (state.features.pollingOnSource) " and the polling interval" else "") +
                    " belong to a shared git source" + when (others) {
                        null -> "."
                        0 -> " no other stack uses."
                        1 -> " that 1 other stack also uses."
                        else -> " that $others other stacks also use."
                    },
            )
        }
        // Read-only after creation, as in Portainer.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Hint("Repository URL")
            Text(form.url, fontFamily = FontFamily.Monospace)
        }
        // CE has no ref listing (Business Edition only): a text field.
        TextInput("Reference", form.referenceName, enabled, supporting = "e.g. refs/heads/main or refs/tags/v1.0") {
            viewModel.edit { copy(referenceName = it) }
        }
        TextInput("Compose path", form.configFilePath, enabled && state.pathsEditable) { viewModel.edit { copy(configFilePath = it) } }
        TextInput("Additional compose paths", form.additionalFiles, enabled && state.pathsEditable, singleLine = false, supporting = "One per line") {
            viewModel.edit { copy(additionalFiles = it) }
        }
        if (!state.pathsEditable) Hint("This Portainer version can't change the compose paths of a git stack.")

        HorizontalDivider()
        SwitchLine("Authentication", form.authEnabled, enabled) { viewModel.edit { copy(authEnabled = it) } }
        if (form.authEnabled) {
            TextInput("Username", form.username, enabled) { viewModel.edit { copy(username = it) } }
            OutlinedTextField(
                value = form.password,
                onValueChange = { viewModel.edit { copy(password = it) } },
                label = { Text("Password or token") },
                placeholder = { if (state.passwordStored) Text("Unchanged") },
                supportingText = { Text(if (state.passwordStored) "Leave empty to keep the stored one." else "Sent to Portainer only; never stored here.") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwitchLine("Skip TLS verification", form.tlsSkipVerify, enabled) { viewModel.edit { copy(tlsSkipVerify = it) } }

        HorizontalDivider()
        Title("Automatic updates")
        SwitchLine("Update automatically", form.autoUpdateEnabled, enabled, viewModel::setAutoUpdateEnabled)
        if (form.autoUpdateEnabled) {
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
            when (form.mechanism) {
                AutoUpdateMechanism.Polling -> TextInput("Interval", form.interval, enabled, supporting = "e.g. 5m or 1h") {
                    viewModel.edit { copy(interval = it) }
                }
                AutoUpdateMechanism.Webhook -> state.webhookUrl?.let { WebhookUrl(it, saved = form.webhookId == state.original?.webhookId) }
            }
            SwitchLine("Re-pull images", form.forcePullImage, enabled) { viewModel.edit { copy(forcePullImage = it) } }
            SwitchLine("Force redeployment", form.forceUpdate, enabled) { viewModel.edit { copy(forceUpdate = it) } }
            Hint("Force redeployment redeploys even when the repository hasn't changed.")
        }

        if (state.canDetach) {
            HorizontalDivider()
            OutlinedButton(
                onClick = viewModel::requestDetach,
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Detach from git") }
        }
    }
}

@Suppress("DEPRECATION") // LocalClipboardManager: plain-text copy without per-platform ClipEntry code
@Composable
private fun WebhookUrl(url: String, saved: Boolean) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hint("Webhook URL")
        Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy URL") }
        if (!saved) Hint("The webhook works once the settings are saved.")
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun TextInput(
    label: String,
    value: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = plainKeyboard,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().let { if (singleLine) it else it.heightIn(min = 96.dp) },
    )
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Bottom bar of the Git tab: Pull and redeploy, and Save (the primary action, under the thumb). */
@Composable
fun GitSettingsBar(state: GitSettingsState, onRedeploy: () -> Unit, onSave: () -> Unit) {
    if (!state.available) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val running = state.running
            if (running != null || state.checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(running?.let(::runningLabel) ?: "Checking the stack…")
                return@Row
            }
            OutlinedButton(onClick = onRedeploy, modifier = Modifier.weight(1f)) { Text("Pull and redeploy") }
            Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.weight(1f)) { Text("Save") }
        }
    }
}

/** Save confirmation: the changes, the shared-source warning and the connection reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSaveSheet(confirm: GitSaveConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Save the git settings?", style = MaterialTheme.typography.headlineSmall)
            confirm.changes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Hint("Saving doesn't redeploy: the settings apply on the next pull and redeploy or automatic update.")
            confirm.sharedWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text(confirm.reminder, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Save") }
            }
        }
    }
}

/** The one-way detach confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDetachSheet(confirm: GitDetachConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(confirm.title, style = MaterialTheme.typography.headlineSmall)
            Text(confirm.body)
            Text(confirm.reminder, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Detach") }
            }
        }
    }
}
