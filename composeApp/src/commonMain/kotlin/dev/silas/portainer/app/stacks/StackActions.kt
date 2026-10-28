package dev.silas.portainer.app.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.support.CONNECTION_DEPENDENCY_REMINDER
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

/** The compose file couldn't be read, so whether the stack runs Portainer is unknown. */
const val PROTECTION_UNKNOWN_MESSAGE = "Couldn't check whether this stack runs Portainer — try again."

/** A web-editor stack's file is what a duplicate copies. */
const val DUPLICATE_FILE_UNREADABLE_MESSAGE = "The stack's compose file couldn't be read, so it can't be copied."

/** The stack read right before an operation is not the one its confirmation or review showed. */
const val STACK_CHANGED_MESSAGE = "The stack changed — check it and try again."

/** `GET /api/status` failed: version-gated requests (git settings, redeploy, repository creates) would be guesses. */
const val VERSION_UNKNOWN_MESSAGE = "Couldn't read the Portainer version — try again."

/** Portainer CE can't act on stacks it didn't create (its own UI offers nothing for them). */
const val EXTERNAL_STACK_MESSAGE = "Portainer did not create this stack, so it can only be viewed here."

/** The stack screen holds edits that leaving it (or a delete or migrate) would throw away. */
fun stackScreenHasUnsavedEdits(editor: StackEditorState, git: GitSettingsState): Boolean =
    editor.hasUnsavedChanges || git.hasUnsavedChanges

/** Operations the detail screen offers for the stack as loaded; external stacks get none. */
fun availableStackOps(state: StackDetailState): List<StackOp> {
    val stack = state.stack ?: return emptyList()
    if (stack.control != StackControl.Full || stack.id == null) return emptyList()
    return when (stack.status) {
        StackStatus.Inactive -> listOf(StackOp.Start, StackOp.Delete)
        StackStatus.Active, StackStatus.Error -> listOf(StackOp.Stop, StackOp.Delete)
        // Portainer refuses start/stop while it deploys; wait for it.
        StackStatus.Deploying -> emptyList()
    }
}

/**
 * Why [op] may not run on the stack as loaded, or null. Protection blocks every operation, start included: compose up
 * can recreate drifted Portainer/agent containers.
 */
fun stackOpRefusal(state: StackDetailState, op: StackOp): String? {
    val stack = state.stack ?: return null
    if (stack.control != StackControl.Full || stack.id == null) return EXTERNAL_STACK_MESSAGE
    return when {
        state.protected -> PROTECTED_IMAGE_MESSAGE
        state.protectionUnknown -> PROTECTION_UNKNOWN_MESSAGE
        else -> null
    }
}

/**
 * Why the stack as loaded can't be migrated or duplicated, or null. A migrate takes the stack down on its source, so it
 * follows the protected rule of every operation; a duplicate only reads the stack, but a web-editor one needs its file.
 * External stacks get neither.
 */
fun transferRefusal(state: StackDetailState, mode: TransferMode): String? {
    val stack = state.stack ?: return null
    if (stack.control != StackControl.Full || stack.id == null) return EXTERNAL_STACK_MESSAGE
    if (stack.status == StackStatus.Deploying) return STACK_DEPLOYING_MESSAGE
    return when (mode) {
        TransferMode.Migrate -> stackOpRefusal(state, StackOp.Migrate(targetEndpointId = 0, newName = null))
        TransferMode.Duplicate -> when {
            stack.git == null && state.file == null -> DUPLICATE_FILE_UNREADABLE_MESSAGE
            // A git copy is a repository create: its credentials and Source depend on the version.
            stack.git != null && state.versionUnknown -> VERSION_UNKNOWN_MESSAGE
            else -> null
        }
    }
}

