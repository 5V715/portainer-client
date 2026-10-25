package dev.silas.portainer.app.editor.sections

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel

@Composable
fun LabelsSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = state.editable
    val labels = state.draft.labels
    SectionCard("Labels", countSummary(labels.size, "label"), expanded, onToggle, state.visibleErrors(EditorSection.Labels)) {
        labels.forEachIndexed { i, (key, value) ->
            KeyValueRow(key, value, "Key", "Value", "Remove label ${key.ifBlank { "${i + 1}" }}", enabled,
                onKey = { vm.setLabel(i, it, value) }, onValue = { vm.setLabel(i, key, it) }, onRemove = { vm.removeLabel(i) })
        }
        TextButton(onClick = vm::addLabel, enabled = enabled) { Text("Add label") }
    }
}
