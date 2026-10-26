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
import dev.silas.portainer.core.model.StackControl
import dev.silas.portainer.core.model.StackStatus
import dev.silas.portainer.core.recreate.PROTECTED_IMAGE_MESSAGE

/** Shown on every confirmation of an operation that takes stack containers down. */
const val STACK_CONNECTION_REMINDER =
    "If this stack runs something the app needs to reach Portainer (VPN, reverse proxy), the app may lose its connection."

/** The compose file couldn't be read, so whether the stack runs Portainer is unknown. */
const val PROTECTION_UNKNOWN_MESSAGE = "Couldn't check whether this stack runs Portainer — try again."

/** Portainer CE can't act on stacks it didn't create (its own UI offers nothing for them). */
const val EXTERNAL_STACK_MESSAGE = "Portainer did not create this stack, so it can only be viewed here."

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

/** Why [op] may not run on the stack as loaded, or null. Protection blocks everything that takes containers down. */
fun stackOpRefusal(state: StackDetailState, op: StackOp): String? {
    val stack = state.stack ?: return null
    if (stack.control != StackControl.Full || stack.id == null) return EXTERNAL_STACK_MESSAGE
    val takesDown = op != StackOp.Start
    return when {
        takesDown && state.protected -> PROTECTED_IMAGE_MESSAGE
        takesDown && state.protectionUnknown -> PROTECTION_UNKNOWN_MESSAGE
        else -> null
    }
}

/** The confirmation of [op] on [stackName]; delete needs the name typed exactly. */
data class StackConfirm(val op: StackOp, val stackName: String, val typedName: String = "") {
    val reminder: String get() = STACK_CONNECTION_REMINDER
    val needsTypedName: Boolean get() = op == StackOp.Delete
    val canConfirm: Boolean get() = !needsTypedName || typedName == stackName

    val title: String
        get() = when (op) {
            StackOp.Start -> "Start stack $stackName?"
            StackOp.Stop -> "Stop stack $stackName?"
            StackOp.Delete -> "Delete stack $stackName?"
        }

    val body: String
        get() = when (op) {
            StackOp.Start -> "Portainer starts the stack's services."
            StackOp.Stop -> "All of the stack's containers are stopped."
            StackOp.Delete ->
                "The stack and its containers are removed from Portainer and Docker. Its volumes are kept. This can't be undone."
        }
}

/** Start needs no confirmation; operations that take containers down do. */
fun needsConfirm(op: StackOp): Boolean = op != StackOp.Start

internal fun opLabel(op: StackOp): String = when (op) {
    StackOp.Start -> "Start"
    StackOp.Stop -> "Stop"
    StackOp.Delete -> "Delete"
}

internal fun runningLabel(op: StackOp): String = when (op) {
    StackOp.Start -> "Starting the stack…"
    StackOp.Stop -> "Stopping the stack…"
    StackOp.Delete -> "Deleting the stack…"
}

internal fun failureTitle(op: StackOp): String = when (op) {
    StackOp.Start -> "Couldn't start the stack"
    StackOp.Stop -> "Couldn't stop the stack"
    StackOp.Delete -> "Couldn't delete the stack"
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
