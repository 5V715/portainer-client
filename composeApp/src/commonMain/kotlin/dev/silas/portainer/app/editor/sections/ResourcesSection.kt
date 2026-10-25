package dev.silas.portainer.app.editor.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel

@Composable
fun ResourcesSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = state.editable
    val d = state.draft
    val summary = listOfNotNull(
        d.memory.takeIf { it.isNotBlank() }?.let { "Memory $it" },
        d.cpus.takeIf { it.isNotBlank() }?.let { "CPU $it" },
    ).joinToString(", ").ifEmpty { "Unlimited" }
    SectionCard("Resources", summary, expanded, onToggle, state.visibleErrors(EditorSection.Resources)) {
        FormTextField(d.memory, vm::setMemory, "Memory limit", enabled, mono = true, placeholder = "Unlimited", supporting = "e.g. 512m, 1g or bytes")
        FormTextField(d.memoryReservation, vm::setMemoryReservation, "Memory reservation", enabled, mono = true, placeholder = "None")
        FormTextField(
            d.cpus, vm::setCpus, "CPU limit (cores)", enabled, mono = true, placeholder = "Unlimited",
            supporting = "e.g. 0.5 or 2", keyboardType = KeyboardType.Decimal,
        )
        FormTextField(d.cpuShares, vm::setCpuShares, "CPU shares", enabled, mono = true, placeholder = "Default", keyboardType = KeyboardType.Number)
    }
}
