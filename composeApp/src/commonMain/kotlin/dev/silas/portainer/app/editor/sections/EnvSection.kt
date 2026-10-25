package dev.silas.portainer.app.editor.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel
import dev.silas.portainer.app.editor.envKey
import dev.silas.portainer.app.editor.envValue

@Composable
fun EnvSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = state.editable
    val env = state.draft.env
    SectionCard("Environment variables", countSummary(env.size, "variable"), expanded, onToggle, state.visibleErrors(EditorSection.Env)) {
        env.forEachIndexed { i, entry ->
            val key = envKey(entry)
            val value = envValue(entry)
            KeyValueRow(key, value, "Name", "Value", "Remove variable ${key.ifBlank { "${i + 1}" }}", enabled,
                onKey = { vm.setEnvRow(i, it, value) }, onValue = { vm.setEnvRow(i, key, it) }, onRemove = { vm.removeEnvRow(i) })
        }
        TextButton(onClick = vm::addEnvRow, enabled = enabled) { Text("Add variable") }
    }
}

/** Two side-by-side fields and a remove button; fits a phone width without scrolling. */
@Composable
internal fun KeyValueRow(
    key: String,
    value: String,
    keyLabel: String,
    valueLabel: String,
    removeLabel: String,
    enabled: Boolean,
    onKey: (String) -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(key, onKey, keyLabel, enabled, modifier = Modifier.weight(1f), mono = true)
        FormTextField(value, onValue, valueLabel, enabled, modifier = Modifier.weight(1f), mono = true)
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Default.Close, contentDescription = removeLabel) }
    }
}

internal fun countSummary(n: Int, noun: String) = when (n) {
    0 -> "None"
    1 -> "1 $noun"
    else -> "$n ${noun}s"
}
