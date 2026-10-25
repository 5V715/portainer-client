package dev.silas.portainer.app.editor.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import dev.silas.portainer.app.editor.EditorSection
import dev.silas.portainer.app.editor.EditorState
import dev.silas.portainer.app.editor.EditorViewModel

private val POLICIES = listOf("no" to "No", "always" to "Always", "on-failure" to "On failure", "unless-stopped" to "Unless stopped")

@Composable
fun RestartSection(state: EditorState, vm: EditorViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = state.editable
    val d = state.draft
    val summary = POLICIES.firstOrNull { it.first == d.restartName }?.second ?: d.restartName
    SectionCard("Restart policy", summary, expanded, onToggle, state.visibleErrors(EditorSection.Restart)) {
        ChoiceChips(POLICIES, d.restartName, enabled, vm::setRestartPolicy)
        if (d.restartName == "on-failure") {
            FormTextField(
                d.maxRetries, vm::setMaxRetries, "Maximum retries", enabled,
                supporting = "0 = no limit", keyboardType = KeyboardType.Number,
            )
        }
    }
}
