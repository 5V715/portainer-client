package dev.silas.portainer.app.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.model.Endpoint

/** Copy a stack to another (or the same) Docker environment under a new name, or move it. */
enum class TransferMode { Duplicate, Migrate }

/**
 * The duplicate / migrate sheet. [targets] are the online Docker standalone environments (the source is left out for a
 * migrate); [existingNames] are the stack names on the chosen target (null while they load). The git [password] is only
 * what was typed: it is sent once and dropped from the form the moment the request starts, and never shown back.
 */
data class TransferForm(
    val mode: TransferMode,
    val sourceName: String,
    val sourceEndpointId: Int,
    val sourceEndpointName: String? = null,
    val targets: List<Endpoint> = emptyList(),
    val loadingTargets: Boolean = true,
    val targetId: Int? = null,
    val name: String,
    val existingNames: Set<String>? = null,
    /** Migrate: the stack's name, typed to confirm. */
    val typedName: String = "",
    val password: String = "",
    /** Duplicate of a git stack that needs credentials Portainer CE can't return. */
    val passwordNeeded: Boolean = false,
    val usesGitSource: Boolean = false,
    /** The source has automatic updates: they are not copied. */
    val autoUpdateDropped: Boolean = false,
    /** The compose file runs Portainer, or whether it does is unknown: the copy's own lifecycle actions may be blocked. */
    val protectedWarning: Boolean = false,
    /** The source has "prune services" on: CE's create requests have no such field, so the copy starts with it off. */
    val pruneNotCopied: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
) {
    val reminder: String? get() = CONNECTION_DEPENDENCY_REMINDER.takeIf { mode == TransferMode.Migrate }
    val needsTypedName: Boolean get() = mode == TransferMode.Migrate

    /** The name the stack gets on the target; a migrate with an empty field keeps its name. */
    val effectiveName: String get() = name.trim().ifEmpty { if (mode == TransferMode.Migrate) sourceName else "" }

    /** The name sent to a migrate: null keeps the name. */
    val newName: String? get() = effectiveName.takeIf { mode == TransferMode.Migrate && it != sourceName }

    /** Why [effectiveName] can't be used on the chosen target, or null (also while nothing is chosen yet). */
    val nameError: String?
        get() {
            val n = effectiveName
            if (mode == TransferMode.Migrate && n == sourceName) return takenError(n)
            return stackNameError(n, emptyList()) ?: takenError(n)
        }

    private fun takenError(n: String): String? {
        val sameEnvironment = targetId == sourceEndpointId
        val taken = existingNames.orEmpty().any { it.equals(n, ignoreCase = true) } ||
            (sameEnvironment && mode == TransferMode.Duplicate && n.equals(sourceName, ignoreCase = true))
        return STACK_NAME_TAKEN_MESSAGE.takeIf { taken }
    }

    val canSubmit: Boolean
        get() = !running && targetId != null && existingNames != null && nameError == null &&
            when (mode) {
                TransferMode.Migrate -> typedName == sourceName
                TransferMode.Duplicate -> !passwordNeeded || password.isNotEmpty()
            }

    // The password and the typed name are never printed.
    override fun toString(): String =
        "TransferForm(mode=$mode, target=$targetId, name=$name, password=${if (password.isEmpty()) "none" else "***"}, running=$running, error=$error)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(
    form: TransferForm,
    onTarget: (Int) -> Unit,
    onName: (String) -> Unit,
    onTypedName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val migrate = form.mode == TransferMode.Migrate
    ModalBottomSheet(
        // A running request goes on without the sheet, but its result belongs here: stay until it ends.
        onDismissRequest = { if (!form.running) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (migrate) "Migrate ${form.sourceName}?" else "Duplicate ${form.sourceName}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (migrate) {
                    "The stack is deployed on the target environment and then removed from this one: its containers here are taken down. Volumes are not moved."
                } else {
                    "A new stack is created from this stack's current settings. This stack is not changed."
                },
            )
            ReviewLabel("Target environment")
            when {
                form.loadingTargets -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                form.targets.isEmpty() -> Text("No other online Docker environment.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            form.targets.forEach { endpoint ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !form.running) { onTarget(endpoint.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = endpoint.id == form.targetId, onClick = { onTarget(endpoint.id) }, enabled = !form.running)
                    Text(endpoint.name)
                }
            }
            OutlinedTextField(
                value = form.name,
                onValueChange = onName,
                label = { Text(if (migrate) "Name on the target (optional)" else "New stack name") },
                isError = form.nameError != null && form.name.isNotEmpty(),
                supportingText = { (form.nameError.takeIf { form.name.isNotEmpty() || !migrate })?.let { Text(it) } },
                singleLine = true,
                enabled = !form.running,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!migrate && form.passwordNeeded) {
                Text(
                    "This stack's repository needs a login. Portainer doesn't give the saved password back, so type it again. It is sent to Portainer only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = onPassword,
                    label = { Text("Password or token") },
                    singleLine = true,
                    enabled = !form.running,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!migrate && form.usesGitSource) {
                Text("The repository's saved GitOps source is used, so no password is needed.", style = MaterialTheme.typography.bodyMedium)
            }
            if (!migrate && form.autoUpdateDropped) {
                Text("Automatic updates are not copied; turn them on for the new stack.", style = MaterialTheme.typography.bodyMedium)
            }
            if (!migrate && form.pruneNotCopied) {
                Text("Prune services is not copied; turn it on for the new stack if you need it.", style = MaterialTheme.typography.bodyMedium)
            }
            if (!migrate && form.protectedWarning) {
                Text(PROTECTED_IMAGE_CREATE_WARNING, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            form.reminder?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (form.needsTypedName) {
                OutlinedTextField(
                    value = form.typedName,
                    onValueChange = onTypedName,
                    label = { Text("Type ${form.sourceName} to confirm") },
                    singleLine = true,
                    enabled = !form.running,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            form.error?.let {
                Text(
                    "${if (migrate) "Couldn't migrate the stack" else "Couldn't create the copy"}: $it",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (form.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(if (migrate) "Migrating the stack…" else "Creating the copy…")
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = onSubmit,
                    enabled = form.canSubmit,
                    colors = if (migrate) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text(if (migrate) "Migrate" else "Duplicate") }
            }
        }
    }
}