/** The confirmation of [op] (stop or delete; start asks nothing) on [stackName]; delete needs the name typed exactly. */
data class StackConfirm(
    val op: StackOp,
    val stackName: String,
    val typedName: String = "",
    /** The editor or the git settings have unsaved edits; deleting the stack throws them away. */
    val unsavedEdits: Boolean = false,
) {
    init {
        require(op == StackOp.Stop || op == StackOp.Delete) { "Only stop and delete use this confirmation" }
    }

    val reminder: String get() = CONNECTION_DEPENDENCY_REMINDER
    val needsTypedName: Boolean get() = op == StackOp.Delete
    val canConfirm: Boolean get() = !needsTypedName || typedName == stackName

    val title: String
        get() = if (op == StackOp.Delete) "Delete stack $stackName?" else "Stop stack $stackName?"

    val body: String
        get() = if (op == StackOp.Delete) {
            "The stack and its containers are removed from Portainer and Docker. Its volumes are kept. This can't be undone." +
                if (unsavedEdits) " Your unsaved changes on this screen are discarded too." else ""
        } else {
            "All of the stack's containers are stopped."
        }
}

/** Start needs no confirmation; operations that take containers down do (an update has its own review). */
fun needsConfirm(op: StackOp): Boolean = op != StackOp.Start

internal fun opLabel(op: StackOp): String = when (op) {
    StackOp.Start -> "Start"
    StackOp.Stop -> "Stop"
    StackOp.Delete -> "Delete"
    is StackOp.Update -> "Update"
    is StackOp.GitSettings -> "Save"
    is StackOp.GitRedeploy -> "Pull and redeploy"
    StackOp.Detach -> "Detach"
    is StackOp.Migrate -> "Migrate"
    is StackOp.Create -> "Deploy"
}

internal fun runningLabel(op: StackOp): String = when (op) {
    StackOp.Start -> "Starting the stack…"
    StackOp.Stop -> "Stopping the stack…"
    StackOp.Delete -> "Deleting the stack…"
    is StackOp.Update -> "Updating the stack…"
    is StackOp.GitSettings -> "Saving the git settings…"
    is StackOp.GitRedeploy -> "Pulling and redeploying…"
    StackOp.Detach -> "Detaching from git…"
    is StackOp.Migrate -> "Migrating the stack…"
    is StackOp.Create -> "Creating the stack…"
}

internal fun failureTitle(op: StackOp): String = when (op) {
    StackOp.Start -> "Couldn't start the stack"
    StackOp.Stop -> "Couldn't stop the stack"
    StackOp.Delete -> "Couldn't delete the stack"
    is StackOp.Update -> "Couldn't update the stack"
    is StackOp.GitSettings -> "Couldn't save the git settings"
    is StackOp.GitRedeploy -> "Couldn't redeploy the stack"
    StackOp.Detach -> "Couldn't detach the stack from git"
    is StackOp.Migrate -> "Couldn't migrate the stack"
    is StackOp.Create -> "Couldn't create the stack"
}

/** Bottom action bar: the stack's operations, or the one running. */
@Composable
fun StackActionBar(ops: List<StackOp>, running: StackOp?, busy: Boolean, onRequest: (StackOp) -> Unit) {
    if (ops.isEmpty() && running == null) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running != null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(runningLabel(running))
                return@Row
            }
            ops.filter { it == StackOp.Delete }.forEach { op ->
                OutlinedButton(
                    onClick = { onRequest(op) },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text(opLabel(op)) }
            }
            // The primary action sits at the end, under the thumb.
            ops.filter { it != StackOp.Delete }.forEach { op ->
                Button(onClick = { onRequest(op) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(opLabel(op)) }
            }
        }
    }
}

/** Confirmation sheet: what happens, the connection reminder and, for delete, the typed name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackConfirmSheet(confirm: StackConfirm, onTypedName: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(confirm.title, style = MaterialTheme.typography.headlineSmall)
            Text(confirm.body)
            Text(confirm.reminder, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (confirm.needsTypedName) {
                OutlinedTextField(
                    value = confirm.typedName,
                    onValueChange = onTypedName,
                    label = { Text("Type ${confirm.stackName} to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val destructive = confirm.op == StackOp.Delete
                Button(
                    onClick = onConfirm,
                    enabled = confirm.canConfirm,
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text(opLabel(confirm.op)) }
            }
        }
    }
}
