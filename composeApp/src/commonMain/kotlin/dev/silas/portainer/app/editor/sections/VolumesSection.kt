package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel
import dev.silas.portainer.core.editor.MountOrigin
import dev.silas.portainer.core.editor.MountRow
import dev.silas.portainer.core.editor.MountType

private val TYPES = listOf(MountType.Bind to "Bind", MountType.Volume to "Volume", MountType.Tmpfs to "tmpfs")

internal fun mountSource(row: MountRow) = when (row.type) {
    MountType.Bind -> row.source
    MountType.Volume -> row.source.ifEmpty { "anonymous volume" }
    MountType.Tmpfs -> "tmpfs"
}

@Composable
fun VolumesSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = !state.readOnly
    val mounts = state.draft.mounts
    val summary = when {
        mounts.isEmpty() && state.anonymousVolumes.isEmpty() -> "None"
        else -> (mounts.map { "${mountSource(it)} → ${it.target}" } + state.anonymousVolumes).joinToString(", ")
    }
    SectionCard("Volumes", summary, expanded, onToggle, state.visibleErrors(EditorSection.Volumes)) {
        mounts.forEachIndexed { i, row ->
            RowHeader("Mount ${i + 1}", "Remove mount ${i + 1}", enabled) { vm.removeMount(i) }
            if (state.isLocked(row)) LockedMount(row) else MountFields(state, vm, i, row, enabled)
        }
        state.anonymousVolumes.forEach { target ->
            Text("Anonymous volume at $target (kept on replace, fresh on copy)", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = vm::addMount, enabled = enabled) { Text("Add mount") }
    }
}

@Composable
private fun LockedMount(row: MountRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(
                "${mountSource(row)} → ${row.target}" + if (row.readOnly) " (read-only)" else "",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            HintText("Written in a form this app can't edit without changing it, so it's kept exactly as is. You can still remove it.")
        }
    }
}

@Composable
private fun MountFields(state: EditorState, vm: EditorViewModel, index: Int, row: MountRow, enabled: Boolean) {
    fun update(next: MountRow) = vm.updateMount(index, next)
    if (row.origin == MountOrigin.Binds) {
        // A -v string's type follows its source; no switch it couldn't hold.
        HintText(if (row.type == MountType.Bind) "Bind mount (host path)" else "Volume (name, or empty for anonymous)")
    } else {
        ChoiceChips(TYPES, row.type, enabled) { update(row.copy(type = it)) }
    }
    when {
        row.type == MountType.Tmpfs -> Unit
        row.type == MountType.Bind && row.origin != MountOrigin.Binds ->
            FormTextField(row.source, { update(row.copy(source = it)) }, "Host path", enabled, mono = true, placeholder = "/srv/data")
        else -> {
            val name = row.source.trim()
            val hint = when {
                row.origin == MountOrigin.Binds -> "A host path makes this a bind mount"
                name.isEmpty() -> "Empty = anonymous volume"
                name !in state.volumes -> "A new volume named $name will be created"
                else -> null
            }
            SuggestField(row.source, { update(row.copy(source = it)) }, if (row.type == MountType.Bind) "Host path" else "Volume", state.volumes, enabled, hint)
        }
    }
    FormTextField(row.target, { update(row.copy(target = it)) }, "Container path", enabled, mono = true, placeholder = "/data")
    SwitchRow("Read-only", row.readOnly, enabled) { update(row.copy(readOnly = it)) }
}
