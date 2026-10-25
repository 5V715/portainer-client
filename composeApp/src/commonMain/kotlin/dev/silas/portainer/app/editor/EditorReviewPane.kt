package dev.silas.portainer.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.recreate.StepStatus
import dev.silas.portainer.app.theme.StatusTone
import dev.silas.portainer.app.theme.color

private fun confirmLabel(action: FinishAction) = when (action) {
    FinishAction.Replace -> "Replace"
    FinishAction.Copy -> "Deploy"
    FinishAction.Create -> "Create"
}

private fun reviewTitle(action: FinishAction) = when (action) {
    FinishAction.Replace -> "Review: replace container"
    FinishAction.Copy -> "Review: deploy as copy"
    FinishAction.Create -> "Review: create container"
}

/** The finish step in place of the form: checking, review (conflicts, warnings), deploy progress. */
@Composable
fun EditorReviewPane(phase: FinishPhase, vm: EditorViewModel) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            is FinishPhase.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Checking the container and the endpoint…")
            }
            is FinishPhase.Failed -> {
                Text(phase.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::backToForm, modifier = Modifier.fillMaxWidth()) { Text("Back to the form") }
            }
            is FinishPhase.Review -> Review(phase, vm)
            is FinishPhase.Deploying -> Deploying(phase.progress, vm)
        }
    }
}

@Composable
private fun Review(r: FinishPhase.Review, vm: EditorViewModel) {
    Text(reviewTitle(r.action), style = MaterialTheme.typography.titleMedium)
    r.notice?.let { Banner(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
    r.blocked?.let { Banner(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }

    if (r.nameEditable) {
        OutlinedTextField(
            value = r.name,
            onValueChange = vm::setDeployName,
            label = { Text("Name of the copy") },
            singleLine = true,
            isError = r.nameError != null,
            supportingText = r.nameError?.let { { Text(it) } },
            enabled = !r.starting,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (r.name.isNotEmpty()) {
        Text("Name: ${r.name}")
    }

    Text("Changes", style = MaterialTheme.typography.titleSmall)
    if (r.changes.isEmpty()) {
        Text(if (r.action == FinishAction.Replace) "No changes — the container will be recreated with the same settings." else "Same settings as the original.")
    } else {
        r.changes.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
    }
    if (r.action != FinishAction.Replace) {
        Text(
            if (r.start) "The new container will be started." else "The new container will be created but not started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    r.blocking.forEach { Banner(conflictText(it), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
    r.warnings.forEach { w ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Text(w, Modifier.padding(12.dp), color = StatusTone.Warn.color())
        }
    }
    if (r.blocking.isNotEmpty()) {
        Text("Change these in the form first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::backToForm, enabled = !r.starting, modifier = Modifier.weight(1f)) { Text("Back to the form") }
        Button(onClick = vm::confirm, enabled = r.canConfirm, modifier = Modifier.weight(1f)) {
            Text(if (r.starting) "Starting…" else confirmLabel(r.action))
        }
    }
}

@Composable
private fun Deploying(p: DeployProgress, vm: EditorViewModel) {
    val result = p.result
    Text(
        when (result) {
            null -> "Creating ${p.name.ifEmpty { "container" }}…"
            is DeployResult.Success -> "Done"
            is DeployResult.Failure -> "Deploy failed"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    p.steps.forEach { s ->
        val (mark, tone) = when (s.status) {
            StepStatus.Pending -> "○" to StatusTone.Idle
            StepStatus.Active -> "●" to StatusTone.Warn
            StepStatus.Done -> "✓" to StatusTone.Good
            StepStatus.Failed -> "✗" to StatusTone.Bad
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(mark, color = tone.color())
            Text(deployStepLabel(s.step))
        }
    }
    when (result) {
        null -> Text(
            "You can leave this screen; the deploy keeps going.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is DeployResult.Success -> Unit
        is DeployResult.Failure -> {
            result.step?.let { Text("Failed at: ${deployStepLabel(it)}", color = MaterialTheme.colorScheme.error) }
            Text(deployFailureText(p))
            if (result.createdId != null) {
                Button(onClick = vm::openCreated, modifier = Modifier.fillMaxWidth()) { Text("Open ${p.name.ifEmpty { "the container" }}") }
            }
            OutlinedButton(onClick = vm::backToForm, modifier = Modifier.fillMaxWidth()) { Text("Back to the form") }
        }
    }
}

@Composable
private fun Banner(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = content)
    }
}
