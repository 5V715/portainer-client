package dev.silas.portainer.app.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.images.formatBytes
import dev.silas.portainer.core.cleanup.CleanupItem
import dev.silas.portainer.core.cleanup.CleanupKind

private fun CleanupKind.noun(): String = when (this) {
    CleanupKind.Containers -> "containers"
    CleanupKind.Images -> "images"
    CleanupKind.Volumes -> "volumes"
    CleanupKind.Networks -> "networks"
}

/** Top-bar overflow menu with the "Clean up" entry. */
@Composable
fun CleanupOverflow(onCleanUp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Clean up") },
                onClick = {
                    open = false
                    onCleanUp()
                },
            )
        }
    }
}

/** Previewed cleanup of one resource kind. Dismissing it never stops a running cleanup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupSheet(viewModel: CleanupViewModel, onDismiss: () -> Unit) {
    val phase by viewModel.phase.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Clean up ${viewModel.kind.noun()}", style = MaterialTheme.typography.headlineSmall) }
            when (val p = phase) {
                CleanupPhase.Idle, CleanupPhase.Loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Checking what can be removed…")
                    }
                }
                is CleanupPhase.Error -> {
                    item { Text(p.message, color = MaterialTheme.colorScheme.error) }
                    item {
                        Buttons {
                            TextButton(onClick = onDismiss) { Text("Close") }
                            Button(onClick = viewModel::open) { Text("Retry") }
                        }
                    }
                }
                is CleanupPhase.Preview -> preview(p, viewModel, onDismiss)
                is CleanupPhase.ConfirmNamed -> {
                    item {
                        Text("Delete named volumes?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    }
                    item { Text(NAMED_VOLUMES_WARNING, color = MaterialTheme.colorScheme.error) }
                    items(p.preview.plan.namedVolumes, key = { "c-" + it.id }) { ItemRow(it) }
                    item {
                        Buttons {
                            TextButton(onClick = viewModel::cancelNamed) { Text("Back") }
                            Button(
                                onClick = viewModel::confirmNamed,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Delete permanently") }
                        }
                    }
                }
                is CleanupPhase.Running -> {
                    item {
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.completed.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { Text("Deleting ${minOf(p.completed + 1, p.total)} of ${p.total}…") }
                    item { Text("You can close this; the cleanup keeps running.", style = MaterialTheme.typography.bodySmall) }
                }
                is CleanupPhase.Done -> {
                    item {
                        val reclaimed = if (p.reclaimedBytes > 0) " · ${formatBytes(p.reclaimedBytes)} reclaimed" else ""
                        Text("Deleted ${p.deleted}$reclaimed", style = MaterialTheme.typography.titleMedium)
                    }
                    if (p.failures.isNotEmpty()) {
                        item { Section("Not deleted (${p.failed})", MaterialTheme.colorScheme.error) }
                        items(p.failures, key = { "f-" + it.item.id }) {
                            Column {
                                Text(it.item.label, style = MaterialTheme.typography.bodyLarge)
                                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item { Buttons { Button(onClick = onDismiss) { Text("Close") } } }
                }
            }
            item { Row(Modifier.padding(bottom = 16.dp)) {} }
        }
    }
}

private fun LazyListScope.preview(p: CleanupPhase.Preview, viewModel: CleanupViewModel, onDismiss: () -> Unit) {
    if (viewModel.kind == CleanupKind.Images) {
        item { Option("Also remove unused tagged images", p.includeTagged, danger = false, onChange = viewModel::setIncludeTagged) }
    }
    if (p.isEmpty) {
        item { Text("Nothing to clean up.", Modifier.padding(vertical = 8.dp)) }
    }
    if (p.plan.toDelete.isNotEmpty()) {
        item { Section("Will delete (${p.plan.toDelete.size})") }
        items(p.plan.toDelete, key = { "d-" + it.id }) { ItemRow(it) }
    }
    if (p.plan.namedVolumes.isNotEmpty()) {
        item { Section("Named volumes (${p.plan.namedVolumes.size})", MaterialTheme.colorScheme.error) }
        item { Text(NAMED_VOLUMES_WARNING, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        item { Option("Also remove unused named volumes", p.includeNamed, danger = true, onChange = viewModel::setIncludeNamed) }
        items(p.plan.namedVolumes, key = { "n-" + it.id }) {
            ItemRow(it, Modifier.alpha(if (p.includeNamed) 1f else 0.5f))
        }
    }
    if (p.plan.kept.isNotEmpty()) {
        item { Section("Kept (Portainer)") }
        items(p.plan.kept, key = { "k-" + it.id }) { ItemRow(it, Modifier.alpha(0.7f)) }
    }
    item {
        val count = p.selected.size
        Buttons {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = viewModel::confirm,
                enabled = count > 0,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete $count") }
        }
    }
}

@Composable
private fun Section(title: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ItemRow(item: CleanupItem, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        item.sizeBytes?.let { Text(formatBytes(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Option(label: String, checked: Boolean, danger: Boolean, onChange: (Boolean) -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (danger) error else Color.Unspecified)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = if (danger) {
                SwitchDefaults.colors(checkedTrackColor = error, checkedThumbColor = MaterialTheme.colorScheme.onError)
            } else {
                SwitchDefaults.colors()
            },
        )
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) { content() }
}
