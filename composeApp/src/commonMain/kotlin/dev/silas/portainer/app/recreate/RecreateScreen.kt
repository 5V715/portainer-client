package dev.silas.portainer.app.recreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.theme.BackButton
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.app.theme.color
import dev.silas.portainer.core.model.Registry
import dev.silas.portainer.core.recreate.ChangeSummary
import dev.silas.portainer.core.recreate.RecreateStep
import dev.silas.portainer.core.recreate.RecreateWarning

private const val NO_REGISTRY = "None (public)"

/** Warning copy shown on the review, exactly as specified. */
internal fun warningText(w: RecreateWarning): String = when (w) {
    is RecreateWarning.StackMember ->
        "This container belongs to stack ${w.project}. Any stack redeploy — manual, git auto-update or webhook — will undo this change."
    RecreateWarning.AutoRemove ->
        "This container is removed by Docker when stopped. If starting the new one fails, the old one can't be restored."
    RecreateWarning.ConnectionDependency ->
        "If this container is needed to reach Portainer (VPN, reverse proxy), the app may lose connection and can't roll back."
    RecreateWarning.ImageConfigUnavailable ->
        "The old image's settings couldn't be read, so every setting is kept as-is, including ones that came from the image."
    RecreateWarning.ImageDefaultCommand ->
        "The command is empty, so the image's default command will be used."
    is RecreateWarning.SharedNetworkNamespace ->
        "The copy shares the network of container ${w.container.take(12)}, so its ports may clash with the original's."
}

internal fun stepLabel(step: RecreateStep): String = when (step) {
    RecreateStep.Pull -> "Pull image"
    RecreateStep.RenameOld -> "Rename original container"
    RecreateStep.CreateNew -> "Create new container"
    RecreateStep.ConnectNetworks -> "Connect networks"
    RecreateStep.StopOld -> "Stop original container"
    RecreateStep.StartNew -> "Start new container"
    RecreateStep.RemoveOld -> "Remove original container"
}

internal fun rollbackText(rolledBack: Boolean): String =
    if (rolledBack) "Your original container was restored."
    else "Not everything could be undone — check the message for which containers remain."

/** Plain-text lines of the change summary, in display order. */
internal fun summaryLines(c: ChangeSummary): List<String> = buildList {
    c.imageChange?.let { (old, new) -> add("Image: $old → $new") }
    c.envAdded.forEach { add("Added: $it") }
    c.envChanged.forEach { (old, new) -> add("Changed: $old → $new") }
    c.envRemoved.forEach { add("Removed: $it") }
    c.droppedImageDefaults.forEach { add("Image default dropped: $it") }
    c.envReinherited.forEach { add("Will revert to the image's value: $it") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecreateScreen(
    viewModel: RecreateViewModel,
    quick: Boolean = false,
    onSucceeded: (newId: String) -> Unit,
    onToContainers: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val phase = state.phase

    LaunchedEffect(phase) {
        val done = phase as? RecreatePhase.Done ?: return@LaunchedEffect
        (done.result as? RecreateResult.Success)?.let {
            viewModel.acknowledge()
            onSucceeded(it.newId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (quick) "Recreate" else "Edit & recreate") }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (phase) {
                RecreatePhase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RecreatePhase.Error -> Text(phase.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                RecreatePhase.Editing -> EditForm(state, viewModel)
                is RecreatePhase.Reviewing -> ReviewPane(phase, viewModel)
                is RecreatePhase.Running -> StepsPane(phase.steps, header = "Recreating ${state.containerName}…") {
                    Text(
                        "You can leave this screen; the recreate keeps going.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is RecreatePhase.Done -> when (val r = phase.result) {
                    is RecreateResult.Success -> StepsPane(phase.steps, header = "Done")
                    is RecreateResult.Failure -> FailurePane(phase.steps, r) {
                        viewModel.acknowledge()
                        onToContainers()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditForm(state: RecreateState, vm: RecreateViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.image,
            onValueChange = vm::setImage,
            label = { Text("Image") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pull latest image", Modifier.weight(1f))
            Switch(checked = state.pull, onCheckedChange = vm::setPull)
        }
        RegistryPicker(state.registries, state.registryId, vm::setRegistry)

        Text("Environment variables", style = MaterialTheme.typography.titleSmall)
        state.env.forEachIndexed { index, entry ->
            val key = entry.substringBefore('=')
            val value = entry.substringAfter('=', "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { vm.setEnvRow(index, it, value) },
                    label = { Text("Key") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { vm.setEnvRow(index, key, it) },
                    label = { Text("Value") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.removeEnvRow(index) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove variable ${key.ifBlank { index + 1 }}")
                }
            }
        }
        TextButton(onClick = vm::addEnvRow) { Text("Add variable") }

        state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::review, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.checking) "Checking container…" else "Review changes")
        }
    }
}

/** "Registry used for the pull" dropdown; [onPick] gets null for none (public). */
@Composable
internal fun RegistryPicker(registries: List<Registry>, registryId: Int?, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = registries.firstOrNull { it.id == registryId }?.name ?: NO_REGISTRY
    Column {
        Text("Registry", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(NO_REGISTRY) }, onClick = { onPick(null); open = false })
                registries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.name) }, onClick = { onPick(r.id); open = false })
                }
            }
        }
    }
}

@Composable
private fun ReviewPane(phase: RecreatePhase.Reviewing, vm: RecreateViewModel) {
    val lines = summaryLines(phase.plan.changes)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review changes", style = MaterialTheme.typography.titleMedium)
        phase.notice?.let { notice ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(notice, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (lines.isEmpty()) {
            Text("No changes — the container will be recreated with the same settings.")
        } else {
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
        }
        phase.plan.warnings.forEach { w ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Text(warningText(w), Modifier.padding(12.dp), color = StatusTone.Warn.color())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = vm::cancelReview) { Text("Cancel") }
            Button(onClick = vm::confirm) { Text("Recreate") }
        }
    }
}

@Composable
private fun StepsPane(steps: List<StepState>, header: String, footer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(header, style = MaterialTheme.typography.titleMedium)
        steps.forEach { StepRow(it) }
        footer()
    }
}

@Composable
private fun StepRow(s: StepState) {
    val (mark, tone) = when (s.status) {
        StepStatus.Pending -> "○" to StatusTone.Idle
        StepStatus.Active -> "●" to StatusTone.Warn
        StepStatus.Done -> "✓" to StatusTone.Good
        StepStatus.Failed -> "✗" to StatusTone.Bad
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(mark, color = tone.color())
        Text(stepLabel(s.step))
    }
}

@Composable
private fun FailurePane(steps: List<StepState>, failure: RecreateResult.Failure, onToContainers: () -> Unit) {
    StepsPane(steps, header = "Recreate failed") {
        failure.step?.let { Text("Failed at: ${stepLabel(it)}", color = MaterialTheme.colorScheme.error) }
        Text(failure.message)
        Text(rollbackText(failure.rolledBack), style = MaterialTheme.typography.titleSmall)
        Button(onClick = onToContainers, modifier = Modifier.fillMaxWidth()) { Text("Back to containers") }
    }
}
